package com.xam.kiosk.boot;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import java.util.List;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    private static final String PREFS_NAME = "kiosk_boot_prefs";
    private static final String KEY_BOOT_FLOW_STARTED = "boot_flow_started";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String action = intent.getAction();
        Log.i(TAG, "onReceive action=" + action);

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.w(TAG, "LOCKED_BOOT_COMPLETED: device in Direct Boot, not launching kiosk yet.");
            return;
        }

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_USER_UNLOCKED.equals(action)) {
            return;
        }

        if (!isUserUnlocked(context)) {
            Log.w(TAG, action + ": user still not unlocked, skipping launch (will wait for USER_UNLOCKED).");
            return;
        }

        if (isKioskAlreadyRunning(context)) {
            Log.i(TAG, "Kiosk already running, skipping boot launch.");
            markBootFlowStarted(context);
            return;
        }

        if (hasBootFlowAlreadyStarted(context)) {
            Log.i(TAG, "Boot flow already started once, skipping duplicate launch for action=" + action);
            return;
        }

        markBootFlowStarted(context);

        Intent svc = new Intent(context, BootLaunchService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            Log.i(TAG, "BootLaunchService started.");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start BootLaunchService", t);
            clearBootFlowStarted(context);
        }
    }

    private boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return um != null && um.isUserUnlocked();
        } catch (Throwable t) {
            Log.w(TAG, "isUserUnlocked check failed; assume locked", t);
            return false;
        }
    }

    private boolean hasBootFlowAlreadyStarted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_BOOT_FLOW_STARTED, false);
    }

    private void markBootFlowStarted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_BOOT_FLOW_STARTED, true).apply();
    }

    private void clearBootFlowStarted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_BOOT_FLOW_STARTED).apply();
    }

    private boolean isKioskAlreadyRunning(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;

            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(10);
            if (tasks == null) return false;

            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null) continue;

                if (task.topActivity != null
                        && "com.xam.kiosk".equals(task.topActivity.getPackageName())) {
                    Log.i(TAG, "Detected running kiosk task. topActivity=" + task.topActivity.flattenToShortString());
                    return true;
                }

                if (task.baseActivity != null
                        && "com.xam.kiosk".equals(task.baseActivity.getPackageName())) {
                    Log.i(TAG, "Detected kiosk task by baseActivity=" + task.baseActivity.flattenToShortString());
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isKioskAlreadyRunning check failed", t);
        }
        return false;
    }
}
