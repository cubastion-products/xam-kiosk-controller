package com.xam.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

@SuppressWarnings("deprecation")
public class WifiMonitorReceiver extends BroadcastReceiver {

    private static final String TAG = "KioskWifi";
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_INTERVAL_MS = 5000;

    private static boolean retrying = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(intent.getAction())) return;

        NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
        if (networkInfo == null || networkInfo.getState() != NetworkInfo.State.DISCONNECTED) return;

        if (retrying) return; // already running a retry loop

        String ssid = context.getSharedPreferences("hub_config", Context.MODE_PRIVATE)
                .getString("hub_wifi_ssid", null);
        if (ssid == null) return;

        Log.i(TAG, "WiFi dropped — starting reconnect loop for " + ssid);
        retrying = true;
        attemptReconnect(context.getApplicationContext(), ssid, 1);
    }

    private static void attemptReconnect(Context context, String ssid, int attempt) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        List<WifiConfiguration> configs = wm.getConfiguredNetworks();
        if (configs != null) {
            for (WifiConfiguration c : configs) {
                if (("\"" + ssid + "\"").equals(c.SSID)) {
                    wm.enableNetwork(c.networkId, true);
                    wm.reconnect();
                    Log.i(TAG, "Reconnect attempt " + attempt + "/" + MAX_RETRIES + " for " + ssid);

                    // Schedule a check to see if we connected
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (isConnectedTo(wm, ssid)) {
                            Log.i(TAG, "Reconnected to " + ssid + " on attempt " + attempt);
                            retrying = false;
                        } else if (attempt < MAX_RETRIES) {
                            attemptReconnect(context, ssid, attempt + 1);
                        } else {
                            Log.e(TAG, "Failed to reconnect after " + MAX_RETRIES + " attempts");
                            retrying = false;
                        }
                    }, RETRY_INTERVAL_MS);
                    return;
                }
            }
        }

        Log.e(TAG, "No saved network found for " + ssid);
        retrying = false;
    }

    private static boolean isConnectedTo(WifiManager wm, String ssid) {
        android.net.wifi.WifiInfo info = wm.getConnectionInfo();
        return info != null && ("\"" + ssid + "\"").equals(info.getSSID());
    }
}
