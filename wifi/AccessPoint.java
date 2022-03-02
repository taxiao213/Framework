package vl.vision.home.util.data.wifi;

/**
 * Created by hanqq on 2022/2/21
 * Email:yin13753884368@163.com
 * CSDN:http://blog.csdn.net/yin13753884368/article
 * Github:https://github.com/taxiao213
 */

import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.net.ScoredNetwork;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo.State;
import android.net.wifi.IWifiManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.IWifiManager.Stub;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiManager.ActionListener;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.android.internal.util.CollectionUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import vl.vision.home.R;

public class AccessPoint implements Comparable<AccessPoint> {
    static final String TAG = "SettingsLib.AccessPoint";
    public static final int LOWER_FREQ_24GHZ = 2400;
    public static final int HIGHER_FREQ_24GHZ = 2500;
    public static final int LOWER_FREQ_5GHZ = 4900;
    public static final int HIGHER_FREQ_5GHZ = 5900;
    public static final int LOWER_FREQ_60GHZ = 58320;
    public static final int HIGHER_FREQ_60GHZ = 70200;
    public static final int LOWER_FREQ_6GHZ = 5925;
    public static final int HIGHER_FREQ_6GHZ = 7125;
    private String mKey;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArraySet<ScanResult> mScanResults = new ArraySet();
    @GuardedBy("mLock")
    private final ArraySet<ScanResult> mExtraScanResults = new ArraySet();
    private final Map<String, TimestampedScoredNetwork> mScoredNetworkCache = new HashMap();
    static final String KEY_NETWORKINFO = "key_networkinfo";
    static final String KEY_WIFIINFO = "key_wifiinfo";
    static final String KEY_SSID = "key_ssid";
    static final String KEY_SECURITY = "key_security";
    static final String KEY_SPEED = "key_speed";
    static final String KEY_PSKTYPE = "key_psktype";
    static final String KEY_SCANRESULTS = "key_scanresults";
    static final String KEY_SCOREDNETWORKCACHE = "key_scorednetworkcache";
    static final String KEY_CONFIG = "key_config";
    static final String KEY_FQDN = "key_fqdn";
    static final String KEY_PROVIDER_FRIENDLY_NAME = "key_provider_friendly_name";
    static final String KEY_IS_CARRIER_AP = "key_is_carrier_ap";
    static final String KEY_CARRIER_AP_EAP_TYPE = "key_carrier_ap_eap_type";
    static final String KEY_CARRIER_NAME = "key_carrier_name";
    static final String KEY_EAPTYPE = "eap_psktype";
    static final String KEY_IS_PSK_SAE_TRANSITION_MODE = "key_is_psk_sae_transition_mode";
    static final String KEY_IS_OWE_TRANSITION_MODE = "key_is_owe_transition_mode";
    static final AtomicInteger sLastId = new AtomicInteger(0);
    public static final int SECURITY_NONE = 0;
    public static final int SECURITY_WEP = 1;
    public static final int SECURITY_PSK = 2;
    public static final int SECURITY_EAP = 3;
    public static final int SECURITY_OWE = 4;
    public static final int SECURITY_SAE = 5;
    public static final int SECURITY_EAP_SUITE_B = 6;
    public static final int SECURITY_DPP = 7;
    public static final int SECURITY_MAX_VAL = 8;
    private static final int PSK_UNKNOWN = 0;
    private static final int PSK_WPA = 1;
    private static final int PSK_WPA2 = 2;
    private static final int PSK_WPA_WPA2 = 3;
    private static final int EAP_UNKNOWN = 0;
    private static final int EAP_WPA = 1;
    private static final int EAP_WPA2_WPA3 = 2;
    private static final int LEGACY_CAPABLE_BSSID = 0;
    private static final int HT_CAPABLE_BSSID = 1;
    private static final int VHT_CAPABLE_BSSID = 2;
    private static final int HE_CAPABLE_BSSID = 3;
    private static final int MAX_CAPABLE_BSSID = 2147483647;
    private static final int WIFI_GENERATION_LEGACY = 0;
    private static final int WIFI_GENERATION_4 = 4;
    private static final int WIFI_GENERATION_5 = 5;
    private static final int WIFI_GENERATION_6 = 6;
    public static final int SIGNAL_LEVELS = 5;
    public static final int UNREACHABLE_RSSI = -2147483648;
    public static final String KEY_PREFIX_AP = "AP:";
    public static final String KEY_PREFIX_FQDN = "FQDN:";
    public static final String KEY_PREFIX_OSU = "OSU:";
    private final Context mContext;
    private WifiManager mWifiManager;
    private ActionListener mConnectListener;
    private String ssid;
    private String bssid;
    private int security;
    private int networkId = -1;
    private int pskType = 0;
    private int mEapType = 0;
    private WifiConfiguration mConfig;
    private int mRssi = -2147483648;
    private int mWifiGeneration = 0;
    private boolean mHe8ssCapableAp = false;
    private boolean mVhtMax8SpatialStreamsSupport = false;
    private WifiInfo mInfo;
    private NetworkInfo mNetworkInfo;
    AccessPoint.AccessPointListener mAccessPointListener;
    private Object mTag;
    private int mSpeed = 0;
    private boolean mIsScoredNetworkMetered = false;
    private String mFqdn;
    private String mProviderFriendlyName;
    private boolean mIsRoaming = false;
    private boolean mIsCarrierAp = false;
    private OsuProvider mOsuProvider;
    private String mOsuStatus;
    private String mOsuFailure;
    private boolean mOsuProvisioningComplete = false;
    private boolean mIsPskSaeTransitionMode = false;
    private boolean mIsOweTransitionMode = false;
    private int mCarrierApEapType = -1;
    private String mCarrierName = null;
    private int mType = -1;

    public AccessPoint(int type) {
        this.mContext = null;
        this.mType = type;
    }

    public AccessPoint(Context context, Bundle savedState) {
        this.mContext = context;
        if (savedState.containsKey("key_config")) {
            this.mConfig = (WifiConfiguration) savedState.getParcelable("key_config");
        }

        if (this.mConfig != null) {
            this.loadConfig(this.mConfig);
        }

        if (savedState.containsKey("key_ssid")) {
            this.ssid = savedState.getString("key_ssid");
        }

        if (savedState.containsKey("key_security")) {
            this.security = savedState.getInt("key_security");
        }

        if (savedState.containsKey("key_speed")) {
            this.mSpeed = savedState.getInt("key_speed");
        }

        if (savedState.containsKey("key_psktype")) {
            this.pskType = savedState.getInt("key_psktype");
        }

        if (savedState.containsKey("eap_psktype")) {
            this.mEapType = savedState.getInt("eap_psktype");
        }

        this.mInfo = (WifiInfo) savedState.getParcelable("key_wifiinfo");
        if (savedState.containsKey("key_networkinfo")) {
            this.mNetworkInfo = (NetworkInfo) savedState.getParcelable("key_networkinfo");
        }

        if (savedState.containsKey("key_scanresults")) {
            Parcelable[] scanResults = savedState.getParcelableArray("key_scanresults");
            this.mScanResults.clear();
            Parcelable[] var4 = scanResults;
            int var5 = scanResults.length;

            for (int var6 = 0; var6 < var5; ++var6) {
                Parcelable result = var4[var6];
                this.mScanResults.add((ScanResult) result);
            }
        }

        if (savedState.containsKey("key_scorednetworkcache")) {
            ArrayList<TimestampedScoredNetwork> scoredNetworkArrayList = savedState.getParcelableArrayList("key_scorednetworkcache");
            Iterator var9 = scoredNetworkArrayList.iterator();

            while (var9.hasNext()) {
                TimestampedScoredNetwork timedScore = (TimestampedScoredNetwork) var9.next();
                this.mScoredNetworkCache.put(timedScore.getScore().networkKey.wifiKey.bssid, timedScore);
            }
        }

        if (savedState.containsKey("key_fqdn")) {
            this.mFqdn = savedState.getString("key_fqdn");
        }

        if (savedState.containsKey("key_provider_friendly_name")) {
            this.mProviderFriendlyName = savedState.getString("key_provider_friendly_name");
        }

        if (savedState.containsKey("key_is_carrier_ap")) {
            this.mIsCarrierAp = savedState.getBoolean("key_is_carrier_ap");
        }

        if (savedState.containsKey("key_carrier_ap_eap_type")) {
            this.mCarrierApEapType = savedState.getInt("key_carrier_ap_eap_type");
        }

        if (savedState.containsKey("key_carrier_name")) {
            this.mCarrierName = savedState.getString("key_carrier_name");
        }

        if (savedState.containsKey("key_is_psk_sae_transition_mode")) {
            this.mIsPskSaeTransitionMode = savedState.getBoolean("key_is_psk_sae_transition_mode");
        }

        if (savedState.containsKey("key_is_owe_transition_mode")) {
            this.mIsOweTransitionMode = savedState.getBoolean("key_is_owe_transition_mode");
        }

        this.update(this.mConfig, this.mInfo, this.mNetworkInfo);
        this.updateKey();
        this.updateBestRssiInfo();
        this.updateWifiGeneration();
    }

    public AccessPoint(Context context, WifiConfiguration config) {
        this.mContext = context;
        this.loadConfig(config);
        this.updateKey();
    }

    public AccessPoint(Context context, PasspointConfiguration config) {
        this.mContext = context;
        this.mFqdn = config.getHomeSp().getFqdn();
        this.mProviderFriendlyName = config.getHomeSp().getFriendlyName();
        this.updateKey();
    }

    public AccessPoint(@NonNull Context context, @NonNull WifiConfiguration config, Collection<ScanResult> homeScans, Collection<ScanResult> roamingScans) {
        this.mContext = context;
        this.networkId = config.networkId;
        this.mConfig = config;
        this.mFqdn = config.FQDN;
        this.setScanResultsPasspoint(homeScans, roamingScans);
        this.updateKey();
    }

    public AccessPoint(@NonNull Context context, @NonNull OsuProvider provider, @NonNull Collection<ScanResult> results) {
        this.mContext = context;
        this.mOsuProvider = provider;
        this.setScanResults(results);
        this.updateKey();
    }

    AccessPoint(Context context, Collection<ScanResult> results) {
        this.mContext = context;
        this.setScanResults(results);
        this.updateKey();
    }

    public void setmType(int mType) {
        this.mType = mType;
    }

    public int getmType() {
        return mType;
    }

    void loadConfig(WifiConfiguration config) {
        this.ssid = config.SSID == null ? "" : removeDoubleQuotes(config.SSID);
        this.bssid = config.BSSID;
        this.security = getSecurity(config);
        this.networkId = config.networkId;
        this.mConfig = config;
    }

    private void updateKey() {
        if (this.isPasspoint()) {
            this.mKey = getKey(this.mConfig);
        } else if (this.isPasspointConfig()) {
            this.mKey = getKey(this.mFqdn);
        } else if (this.isOsuProvider()) {
            this.mKey = getKey(this.mOsuProvider);
        } else {
            this.mKey = getKey(this.getSsidStr(), this.getBssid(), this.getSecurity());
        }

    }

    public int compareTo(AccessPoint other) {
        if (this.isActive() && !other.isActive()) {
            return -1;
        } else if (!this.isActive() && other.isActive()) {
            return 1;
        } else if (this.isReachable() && !other.isReachable()) {
            return -1;
        } else if (!this.isReachable() && other.isReachable()) {
            return 1;
        } else if (this.isSaved() && !other.isSaved()) {
            return -1;
        } else if (!this.isSaved() && other.isSaved()) {
            return 1;
        } else if (this.getSpeed() != other.getSpeed()) {
            return other.getSpeed() - this.getSpeed();
        } else {
            int difference = WifiManager.calculateSignalLevel(other.mRssi, 5) - WifiManager.calculateSignalLevel(this.mRssi, 5);
            if (difference != 0) {
                return difference;
            } else {
                difference = this.getTitle().compareToIgnoreCase(other.getTitle());
                return difference != 0 ? difference : this.getSsidStr().compareTo(other.getSsidStr());
            }
        }
    }

    public boolean equals(Object other) {
        if (!(other instanceof AccessPoint)) {
            return false;
        } else {
            return (compareTo((AccessPoint) other) == 0);
        }
    }

    public int hashCode() {
        int result = 0;
        if (this.mInfo != null) {
            result += 13 * this.mInfo.hashCode();
        }

        result += 19 * this.mRssi;
        result += 23 * this.networkId;
        result += 29 * this.ssid.hashCode();
        return result;
    }

    public String toString() {
        StringBuilder builder = (new StringBuilder()).append("AccessPoint(").append(this.ssid);
        if (this.bssid != null) {
            builder.append(":").append(this.bssid);
        }

        if (this.isSaved()) {
            builder.append(',').append("saved");
        }

        if (this.isActive()) {
            builder.append(',').append("active");
        }

        if (this.isEphemeral()) {
            builder.append(',').append("ephemeral");
        }

        if (this.isConnectable()) {
            builder.append(',').append("connectable");
        }

        if (this.security != 0 && this.security != 4) {
            builder.append(',').append(securityToString(this.security, this.pskType));
        }

        builder.append(",level=").append(this.getLevel());
        if (this.mSpeed != 0) {
            builder.append(",speed=").append(this.mSpeed);
        }

        builder.append(",metered=").append(this.isMetered());
        if (isVerboseLoggingEnabled()) {
            builder.append(",rssi=").append(this.mRssi);
            synchronized (this.mLock) {
                builder.append(",scan cache size=").append(this.mScanResults.size() + this.mExtraScanResults.size());
            }
        }

        return builder.append(')').toString();
    }

    boolean update(WifiNetworkScoreCache scoreCache, boolean scoringUiEnabled, long maxScoreCacheAgeMillis) {
        boolean scoreChanged = false;
        if (scoringUiEnabled) {
            scoreChanged = this.updateScores(scoreCache, maxScoreCacheAgeMillis);
        }

        return this.updateMetered(scoreCache) || scoreChanged;
    }

    private boolean updateScores(WifiNetworkScoreCache scoreCache, long maxScoreCacheAgeMillis) {
        long nowMillis = SystemClock.elapsedRealtime();
        synchronized (this.mLock) {
            Iterator var7 = this.mScanResults.iterator();

            while (true) {
                if (!var7.hasNext()) {
                    break;
                }

                ScanResult result = (ScanResult) var7.next();
                ScoredNetwork score = scoreCache.getScoredNetwork(result);
                if (score != null) {
                    TimestampedScoredNetwork timedScore = (TimestampedScoredNetwork) this.mScoredNetworkCache.get(result.BSSID);
                    if (timedScore == null) {
                        this.mScoredNetworkCache.put(result.BSSID, new TimestampedScoredNetwork(score, nowMillis));
                    } else {
                        timedScore.update(score, nowMillis);
                    }
                }
            }
        }

        long evictionCutoff = nowMillis - maxScoreCacheAgeMillis;
        Iterator<TimestampedScoredNetwork> iterator = this.mScoredNetworkCache.values().iterator();
        iterator.forEachRemaining((timestampedScoredNetwork) -> {
            if (timestampedScoredNetwork.getUpdatedTimestampMillis() < evictionCutoff) {
                iterator.remove();
            }

        });
        return this.updateSpeed();
    }

    private boolean updateSpeed() {
        int oldSpeed = this.mSpeed;
        this.mSpeed = this.generateAverageSpeedForSsid();
        boolean changed = oldSpeed != this.mSpeed;
        if (isVerboseLoggingEnabled() && changed) {
            Log.i("SettingsLib.AccessPoint", String.format("%s: Set speed to %d", this.ssid, this.mSpeed));
        }

        return changed;
    }

    private int generateAverageSpeedForSsid() {
        if (this.mScoredNetworkCache.isEmpty()) {
            return 0;
        } else {
            if (Log.isLoggable("SettingsLib.AccessPoint", 3)) {
                Log.d("SettingsLib.AccessPoint", String.format("Generating fallbackspeed for %s using cache: %s", this.getSsidStr(), this.mScoredNetworkCache));
            }

            int count = 0;
            int totalSpeed = 0;
            Iterator var3 = this.mScoredNetworkCache.values().iterator();

            while (var3.hasNext()) {
                TimestampedScoredNetwork timedScore = (TimestampedScoredNetwork) var3.next();
                int speed = timedScore.getScore().calculateBadge(this.mRssi);
                if (speed != 0) {
                    ++count;
                    totalSpeed += speed;
                }
            }

            int speed = count == 0 ? 0 : totalSpeed / count;
            if (isVerboseLoggingEnabled()) {
                Log.i("SettingsLib.AccessPoint", String.format("%s generated fallback speed is: %d", this.getSsidStr(), speed));
            }

            return roundToClosestSpeedEnum(speed);
        }
    }

    private boolean updateMetered(WifiNetworkScoreCache scoreCache) {
        boolean oldMetering = this.mIsScoredNetworkMetered;
        this.mIsScoredNetworkMetered = false;
        if (this.isActive() && this.mInfo != null) {
            NetworkKey key = NetworkKey.createFromWifiInfo(this.mInfo);
            ScoredNetwork score = scoreCache.getScoredNetwork(key);
            if (score != null) {
                this.mIsScoredNetworkMetered |= score.meteredHint;
            }
        } else {
            synchronized (this.mLock) {
                Iterator var4 = this.mScanResults.iterator();

                while (var4.hasNext()) {
                    ScanResult result = (ScanResult) var4.next();
                    ScoredNetwork score = scoreCache.getScoredNetwork(result);
                    if (score != null) {
                        this.mIsScoredNetworkMetered |= score.meteredHint;
                    }
                }
            }
        }

        return oldMetering == this.mIsScoredNetworkMetered;
    }

    public static String getKey(Context context, ScanResult result) {
        return getKey(result.SSID, result.BSSID, getSecurity(context, result));
    }

    public static String getKey(WifiConfiguration config) {
        return config.isPasspoint() ? getKey(config.FQDN) : getKey(removeDoubleQuotes(config.SSID), config.BSSID, getSecurity(config));
    }

    public static String getKey(String fqdn) {
        return "FQDN:" + fqdn;
    }

    public static String getKey(OsuProvider provider) {
        return "OSU:" + provider.getFriendlyName() + ',' + provider.getServerUri();
    }

    private static String getKey(String ssid, String bssid, int security) {
        StringBuilder builder = new StringBuilder();
        builder.append("AP:");
        if (TextUtils.isEmpty(ssid)) {
            builder.append(bssid);
        } else {
            builder.append(ssid);
        }

        builder.append(',').append(security);
        return builder.toString();
    }

    public String getKey() {
        return this.mKey;
    }

    public boolean matches(AccessPoint other) {
        if (!this.isPasspoint() && !this.isPasspointConfig() && !this.isOsuProvider()) {
            if (!this.isSameSsidOrBssid(other)) {
                return false;
            } else {
                int otherApSecurity = other.getSecurity();
                if (this.mIsPskSaeTransitionMode) {
                    if (otherApSecurity == 5 && this.getWifiManager().isWpa3SaeSupported()) {
                        return true;
                    }

                    if (otherApSecurity == 2) {
                        return true;
                    }
                } else if ((this.security == 5 || this.security == 2) && other.isPskSaeTransitionMode()) {
                    return true;
                }

                if (this.mIsOweTransitionMode) {
                    if (otherApSecurity == 4 && this.getWifiManager().isEnhancedOpenSupported()) {
                        return true;
                    }

                    if (otherApSecurity == 0) {
                        return true;
                    }
                } else if ((this.security == 4 || this.security == 0) && other.isOweTransitionMode()) {
                    return true;
                }

                return this.security == other.getSecurity();
            }
        } else {
            return this.getKey().equals(other.getKey());
        }
    }

    public boolean matches(WifiConfiguration config) {
        if (config.isPasspoint()) {
            return this.isPasspoint() && config.FQDN.equals(this.mConfig.FQDN);
        } else if (this.ssid.equals(removeDoubleQuotes(config.SSID)) && (this.mConfig == null || this.mConfig.shared == config.shared)) {
            int configSecurity = getSecurity(config);
            if (this.mIsPskSaeTransitionMode) {
                if (configSecurity == 5 && this.getWifiManager().isWpa3SaeSupported()) {
                    return true;
                }

                if (configSecurity == 2) {
                    return true;
                }
            }

            if (this.mIsOweTransitionMode) {
                if (configSecurity == 4 && this.getWifiManager().isEnhancedOpenSupported()) {
                    return true;
                }

                if (configSecurity == 0) {
                    return true;
                }
            }

            return this.security == getSecurity(config);
        } else {
            return false;
        }
    }

    private boolean matches(WifiConfiguration config, WifiInfo wifiInfo) {
        if (config != null && wifiInfo != null) {
            return !config.isPasspoint() && !this.isSameSsidOrBssid(wifiInfo) ? false : this.matches(config);
        } else {
            return false;
        }
    }

    boolean matches(ScanResult scanResult) {
        if (scanResult == null) {
            return false;
        } else if (!this.isPasspoint() && !this.isOsuProvider()) {
            if (!this.isSameSsidOrBssid(scanResult)) {
                return false;
            } else {
                if (this.mIsPskSaeTransitionMode) {
                    if (scanResult.capabilities.contains("SAE") && this.getWifiManager().isWpa3SaeSupported()) {
                        return true;
                    }

                    if (scanResult.capabilities.contains("PSK")) {
                        return true;
                    }
                } else if ((this.security == 5 || this.security == 2) && isPskSaeTransitionMode(scanResult)) {
                    return true;
                }

                if (this.mIsOweTransitionMode) {
                    int scanResultSccurity = getSecurity(this.mContext, scanResult);
                    if (scanResultSccurity == 4 && this.getWifiManager().isEnhancedOpenSupported()) {
                        return true;
                    }

                    if (scanResultSccurity == 0) {
                        return true;
                    }
                } else if ((this.security == 4 || this.security == 0) && isOweTransitionMode(scanResult)) {
                    return true;
                }

                return this.security == getSecurity(this.mContext, scanResult);
            }
        } else {
            throw new IllegalStateException("Should not matches a Passpoint by ScanResult");
        }
    }

    public WifiConfiguration getConfig() {
        return this.mConfig;
    }

    public String getPasspointFqdn() {
        return this.mFqdn;
    }

    public void clearConfig() {
        this.mConfig = null;
        this.networkId = -1;
    }

    public boolean isFils256Supported() {
        IWifiManager wifiManager = Stub.asInterface(ServiceManager.getService("wifi"));
        String capability = "";

        try {
            capability = wifiManager.getCapabilities("key_mgmt");
        } catch (RemoteException var5) {
            Log.w("SettingsLib.AccessPoint", "Remote Exception", var5);
        }

        if (!capability.contains("FILS-SHA256")) {
            return false;
        } else {
            Iterator var3 = this.mScanResults.iterator();

            ScanResult result;
            do {
                if (!var3.hasNext()) {
                    return false;
                }

                result = (ScanResult) var3.next();
            } while (!result.capabilities.contains("FILS-SHA256"));

            return true;
        }
    }

    public boolean isSuiteBSupported() {
        IWifiManager wifiManager = Stub.asInterface(ServiceManager.getService("wifi"));
        String capability = "";

        try {
            capability = wifiManager.getCapabilities("key_mgmt");
        } catch (RemoteException var5) {
            Log.w("SettingsLib.AccessPoint", "Remote Exception", var5);
        }

        if (!capability.contains("WPA-EAP-SUITE-B-192")) {
            return false;
        } else {
            Iterator var3 = this.mScanResults.iterator();

            ScanResult result;
            do {
                if (!var3.hasNext()) {
                    return false;
                }

                result = (ScanResult) var3.next();
            } while (!result.capabilities.contains("EAP_SUITE_B_192"));

            return true;
        }
    }

    public boolean isFils384Supported() {
        IWifiManager wifiManager = Stub.asInterface(ServiceManager.getService("wifi"));
        String capability = "";

        try {
            capability = wifiManager.getCapabilities("key_mgmt");
        } catch (RemoteException var5) {
            Log.w("SettingsLib.AccessPoint", "Remote Exception", var5);
        }

        if (!capability.contains("FILS-SHA384")) {
            return false;
        } else {
            Iterator var3 = this.mScanResults.iterator();

            ScanResult result;
            do {
                if (!var3.hasNext()) {
                    return false;
                }

                result = (ScanResult) var3.next();
            } while (!result.capabilities.contains("FILS-SHA384"));

            return true;
        }
    }

    private static boolean isWpa3SaeSupported() {
        IWifiManager wifiManager = Stub.asInterface(ServiceManager.getService("wifi"));
        long supportedFeature = 0L;
        long feature = 134217728L;

        try {
            supportedFeature = wifiManager.getSupportedFeatures();
        } catch (RemoteException var6) {
            Log.w("SettingsLib.AccessPoint", "Remote Exception", var6);
        }

        return (supportedFeature & feature) == feature;
    }

    private static boolean isEnhancedOpenSupported() {
        IWifiManager wifiManager = Stub.asInterface(ServiceManager.getService("wifi"));
        long supportedFeature = 0L;
        long feature = 536870912L;

        try {
            supportedFeature = wifiManager.getSupportedFeatures();
        } catch (RemoteException var6) {
            Log.w("SettingsLib.AccessPoint", "Remote Exception", var6);
        }

        return (supportedFeature & feature) == feature;
    }

    public static boolean checkForSaeTransitionMode(ScanResult result) {
        return result.capabilities.contains("SAE") && result.capabilities.contains("PSK");
    }

    public static boolean checkForOweTransitionMode(ScanResult result) {
        return result.capabilities.contains("OWE_TRANSITION");
    }

    public String getFallbackKey() {
        if (this.security == 5) {
            return getKey(this.ssid, this.bssid, 2);
        } else {
            return this.security == 4 ? getKey(this.ssid, this.bssid, 0) : this.mKey;
        }
    }

    public WifiInfo getInfo() {
        return this.mInfo;
    }

    public int getLevel() {
        return WifiManager.calculateSignalLevel(this.mRssi, 5);
    }

    public int getRssi() {
        return this.mRssi;
    }

    public Set<ScanResult> getScanResults() {
        Set<ScanResult> allScans = new ArraySet();
        synchronized (this.mLock) {
            allScans.addAll(this.mScanResults);
            allScans.addAll(this.mExtraScanResults);
            return allScans;
        }
    }

    public Map<String, TimestampedScoredNetwork> getScoredNetworkCache() {
        return this.mScoredNetworkCache;
    }

    private void updateBestRssiInfo() {
        if (!this.isActive()) {
            ScanResult bestResult = null;
            int bestRssi = -2147483648;
            synchronized (this.mLock) {
                Iterator var4 = this.mScanResults.iterator();

                while (true) {
                    if (!var4.hasNext()) {
                        break;
                    }

                    ScanResult result = (ScanResult) var4.next();
                    if (result.level > bestRssi) {
                        bestRssi = result.level;
                        bestResult = result;
                    }
                }
            }

            if (bestRssi != -2147483648 && this.mRssi != -2147483648) {
                this.mRssi = (this.mRssi + bestRssi) / 2;
            } else {
                this.mRssi = bestRssi;
            }

            if (bestResult != null) {
                this.ssid = bestResult.SSID;
                this.bssid = bestResult.BSSID;
                this.security = getSecurity(this.mContext, bestResult);
                if (this.security == 2 || this.security == 5) {
                    this.pskType = getPskType(bestResult);
                }

                if (this.security == 3) {
                    this.mEapType = getEapType(bestResult);
                }

                this.mIsPskSaeTransitionMode = isPskSaeTransitionMode(bestResult);
                this.mIsOweTransitionMode = isOweTransitionMode(bestResult);
                this.mIsCarrierAp = bestResult.isCarrierAp;
                this.mCarrierApEapType = bestResult.carrierApEapType;
                this.mCarrierName = bestResult.carrierName;
            }

            if (this.isPasspoint()) {
                this.mConfig.SSID = convertToQuotedString(this.ssid);
            }

        }
    }

    private int getMaxCapability(ScanResult result) {
        if (isVerboseLoggingEnabled()) {
            Log.i("SettingsLib.AccessPoint", "SSID: " + result.SSID + ", bssid: " + result.BSSID + ", capabilities: " + result.capabilities);
        }

        if (result.capabilities.contains("WFA-HE")) {
            return 3;
        } else if (result.capabilities.contains("WFA-VHT")) {
            return 2;
        } else {
            return result.capabilities.contains("WFA-HT") ? 1 : 0;
        }
    }

    private void updateWifiGeneration() {
        if (!this.isActive()) {
            int scanResultsMinCapability = 2147483647;
            this.mHe8ssCapableAp = false;
            this.mVhtMax8SpatialStreamsSupport = false;
            Iterator var3 = this.mScanResults.iterator();

            while (var3.hasNext()) {
                ScanResult result = (ScanResult) var3.next();
                int currBssidMaxCapability = this.getMaxCapability(result);
                if (currBssidMaxCapability < scanResultsMinCapability) {
                    scanResultsMinCapability = currBssidMaxCapability;
                }
            }

            switch (scanResultsMinCapability) {
                case 1:
                    this.mWifiGeneration = 4;
                    break;
                case 2:
                    this.mWifiGeneration = 5;
                    break;
                case 3:
                    this.mWifiGeneration = 6;
                    break;
                default:
                    this.mWifiGeneration = 0;
            }

        }
    }

    public int getWifiGeneration() {
        return this.mWifiGeneration;
    }

    public boolean isHe8ssCapableAp() {
        return this.mHe8ssCapableAp;
    }

    public boolean isVhtMax8SpatialStreamsSupported() {
        return this.mVhtMax8SpatialStreamsSupport;
    }

    public boolean isMetered() {
        return this.mIsScoredNetworkMetered || WifiConfiguration.isMetered(this.mConfig, this.mInfo);
    }

    public NetworkInfo getNetworkInfo() {
        return this.mNetworkInfo;
    }

    public int getSecurity() {
        return this.security;
    }

    public String getSecurityString(boolean concise) {
        Context context = this.mContext;
        if (!this.isPasspoint() && !this.isPasspointConfig()) {
            if (this.mIsPskSaeTransitionMode) {
                return concise ? context.getString(R.string.wifi_security_short_psk_sae) : context.getString(R.string.wifi_security_psk_sae);
            } else {
                switch (this.security) {
                    case 0:
                    default:
                        return concise ? "" : context.getString(R.string.wifi_security_none);
                    case 1:
                        return concise ? context.getString(R.string.wifi_security_short_wep) : context.getString(R.string.wifi_security_wep);
                    case 2:
                        switch (this.pskType) {
                            case 0:
                            default:
                                return concise ? context.getString(R.string.wifi_security_short_psk_generic) : context.getString(R.string.wifi_security_psk_generic);
                            case 1:
                                return concise ? context.getString(R.string.wifi_security_short_wpa) : context.getString(R.string.wifi_security_wpa);
                            case 2:
                                return concise ? context.getString(R.string.wifi_security_short_wpa2) : context.getString(R.string.wifi_security_wpa2);
                            case 3:
                                return concise ? context.getString(R.string.wifi_security_short_wpa_wpa2) : context.getString(R.string.wifi_security_wpa_wpa2);
                        }
                    case 3:
                        switch (this.mEapType) {
                            case 0:
                            default:
                                return concise ? context.getString(R.string.wifi_security_short_eap) : context.getString(R.string.wifi_security_eap);
                            case 1:
                                return concise ? context.getString(R.string.wifi_security_short_eap_wpa) : context.getString(R.string.wifi_security_eap_wpa);
                            case 2:
                                return concise ? context.getString(R.string.wifi_security_short_eap_wpa2_wpa3) : context.getString(R.string.wifi_security_eap_wpa2_wpa3);
                        }
                    case 4:
                        return concise ? context.getString(R.string.wifi_security_short_owe) : context.getString(R.string.wifi_security_owe);
                    case 5:
                        return concise ? context.getString(R.string.wifi_security_short_sae) : context.getString(R.string.wifi_security_sae);
                    case 6:
                        return concise ? context.getString(R.string.wifi_security_short_eap_suiteb) : context.getString(R.string.wifi_security_eap_suiteb);
                    case 7:
                        return concise ? context.getString(R.string.wifi_security_short_dpp) : context.getString(R.string.wifi_security_dpp);
                }
            }
        } else {
            return concise ? context.getString(R.string.wifi_security_short_eap) : context.getString(R.string.wifi_security_eap);
        }
    }

    public String getSsidStr() {
        return this.ssid;
    }

    public String getBssid() {
        return this.bssid;
    }

    public CharSequence getSsid() {
        return this.ssid;
    }

    /**
     * @deprecated
     */
    @Deprecated
    public String getConfigName() {
        if (this.mConfig != null && this.mConfig.isPasspoint()) {
            return this.mConfig.providerFriendlyName;
        } else {
            return this.mFqdn != null ? this.mProviderFriendlyName : this.ssid;
        }
    }

    public DetailedState getDetailedState() {
        if (this.mNetworkInfo != null) {
            return this.mNetworkInfo.getDetailedState();
        } else {
            Log.w("SettingsLib.AccessPoint", "NetworkInfo is null, cannot return detailed state");
            return null;
        }
    }

    public boolean isCarrierAp() {
        return this.mIsCarrierAp;
    }

    public int getCarrierApEapType() {
        return this.mCarrierApEapType;
    }

    public String getCarrierName() {
        return this.mCarrierName;
    }

    public String getSavedNetworkSummary() {
        WifiConfiguration config = this.mConfig;
        if (config != null) {
            PackageManager pm = this.mContext.getPackageManager();
            String systemName = pm.getNameForUid(1000);
            int userId = UserHandle.getUserId(config.creatorUid);
            ApplicationInfo appInfo = null;
            if (config.creatorName != null && config.creatorName.equals(systemName)) {
                appInfo = this.mContext.getApplicationInfo();
            } else {
                try {
                    IPackageManager ipm = AppGlobals.getPackageManager();
                    appInfo = ipm.getApplicationInfo(config.creatorName, 0, userId);
                } catch (RemoteException var7) {
                }
            }

            if (appInfo != null && !appInfo.packageName.equals(this.mContext.getString(R.string.settings_package)) && !appInfo.packageName.equals(this.mContext.getString(R.string.certinstaller_package))) {
                return this.mContext.getString(R.string.saved_network, new Object[]{appInfo.loadLabel(pm)});
            }
        }

        return "";
    }

    public String getTitle() {
        if (this.isPasspoint()) {
            return this.mConfig.providerFriendlyName;
        } else if (this.isPasspointConfig()) {
            return this.mProviderFriendlyName;
        } else {
            return this.isOsuProvider() ? this.mOsuProvider.getFriendlyName() : this.getSsidStr();
        }
    }

    public String getSummary() {
        return this.getSettingsSummary();
    }

    public String getSettingsSummary() {
        return this.getSettingsSummary(false);
    }

    public String getSettingsSummary(boolean convertSavedAsDisconnected) {
        StringBuilder summary = new StringBuilder();
        if (this.isOsuProvider()) {
            if (this.mOsuProvisioningComplete) {
                summary.append(this.mContext.getString(R.string.osu_sign_up_complete));
            } else if (this.mOsuFailure != null) {
                summary.append(this.mOsuFailure);
            } else if (this.mOsuStatus != null) {
                summary.append(this.mOsuStatus);
            } else {
                summary.append(this.mContext.getString(R.string.tap_to_sign_up));
            }
        } else if (this.isActive()) {
            if (this.getDetailedState() == DetailedState.CONNECTED && this.mIsCarrierAp) {
                summary.append(String.format(this.mContext.getString(R.string.connected_via_carrier), this.mCarrierName));
            } else {
                summary.append(getSummary(this.mContext, (String) null, this.getDetailedState(), this.mInfo != null && this.mInfo.isEphemeral(), this.mInfo != null ? this.mInfo.getNetworkSuggestionOrSpecifierPackageName() : null));
            }
        } else if (this.mConfig != null && this.mConfig.hasNoInternetAccess()) {
            int messageID = this.mConfig.getNetworkSelectionStatus().isNetworkPermanentlyDisabled() ? R.string.wifi_no_internet_no_reconnect : R.string.wifi_no_internet;
            summary.append(this.mContext.getString(messageID));
        } else if (this.mConfig != null && !this.mConfig.getNetworkSelectionStatus().isNetworkEnabled()) {
            NetworkSelectionStatus networkStatus = this.mConfig.getNetworkSelectionStatus();
            switch (networkStatus.getNetworkSelectionDisableReason()) {
                case 2:
                    summary.append(this.mContext.getString(R.string.wifi_disabled_generic));
                    break;
                case 3:
                    summary.append(this.mContext.getString(R.string.wifi_disabled_password_failure));
                    break;
                case 4:
                case 5:
                    summary.append(this.mContext.getString(R.string.wifi_disabled_network_failure));
                case 6:
                case 7:
                case 8:
                case 9:
                case 10:
                case 11:
                case 12:
                default:
                    break;
                case 13:
                    summary.append(this.mContext.getString(R.string.wifi_check_password_try_again));
            }
        } else if (this.mConfig != null && this.mConfig.getNetworkSelectionStatus().isNotRecommended()) {
            summary.append(this.mContext.getString(R.string.wifi_disabled_by_recommendation_provider));
        } else if (this.mIsCarrierAp) {
            summary.append(String.format(this.mContext.getString(R.string.available_via_carrier), this.mCarrierName));
        } else if (!this.isReachable()) {
            summary.append(this.mContext.getString(R.string.wifi_not_in_range));
        } else if (this.mConfig != null) {
            switch (this.mConfig.recentFailure.getAssociationStatus()) {
                case 17:
                    summary.append(this.mContext.getString(R.string.wifi_ap_unable_to_handle_new_sta));
                    break;
                default:
                    if (convertSavedAsDisconnected) {
                        summary.append(this.mContext.getString(R.string.wifi_disconnected));
                    } else {
                        summary.append(this.mContext.getString(R.string.wifi_remembered));
                    }
            }
        }

        if (isVerboseLoggingEnabled()) {
            summary.append(WifiUtils.buildLoggingSummary(this, this.mConfig));
        }

        if (this.mConfig != null && (WifiUtils.isMeteredOverridden(this.mConfig) || this.mConfig.meteredHint)) {
            return this.mContext.getResources().getString(R.string.preference_summary_default_combination, new Object[]{WifiUtils.getMeteredLabel(this.mContext, this.mConfig), summary.toString()});
        } else if (this.getSpeedLabel() != null && summary.length() != 0) {
            return this.mContext.getResources().getString(R.string.preference_summary_default_combination, new Object[]{this.getSpeedLabel(), summary.toString()});
        } else {
            return this.getSpeedLabel() != null ? this.getSpeedLabel() : summary.toString();
        }
    }

    public boolean isActive() {
        return this.mNetworkInfo != null && (this.networkId != -1 || this.mNetworkInfo.getState() != State.DISCONNECTED);
    }

    public boolean isConnectable() {
        return this.getLevel() != -1 && this.getDetailedState() == null;
    }

    public boolean isEphemeral() {
        return this.mInfo != null && this.mInfo.isEphemeral() && this.mNetworkInfo != null && this.mNetworkInfo.getState() != State.DISCONNECTED;
    }

    public boolean isPasspoint() {
        return this.mConfig != null && this.mConfig.isPasspoint();
    }

    public boolean isPasspointConfig() {
        return this.mFqdn != null && this.mConfig == null;
    }

    public boolean isOsuProvider() {
        return this.mOsuProvider != null;
    }

    public void startOsuProvisioning(ActionListener connectListener) {
        this.mConnectListener = connectListener;
        this.getWifiManager().startSubscriptionProvisioning(this.mOsuProvider, this.mContext.getMainExecutor(), new AccessPoint.AccessPointProvisioningCallback());
    }

    private boolean isInfoForThisAccessPoint(WifiConfiguration config, WifiInfo info) {
        if (!info.isOsuAp() && this.mOsuStatus == null) {
            if (!info.isPasspointAp() && !this.isPasspoint()) {
                if (this.networkId != -1) {
                    return this.networkId == info.getNetworkId();
                } else {
                    return config != null ? this.matches(config, info) : TextUtils.equals(removeDoubleQuotes(info.getSSID()), this.ssid);
                }
            } else {
                return info.isPasspointAp() && this.isPasspoint() && TextUtils.equals(info.getPasspointFqdn(), this.mConfig.FQDN);
            }
        } else {
            return info.isOsuAp() && this.mOsuStatus != null;
        }
    }

    public boolean isSaved() {
        return this.mConfig != null;
    }

    public Object getTag() {
        return this.mTag;
    }

    public void setTag(Object tag) {
        this.mTag = tag;
    }

    public void generateOpenNetworkConfig() {
        if (this.security != 0 && this.security != 4) {
            throw new IllegalStateException();
        } else if (this.mConfig == null) {
            this.mConfig = new WifiConfiguration();
            this.mConfig.SSID = convertToQuotedString(this.ssid);
            if (this.security != 0 && this.getWifiManager().isEasyConnectSupported()) {
                this.mConfig.allowedKeyManagement.set(9);
                this.mConfig.requirePMF = true;
            } else {
                this.mConfig.allowedKeyManagement.set(0);
            }

        }
    }

    public void saveWifiState(Bundle savedState) {
        if (this.ssid != null) {
            savedState.putString("key_ssid", this.getSsidStr());
        }

        savedState.putInt("key_security", this.security);
        savedState.putInt("key_speed", this.mSpeed);
        savedState.putInt("key_psktype", this.pskType);
        savedState.putInt("eap_psktype", this.mEapType);
        if (this.mConfig != null) {
            savedState.putParcelable("key_config", this.mConfig);
        }

        savedState.putParcelable("key_wifiinfo", this.mInfo);
        synchronized (this.mLock) {
            savedState.putParcelableArray("key_scanresults", (Parcelable[]) this.mScanResults.toArray(new Parcelable[this.mScanResults.size() + this.mExtraScanResults.size()]));
        }

        savedState.putParcelableArrayList("key_scorednetworkcache", new ArrayList(this.mScoredNetworkCache.values()));
        if (this.mNetworkInfo != null) {
            savedState.putParcelable("key_networkinfo", this.mNetworkInfo);
        }

        if (this.mFqdn != null) {
            savedState.putString("key_fqdn", this.mFqdn);
        }

        if (this.mProviderFriendlyName != null) {
            savedState.putString("key_provider_friendly_name", this.mProviderFriendlyName);
        }

        savedState.putBoolean("key_is_carrier_ap", this.mIsCarrierAp);
        savedState.putInt("key_carrier_ap_eap_type", this.mCarrierApEapType);
        savedState.putString("key_carrier_name", this.mCarrierName);
        savedState.putBoolean("key_is_psk_sae_transition_mode", this.mIsPskSaeTransitionMode);
        savedState.putBoolean("key_is_owe_transition_mode", this.mIsOweTransitionMode);
    }

    public void setListener(AccessPoint.AccessPointListener listener) {
        this.mAccessPointListener = listener;
    }

    void setScanResults(Collection<ScanResult> scanResults) {
        if (CollectionUtils.isEmpty(scanResults)) {
            Log.d("SettingsLib.AccessPoint", "Cannot set scan results to empty list");
        } else {
            if (this.mKey != null && !this.isPasspoint() && !this.isOsuProvider()) {
                Iterator var2 = scanResults.iterator();

                while (var2.hasNext()) {
                    ScanResult result = (ScanResult) var2.next();
                    if (!this.matches(result)) {
                        Log.d("SettingsLib.AccessPoint", String.format("ScanResult %s\nkey of %s did not match current AP key %s", result, getKey(this.mContext, result), this.mKey));
                        return;
                    }
                }
            }

            int oldLevel = this.getLevel();
            synchronized (this.mLock) {
                this.mScanResults.clear();
                this.mScanResults.addAll(scanResults);
            }

            this.updateBestRssiInfo();
            this.updateWifiGeneration();
            int newLevel = this.getLevel();
            if (newLevel > 0 && newLevel != oldLevel) {
                this.updateSpeed();
                ThreadUtils.postOnMainThread(() -> {
                    if (this.mAccessPointListener != null) {
                        this.mAccessPointListener.onLevelChanged(this);
                    }

                });
            }

            ThreadUtils.postOnMainThread(() -> {
                if (this.mAccessPointListener != null) {
                    this.mAccessPointListener.onAccessPointChanged(this);
                }

            });
        }
    }

    void setScanResultsPasspoint(Collection<ScanResult> homeScans, Collection<ScanResult> roamingScans) {
        synchronized (this.mLock) {
            this.mExtraScanResults.clear();
            if (!CollectionUtils.isEmpty(homeScans)) {
                this.mIsRoaming = false;
                if (!CollectionUtils.isEmpty(roamingScans)) {
                    this.mExtraScanResults.addAll(roamingScans);
                }

                this.setScanResults(homeScans);
            } else if (!CollectionUtils.isEmpty(roamingScans)) {
                this.mIsRoaming = true;
                this.setScanResults(roamingScans);
            }

        }
    }

    public boolean update(WifiConfiguration config, WifiInfo info, NetworkInfo networkInfo) {
        boolean updated = false;
        int oldLevel = this.getLevel();
        if (info != null && this.isInfoForThisAccessPoint(config, info)) {
            updated = this.mInfo == null;
            if (!this.isPasspoint() && this.mConfig != config) {
                this.update(config);
            }

            if (this.mWifiGeneration != info.getWifiGeneration() || this.mHe8ssCapableAp != info.isHe8ssCapableAp() || this.mVhtMax8SpatialStreamsSupport != info.isVhtMax8SpatialStreamsSupported()) {
                this.mWifiGeneration = info.getWifiGeneration();
                this.mHe8ssCapableAp = info.isHe8ssCapableAp();
                this.mVhtMax8SpatialStreamsSupport = info.isVhtMax8SpatialStreamsSupported();
                updated = true;
            }

            if (this.mRssi != info.getRssi() && info.getRssi() != -127) {
                this.mRssi = info.getRssi();
                updated = true;
            } else if (this.mNetworkInfo != null && networkInfo != null && this.mNetworkInfo.getDetailedState() != networkInfo.getDetailedState()) {
                updated = true;
            }

            this.mInfo = info;
            this.mNetworkInfo = networkInfo;
        } else if (this.mInfo != null) {
            updated = true;
            this.mInfo = null;
            this.mNetworkInfo = null;
            this.updateWifiGeneration();
        }

        if (updated && this.mAccessPointListener != null) {
            ThreadUtils.postOnMainThread(() -> {
                if (this.mAccessPointListener != null) {
                    this.mAccessPointListener.onAccessPointChanged(this);
                }

            });
            if (oldLevel != this.getLevel()) {
                ThreadUtils.postOnMainThread(() -> {
                    if (this.mAccessPointListener != null) {
                        this.mAccessPointListener.onLevelChanged(this);
                    }

                });
            }
        }

        return updated;
    }

    void update(WifiConfiguration config) {
        this.mConfig = config;
        if (this.mConfig != null && !this.isPasspoint()) {
            this.ssid = removeDoubleQuotes(this.mConfig.SSID);
        }

        this.networkId = config != null ? config.networkId : -1;
        ThreadUtils.postOnMainThread(() -> {
            if (this.mAccessPointListener != null) {
                this.mAccessPointListener.onAccessPointChanged(this);
            }

        });
    }

    void setRssi(int rssi) {
        this.mRssi = rssi;
    }

    void setUnreachable() {
        this.setRssi(-2147483648);
    }

    int getSpeed() {
        return this.mSpeed;
    }

    String getSpeedLabel() {
        return this.getSpeedLabel(this.mSpeed);
    }

    private static int roundToClosestSpeedEnum(int speed) {
        if (speed < 5) {
            return 0;
        } else if (speed < 7) {
            return 5;
        } else if (speed < 15) {
            return 10;
        } else {
            return speed < 25 ? 20 : 30;
        }
    }

    String getSpeedLabel(int speed) {
        return getSpeedLabel(this.mContext, speed);
    }

    private static String getSpeedLabel(Context context, int speed) {
        switch (speed) {
            case 0:
            default:
                return null;
            case 5:
                return context.getString(R.string.speed_label_slow);
            case 10:
                return context.getString(R.string.speed_label_okay);
            case 20:
                return context.getString(R.string.speed_label_fast);
            case 30:
                return context.getString(R.string.speed_label_very_fast);
        }
    }

    public static String getSpeedLabel(Context context, ScoredNetwork scoredNetwork, int rssi) {
        return getSpeedLabel(context, roundToClosestSpeedEnum(scoredNetwork.calculateBadge(rssi)));
    }

    public boolean isReachable() {
        return this.mRssi != -2147483648;
    }

    private static CharSequence getAppLabel(String packageName, PackageManager packageManager) {
        CharSequence appLabel = "";
        ApplicationInfo appInfo = null;

        try {
            int userId = UserHandle.getUserId(-2);
            appInfo = packageManager.getApplicationInfoAsUser(packageName, 0, userId);
        } catch (NameNotFoundException var5) {
            Log.e("SettingsLib.AccessPoint", "Failed to get app info", var5);
            return (CharSequence) appLabel;
        }

        if (appInfo != null) {
            appLabel = appInfo.loadLabel(packageManager);
        }

        return (CharSequence) appLabel;
    }

    public static String getSummary(Context context, String ssid, DetailedState state, boolean isEphemeral, String suggestionOrSpecifierPackageName) {
        if (state == DetailedState.CONNECTED) {
            if (isEphemeral && !TextUtils.isEmpty(suggestionOrSpecifierPackageName)) {
                CharSequence appLabel = getAppLabel(suggestionOrSpecifierPackageName, context.getPackageManager());
                return context.getString(R.string.connected_via_app, new Object[]{appLabel});
            }

            if (isEphemeral) {
                NetworkScoreManager networkScoreManager = (NetworkScoreManager) context.getSystemService(NetworkScoreManager.class);
                NetworkScorerAppData scorer = networkScoreManager.getActiveScorer();
                if (scorer != null && scorer.getRecommendationServiceLabel() != null) {
                    String format = context.getString(R.string.connected_via_network_scorer);
                    return String.format(format, scorer.getRecommendationServiceLabel());
                }

                return context.getString(R.string.connected_via_network_scorer_default);
            }
        }

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService("connectivity");
        if (state == DetailedState.CONNECTED) {
            IWifiManager wifiManager = Stub.asInterface(ServiceManager.getService("wifi"));
            NetworkCapabilities nc = null;

            try {
                nc = cm.getNetworkCapabilities(wifiManager.getCurrentNetwork());
            } catch (RemoteException var9) {
            }

            if (nc != null) {
                if (nc.hasCapability(17)) {
                    int id = context.getResources().getIdentifier("network_available_sign_in", "string", "android");
                    return context.getString(id);
                }

                if (nc.hasCapability(24)) {
                    return context.getString(R.string.wifi_limited_connection);
                }

                if (!nc.hasCapability(16)) {
                    return context.getString(R.string.wifi_connected_no_internet);
                }
            }
        }

        if (state == null) {
            Log.w("SettingsLib.AccessPoint", "state is null, returning empty summary");
            return "";
        } else {
            String[] formats = context.getResources().getStringArray(ssid == null ? R.array.wifi_status : R.array.wifi_status_with_ssid);
            int index = state.ordinal();
            return index < formats.length && formats[index].length() != 0 ? String.format(formats[index], ssid) : "";
        }
    }

    public static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    private static int getPskType(ScanResult result) {
        boolean wpa = result.capabilities.contains("WPA-PSK");
        boolean wpa2 = result.capabilities.contains("RSN-PSK");
        boolean wpa3 = result.capabilities.contains("RSN-SAE");
        if (wpa2 && wpa) {
            return 3;
        } else if (wpa2) {
            return 2;
        } else if (wpa) {
            return 1;
        } else {
            if (!wpa3) {
                Log.w("SettingsLib.AccessPoint", "Received abnormal flag string: " + result.capabilities);
            }

            return 0;
        }
    }

    private static int getEapType(ScanResult result) {
        if (result.capabilities.contains("RSN-EAP")) {
            return 2;
        } else {
            return result.capabilities.contains("WPA-EAP") ? 1 : 0;
        }
    }

    private static int getSecurity(Context context, ScanResult result) {
        boolean isWep = result.capabilities.contains("WEP");
        boolean isSae = result.capabilities.contains("SAE");
        boolean isPsk = result.capabilities.contains("PSK");
        boolean isEapSuiteB192 = result.capabilities.contains("EAP_SUITE_B_192");
        boolean isEap = result.capabilities.contains("EAP");
        boolean isOwe = result.capabilities.contains("OWE");
        boolean isOweTransition = result.capabilities.contains("OWE_TRANSITION");
        boolean isDpp = result.capabilities.contains("DPP");
        boolean isPskSae = result.capabilities.contains("PSK+SAE");
        WifiManager wifiManager;
        if (isSae && isPsk) {
            wifiManager = (WifiManager) context.getSystemService("wifi");
            return wifiManager.isWpa3SaeSupported() ? 5 : 2;
        } else if (isOweTransition) {
            wifiManager = (WifiManager) context.getSystemService("wifi");
            return wifiManager.isEnhancedOpenSupported() ? 4 : 0;
        } else if (isDpp) {
            return 7;
        } else if (isWep) {
            return 1;
        } else if (checkForSaeTransitionMode(result)) {
            return isWpa3SaeSupported() ? 5 : 2;
        } else if (isSae) {
            return 5;
        } else if (isPsk) {
            return 2;
        } else if (isEapSuiteB192) {
            return 6;
        } else if (isEap) {
            return 3;
        } else if (checkForOweTransitionMode(result)) {
            return isEnhancedOpenSupported() ? 4 : 0;
        } else {
            return isOwe ? 4 : 0;
        }
    }

    static int getSecurity(WifiConfiguration config) {
        if (config.allowedKeyManagement.get(8)) {
            return 5;
        } else if (config.allowedKeyManagement.get(1)) {
            return 2;
        } else if (config.allowedKeyManagement.get(10)) {
            return 6;
        } else if (!config.allowedKeyManagement.get(2) && !config.allowedKeyManagement.get(3)) {
            if (config.allowedKeyManagement.get(15)) {
                return 7;
            } else if (config.allowedKeyManagement.get(9)) {
                return 4;
            } else {
                return config.wepKeys[0] != null ? 1 : 0;
            }
        } else {
            return 3;
        }
    }

    public static String securityToString(int security, int pskType) {
        if (security == 1) {
            return "WEP";
        } else if (security == 2) {
            if (pskType == 1) {
                return "WPA";
            } else if (pskType == 2) {
                return "WPA2";
            } else {
                return pskType == 3 ? "WPA_WPA2" : "PSK";
            }
        } else if (security == 3) {
            return "EAP";
        } else if (security == 7) {
            return "DPP";
        } else if (security == 5) {
            return "SAE";
        } else if (security == 6) {
            return "SUITE_B";
        } else {
            return security == 4 ? "OWE" : "NONE";
        }
    }

    static String removeDoubleQuotes(String string) {
        if (TextUtils.isEmpty(string)) {
            return "";
        } else {
            int length = string.length();
            return length > 1 && string.charAt(0) == '"' && string.charAt(length - 1) == '"' ? string.substring(1, length - 1) : string;
        }
    }

    private WifiManager getWifiManager() {
        if (this.mWifiManager == null) {
            this.mWifiManager = (WifiManager) this.mContext.getSystemService("wifi");
        }

        return this.mWifiManager;
    }

    private static boolean isVerboseLoggingEnabled() {
        return WifiTracker.sVerboseLogging || Log.isLoggable("SettingsLib.AccessPoint", 2);
    }

    public boolean isPskSaeTransitionMode() {
        return this.mIsPskSaeTransitionMode;
    }

    public boolean isOweTransitionMode() {
        return this.mIsOweTransitionMode;
    }

    private static boolean isPskSaeTransitionMode(ScanResult scanResult) {
        return scanResult.capabilities.contains("PSK") && scanResult.capabilities.contains("SAE");
    }

    private static boolean isOweTransitionMode(ScanResult scanResult) {
        return scanResult.capabilities.contains("OWE_TRANSITION");
    }

    private boolean isSameSsidOrBssid(ScanResult scanResult) {
        if (scanResult == null) {
            return false;
        } else if (TextUtils.equals(this.ssid, scanResult.SSID)) {
            return true;
        } else {
            return scanResult.BSSID != null && TextUtils.equals(this.bssid, scanResult.BSSID);
        }
    }

    private boolean isSameSsidOrBssid(WifiInfo wifiInfo) {
        if (wifiInfo == null) {
            return false;
        } else if (TextUtils.equals(this.ssid, removeDoubleQuotes(wifiInfo.getSSID()))) {
            return true;
        } else {
            return wifiInfo.getBSSID() != null && TextUtils.equals(this.bssid, wifiInfo.getBSSID());
        }
    }

    private boolean isSameSsidOrBssid(AccessPoint accessPoint) {
        if (accessPoint == null) {
            return false;
        } else if (TextUtils.equals(this.ssid, accessPoint.getSsid())) {
            return true;
        } else {
            return accessPoint.getBssid() != null && TextUtils.equals(this.bssid, accessPoint.getBssid());
        }
    }

    class AccessPointProvisioningCallback extends ProvisioningCallback {
        AccessPointProvisioningCallback() {
        }

        public void onProvisioningFailure(int status) {
            if (TextUtils.equals(AccessPoint.this.mOsuStatus, AccessPoint.this.mContext.getString(R.string.osu_completing_sign_up))) {
                AccessPoint.this.mOsuFailure = AccessPoint.this.mContext.getString(R.string.osu_sign_up_failed);
            } else {
                AccessPoint.this.mOsuFailure = AccessPoint.this.mContext.getString(R.string.osu_connect_failed);
            }

            AccessPoint.this.mOsuStatus = null;
            AccessPoint.this.mOsuProvisioningComplete = false;
            ThreadUtils.postOnMainThread(() -> {
                if (AccessPoint.this.mAccessPointListener != null) {
                    AccessPoint.this.mAccessPointListener.onAccessPointChanged(AccessPoint.this);
                }

            });
        }

        public void onProvisioningStatus(int status) {
            String newStatus = null;
            switch (status) {
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    newStatus = String.format(AccessPoint.this.mContext.getString(R.string.osu_opening_provider), AccessPoint.this.mOsuProvider.getFriendlyName());
                    break;
                case 8:
                case 9:
                case 10:
                case 11:
                    newStatus = AccessPoint.this.mContext.getString(R.string.osu_completing_sign_up);
            }

            boolean updated = !TextUtils.equals(AccessPoint.this.mOsuStatus, newStatus);
            AccessPoint.this.mOsuStatus = newStatus;
            AccessPoint.this.mOsuFailure = null;
            AccessPoint.this.mOsuProvisioningComplete = false;
            if (updated) {
                ThreadUtils.postOnMainThread(() -> {
                    if (AccessPoint.this.mAccessPointListener != null) {
                        AccessPoint.this.mAccessPointListener.onAccessPointChanged(AccessPoint.this);
                    }

                });
            }

        }

        public void onProvisioningComplete() {
            AccessPoint.this.mOsuProvisioningComplete = true;
            AccessPoint.this.mOsuFailure = null;
            AccessPoint.this.mOsuStatus = null;
            ThreadUtils.postOnMainThread(() -> {
                if (AccessPoint.this.mAccessPointListener != null) {
                    AccessPoint.this.mAccessPointListener.onAccessPointChanged(AccessPoint.this);
                }

            });
            WifiManager wifiManager = AccessPoint.this.getWifiManager();
            PasspointConfiguration passpointConfig = (PasspointConfiguration) wifiManager.getMatchingPasspointConfigsForOsuProviders(Collections.singleton(AccessPoint.this.mOsuProvider)).get(AccessPoint.this.mOsuProvider);
            if (passpointConfig == null) {
                Log.e("SettingsLib.AccessPoint", "Missing PasspointConfiguration for newly provisioned network!");
                if (AccessPoint.this.mConnectListener != null) {
                    AccessPoint.this.mConnectListener.onFailure(0);
                }

            } else {
                String fqdn = passpointConfig.getHomeSp().getFqdn();
                Iterator var4 = wifiManager.getAllMatchingWifiConfigs(wifiManager.getScanResults()).iterator();

                Pair pairing;
                WifiConfiguration config;
                do {
                    if (!var4.hasNext()) {
                        if (AccessPoint.this.mConnectListener != null) {
                            AccessPoint.this.mConnectListener.onFailure(0);
                        }

                        return;
                    }

                    pairing = (Pair) var4.next();
                    config = (WifiConfiguration) pairing.first;
                } while (!TextUtils.equals(config.FQDN, fqdn));

                List<ScanResult> homeScans = (List) ((Map) pairing.second).get(0);
                List<ScanResult> roamingScans = (List) ((Map) pairing.second).get(1);
                AccessPoint connectionAp = new AccessPoint(AccessPoint.this.mContext, config, homeScans, roamingScans);
                wifiManager.connect(connectionAp.getConfig(), AccessPoint.this.mConnectListener);
            }
        }
    }

    public interface AccessPointListener {
        void onAccessPointChanged(AccessPoint var1);

        void onLevelChanged(AccessPoint var1);
    }

    @Retention(RetentionPolicy.SOURCE)
    public @interface Speed {
        int NONE = 0;
        int SLOW = 5;
        int MODERATE = 10;
        int FAST = 20;
        int VERY_FAST = 30;
    }
}