package com.xam.kiosk.ui;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.xam.kiosk.R;
import com.xam.kiosk.admin.KioskDeviceAdminReceiver;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

public class KioskActivity extends Activity {

    private static final String TAG = "KioskActivity";

    // NodeApp details
    private static final String NODE_APP_PACKAGE = "com.xam.nodeapp";
    private static final String NODE_APK_NAME = "NodeApp.apk";

    // Default hub Wi-Fi SSID — used when no config file or broadcast overrides it
    private static final String DEFAULT_HUB_SSID = "4c4c4544004a39108038b7c04f334a-1";

    // Retry pacing
    private static final long INSTALL_RECHECK_MS = 5000;

    // Wi-Fi watchdog: check connection every 15 seconds
    private static final long WIFI_CHECK_INTERVAL_MS = 15000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean launchAttempted = false;
    private boolean installTriggered = false;

    // =========================
    // Paths (scoped-storage safe)
    // =========================

    private File getProvisioningDir() {
        return getExternalFilesDir(null); // /sdcard/Android/data/<pkg>/files
    }

    private File getNodeApkFile() {
        File dir = getProvisioningDir();
        return (dir == null) ? null : new File(dir, NODE_APK_NAME);
    }

    // =========================
    // Activity lifecycle
    // =========================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ensure decor view exists before immersive APIs
        setContentView(R.layout.activity_kiosk);

        keepScreenOn();
        forceMaxBrightness();

        // Do not call immersive synchronously here on some OEM builds; post it.
        handler.post(this::enableImmersiveModeSafe);

        // If device owner: force kiosk as HOME + allow locktask packages
        ensurePoliciesIfDeviceOwner();

        // Apply hub Wi-Fi before launching NodeApp
        applyHubWifi();
        ensureNodeAppInstalledThenLaunch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveModeSafe();

        // If NodeApp was already launched and user lands back here (e.g. back gesture),
        // immediately re-launch NodeApp so user never gets stuck on this screen.
        if (launchAttempted && isPackageInstalled(NODE_APP_PACKAGE)) {
            launchAttempted = false; // reset so launchNodeApp() will fire
            launchNodeApp();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveModeSafe();
    }

    // =========================
    // Hub Wi-Fi
    // =========================

    private String resolveHubSsid() {
        File configFile = new File(getExternalFilesDir(null), "wifi_config.json");

        if (configFile.exists()) {
            try {
                FileInputStream fis = new FileInputStream(configFile);
                byte[] data = new byte[(int) configFile.length()];
                fis.read(data);
                fis.close();

                JSONObject json = new JSONObject(new String(data));
                String ssid = json.getString("ssid");

                getSharedPreferences("hub_config", MODE_PRIVATE)
                        .edit().putString("hub_wifi_ssid", ssid).apply();

                configFile.delete();
                return ssid;
            } catch (Exception e) {
                Log.w("KioskWifi", "Failed to parse wifi_config.json: " + e);
            }
        }

        // Try saved SSID from last provisioning or broadcast
        String saved = getSharedPreferences("hub_config", MODE_PRIVATE)
                .getString("hub_wifi_ssid", null);
        if (saved != null) return saved;

        // Fall back to hardcoded default
        Log.i("KioskWifi", "No saved SSID — using default: " + DEFAULT_HUB_SSID);
        return DEFAULT_HUB_SSID;
    }

    private void applyHubWifi() {
        String ssid = resolveHubSsid();
        connectToWifi(ssid);
        startWifiWatchdog(ssid);
    }

    /**
     * Persistent watchdog: checks every 15s if still connected to the target SSID.
     * If not connected, attempts to reconnect. Runs for the lifetime of the activity.
     */
    private void startWifiWatchdog(String ssid) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnectedToSsid(ssid)) {
                    Log.d("KioskWifi", "Watchdog: connected to " + ssid);
                } else {
                    Log.w("KioskWifi", "Watchdog: not connected — reconnecting to " + ssid);
                    connectToWifi(ssid);
                }
                handler.postDelayed(this, WIFI_CHECK_INTERVAL_MS);
            }
        }, WIFI_CHECK_INTERVAL_MS);
    }

    @SuppressWarnings("deprecation")
    private boolean isConnectedToSsid(String ssid) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();
        return info != null && ("\"" + ssid + "\"").equals(info.getSSID());
    }

    @SuppressWarnings("deprecation")
    private void connectToWifi(String ssid) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        // Skip if already connected to this SSID
        if (isConnectedToSsid(ssid)) {
            Log.d("KioskWifi", "Already connected to " + ssid);
            return;
        }

        if (!wm.isWifiEnabled()) {
            wm.setWifiEnabled(true);
        }

        // Remove stale entries for same SSID
        java.util.List<WifiConfiguration> configs = wm.getConfiguredNetworks();
        if (configs != null) {
            for (WifiConfiguration c : configs) {
                if (("\"" + ssid + "\"").equals(c.SSID)) {
                    wm.removeNetwork(c.networkId);
                }
            }
        }

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.priority = 999;

        int netId = wm.addNetwork(config);
        if (netId == -1) {
            Log.e("KioskWifi", "addNetwork failed — is Device Owner confirmed?");
            return;
        }
        wm.disconnect();
        wm.enableNetwork(netId, true);
        wm.reconnect();
        Log.i("KioskWifi", "WiFi connecting: ssid=" + ssid + " netId=" + netId);
    }

    // =========================
    // Install & Launch NodeApp
    // =========================

    private void ensureNodeAppInstalledThenLaunch() {
        Log.d(TAG, "ENTER ensureNodeAppInstalledThenLaunch()");

        boolean installed = isPackageInstalled(NODE_APP_PACKAGE);
        Log.i(TAG, "NodeApp installed? " + installed);

        if (!installed) {
            File apk = getNodeApkFile();
            boolean apkExists = apk != null && apk.exists();

            Log.i(
                TAG,
                "NodeApp APK path=" + (apk == null ? "null" : apk.getAbsolutePath())
                    + " exists=" + apkExists
                    + " installTriggered=" + installTriggered
            );

            if (!apkExists) {
                Log.i(TAG, "NodeApp not installed and APK not found yet. Waiting...");
                handler.postDelayed(this::ensureNodeAppInstalledThenLaunch, INSTALL_RECHECK_MS);
                return;
            }

            // Trigger installer only once; then just poll until installed.
            if (!installTriggered) {
                installTriggered = true;
                Log.i(TAG, "Found NodeApp APK, triggering install");
                //installApk(apk);
            }

            handler.postDelayed(this::ensureNodeAppInstalledThenLaunch, INSTALL_RECHECK_MS);
            return;
        }

        //Log.i(TAG, "NodeApp already installed, proceeding to launch");
	Log.i(TAG, "NodeApp already installed, preparing kiosk mode");
	finalizeKioskBeforeLaunch();
        launchNodeApp();

	new Handler().postDelayed(() -> {
    try {
        startLockTask();
        Log.i(TAG, "LockTask enforced after NodeApp launch");
    } catch (Exception e) {
        Log.e(TAG, "LockTask failed", e);
    }
}, 1000);

        // After success, switch USB to charging-only + locktask
        //finalizeKioskAfterSuccess();
    }

    private void finalizeKioskBeforeLaunch() {
    DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
    ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

    if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
            Log.i(TAG, "USB file transfer disabled (charging-only behavior).");
        } catch (Exception e) {
            Log.e(TAG, "DISALLOW_USB_FILE_TRANSFER failed: " + e.getMessage(), e);
        }

        try {
            startLockTask();
            Log.i(TAG, "LockTask started in KioskActivity.");
        } catch (Exception e) {
            Log.e(TAG, "startLockTask failed: " + e.getMessage(), e);
        }
    }
}

    private boolean isPackageInstalled(String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageManager().getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
            } else {
                getPackageManager().getPackageInfo(pkg, 0);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "isPackageInstalled(" + pkg + ") failed: " + e.getMessage(), e);
            return false;
        }
    }

    private void installApk(File apkFile) {
        try {
            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(intent);
            Log.i(TAG, "Triggered installer for: " + apkFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "installApk failed: " + e.getMessage(), e);
            installTriggered = false; // allow retry
        }
    }


	private void launchNodeApp() {
    if (launchAttempted) return;
    launchAttempted = true;

    try {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setClassName("com.xam.nodeapp", "com.xam.nodeapp.MainActivity");
        intent.setFlags(0);

        Log.i(TAG, "Launching NodeApp with explicit intent: " + intent);
        startActivity(intent);
        Log.i(TAG, "NodeApp launch requested successfully.");
    } catch (Exception e) {
        Log.e(TAG, "Failed to launch NodeApp: " + e.getMessage(), e);
        launchAttempted = false;
    }
}
	
    // =========================
    // Device Owner / Kiosk
    // =========================

    private void ensurePoliciesIfDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

        if (dpm == null) return;

        if (!dpm.isDeviceOwnerApp(getPackageName())) {
            Log.w(TAG, "Not device owner. Cannot force HOME or LockTask packages.");
            return;
        }

        // 1) Force our activity as default HOME
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            ComponentName home = new ComponentName(getPackageName(), KioskActivity.class.getName());
            dpm.addPersistentPreferredActivity(admin, filter, home);
            Log.i(TAG, "Set persistent preferred HOME to KioskActivity");
        } catch (Exception e) {
            Log.e(TAG, "addPersistentPreferredActivity failed: " + e.getMessage(), e);
        }

        // 2) Allow LockTask for our app + NodeApp
        try {
            dpm.setLockTaskPackages(admin, new String[]{getPackageName(), NODE_APP_PACKAGE});
            Log.i(TAG, "setLockTaskPackages applied for kiosk and node app");
        } catch (Exception e) {
            Log.e(TAG, "setLockTaskPackages failed: " + e.getMessage(), e);
        }

	// 3) Harden kiosk UI
try {
    dpm.setStatusBarDisabled(admin, true);
    Log.i(TAG, "Status bar disabled.");
} catch (Exception e) {
    Log.e(TAG, "setStatusBarDisabled failed: " + e.getMessage(), e);
}

try {
    dpm.setKeyguardDisabled(admin, true);
    Log.i(TAG, "Keyguard disabled.");
} catch (Exception e) {
    Log.e(TAG, "setKeyguardDisabled failed: " + e.getMessage(), e);
}

// 4) Optional restrictions
try {
    dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME);
    dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_BRIGHTNESS);
} catch (Exception ignored) {}
    }

    private void finalizeKioskAfterSuccess() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            // Disable USB file transfer only AFTER provisioning is done
            try {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
                Log.i(TAG, "USB file transfer disabled (charging-only behavior).");
            } catch (Exception e) {
                Log.e(TAG, "DISALLOW_USB_FILE_TRANSFER failed: " + e.getMessage(), e);
            }

            // Start lock task (kiosk)
            try {
		Log.i(TAG, "Calling startLockTask()");
                startLockTask();
                Log.i(TAG, "LockTask started.");
            } catch (Exception e) {
                Log.e(TAG, "startLockTask failed: " + e.getMessage(), e);
            }
        }
    }

    // =========================
    // UI / Hardening
    // =========================

    private void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void forceMaxBrightness() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 1.0f;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {
        }
    }

    private void enableImmersiveModeSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                View decor = getWindow().getDecorView();
                if (decor == null) return;

                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller == null) {
                    handler.postDelayed(this::enableImmersiveModeSafe, 200);
                    return;
                }

                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            } else {
                View decorView = getWindow().getDecorView();
                if (decorView == null) return;

                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            }
        } catch (Throwable t) {
            Log.e(TAG, "enableImmersiveModeSafe failed", t);
        }
    }

    // swallow volume keys
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
