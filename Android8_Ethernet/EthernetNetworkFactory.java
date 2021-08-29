/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.ethernet;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.PppoeManager;
import android.net.IEthernetServiceListener;
import android.net.InterfaceConfiguration;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.StaticIpConfiguration;
import android.net.ip.IpManager;
import android.net.ip.IpManager.ProvisioningConfiguration;
import android.net.RouteInfo;
import android.net.LinkAddress;
import android.net.NetworkUtils;


import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;
import android.content.Intent;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.Exception;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.net.BaseNetworkObserver;

import android.os.UserHandle;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Manages connectivity for an Ethernet interface.
 * <p>
 * Ethernet Interfaces may be present at boot time or appear after boot (e.g.,
 * for Ethernet adapters connected over USB). This class currently supports
 * only one interface. When an interface appears on the system (or is present
 * at boot time) this class will start tracking it and bring it up, and will
 * attempt to connect when requested. Any other interfaces that subsequently
 * appear will be ignored until the tracked interface disappears. Only
 * interfaces whose names match the <code>config_ethernet_iface_regex</code>
 * regular expression are tracked.
 * <p>
 * This class reports a static network score of 70 when it is tracking an
 * interface and that interface's link is up, and a score of 0 otherwise.
 *
 * @hide
 */
class EthernetNetworkFactory {
    private static final String NETWORK_TYPE = "Ethernet";
    private static final String TAG = "EthernetNetworkFactory";
    //这里就是当发现网卡up的话就设置评分  否则将评分设置成-1.
    // mFactory.setScoreFilter(up ? NETWORK_SCORE : -1); //NETWORK_SCORE 就是自己定义的分数，我这里假设定义70
    private static final int NETWORK_SCORE = 100;
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    /**
     * Tracks interface changes. Called from NetworkManagementService.
     */
    private InterfaceObserver mInterfaceObserver;

    /**
     * For static IP configuration
     */
    private EthernetManager mEthernetManager;
    private PppoeManager mPppoeManager;

    /**
     * To set link state and configure IP addresses.
     */
    private INetworkManagementService mNMService;

    /**
     * All code runs here, including start().
     */
    private Handler mHandler;

    /* To communicate with ConnectivityManager */
    private NetworkCapabilities mNetworkCapabilities;
    private LocalNetworkFactory mFactory;
    private Context mContext;

    /**
     * Product-dependent regular expression of interface names we track.
     */
    private static String mIfaceMatch = "";

    /**
     * To notify Ethernet status.
     */
    private final RemoteCallbackList<IEthernetServiceListener> mListeners;

    /**
     * Data members. All accesses to these must be on the handler thread.
     */
    private static String mIfaceTmp = "";
    private static String mPppIface = "ppp0";
    private boolean mPppoeConnected = false;
    private boolean mLinkUp;
    private LinkProperties mLinkProperties;
    private boolean mNetworkRequested = false;
    public int mEthernetCurrentState = EthernetManager.ETHER_STATE_DISCONNECTED;
    private IpAssignment mConnectMode;

    /* add  */
    private HashMap<String, IpManager> mMapIpManager = new HashMap<>();
    private HashMap<String, NetworkInfo> mMapNetworkInfo = new HashMap<>();
    private HashMap<String, NetworkAgent> mMapNetworkAgent = new HashMap<>();
    private HashMap<String, LinkProperties> mMapLinkProperties = new HashMap<>();
    private HashMap<String, Integer> mMapEtherState = new HashMap<>();
    private HashMap<String, Boolean> mMapFaceChange = new HashMap<>();// true 已经调用过

    EthernetNetworkFactory(RemoteCallbackList<IEthernetServiceListener> listeners) {
        initNetworkCapabilities();
        clearInfo();
        mListeners = listeners;
    }

    private void initNetworkCapabilities() {
        mNetworkCapabilities = new NetworkCapabilities();
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        // We have no useful data on bandwidth. Say 100M up and 100M down. :-(
        mNetworkCapabilities.setLinkUpstreamBandwidthKbps(100 * 1000);
        mNetworkCapabilities.setLinkDownstreamBandwidthKbps(100 * 1000);
    }

    private void clearInfo() {
        try {
            if (mMapFaceChange.size() > 0) {
                Set<String> setFace = mMapFaceChange.keySet();
                if (setFace.size() > 0) {
                    for (String face : setFace) {
                        NetworkInfo networkInfo = mMapNetworkInfo.get(face);
                        if (networkInfo != null) {
                            networkInfo.setExtraInfo(null);
                            networkInfo.setIsAvailable(false);
                            networkInfo = null;
                        }
                        NetworkAgent networkAgent = mMapNetworkAgent.get(face);
                        networkAgent = null;
                        IpManager ipManager = mMapIpManager.get(face);
                        if (ipManager != null) {
                            ipManager.shutdown();
                            ipManager = null;
                        }
                        LinkProperties linkProperties = mMapLinkProperties.get(face);
                        if (linkProperties != null) {
                            linkProperties.clear();
                            linkProperties = null;
                        }
                        mMapFaceChange.put(face, false);
                        mMapNetworkInfo.put(face, null);
                        mMapNetworkAgent.put(face, null);
                        mMapIpManager.put(face, null);
                        mMapLinkProperties.put(face, null);
                        mNMService.clearInterfaceAddresses(face);
                    }
                }
                mMapFaceChange.clear();
                mMapNetworkInfo.clear();
                mMapNetworkAgent.clear();
                mMapIpManager.clear();
                mMapLinkProperties.clear();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear addresses or disable ipv6" + e);
        }
        sendStopAllEthernetStateChangedBroadcast();
    }

    /**
     * Begin monitoring connectivity
     */
    public void start(Context context, Handler handler) {
        mHandler = handler;
        mContext = context;
        // The services we use.
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNMService = INetworkManagementService.Stub.asInterface(b);
        mEthernetManager = (EthernetManager) mContext.getSystemService(mContext.ETHERNET_SERVICE);
        mPppoeManager = (PppoeManager) mContext.getSystemService(mContext.PPPOE_SERVICE);
        // Interface match regex.
        mIfaceMatch = context.getResources().getString(com.android.internal.R.string.config_ethernet_iface_regex);
        Log.d(TAG, "EthernetNetworkFactory start " + mIfaceMatch);
        // Create and register our NetworkFactory.
        mFactory = new LocalNetworkFactory(NETWORK_TYPE, context, mHandler.getLooper());
        mFactory.setCapabilityFilter(mNetworkCapabilities);
        mFactory.setScoreFilter(NETWORK_SCORE);
        mFactory.register();

        mConnectMode = IpAssignment.DHCP;
        // Start tracking interface change events.
        mInterfaceObserver = new InterfaceObserver();
        try {
            mNMService.registerObserver(mInterfaceObserver);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register InterfaceObserver " + e);
        }
        // If an Ethernet interface is already connected, start tracking that.
        // Otherwise, the first Ethernet interface to appear will be tracked.
        mHandler.post(() -> trackFirstAvailableInterface());
    }

    public void trackFirstAvailableInterface() {
        Log.d(TAG, "trackFirstAvailableInterface : start");
        try {
            String[] ifaces = mNMService.listInterfaces();
            for (String iface : ifaces) {
                if (maybeTrackInterface(iface)) {
                    // We have our interface. Track it.
                    // Note: if the interface already has link (e.g., if we crashed and got
                    // restarted while it was running), we need to fake a link up notification so we
                    // start configuring it.
                    //if (mNMService.getInterfaceConfig(iface).hasFlag("running")) {
                    mIfaceTmp = iface;
                    int carrier = getEthernetCarrierState(mIfaceTmp);
                    Log.d(TAG, "trackFirstAvailableInterface : iface = " + iface + " carrier = " + carrier);
                    // 初始化时会冲突
//                    updateInterfaceState(mIfaceTmp, carrier == 1);
//                    if (mNMService.getInterfaceConfig(iface).hasFlag("running") && carrier == 1) {
//                        Log.d(TAG, "trackFirstAvailableInterface : iface = " + iface + " running");
//                        updateInterfaceState(iface, true);
//                    }

                }
            }
        } catch (RemoteException | IllegalStateException e) {
            Log.e(TAG, "Could not get list of interfaces " + e);
        }
    }

    private boolean maybeTrackInterface(String iface) {
        // If we don't already have an interface, and if this interface matches
        // our regex, start tracking it.
        if (!iface.matches(mIfaceMatch) || (mMapFaceChange.containsKey(iface) && mMapFaceChange.get(iface)))
            return false;
        mEthernetManager.createIpConfigTxt(iface);
        Log.d(TAG, "maybeTrackInterface: Started tracking interface " + iface);
        mMapFaceChange.put(iface, true);
        setInterfaceUp(iface);
        return true;
    }

    private void setInterfaceUp(String iface) {
        // Bring up the interface so we get link status indications.
        Log.d(TAG, "setInterfaceUp: " + iface);
        try {
            mNMService.setInterfaceUp(iface);
        } catch (RemoteException | IllegalStateException e) {
            Log.e(TAG, "Error upping interface " + ": " + e);
        }
    }

    /**
     * Updates interface state variables.
     * Called on link state changes or on startup.
     */
    private void updateInterfaceState(String face, boolean up) {
        Log.d(TAG, "updateInterfaceState: " + face + " link " + (up ? "up" : "down"));
        if (!mMapFaceChange.containsKey(face)) return;
        IpManager ipmanager = mMapIpManager.get(face);
        if (up && ipmanager != null) {
            Log.d(TAG, "updateInterfaceState: Already connected or connecting, skip connect");
            return;
        }
        mLinkUp = up;
        try {
            setInterfaceInfo(face, up);
            if (up) {
                maybeStartIpManager(face);
            } else {
                stopIpManager(face);
                if (mConnectMode == IpAssignment.PPPOE) {
                    mPppoeConnected = false;
                    Log.d(TAG, "updateInterfaceState: pppoe stop");
                    mPppoeManager.stopPppoe();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "updateInterfaceState: " + e);
        }
    }

    private void stopIpManager(String face) {
        Log.d(TAG, " stopIpManager face: " + face);
        try {
            mMapFaceChange.put(face, false);
            IpManager ipManager = mMapIpManager.get(face);
            if (ipManager != null) {
                // TODO: 2020/11/6 新增
                ipManager.shutdown();
                ipManager = null;
            }
            // TODO: 2020/11/6 新增
            mMapIpManager.put(face, null);
            sendEthernetStateChangedBroadcast(face, EthernetManager.ETHER_STATE_DISCONNECTED);
            NetworkInfo networkInfo = mMapNetworkInfo.get(face);
            if (networkInfo != null) {
                networkInfo.setDetailedState(DetailedState.DISCONNECTED, null, getHardwareAddress(face));
            }
            // 清空ip地址
            mNMService.clearInterfaceAddresses(face);
        } catch (RemoteException e) {
            Log.e(TAG, " stopIpManager exception: " + e);
        }
        // ConnectivityService will only forget our NetworkAgent if we send it a NetworkInfo object
        // with a state of DISCONNECTED or SUSPENDED. So we can't simply clear our NetworkInfo here:
        // that sets the state to IDLE, and ConnectivityService will still think we're connected.
        updateAgent(face);
    }

    private void stopAllIpManager() {
        Log.d(TAG, " stopAllIpManager");
        try {
            if (mMapIpManager.size() > 0) {
                Set<Map.Entry<String, IpManager>> entries = mMapIpManager.entrySet();
                Iterator<Map.Entry<String, IpManager>> iterator = entries.iterator();
                if (iterator.hasNext()) {
                    Map.Entry<String, IpManager> next = iterator.next();
                    if (next != null) {
                        String face = next.getKey();
                        NetworkInfo networkInfo = mMapNetworkInfo.get(face);
                        if (networkInfo != null) {
                            networkInfo.setDetailedState(DetailedState.DISCONNECTED, null, mNMService.getInterfaceConfig(face).getHardwareAddress());
                        }
                        IpManager ipManager = next.getValue();
                        if (ipManager != null) {
                            ipManager.shutdown();
                            ipManager = null;
                        }
                        entries.remove(next);
                        updateAgent(face);
                        setInterfaceInfo(face);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "stopAllIpManager RemoteException " + e);
        }

        // ConnectivityService will only forget our NetworkAgent if we send it a NetworkInfo object
        // with a state of DISCONNECTED or SUSPENDED. So we can't simply clear our NetworkInfo here:
        // that sets the state to IDLE, and ConnectivityService will still think we're connected.

        clearInfo();
    }

    public void stop() {
        Log.d(TAG, " stop");
        stopAllIpManager();
        mFactory.unregister();
    }

    private void sendStopAllEthernetStateChangedBroadcast() {
        if (mMapEtherState.size() > 0) {
            Set<Map.Entry<String, Integer>> entries = mMapEtherState.entrySet();
            Iterator<Map.Entry<String, Integer>> iterator = entries.iterator();
            if (iterator.hasNext()) {
                Map.Entry<String, Integer> next = iterator.next();
                if (next != null) {
                    String face = next.getKey();
                    sendEthernetStateChangedBroadcast(face, EthernetManager.ETHER_STATE_DISCONNECTED);
                }
            }
        }
    }

    private String getHardwareAddress(String face) {
        try {
            return mNMService.getInterfaceConfig(face).getHardwareAddress();
        } catch (RemoteException e) {
            Log.d(TAG, "getHardwareAddress: " + e);
        }
        return "";
    }

    public String dumpEthCurrentState(int curState) {
        if (curState == EthernetManager.ETHER_STATE_DISCONNECTED)
            return "DISCONNECTED";
        else if (curState == EthernetManager.ETHER_STATE_CONNECTING)
            return "CONNECTING";
        else if (curState == EthernetManager.ETHER_STATE_CONNECTED)
            return "CONNECTED";
        else if (curState == EthernetManager.ETHER_STATE_DISCONNECTING)
            return "DISCONNECTING";
        return "DISCONNECTED";
    }

    private void sendEthernetStateChangedBroadcast(String faceName, int etherState) {
        if (TextUtils.isEmpty(faceName) || etherState < 0) return;
        mMapEtherState.put(faceName, etherState);
        Log.d(TAG, "sendEthernetStateChangedBroadcast: curState = " + dumpEthCurrentState(etherState));
        mEthernetCurrentState = etherState;
        final Intent intent = new Intent(EthernetManager.ETHERNET_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(EthernetManager.EXTRA_ETHERNET_STATE, etherState);
        intent.putExtra(EthernetManager.EXTRA_ETHERNET_FACE_NAME, faceName);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }


    private class LocalNetworkFactory extends NetworkFactory {
        LocalNetworkFactory(String name, Context context, Looper looper) {
            super(looper, context, name, new NetworkCapabilities());
        }

        protected void startNetwork() {
            Log.d(TAG, "startNetwork: ");
            if (!mNetworkRequested) {
                mNetworkRequested = true;
                Set<Map.Entry<String, IpManager>> entries = mMapIpManager.entrySet();
                Iterator<Map.Entry<String, IpManager>> iterator = entries.iterator();
                if (iterator.hasNext()) {
                    Map.Entry<String, IpManager> next = iterator.next();
                    if (next != null) {
                        String face = next.getKey();
                        IpManager ipManager = next.getValue();
                        if (ipManager != null) {
                            maybeStartIpManager(face);
                        }
                    }
                }
            }
        }

        protected void stopNetwork() {
            Log.d(TAG, "stopNetwork: ");
            mNetworkRequested = false;
            stopAllIpManager();
//            sendEthernetStateChangedBroadcast(EthernetManager.ETHER_STATE_DISCONNECTED);
        }
    }


    // first disconnect, then connect
    public void reconnect(String iface) {
        Log.d(TAG, "reconnect: iface = " + iface);
        if (!mMapFaceChange.containsKey(iface)) return;

        Log.d(TAG, "reconnect: first disconnect");
        updateInterfaceState(iface, false);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignore) {
        }

        Log.d(TAG, "reconnect: then connect");
        updateInterfaceState(iface, true);

    }

    // 开启所有网络
    public void reconnectAllInterface() {
        Log.d(TAG, "reconnectAllInterface:");
        if (mMapFaceChange.size() > 0) {
            Set<String> setFace = mMapFaceChange.keySet();
            if (setFace.size() > 0) {
                for (String face : setFace) {
                    updateInterfaceState(face, true);
                    Log.d(TAG, "reconnectAllInterface connect");
                }
            }
            mMapFaceChange.clear();
        }
    }

    public void disconnect(String iface) {
        Log.d(TAG, "disconnect: iface = " + iface);
        updateInterfaceState(iface, false);
    }

    private class InterfaceObserver extends BaseNetworkObserver {
        @Override
        public void interfaceLinkStateChanged(String iface, boolean up) {
            Log.d(TAG, "interfaceLinkStateChanged: " + iface + " isUp: " + up);
            mHandler.post(() -> {
                updateInterfaceState(iface, up);
            });
        }

        @Override
        public void interfaceAdded(String iface) {
            Log.d(TAG, "interfaceAdded: " + iface);
            mHandler.post(() -> {
                maybeTrackInterface(iface);
            });
//            if (mPppIface.equals(iface) && mPppoeManager.getPppoePhyIface().equals(mIface)) {
//                pppoeConnected();
//            }
        }

        @Override
        public void interfaceRemoved(String face) {
            Log.d(TAG, "interfaceRemoved: " + face);
            mHandler.post(() -> {
                if (stopTrackingInterface(face)) {
                    trackFirstAvailableInterface();
                }
            });
//            if (mPppIface.equals(iface) && mPppoeManager.getPppoePhyIface().equals(mIface)) {
//                pppoeDisconnected();
//            }
        }
    }

    /**
     * 开启 关闭网关
     *
     * @param face 网关口
     * @param up   true 开 false 关闭
     */
    public void setInterfaceChange(String face, boolean up) {
        try {
            if (up) {
                mNMService.setInterfaceUp(face);
            } else {
                mNMService.setInterfaceDown(face);
            }
        } catch (RemoteException e) {
            Log.d(TAG, "setInterfaceDown : " + face);
        }
    }

    private void pppoeDisconnected() {
        if (!mPppoeConnected) {
            if (VDBG) Log.d(TAG, "Pppoe already disconnected, skip disconnect");
            return;
        }
        try {
            Thread.sleep(2000);        //wait pppd killed
        } catch (InterruptedException ignore) {
        }
        if (mEthernetCurrentState == EthernetManager.ETHER_STATE_DISCONNECTED) {
            if (VDBG) Log.d(TAG, "Already disconnected, skip disconnect");
            return;
        }
        Log.d(TAG, "pppoe stop by terminated");
        stopIpManager("");
        mPppoeConnected = false;
//        sendEthernetStateChangedBroadcast(EthernetManager.ETHER_STATE_DISCONNECTED);
    }

    private void pppoeConnected(String face) {
        if (mPppoeConnected) {
            if (VDBG) Log.d(TAG, "Pppoe already connected, skip connect");
            return;
        }
        try {
            Thread.sleep(4000);          //wait pppoe connected
        } catch (InterruptedException ignore) {
        }

        if (mPppoeManager.getPppoeState() != PppoeManager.PPPOE_STATE_CONNECT) {
            if (VDBG) Log.d(TAG, "getPppoeState() != PPPOE_STATE_CONNECT , skip pppoeConnected");
            return;
        }
        Log.d(TAG, "pppoe auto connected");
        LinkProperties mPppLinkProperties = mPppoeManager.getLinkProperties();
        String iface = mPppoeManager.getPppIfaceName();
        mPppLinkProperties.setInterfaceName(iface);
        mHandler.post(() -> onIpLayerStarted(mPppLinkProperties, face));
        mPppoeConnected = true;
    }


    private boolean stopTrackingInterface(String face) {
        if (!mMapFaceChange.containsKey(face)) return false;
        Log.d(TAG, "Stopped tracking interface " + face);
        setInterfaceInfo(face);
        stopIpManager(face);
        sendEthernetStateChangedBroadcast(face, EthernetManager.ETHER_STATE_DISCONNECTED);
        return true;
    }

    private boolean setStaticIpAddress(StaticIpConfiguration staticConfig, String face) {
        if (staticConfig.ipAddress != null &&
                staticConfig.gateway != null &&
                staticConfig.dnsServers.size() > 0) {
            try {
                Log.i(TAG, "Applying static IPv4 configuration to " + ": " + staticConfig);
                InterfaceConfiguration config = mNMService.getInterfaceConfig(face);
                config.setLinkAddress(staticConfig.ipAddress);
                mNMService.setInterfaceConfig(face, config);
                return true;
            } catch (RemoteException | IllegalStateException e) {
                Log.e(TAG, "Setting static IP address failed: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "Invalid static IP configuration.");
        }
        return false;
    }


    public void maybeStartIpManager(String face) {
        Log.d(TAG, "maybeStartIpManager: " + face);
        if (mNetworkRequested && !TextUtils.isEmpty(face)) {
            startIpManager(face);
        }
    }

    public void startIpManager(final String face) {
        int carrier = getEthernetCarrierState(face);
        Log.d(TAG, String.format("starting IpManager(%s): carrier:%s  networkInfo=%s ", face, carrier, mMapNetworkInfo.get(face)));
        if (carrier != 1) {
            return;
        }
        if (mMapIpManager.containsKey(face) && mMapIpManager.get(face) != null) {
            Log.d(TAG, "Already connected, skip connect");
            return;
        }
        sendEthernetStateChangedBroadcast(face, EthernetManager.ETHER_STATE_CONNECTING);

        Thread ipProvisioningThread = new Thread(new Runnable() {
            public void run() {
                IpConfiguration config = mEthernetManager.getConfigurationWithIFace(face);
                mConnectMode = config.getIpAssignment();
                final String tcpBufferSizes = mContext.getResources().getString(
                        com.android.internal.R.string.config_ethernet_tcp_buffers);
                if (mPppoeManager == null && mConnectMode == IpAssignment.PPPOE) {
                    mConnectMode = IpAssignment.DHCP;
                    Log.d(TAG, "mPppoeManager == null, set mConnectMode to DHCP");
                }
                if (config.getIpAssignment() == IpAssignment.STATIC) {
                    Log.d(TAG, "config STATIC");
                    if (!setStaticIpAddress(config.getStaticIpConfiguration(), face)) {
                        // We've already logged an error.
                        sendEthernetStateChangedBroadcast(face, EthernetManager.ETHER_STATE_DISCONNECTED);
                        return;
                    }
                    LinkProperties linkProperties = config.getStaticIpConfiguration().toLinkProperties(face);
                    linkProperties.setTcpBufferSizes(tcpBufferSizes);
                    if (config.getProxySettings() == ProxySettings.STATIC ||
                            config.getProxySettings() == ProxySettings.PAC) {
                        linkProperties.setHttpProxy(config.getHttpProxy());
                    }
                    mHandler.post(() -> onIpLayerStarted(linkProperties, face));
                } else if (config.getIpAssignment() == IpAssignment.PPPOE) {
                    Log.d(TAG, "config PPPOE");
                    Log.d(TAG, "start pppoe connect: " + config.pppoeAccount + ", " + config.pppoePassword);
                    mPppoeConnected = true;
                    mPppoeManager.connect(config.pppoeAccount, config.pppoePassword, face);

                    int state = mPppoeManager.getPppoeState();
                    Log.d(TAG, "end pppoe connect: state = " + mPppoeManager.dumpCurrentState(state));
                    if (state == PppoeManager.PPPOE_STATE_CONNECT) {
                        LinkProperties linkProperties = mPppoeManager.getLinkProperties();
                        linkProperties.setInterfaceName(face);
                        linkProperties.setTcpBufferSizes(tcpBufferSizes);
                        if (config.getProxySettings() == ProxySettings.STATIC ||
                                config.getProxySettings() == ProxySettings.PAC) {
                            linkProperties.setHttpProxy(config.getHttpProxy());
                        }
                        mHandler.post(() -> onIpLayerStarted(linkProperties, face));
                    } else {
                        Log.e(TAG, "pppoe connect failed.");
                        mPppoeConnected = false;
                        sendEthernetStateChangedBroadcast(face, EthernetManager.ETHER_STATE_DISCONNECTED);
                        return;
                    }
                } else {
                    Log.d(TAG, "config DHCP");
                    try {
                        InterfaceConfiguration config2 = mNMService.getInterfaceConfig(face);
                        NetworkInfo networkInfo = mMapNetworkInfo.get(face);
                        if (networkInfo != null) {
                            networkInfo.setDetailedState(DetailedState.OBTAINING_IPADDR, null, config2.getHardwareAddress());
                            Log.d(TAG, "config DHCP networkInfo: " + networkInfo);
                        }

                        IpManager.Callback ipmCallback = new IpManager.Callback() {
                            @Override
                            public void onProvisioningSuccess(LinkProperties newLp) {
                                Log.d(TAG, "onProvisioningSuccess: lp = " + newLp);
                                mHandler.post(() -> onIpLayerStarted(newLp, face));
                            }

                            @Override
                            public void onProvisioningFailure(LinkProperties newLp) {
                                Log.d(TAG, "onProvisioningFailure: lp = " + newLp);
                                mHandler.post(() -> onIpLayerStopped(newLp, face));
                            }

                            @Override
                            public void onLinkPropertiesChange(LinkProperties newLp) {
                                Log.d(TAG, "onLinkPropertiesChange: lp = " + newLp);
                                mHandler.post(() -> updateLinkProperties(newLp, face));
                            }
                        };

                        synchronized (EthernetNetworkFactory.this) {
                            IpManager ipManager = new IpManager(mContext, face, ipmCallback);
                            mMapIpManager.put(face, ipManager);
                            if (config.getProxySettings() == ProxySettings.STATIC ||
                                    config.getProxySettings() == ProxySettings.PAC) {
                                ipManager.setHttpProxy(config.getHttpProxy());
                            }

                            if (!TextUtils.isEmpty(tcpBufferSizes)) {
                                ipManager.setTcpBufferSizes(tcpBufferSizes);
                            }

                            ProvisioningConfiguration provisioningConfiguration =
                                    ipManager.buildProvisioningConfiguration()
                                            .withProvisioningTimeoutMs(0)
                                            .build();
                            ipManager.startProvisioning(provisioningConfiguration);

                        }
                    } catch (RemoteException e) {
                        Log.d(TAG, " startIpManager RemoteException: " + e);
                    }
                }
            }
        });
        ipProvisioningThread.start();
    }

    void onIpLayerStarted(LinkProperties linkProperties, final String face) {
        // TODO: 2020/11/6 新增
        mLinkProperties = linkProperties;
        mMapLinkProperties.put(face, linkProperties);
        Log.d(TAG, "IP success: lp = " + linkProperties);
        try {
            // Create our NetworkAgent.
            NetworkInfo networkInfo = mMapNetworkInfo.get(face);
            if (networkInfo != null) {
                networkInfo.setDetailedState(DetailedState.CONNECTED, null, mNMService.getInterfaceConfig(face).getHardwareAddress());
                NetworkAgent networkAgent = new NetworkAgent(mHandler.getLooper(), mContext,
                        NETWORK_TYPE, networkInfo, mNetworkCapabilities, linkProperties,
                        NETWORK_SCORE) {
                    @java.lang.Override
                    protected void unwanted() {
                        synchronized (EthernetNetworkFactory.this) {
                            // 网络连接中断
                            Log.d(TAG, "unwanted");
                            stopIpManager(face);
                        }
                    }
                };
                mMapNetworkAgent.put(face, networkAgent);
            }
            sendEthernetStateChangedBroadcast(face, EthernetManager.ETHER_STATE_CONNECTED);
        } catch (RemoteException e) {
            Log.d(TAG, " onIpLayerStarted : " + e);
        }
    }

    void onIpLayerStopped(LinkProperties linkProperties, String face) {
        // This cannot happen due to provisioning timeout, because our timeout is 0. It can only
        // happen if we're provisioned and we lose provisioning.
        mLinkProperties = linkProperties;
        mMapLinkProperties.put(face, linkProperties);
        stopIpManager(face);
        Set<Map.Entry<String, IpManager>> entries = mMapIpManager.entrySet();
        Iterator<Map.Entry<String, IpManager>> iterator = entries.iterator();
        if (iterator.hasNext()) {
            Map.Entry<String, IpManager> next = iterator.next();
            if (next != null) {
                String keyFace = next.getKey();
                maybeStartIpManager(keyFace);
            }
        }
        sendEthernetStateChangedBroadcast(face, EthernetManager.ETHER_STATE_DISCONNECTED);
    }

    void updateLinkProperties(LinkProperties linkProperties, String face) {
        synchronized (EthernetNetworkFactory.this) {
            mLinkProperties = linkProperties;
            mMapLinkProperties.put(face, linkProperties);
            NetworkAgent networkAgent = mMapNetworkAgent.get(face);
            if (networkAgent != null) {
                networkAgent.sendLinkProperties(linkProperties);
            }
        }
    }

    public void updateAgent(String face) {
        Log.d(TAG, "updateAgent: " + face);
        synchronized (EthernetNetworkFactory.this) {
            NetworkAgent networkAgent = mMapNetworkAgent.get(face);
            NetworkInfo networkInfo = mMapNetworkInfo.get(face);
            LinkProperties linkProperties = mMapLinkProperties.get(face);
            if (networkAgent != null) {
                Log.d(TAG, "Updating mNetworkAgent with: " + mNetworkCapabilities + ", " + networkAgent + ", " + linkProperties);
                networkAgent.sendNetworkCapabilities(mNetworkCapabilities);
                networkAgent.sendNetworkInfo(networkInfo);
                networkAgent.sendLinkProperties(linkProperties);
                // never set the network score below 0.
                networkAgent.sendNetworkScore(mLinkUp ? NETWORK_SCORE : 0);
            }
        }
    }

    // TODO: 2020/11/5 测试
    public boolean isTrackingInterface() {
        return mMapFaceChange.isEmpty();
    }

    private String ReadFromFile(File file) {
        Log.e(TAG, "ReadFromFile : ");
        if ((file != null) && file.exists()) {
            try {
                FileInputStream fin = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fin));
                String flag = reader.readLine();
                fin.close();
                return flag;
            } catch (Exception e) {
                Log.e(TAG, "ReadFromFile : Exception :" + e);
                e.printStackTrace();
            }
        }
        return null;
    }

    public int getEthernetCarrierState(String ifname) {
        Log.e(TAG, "getEthernetCarrierState : ");
        if (ifname != "") {
            try {
                File file = new File("/sys/class/net/" + ifname + "/carrier");
                String carrier = ReadFromFile(file);
                if (TextUtils.isEmpty(carrier)) {
                    return 0;
                } else {
                    return Integer.parseInt(carrier);
                }
            } catch (Exception e) {
                Log.e(TAG, "getEthernetCarrierState : Exception :" + e);
                e.printStackTrace();
                return 0;
            }
        } else {
            return 0;
        }
    }

    public String getEthernetMacAddress(String ifname) {
        if (ifname != "") {
            try {
                File file = new File("/sys/class/net/" + ifname + "/address");
                String address = ReadFromFile(file);
                return address;
            } catch (Exception e) {
                e.printStackTrace();
                return "";
            }
        } else {
            return "";
        }
    }


    /**
     * 不同的网口获取地址
     *
     * @param face 网口
     */
    public String getIpAddress(String face) {
        IpConfiguration config = mEthernetManager.getConfigurationWithIFace(face);
        if (config.getIpAssignment() == IpAssignment.STATIC) {
            return config.getStaticIpConfiguration().ipAddress.getAddress().getHostAddress();
        } else {
            if (!mMapLinkProperties.isEmpty()) {
                LinkProperties linkProperties = mMapLinkProperties.get(face);
                if (linkProperties != null) {
                    for (LinkAddress l : linkProperties.getLinkAddresses()) {
                        InetAddress source = l.getAddress();
                        Log.d(TAG, "getIpAddress: " + source.getHostAddress() + " face: " + face);
                        if (source instanceof Inet4Address) {
                            return source.getHostAddress();
                        }
                    }
                }
            }
        }
        return "";
    }

    public String getIpAddress() {
        IpConfiguration config = mEthernetManager.getConfiguration();
        if (config.getIpAssignment() == IpAssignment.STATIC) {
            return config.getStaticIpConfiguration().ipAddress.getAddress().getHostAddress();
        } else {
            for (LinkAddress l : mLinkProperties.getLinkAddresses()) {
                InetAddress source = l.getAddress();
                Log.d(TAG, "getIpAddress: " + source.getHostAddress());
                //Log.d(TAG, "getIpAddress: " + source.getHostAddress());
                if (source instanceof Inet4Address) {
                    return source.getHostAddress();
                }
            }
        }
        return "";
    }

    /**
     * 不同的网口获取地址
     *
     * @param face 网口
     */
    public String getNetmask(String face) {
        IpConfiguration config = mEthernetManager.getConfigurationWithIFace(face);
        if (config.getIpAssignment() == IpAssignment.STATIC) {
            return prefix2netmask(config.getStaticIpConfiguration().ipAddress.getPrefixLength());
        } else {
            if (!mMapLinkProperties.isEmpty()) {
                LinkProperties linkProperties = mMapLinkProperties.get(face);
                if (linkProperties != null) {
                    Log.d(TAG, "getNetmask: linkProperties " + linkProperties + " face: " + face);
                    for (LinkAddress l : linkProperties.getLinkAddresses()) {
                        InetAddress source = l.getAddress();
                        if (source instanceof Inet4Address) {
                            return prefix2netmask(l.getPrefixLength());
                        }
                    }
                }
            }
        }
        return "";
    }

    public String getNetmask() {
        Log.d(TAG, "getNetmask: mLinkProperties " + mLinkProperties);
        IpConfiguration config = mEthernetManager.getConfiguration();
        if (config.getIpAssignment() == IpAssignment.STATIC) {
            return prefix2netmask(config.getStaticIpConfiguration().ipAddress.getPrefixLength());
        } else {
            for (LinkAddress l : mLinkProperties.getLinkAddresses()) {
                InetAddress source = l.getAddress();
                if (source instanceof Inet4Address) {
                    return prefix2netmask(l.getPrefixLength());
                }
            }
        }
        return "";
    }

    /**
     * 不同的网口获取地址
     *
     * @param face 网口
     */
    public String getGateway(String face) {
        IpConfiguration config = mEthernetManager.getConfigurationWithIFace(face);
        if (config.getIpAssignment() == IpAssignment.STATIC) {
            return config.getStaticIpConfiguration().gateway.getHostAddress();
        } else {
            if (!mMapLinkProperties.isEmpty()) {
                LinkProperties linkProperties = mMapLinkProperties.get(face);
                if (linkProperties != null) {
                    Log.d(TAG, "getGateway: linkProperties " + linkProperties + " face: " + face);
                    for (RouteInfo route : linkProperties.getRoutes()) {
                        if (route.hasGateway()) {
                            InetAddress gateway = route.getGateway();
                            if (route.isIPv4Default()) {
                                return gateway.getHostAddress();
                            }
                        }
                    }
                }
            }
        }
        return "";
    }

    public String getGateway() {
        Log.d(TAG, "getGateway: mLinkProperties " + mLinkProperties);
        IpConfiguration config = mEthernetManager.getConfiguration();
        if (config.getIpAssignment() == IpAssignment.STATIC) {
            return config.getStaticIpConfiguration().gateway.getHostAddress();
        } else {
            for (RouteInfo route : mLinkProperties.getRoutes()) {
                if (route.hasGateway()) {
                    InetAddress gateway = route.getGateway();
                    if (route.isIPv4Default()) {
                        return gateway.getHostAddress();
                    }
                }
            }
        }
        return "";
    }


    /**
     * 不同的网口获取地址
     *
     * @param face 网口
     * @return dns format: "8.8.8.8,4.4.4.4"
     */
    public String getDns(String face) {
        String dns = "";
        IpConfiguration config = mEthernetManager.getConfigurationWithIFace(face);
        if (config.getIpAssignment() == IpAssignment.STATIC) {
            for (InetAddress nameserver : config.getStaticIpConfiguration().dnsServers) {
                dns += nameserver.getHostAddress() + ",";
            }
        } else {
            if (!mMapLinkProperties.isEmpty()) {
                LinkProperties linkProperties = mMapLinkProperties.get(face);
                if (linkProperties != null) {
                    Log.d(TAG, "getDns: linkProperties " + linkProperties + " face: " + face);
                    for (InetAddress nameserver : linkProperties.getDnsServers()) {
                        dns += nameserver.getHostAddress() + ",";
                    }
                }
            }
        }
        return dns;
    }

    /*
     * return dns format: "8.8.8.8,4.4.4.4"
     */
    public String getDns() {
        Log.d(TAG, "getDns: mLinkProperties " + mLinkProperties);
        String dns = "";
        IpConfiguration config = mEthernetManager.getConfiguration();
        if (config.getIpAssignment() == IpAssignment.STATIC) {
            for (InetAddress nameserver : config.getStaticIpConfiguration().dnsServers) {
                dns += nameserver.getHostAddress() + ",";
            }
        } else {
            for (InetAddress nameserver : mLinkProperties.getDnsServers()) {
                dns += nameserver.getHostAddress() + ",";
            }
        }
        return dns;
    }

    private String prefix2netmask(int prefix) {
        // convert prefix to netmask
        if (true) {
            int mask = 0xFFFFFFFF << (32 - prefix);
            //Log.d(TAG, "mask = " + mask + " prefix = " + prefix);
            return ((mask >>> 24) & 0xff) + "." + ((mask >>> 16) & 0xff) + "." + ((mask >>> 8) & 0xff) + "." + ((mask) & 0xff);
        } else {
            return NetworkUtils.intToInetAddress(NetworkUtils.prefixLengthToNetmaskInt(prefix)).getHostName();
        }
    }

    // TODO: 2020/11/9 待处理
    public int getEthernetConnectState(String ifname) {
        return mMapEtherState.get(ifname);
    }

    /**
     * Set interface information and notify listeners if availability is changed.
     * 网络变化
     */
    private void setInterfaceInfo(String face) {
        try {
            InterfaceConfiguration config = mNMService.getInterfaceConfig(face);
            NetworkInfo networkInfo = mMapNetworkInfo.get(face);
            if (networkInfo != null) {
                networkInfo.setExtraInfo(config.getHardwareAddress());
                networkInfo.setIsAvailable(mMapFaceChange.get(face));
            }
        } catch (RemoteException e) {
        }
        int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mListeners.getBroadcastItem(i).onAvailabilityChanged(true);
            } catch (RemoteException e) {
                // Do nothing here.
            }
        }
        mListeners.finishBroadcast();
    }

    /**
     * Set interface information and notify listeners if availability is changed.
     * 网络变化 UI 调整
     */
    private void setInterfaceInfo(String face, boolean isAvailable) {
        try {
            // TODO: 2020/11/6 新增
            InterfaceConfiguration config = mNMService.getInterfaceConfig(face);
            NetworkInfo networkInfo = new NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, NETWORK_TYPE, "");
            networkInfo.setExtraInfo(config.getHardwareAddress());
            networkInfo.setIsAvailable(isAvailable);
            mMapNetworkInfo.put(face, networkInfo);
        } catch (RemoteException e) {
        }
        int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mListeners.getBroadcastItem(i).onAvailabilityChanged(true);
            } catch (RemoteException e) {
                // Do nothing here.
            }
        }
        mListeners.finishBroadcast();
    }

    private void postAndWaitForRunnable(Runnable r) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }


    void dump(FileDescriptor fd, final IndentingPrintWriter pw, String[] args) {
        try {
            postAndWaitForRunnable(() -> {
                if (mMapFaceChange != null && mMapFaceChange.size() > 0) {
                    Set<String> setFace = mMapFaceChange.keySet();
                    if (setFace.size() > 0) {
                        for (String face : setFace) {
                            pw.println("Network Requested: " + mNetworkRequested);
                            if (isTrackingInterface()) {
                                pw.println("Tracking interface: " + face);
                                pw.increaseIndent();
//                                pw.println("MAC address: " + mNMService.getInterfaceConfig(face).getHardwareAddress());
                                pw.println("MAC address: ");
                                pw.println("Link state: " + (mMapFaceChange.get(face) ? "up" : "down"));
                                pw.decreaseIndent();
                            } else {
                                pw.println("Not tracking any interface");
                            }

                            pw.println();
                            pw.println("mEthernetCurrentState: " + dumpEthCurrentState(mEthernetCurrentState));

                            pw.println();
                            pw.println("NetworkInfo: " + mMapNetworkInfo.get(face));
                            pw.println("LinkProperties: " + mMapLinkProperties.get(face));
                            pw.println("NetworkAgent: " + mMapNetworkAgent.get(face));
                            IpManager ipManager = mMapIpManager.get(face);
                            if (ipManager != null) {
                                pw.println("IpManager:");
                                pw.increaseIndent();
                                ipManager.dump(fd, pw, args);
                                pw.decreaseIndent();
                            }
                        }
                    }
                }
            });
        } catch (InterruptedException e) {
            Log.d(TAG, "dump() interrupted: " + e);
            throw new IllegalStateException("dump() interrupted");
        }
    }
}
