package vl.vision.home.util.data.wifi;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;

/**
 * Created by hanqq on 2022/2/21
 * Email:yin13753884368@163.com
 * CSDN:http://blog.csdn.net/yin13753884368/article
 * Github:https://github.com/taxiao213
 */
public class WifiTrackerFactory {
    private static WifiTracker sTestingWifiTracker;

    @Keep
    public static void setTestingWifiTracker(WifiTracker tracker) {
        sTestingWifiTracker = tracker;
    }

    public static WifiTracker create(Context context, WifiTracker.WifiListener wifiListener, @NonNull Lifecycle lifecycle, boolean includeSaved, boolean includeScans) {
        if (sTestingWifiTracker != null)
            return sTestingWifiTracker;
        return new WifiTracker(context, wifiListener, lifecycle, includeSaved, includeScans);
    }
}
