package com.xam.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

@SuppressWarnings("deprecation")
public class WifiMonitorReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(intent.getAction())) return;

        NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
        if (networkInfo == null || networkInfo.getState() != NetworkInfo.State.DISCONNECTED) return;

        String ssid = context.getSharedPreferences("hub_config", Context.MODE_PRIVATE)
                .getString("hub_wifi_ssid", null);
        if (ssid == null) return;

        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        List<WifiConfiguration> configs = wm.getConfiguredNetworks();
        if (configs == null) return;

        for (WifiConfiguration c : configs) {
            if (("\"" + ssid + "\"").equals(c.SSID)) {
                wm.enableNetwork(c.networkId, true);
                wm.reconnect();
                Log.i("KioskWifi", "WiFi dropped — reconnecting to " + ssid);
                return;
            }
        }
    }
}
