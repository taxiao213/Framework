package vl.vision.home.util.data.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.INetworkScoreCache;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkKey;
import android.net.NetworkRequest;
import android.net.NetworkScoreManager;
import android.net.ScoredNetwork;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.hotspot2.OsuProvider;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;
import androidx.collection.ArraySet;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;


import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;

import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Created by hanqq on 2022/2/21
 * Email:yin13753884368@163.com
 * CSDN:http://blog.csdn.net/yin13753884368/article
 * Github:https://github.com/taxiao213
 */
public class WifiTracker implements LifecycleObserver, OnStart, OnStop, OnDestroy {

    private static final long DEFAULT_MAX_CACHED_SCORE_AGE_MILLIS = 1200000L;

    @VisibleForTesting
    static final long MAX_SCAN_RESULT_AGE_MILLIS = 15000L;

    private static final String TAG = "WifiTracker";

    public static boolean sVerboseLogging;

    private static final int WIFI_RESCAN_INTERVAL_MS = 10000;

    private final Context mContext;

    private final WifiManager mWifiManager;

    private final IntentFilter mFilter;

    private final ConnectivityManager mConnectivityManager;

    private final NetworkRequest mNetworkRequest;

    private static final boolean DBG() {
        return Log.isLoggable("WifiTracker", 3);
    }

    private static boolean isVerboseLoggingEnabled() {
//        return (sVerboseLogging || Log.isLoggable("WifiTracker", 2));
        return true;
    }

    private final AtomicBoolean mConnected = new AtomicBoolean(false);

    private final WifiListenerExecutor mListener;

    @VisibleForTesting
    Handler mWorkHandler;

    private HandlerThread mWorkThread;

    private WifiTrackerNetworkCallback mNetworkCallback;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<AccessPoint> mInternalAccessPoints = new ArrayList<>();

    @GuardedBy("mLock")
    private final Set<NetworkKey> mRequestedScores = (Set<NetworkKey>) new ArraySet();

    private boolean mStaleScanResults = true;

    private boolean mLastScanSucceeded = true;

    private final HashMap<String, ScanResult> mScanResultCache = new HashMap<>();

    private boolean mRegistered;

    private NetworkInfo mLastNetworkInfo;

    private WifiInfo mLastInfo;

    private final NetworkScoreManager mNetworkScoreManager;

    private WifiNetworkScoreCache mScoreCache;

    private boolean mNetworkScoringUiEnabled;

    private long mMaxSpeedLabelScoreCacheAge;

    private static final String WIFI_SECURITY_PSK = "PSK";

    private static final String WIFI_SECURITY_EAP = "EAP";

    private static final String WIFI_SECURITY_SAE = "SAE";

    private static final String WIFI_SECURITY_OWE = "OWE";

    private static final String WIFI_SECURITY_SUITE_B_192 = "SUITE_B_192";

    @GuardedBy("mLock")
    @VisibleForTesting
    Scanner mScanner;

    @VisibleForTesting
    final BroadcastReceiver mReceiver;

    public WifiTracker(Context context, WifiTracker.WifiListener wifiListener, boolean includeSaved, boolean includeScans) {
        this(context, wifiListener, (WifiManager) context
                        .getSystemService(WifiManager.class), (ConnectivityManager) context
                        .getSystemService(ConnectivityManager.class), (NetworkScoreManager) context
                        .getSystemService(NetworkScoreManager.class),
                newIntentFilter());
    }

    public WifiTracker(Context context, WifiListener wifiListener, @NonNull Lifecycle lifecycle, boolean includeSaved, boolean includeScans) {
        this(context, wifiListener, (WifiManager) context
                        .getSystemService(WifiManager.class), (ConnectivityManager) context
                        .getSystemService(ConnectivityManager.class), (NetworkScoreManager) context
                        .getSystemService(NetworkScoreManager.class),
                newIntentFilter());
        lifecycle.addObserver((LifecycleObserver) this);
    }

    WifiTracker(Context context, WifiListener wifiListener, WifiManager wifiManager, ConnectivityManager connectivityManager, NetworkScoreManager networkScoreManager, IntentFilter filter) {
        this.mReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                WifiTracker.sVerboseLogging = (WifiTracker.this.mWifiManager.getVerboseLoggingLevel() > 0);
                if ("android.net.wifi.WIFI_STATE_CHANGED".equals(action)) {
                    WifiTracker.this.updateWifiState(intent
                            .getIntExtra("wifi_state", 4));
                } else if ("android.net.wifi.SCAN_RESULTS".equals(action)) {
                    WifiTracker.this.mStaleScanResults = false;
                    WifiTracker.this.mLastScanSucceeded = intent
                            .getBooleanExtra("resultsUpdated", true);
                    WifiTracker.this.fetchScansAndConfigsAndUpdateAccessPoints();
                } else if ("android.net.wifi.CONFIGURED_NETWORKS_CHANGE".equals(action) || "android.net.wifi.LINK_CONFIGURATION_CHANGED"
                        .equals(action)) {
                    WifiTracker.this.fetchScansAndConfigsAndUpdateAccessPoints();
                } else if ("android.net.wifi.STATE_CHANGE".equals(action)) {
                    NetworkInfo info = (NetworkInfo) intent.getParcelableExtra("networkInfo");
                    WifiTracker.this.updateNetworkInfo(info);
                    WifiTracker.this.fetchScansAndConfigsAndUpdateAccessPoints();
                } else if ("android.net.wifi.RSSI_CHANGED".equals(action)) {
                    NetworkInfo info = WifiTracker.this.mConnectivityManager.getNetworkInfo(WifiTracker.this.mWifiManager.getCurrentNetwork());
                    WifiTracker.this.updateNetworkInfo(info);
                }
            }
        };
        this.mContext = context;
        this.mWifiManager = wifiManager;
        this.mListener = new WifiListenerExecutor(wifiListener);
        this.mConnectivityManager = connectivityManager;
        sVerboseLogging = (this.mWifiManager != null && this.mWifiManager.getVerboseLoggingLevel() > 0);
        this.mFilter = filter;
        this.mNetworkRequest = (new NetworkRequest.Builder()).clearCapabilities().addCapability(15).addTransportType(1).build();
        this.mNetworkScoreManager = networkScoreManager;
        HandlerThread workThread = new HandlerThread("WifiTracker{" + Integer.toHexString(System.identityHashCode(this)) + "}", 10);
        workThread.start();
        setWorkThread(workThread);
    }

    private static IntentFilter newIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_IDS_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        filter.addAction(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        return filter;
    }

    void setWorkThread(HandlerThread workThread) {
        this.mWorkThread = workThread;
        this.mWorkHandler = new Handler(workThread.getLooper());
        this.mScoreCache = new WifiNetworkScoreCache(this.mContext, new WifiNetworkScoreCache.CacheListener(this.mWorkHandler) {
            public void networkCacheUpdated(List<ScoredNetwork> networks) {
                if (!WifiTracker.this.mRegistered)
                    return;
                if (Log.isLoggable("WifiTracker", 2))
                    Log.v("WifiTracker", "Score cache was updated with networks: " + networks);
                WifiTracker.this.updateNetworkScores();
            }
        });
    }

    private void updateWifiState(int state) {
        if (isVerboseLoggingEnabled())
            Log.d("WifiTracker", "updateWifiState: " + state);
        if (state == 3) {
            synchronized (this.mLock) {
                if (this.mScanner != null)
                    this.mScanner.resume();
            }
        } else {
            clearAccessPointsAndConditionallyUpdate();
            this.mLastInfo = null;
            this.mLastNetworkInfo = null;
            synchronized (this.mLock) {
                if (this.mScanner != null)
                    this.mScanner.pause();
            }
            this.mStaleScanResults = true;
        }
        this.mListener.onWifiStateChanged(state);
    }

    private void updateNetworkScores() {
        synchronized (this.mLock) {
            boolean updated = false;
            for (int i = 0; i < this.mInternalAccessPoints.size(); i++) {
                if (((AccessPoint) this.mInternalAccessPoints.get(i)).update(this.mScoreCache, this.mNetworkScoringUiEnabled, this.mMaxSpeedLabelScoreCacheAge))
                    updated = true;
            }
            if (updated) {
                Collections.sort(this.mInternalAccessPoints);
                conditionallyNotifyListeners();
            }
        }
    }

    public List<AccessPoint> getAccessPoints() {
        synchronized (this.mLock) {
            return new ArrayList<>(this.mInternalAccessPoints);
        }
    }

    private void conditionallyNotifyListeners() {
        if (this.mStaleScanResults)
            return;
        this.mListener.onAccessPointsChanged();
    }

    private AccessPoint getCachedByKey(List<AccessPoint> cache, String key) {
        ListIterator<AccessPoint> lit = cache.listIterator();
        while (lit.hasNext()) {
            AccessPoint currentAccessPoint = lit.next();
            if (currentAccessPoint.getKey().equals(key)) {
                lit.remove();
                return currentAccessPoint;
            }
        }
        return null;
    }

    private void updateNetworkInfo(NetworkInfo networkInfo) {
        if (!isWifiEnabled()) {
            clearAccessPointsAndConditionallyUpdate();
            return;
        }
        if (networkInfo != null) {
            this.mLastNetworkInfo = networkInfo;
            if (DBG())
                Log.d("WifiTracker", "mLastNetworkInfo set: " + this.mLastNetworkInfo);
            if (networkInfo.isConnected() != this.mConnected.getAndSet(networkInfo.isConnected()))
                this.mListener.onConnectedChanged();
        }
        WifiConfiguration connectionConfig = null;
        this.mLastInfo = this.mWifiManager.getConnectionInfo();
        if (DBG())
            Log.d("WifiTracker", "mLastInfo set as: " + this.mLastInfo);
        if (this.mLastInfo != null)
            connectionConfig = getWifiConfigurationForNetworkId(this.mLastInfo.getNetworkId(), this.mWifiManager
                    .getConfiguredNetworks());
        boolean updated = false;
        boolean reorder = false;
        synchronized (this.mLock) {
            for (int i = this.mInternalAccessPoints.size() - 1; i >= 0; i--) {
                AccessPoint ap = this.mInternalAccessPoints.get(i);
                boolean previouslyConnected = ap.isActive();
                if (ap.update(connectionConfig, this.mLastInfo, this.mLastNetworkInfo)) {
                    updated = true;
                    if (previouslyConnected != ap.isActive())
                        reorder = true;
                }
                if (ap.update(this.mScoreCache, this.mNetworkScoringUiEnabled, this.mMaxSpeedLabelScoreCacheAge)) {
                    reorder = true;
                    updated = true;
                }
            }
            if (reorder)
                Collections.sort(this.mInternalAccessPoints);
            if (updated)
                conditionallyNotifyListeners();
        }
    }

    private void clearAccessPointsAndConditionallyUpdate() {
        synchronized (this.mLock) {
            if (!this.mInternalAccessPoints.isEmpty()) {
                this.mInternalAccessPoints.clear();
                conditionallyNotifyListeners();
            }
        }
    }

    private void fetchScansAndConfigsAndUpdateAccessPoints() {
        List<ScanResult> newScanResults = this.mWifiManager.getScanResults();
        List<ScanResult> filteredScanResults = filterScanResultsByCapabilities(newScanResults);
        if (isVerboseLoggingEnabled())
            Log.i("WifiTracker", "Fetched scan results: " + filteredScanResults);
        List<WifiConfiguration> configs = this.mWifiManager.getConfiguredNetworks();
        updateAccessPoints(filteredScanResults, configs);
    }

    private List<ScanResult> filterScanResultsByCapabilities(List<ScanResult> scanResults) {
        if (scanResults == null)
            return null;
        boolean isOweSupported = this.mWifiManager.isEnhancedOpenSupported();
        boolean isSaeSupported = this.mWifiManager.isWpa3SaeSupported();
        boolean isSuiteBSupported = this.mWifiManager.isWpa3SuiteBSupported();
        List<ScanResult> filteredScanResultList = new ArrayList<>();
        for (ScanResult scanResult : scanResults) {
            if (scanResult.capabilities.contains("PSK")) {
                filteredScanResultList.add(scanResult);
                continue;
            }
            if ((scanResult.capabilities.contains("SUITE_B_192") && !isSuiteBSupported) || (scanResult.capabilities
                    .contains("SAE") && !isSaeSupported) || (scanResult.capabilities
                    .contains("OWE") && !isOweSupported)) {
                if (isVerboseLoggingEnabled())
                    Log.v("WifiTracker", "filterScanResultsByCapabilities: Filtering SSID " + scanResult.SSID + " with capabilities: " + scanResult.capabilities);
                continue;
            }
            filteredScanResultList.add(scanResult);
        }
        return filteredScanResultList;
    }

    private AccessPoint getCachedOrCreatePasspoint(WifiConfiguration config, List<ScanResult> homeScans, List<ScanResult> roamingScans, List<AccessPoint> cache) {
        AccessPoint accessPoint = getCachedByKey(cache, AccessPoint.getKey(config));
        if (accessPoint == null) {
            accessPoint = new AccessPoint(this.mContext, config, homeScans, roamingScans);
        } else {
            accessPoint.update(config);
            accessPoint.setScanResultsPasspoint(homeScans, roamingScans);
        }
        return accessPoint;
    }

    private AccessPoint getCachedOrCreateOsu(OsuProvider provider, List<ScanResult> scanResults, List<AccessPoint> cache) {
        AccessPoint accessPoint = getCachedByKey(cache, AccessPoint.getKey(provider));
        if (accessPoint == null) {
            accessPoint = new AccessPoint(this.mContext, provider, scanResults);
        } else {
            accessPoint.setScanResults(scanResults);
        }
        return accessPoint;
    }

    private WifiConfiguration getWifiConfigurationForNetworkId(int networkId, List<WifiConfiguration> configs) {
        if (configs != null)
            for (WifiConfiguration config : configs) {
                if (this.mLastInfo != null && networkId == config.networkId && (!config.selfAdded || config.numAssociation != 0))
                    return config;
            }
        return null;
    }

    private void updateAccessPoints(List<ScanResult> newScanResults, List<WifiConfiguration> configs) {
        WifiConfiguration connectionConfig = null;
        if (this.mLastInfo != null)
            connectionConfig = getWifiConfigurationForNetworkId(this.mLastInfo.getNetworkId(), configs);
        synchronized (this.mLock) {
            ArrayMap<String, List<ScanResult>> scanResultsByApKey = updateScanResultCache(newScanResults);
            List<AccessPoint> cachedAccessPoints = new ArrayList<>(this.mInternalAccessPoints);
            ArrayList<AccessPoint> accessPoints = new ArrayList<>();
            List<NetworkKey> scoresToRequest = new ArrayList<>();
            for (Map.Entry<String, List<ScanResult>> entry : (Iterable<Map.Entry<String, List<ScanResult>>>) scanResultsByApKey.entrySet()) {
                boolean isOweTransition = false;
                boolean isSaeTransition = false;
                for (ScanResult result : entry.getValue()) {
                    NetworkKey key = NetworkKey.createFromScanResult(result);
                    if (key != null && !this.mRequestedScores.contains(key))
                        scoresToRequest.add(key);
                    if (AccessPoint.checkForOweTransitionMode(result))
                        isOweTransition = true;
                    if (AccessPoint.checkForSaeTransitionMode(result))
                        isSaeTransition = true;
                }
                AccessPoint accessPoint = getCachedOrCreate(entry.getValue(), cachedAccessPoints);
                List<WifiConfiguration> matchedConfigs = (List<WifiConfiguration>) configs.stream().filter(config -> accessPoint.matches(config)).collect(Collectors.toList());
                int matchedConfigCount = matchedConfigs.size();
                if (matchedConfigCount == 0) {
                    accessPoint.update(null);
                } else if (matchedConfigCount == 1) {
                    accessPoint.update(matchedConfigs.get(0));
                } else {
                    Optional<WifiConfiguration> preferredConfig = matchedConfigs.stream().filter(config -> isSaeOrOwe(config)).findFirst();
                    if (preferredConfig.isPresent()) {
                        accessPoint.update(preferredConfig.get());
                    } else {
                        accessPoint.update(matchedConfigs.get(0));
                    }
                }
                accessPoints.add(accessPoint);
            }
            List<ScanResult> cachedScanResults = new ArrayList<>(this.mScanResultCache.values());
            accessPoints.addAll(updatePasspointAccessPoints(this.mWifiManager
                    .getAllMatchingWifiConfigs(cachedScanResults), cachedAccessPoints));
            accessPoints.addAll(updateOsuAccessPoints(this.mWifiManager
                    .getMatchingOsuProviders(cachedScanResults), cachedAccessPoints));
            if (this.mLastInfo != null && this.mLastNetworkInfo != null)
                for (AccessPoint ap : accessPoints)
                    ap.update(connectionConfig, this.mLastInfo, this.mLastNetworkInfo);
            if (accessPoints.isEmpty() && connectionConfig != null) {
                AccessPoint activeAp = new AccessPoint(this.mContext, connectionConfig);
                activeAp.update(connectionConfig, this.mLastInfo, this.mLastNetworkInfo);
                accessPoints.add(activeAp);
                scoresToRequest.add(NetworkKey.createFromWifiInfo(this.mLastInfo));
            }
            requestScoresForNetworkKeys(scoresToRequest);
            for (AccessPoint ap : accessPoints)
                ap.update(this.mScoreCache, this.mNetworkScoringUiEnabled, this.mMaxSpeedLabelScoreCacheAge);
            Collections.sort(accessPoints);
            if (DBG()) {
                Log.d("WifiTracker", "------ Dumping AccessPoints that were not seen on this scan ------");
                for (AccessPoint prevAccessPoint : this.mInternalAccessPoints) {
                    String prevTitle = prevAccessPoint.getTitle();
                    boolean found = false;
                    for (AccessPoint newAccessPoint : accessPoints) {
                        if (newAccessPoint.getTitle() != null && newAccessPoint.getTitle()
                                .equals(prevTitle)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        Log.d("WifiTracker", "Did not find " + prevTitle + " in this scan");
                }
                Log.d("WifiTracker", "---- Done dumping AccessPoints that were not seen on this scan ----");
            }
            this.mInternalAccessPoints.clear();
            this.mInternalAccessPoints.addAll(accessPoints);
        }
        conditionallyNotifyListeners();
    }

    private AccessPoint getCachedOrCreate(List<ScanResult> scanResults, List<AccessPoint> cache) {
        AccessPoint accessPoint = getCachedByKey(cache,
                AccessPoint.getKey(this.mContext, scanResults.get(0)));
        if (accessPoint == null) {
            accessPoint = new AccessPoint(this.mContext, scanResults);
        } else {
            accessPoint.setScanResults(scanResults);
        }
        return accessPoint;
    }

    private void registerScoreCache() {
        this.mNetworkScoreManager.registerNetworkScoreCache(1, (INetworkScoreCache) this.mScoreCache, 2);
    }

    private void requestScoresForNetworkKeys(Collection<NetworkKey> keys) {
        if (keys.isEmpty())
            return;
        if (DBG())
            Log.d("WifiTracker", "Requesting scores for Network Keys: " + keys);
        this.mNetworkScoreManager.requestScores(keys.<NetworkKey>toArray(new NetworkKey[keys.size()]));
        synchronized (this.mLock) {
            this.mRequestedScores.addAll(keys);
        }
    }

    private ArrayMap<String, List<ScanResult>> updateScanResultCache(List<ScanResult> newResults) {
        for (ScanResult newResult : newResults) {
            if (newResult.SSID == null || newResult.SSID.isEmpty())
                continue;
            this.mScanResultCache.put(newResult.BSSID, newResult);
        }
        evictOldScans();
        ArrayMap<String, List<ScanResult>> scanResultsByApKey = new ArrayMap();
        for (ScanResult result : this.mScanResultCache.values()) {
            List<ScanResult> resultList;
            if (result.SSID == null || result.SSID.length() == 0 || result.capabilities
                    .contains("[IBSS]"))
                continue;
            String apKey = AccessPoint.getKey(this.mContext, result);
            if (scanResultsByApKey.containsKey(apKey)) {
                resultList = (List<ScanResult>) scanResultsByApKey.get(apKey);
            } else {
                resultList = new ArrayList<>();
                scanResultsByApKey.put(apKey, resultList);
            }
            resultList.add(result);
        }
        return scanResultsByApKey;
    }

    private void evictOldScans() {
        long evictionTimeoutMillis = this.mLastScanSucceeded ? 15000L : 30000L;
        long nowMs = SystemClock.elapsedRealtime();
        for (Iterator<ScanResult> iter = this.mScanResultCache.values().iterator(); iter.hasNext(); ) {
            ScanResult result = iter.next();
            if (nowMs - result.timestamp / 1000L > evictionTimeoutMillis)
                iter.remove();
        }
    }

    private static boolean isSaeOrOwe(WifiConfiguration config) {
        int security = AccessPoint.getSecurity(config);
        return (security == 5 || security == 4);
    }

    @VisibleForTesting
    List<AccessPoint> updatePasspointAccessPoints(List<Pair<WifiConfiguration, Map<Integer, List<ScanResult>>>> passpointConfigsAndScans, List<AccessPoint> accessPointCache) {
        List<AccessPoint> accessPoints = new ArrayList<>();
        ArraySet<String> arraySet = new ArraySet();
        for (Pair<WifiConfiguration, Map<Integer, List<ScanResult>>> pairing : passpointConfigsAndScans) {
            WifiConfiguration config = (WifiConfiguration) pairing.first;
            if (arraySet.add(config.FQDN)) {
                List<ScanResult> homeScans = (List<ScanResult>) ((Map) pairing.second).get(Integer.valueOf(0));
                List<ScanResult> roamingScans = (List<ScanResult>) ((Map) pairing.second).get(Integer.valueOf(1));
                AccessPoint accessPoint = getCachedOrCreatePasspoint(config, homeScans, roamingScans, accessPointCache);
                accessPoints.add(accessPoint);
            }
        }
        return accessPoints;
    }

    @VisibleForTesting
    List<AccessPoint> updateOsuAccessPoints(Map<OsuProvider, List<ScanResult>> providersAndScans, List<AccessPoint> accessPointCache) {
        List<AccessPoint> accessPoints = new ArrayList<>();
        Set<OsuProvider> alreadyProvisioned = this.mWifiManager.getMatchingPasspointConfigsForOsuProviders(providersAndScans.keySet()).keySet();
        for (OsuProvider provider : providersAndScans.keySet()) {
            if (!alreadyProvisioned.contains(provider)) {
                AccessPoint accessPointOsu = getCachedOrCreateOsu(provider, providersAndScans.get(provider), accessPointCache);
                accessPoints.add(accessPointOsu);
            }
        }
        return accessPoints;
    }

    @VisibleForTesting
    class Scanner extends Handler {
        static final int MSG_SCAN = 0;

        private int mRetry = 0;

        void resume() {
            if (WifiTracker.isVerboseLoggingEnabled())
                Log.d("WifiTracker", "Scanner resume");
            if (!hasMessages(0))
                sendEmptyMessage(0);
        }

        void pause() {
            if (WifiTracker.isVerboseLoggingEnabled())
                Log.d("WifiTracker", "Scanner pause");
            this.mRetry = 0;
            removeMessages(0);
        }

        @VisibleForTesting
        boolean isScanning() {
            return hasMessages(0);
        }

        public void handleMessage(Message message) {
            if (message.what != 0)
                return;
            if (WifiTracker.this.mWifiManager.startScan()) {
                this.mRetry = 0;
            } else if (++this.mRetry >= 3) {
                this.mRetry = 0;
                if (WifiTracker.this.mContext != null)
                    Toast.makeText(WifiTracker.this.mContext, "扫描失败", 1).show();
                return;
            }
            sendEmptyMessageDelayed(0, 10000L);
        }
    }

    private static class Multimap<K, V> {
        private final HashMap<K, List<V>> store = new HashMap<>();

        List<V> getAll(K key) {
            List<V> values = this.store.get(key);
            return (values != null) ? values : Collections.<V>emptyList();
        }

        void put(K key, V val) {
            List<V> curVals = this.store.get(key);
            if (curVals == null) {
                curVals = new ArrayList<>(3);
                this.store.put(key, curVals);
            }
            curVals.add(val);
        }
    }

    @VisibleForTesting
    class WifiListenerExecutor implements WifiListener {
        private final WifiTracker.WifiListener mDelegatee;

        public WifiListenerExecutor(WifiTracker.WifiListener listener) {
            this.mDelegatee = listener;
        }

        public void onWifiStateChanged(int state) {
            runAndLog(() -> this.mDelegatee.onWifiStateChanged(state),
                    String.format("Invoking onWifiStateChanged callback with state %d", new Object[]{Integer.valueOf(state)}));
        }

        public void onConnectedChanged() {
            Objects.requireNonNull(this.mDelegatee);
            runAndLog(this.mDelegatee::onConnectedChanged, "Invoking onConnectedChanged callback");
        }

        public void onAccessPointsChanged() {
            Objects.requireNonNull(this.mDelegatee);
            runAndLog(this.mDelegatee::onAccessPointsChanged, "Invoking onAccessPointsChanged callback");
        }

        private void runAndLog(Runnable r, String verboseLog) {
            ThreadUtils.postOnMainThread(() -> {
                if (WifiTracker.this.mRegistered) {
                    if (WifiTracker.isVerboseLoggingEnabled())
                        Log.i("WifiTracker", verboseLog);
                    r.run();
                }
            });
        }
    }

    private final class WifiTrackerNetworkCallback extends ConnectivityManager.NetworkCallback {
        private WifiTrackerNetworkCallback() {
        }

        public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
            if (network.equals(WifiTracker.this.mWifiManager.getCurrentNetwork()))
                WifiTracker.this.updateNetworkInfo(null);
        }
    }

    public interface WifiListener {
        void onWifiStateChanged(int var1);

        void onConnectedChanged();

        void onAccessPointsChanged();
    }

    public WifiManager getManager() {
        return this.mWifiManager;
    }

    public boolean isWifiEnabled() {
        return (this.mWifiManager != null && this.mWifiManager.isWifiEnabled());
    }

    public int getNumSavedNetworks() {
        return WifiSavedConfigUtils.getAllConfigs(this.mContext, this.mWifiManager).size();
    }

    public boolean isConnected() {
        return this.mConnected.get();
    }

    public void dump(PrintWriter pw) {
        pw.println("  - wifi tracker ------");
        for (AccessPoint accessPoint : getAccessPoints())
            pw.println("  " + accessPoint);
    }

    private void unregisterScoreCache() {
        this.mNetworkScoreManager.unregisterNetworkScoreCache(1, (INetworkScoreCache) this.mScoreCache);
        synchronized (this.mLock) {
            this.mRequestedScores.clear();
        }
    }

    public void pauseScanning() {
        synchronized (this.mLock) {
            if (this.mScanner != null) {
                this.mScanner.pause();
                this.mScanner = null;
            }
        }
        this.mStaleScanResults = true;
    }

    public void resumeScanning() {
        synchronized (this.mLock) {
            if (this.mScanner == null)
                this.mScanner = new Scanner();
            if (isWifiEnabled())
                this.mScanner.resume();
        }
    }

    void forceUpdate() {
        this.mLastInfo = this.mWifiManager.getConnectionInfo();
        this.mLastNetworkInfo = this.mConnectivityManager.getNetworkInfo(this.mWifiManager.getCurrentNetwork());
        fetchScansAndConfigsAndUpdateAccessPoints();
    }

    @Override
    public void onStart() {
        forceUpdate();
        registerScoreCache();
        this
                .mNetworkScoringUiEnabled = (Settings.Global.getInt(this.mContext
                .getContentResolver(), "network_scoring_ui_enabled", 0) == 1);
        this
                .mMaxSpeedLabelScoreCacheAge = Settings.Global.getLong(this.mContext
                .getContentResolver(), "speed_label_cache_eviction_age_millis", 1200000L);
        resumeScanning();
        if (!this.mRegistered) {
            this.mContext.registerReceiver(this.mReceiver, this.mFilter, null, this.mWorkHandler);
            this.mNetworkCallback = new WifiTrackerNetworkCallback();
            this.mConnectivityManager.registerNetworkCallback(this.mNetworkRequest, this.mNetworkCallback, this.mWorkHandler);
            this.mRegistered = true;
        }
    }

    @Override
    public void onStop() {
        if (this.mRegistered) {
            this.mContext.unregisterReceiver(this.mReceiver);
            this.mConnectivityManager.unregisterNetworkCallback(this.mNetworkCallback);
            this.mRegistered = false;
        }
        unregisterScoreCache();
        pauseScanning();
        this.mWorkHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroy() {
        this.mWorkThread.quit();
    }

}
