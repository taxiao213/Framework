package vl.vision.home.util.data.wifi;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.PasspointConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by hanqq on 2022/2/21
 * Email:yin13753884368@163.com
 * CSDN:http://blog.csdn.net/yin13753884368/article
 * Github:https://github.com/taxiao213
 */
public class WifiSavedConfigUtils {

    public static List<AccessPoint> getAllConfigs(Context context, WifiManager wifiManager) {
        List<AccessPoint> savedConfigs = new ArrayList<>();
        List<WifiConfiguration> savedNetworks = wifiManager.getConfiguredNetworks();
        for (WifiConfiguration network : savedNetworks) {
            if (network.isPasspoint())
                continue;
            if (network.isEphemeral())
                continue;
            savedConfigs.add(new AccessPoint(context, network));
        }
        try {
            List<PasspointConfiguration> savedPasspointConfigs = wifiManager.getPasspointConfigurations();
            if (savedPasspointConfigs != null)
                for (PasspointConfiguration config : savedPasspointConfigs)
                    savedConfigs.add(new AccessPoint(context, config));
        } catch (UnsupportedOperationException unsupportedOperationException) {}
        return savedConfigs;
    }

}
