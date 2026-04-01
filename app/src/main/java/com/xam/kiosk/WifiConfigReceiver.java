package com.xam.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

@SuppressWarnings("deprecation")
public class WifiConfigReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String ssid = intent.getStringExtra("ssid");
        if (ssid == null || ssid.isEmpty()) return;

        context.getSharedPreferences("hub_config", Context.MODE_PRIVATE)
                .edit().putString("hub_wifi_ssid", ssid).apply();

        // Trigger WiFi connection
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wm.isWifiEnabled()) {
            wm.setWifiEnabled(true);
        }

        // Remove stale entries for same SSID
        List<WifiConfiguration> configs = wm.getConfiguredNetworks();
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
            Log.e("KioskWifi", "WifiConfigReceiver: addNetwork failed — is Device Owner confirmed?");
            return;
        }
        wm.disconnect();
        wm.enableNetwork(netId, true);
        wm.reconnect();
        Log.i("KioskWifi", "WifiConfigReceiver: WiFi connecting ssid=" + ssid + " netId=" + netId);
    }
}
