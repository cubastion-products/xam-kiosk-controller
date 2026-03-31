package com.xam.kiosk.boot;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;

import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import com.xam.kiosk.ui.KioskActivity;

import java.io.File;
import java.util.List;

public class BootLaunchService extends Service {

    private static final String TAG = "BootLaunchService";
    private static final String CHANNEL_ID = "kiosk_boot";
    private static final int NOTIF_ID = 1001;

    // Tuning
    private static final long POLL_EVERY_MS = 700;
    private static final long TIMEOUT_MS = 60_000; // 60s max wait

    private final Handler h = new Handler(Looper.getMainLooper());
    private long startTs;
    private boolean launchIssued = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startTs = System.currentTimeMillis();
        try {
            startForegroundCompat();
        } catch (Throwable t) {
            Log.e(TAG, "startForegroundCompat failed", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: waiting for user unlock + storage ready...");

        if (launchIssued) {
            Log.i(TAG, "Launch already issued earlier, stopping duplicate service instance.");
            shutdownService();
            return START_NOT_STICKY;
        }

        h.removeCallbacks(checkReadyRunnable);
        h.post(checkReadyRunnable);
        return START_NOT_STICKY;
    }

    private final Runnable checkReadyRunnable = new Runnable() {
        @Override
        public void run() {
            if (launchIssued) {
                Log.i(TAG, "checkReadyRunnable: launch already issued, stopping.");
                shutdownService();
                return;
            }

            try {
                if (isKioskAlreadyRunning()) {
                    Log.i(TAG, "Kiosk already running, not launching again.");
                    launchIssued = true;
                    shutdownService();
                    return;
                }

                boolean unlocked = isUserUnlocked();
                boolean storageReady = isStorageReady();

                Log.i(TAG, "readyCheck: unlocked=" + unlocked + " storageReady=" + storageReady);

                if (unlocked && storageReady) {
                    launchKiosk();
                    return;
                }

                if (System.currentTimeMillis() - startTs > TIMEOUT_MS) {
                    Log.w(TAG, "Timeout waiting for storage. Launching kiosk anyway.");
                    launchKiosk();
                    return;
                }

            } catch (Throwable t) {
                Log.e(TAG, "readyCheck failed", t);
                // keep retrying until timeout
            }

            h.postDelayed(this, POLL_EVERY_MS);
        }
    };

    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        try {
            UserManager um = (UserManager) getSystemService(USER_SERVICE);
            return um != null && um.isUserUnlocked();
        } catch (Throwable t) {
            Log.w(TAG, "isUserUnlocked check failed", t);
            return false;
        }
    }

    private boolean isStorageReady() {
        try {
            File emu0 = new File("/storage/emulated/0");
            if (emu0.exists() && emu0.canRead()) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
                if (sm != null) {
                    List<StorageVolume> vols = sm.getStorageVolumes();
                    if (vols != null) {
                        for (StorageVolume v : vols) {
                            File dir = v.getDirectory();
                            if (dir != null && dir.exists() && dir.canRead()) {
                                return true;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "StorageManager check failed", t);
            }
        }

        return false;
    }

    private boolean isKioskAlreadyRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;

            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(10);
            if (tasks == null) return false;

            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null) continue;

                if (task.topActivity != null
                        && "com.xam.kiosk".equals(task.topActivity.getPackageName())) {
                    Log.i(TAG, "Kiosk already running by topActivity="
                            + task.topActivity.flattenToShortString());
                    return true;
                }

                if (task.baseActivity != null
                        && "com.xam.kiosk".equals(task.baseActivity.getPackageName())) {
                    Log.i(TAG, "Kiosk already running by baseActivity="
                            + task.baseActivity.flattenToShortString());
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isKioskAlreadyRunning check failed", t);
        }
        return false;
    }

    private void launchKiosk() {
        if (launchIssued) {
            Log.i(TAG, "launchKiosk skipped: launch already issued.");
            shutdownService();
            return;
        }

        if (isKioskAlreadyRunning()) {
            Log.i(TAG, "launchKiosk skipped: KioskActivity already running.");
            launchIssued = true;
            shutdownService();
            return;
        }

        launchIssued = true;

        try {
            Intent i = new Intent(getApplicationContext(), KioskActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            Log.i(TAG, "KioskActivity launched from BootLaunchService");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start KioskActivity", t);
        } finally {
            shutdownService();
        }
    }

    private void shutdownService() {
        try {
            h.removeCallbacks(checkReadyRunnable);
        } catch (Throwable ignored) {
        }

        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }

        try {
            stopSelf();
        } catch (Throwable ignored) {
        }
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Kiosk Boot",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);

            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Starting Kiosk")
                    .setContentText("Waiting for storage…")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .build();

            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
