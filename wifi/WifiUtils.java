package vl.vision.home.util.data.wifi;

import android.content.Context;
import android.net.IpConfiguration;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;
import android.text.TextUtils;


import java.util.Map;

import vl.vision.home.R;

/**
 * Created by hanqq on 2022/2/21
 * Email:yin13753884368@163.com
 * CSDN:http://blog.csdn.net/yin13753884368/article
 * Github:https://github.com/taxiao213
 */
public class WifiUtils {
    public static String buildLoggingSummary(AccessPoint accessPoint, WifiConfiguration config) {
        StringBuilder summary = new StringBuilder();
        WifiInfo info = accessPoint.getInfo();
        if (accessPoint.isActive() && info != null)
            summary.append(" f=" + Integer.toString(info.getFrequency()));
        summary.append(" " + getVisibilityStatus(accessPoint));
        if (config != null && !config.getNetworkSelectionStatus().isNetworkEnabled()) {
            summary.append(" (" + config.getNetworkSelectionStatus().getNetworkStatusString());
            if (config.getNetworkSelectionStatus().getDisableTime() > 0L) {
                long now = System.currentTimeMillis();
                long diff = (now - config.getNetworkSelectionStatus().getDisableTime()) / 1000L;
                long sec = diff % 60L;
                long min = diff / 60L % 60L;
                long hour = min / 60L % 60L;
                summary.append(", ");
                if (hour > 0L)
                    summary.append(Long.toString(hour) + "h ");
                summary.append(Long.toString(min) + "m ");
                summary.append(Long.toString(sec) + "s ");
            }
            summary.append(")");
        }
        if (config != null) {
            WifiConfiguration.NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
            int index = 0;
            for (; index < 15;
                 index++) {
                if (networkStatus.getDisableReasonCounter(index) != 0)
                    summary.append(" " +
                            WifiConfiguration.NetworkSelectionStatus.getNetworkDisableReasonString(index) + "=" + networkStatus
                            .getDisableReasonCounter(index));
            }
        }
        return summary.toString();
    }

    public static String getVisibilityStatus(AccessPoint accessPoint) {
        WifiInfo info = accessPoint.getInfo();
        StringBuilder visibility = new StringBuilder();
        StringBuilder scans24GHz = new StringBuilder();
        StringBuilder scans5GHz = new StringBuilder();
        StringBuilder scans60GHz = new StringBuilder();
        StringBuilder scans6GHz = new StringBuilder();
        String bssid = null;
        if (accessPoint.isActive() && info != null) {
            bssid = info.getBSSID();
            if (bssid != null)
                visibility.append(" ").append(bssid);
            visibility.append(" rssi=").append(info.getRssi());
            visibility.append(" ");
            visibility.append(" score=").append(info.score);
            if (accessPoint.getSpeed() != 0)
                visibility.append(" speed=").append(accessPoint.getSpeedLabel());
            visibility.append(String.format(" tx=%.1f,", new Object[]{Double.valueOf(info.txSuccessRate)}));
            visibility.append(String.format("%.1f,", new Object[]{Double.valueOf(info.txRetriesRate)}));
            visibility.append(String.format("%.1f ", new Object[]{Double.valueOf(info.txBadRate)}));
            visibility.append(String.format("rx=%.1f", new Object[]{Double.valueOf(info.rxSuccessRate)}));
        }
        int maxRssi6 = WifiConfiguration.INVALID_RSSI;
        int maxRssi5 = WifiConfiguration.INVALID_RSSI;
        int maxRssi24 = WifiConfiguration.INVALID_RSSI;
        int maxRssi60 = WifiConfiguration.INVALID_RSSI;
        int maxDisplayedScans = 4;
        int num6 = 0;
        int num5 = 0;
        int num24 = 0;
        int num60 = 0;
        int numBlackListed = 0;
        long nowMs = SystemClock.elapsedRealtime();
        for (ScanResult result : accessPoint.getScanResults()) {
            if (result == null)
                continue;
            if (result.frequency >= 5925 && result.frequency <= 7125) {
                num6++;
                if (result.level > maxRssi6)
                    maxRssi6 = result.level;
                if (num6 <= 4)
                    scans6GHz.append(
                            verboseScanResultSummary(accessPoint, result, bssid, nowMs));
                continue;
            }
            if (result.frequency >= 4900 && result.frequency <= 5900) {
                num5++;
                if (result.level > maxRssi5)
                    maxRssi5 = result.level;
                if (num5 <= 4)
                    scans5GHz.append(
                            verboseScanResultSummary(accessPoint, result, bssid, nowMs));
                continue;
            }
            if (result.frequency >= 2400 && result.frequency <= 2500) {
                num24++;
                if (result.level > maxRssi24)
                    maxRssi24 = result.level;
                if (num24 <= 4)
                    scans24GHz.append(
                            verboseScanResultSummary(accessPoint, result, bssid, nowMs));
                continue;
            }
            if (result.frequency >= 58320 && result.frequency <= 70200) {
                num60++;
                if (result.level > maxRssi60)
                    maxRssi60 = result.level;
                if (num60 <= 4)
                    scans60GHz.append(
                            verboseScanResultSummary(accessPoint, result, bssid, nowMs));
            }
        }
        visibility.append(" [");
        if (num24 > 0) {
            visibility.append("(").append(num24).append(")");
            if (num24 > 4)
                visibility.append("max=").append(maxRssi24).append(",");
            visibility.append(scans24GHz.toString());
        }
        visibility.append(";");
        if (num5 > 0) {
            visibility.append("(").append(num5).append(")");
            if (num5 > 4)
                visibility.append("max=").append(maxRssi5).append(",");
            visibility.append(scans5GHz.toString());
        }
        visibility.append(";");
        if (num60 > 0) {
            visibility.append("(").append(num60).append(")");
            if (num60 > 4)
                visibility.append("max=").append(maxRssi60).append(",");
            visibility.append(scans60GHz.toString());
        }
        visibility.append(";");
        if (num6 > 0) {
            visibility.append("(").append(num6).append(")");
            if (num6 > 4)
                visibility.append("max=").append(maxRssi6).append(",");
            visibility.append(scans6GHz.toString());
        }
        if (numBlackListed > 0)
            visibility.append("!").append(numBlackListed);
        visibility.append("]");
        return visibility.toString();
    }

    static String verboseScanResultSummary(AccessPoint accessPoint, ScanResult result, String bssid, long nowMs) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(" \n{").append(result.BSSID);
        if (result.BSSID.equals(bssid))
            stringBuilder.append("*");
        stringBuilder.append("=").append(result.frequency);
        stringBuilder.append(",").append(result.level);
        int speed = getSpecificApSpeed(result, accessPoint.getScoredNetworkCache());
        if (speed != 0)
            stringBuilder.append(",")
                    .append(accessPoint.getSpeedLabel(speed));
        int ageSeconds = (int) (nowMs - result.timestamp / 1000L) / 1000;
        stringBuilder.append(",").append(ageSeconds).append("s");
        stringBuilder.append("}");
        return stringBuilder.toString();
    }

    private static int getSpecificApSpeed(ScanResult result, Map<String, TimestampedScoredNetwork> scoredNetworkCache) {
        TimestampedScoredNetwork timedScore = scoredNetworkCache.get(result.BSSID);
        if (timedScore == null)
            return 0;
        return timedScore.getScore().calculateBadge(result.level);
    }

    public static String getMeteredLabel(Context context, WifiConfiguration config) {
        if (config.meteredOverride == 1 || (config.meteredHint &&
                !isMeteredOverridden(config)))
            return context.getString(R.string.wifi_metered_label);
        return context.getString(R.string.wifi_unmetered_label);
    }

    public static boolean isMeteredOverridden(WifiConfiguration config) {
        return (config.meteredOverride != 0);
    }

    public static WifiConfiguration getWifiConfiguration(AccessPoint mAccessPoint, String mPasswordView) {
        WifiConfiguration config = new WifiConfiguration();
        if (mAccessPoint == null) {
            config.SSID = AccessPoint.convertToQuotedString(
                    mAccessPoint.getSsid().toString());
            // If the user adds a network manually, assume that it is hidden.
            config.hiddenSSID = false;
        } else if (!mAccessPoint.isSaved()) {
            config.SSID = AccessPoint.convertToQuotedString(
                    mAccessPoint.getSsidStr());
        } else {
            config.networkId = mAccessPoint.getConfig().networkId;
            config.hiddenSSID = mAccessPoint.getConfig().hiddenSSID;
        }
        if (mAccessPoint != null) {
            switch (mAccessPoint.getSecurity()) {
                case AccessPoint.SECURITY_NONE:
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    break;

                case AccessPoint.SECURITY_WEP:
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                    config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                    if (!TextUtils.isEmpty(mPasswordView)) {
                        int length = mPasswordView.length();
                        String password = mPasswordView;
                        // WEP-40, WEP-104, and 256-bit WEP (WEP-232?)
                        if ((length == 10 || length == 26 || length == 58)
                                && password.matches("[0-9A-Fa-f]*")) {
                            config.wepKeys[0] = password;
                        } else {
                            config.wepKeys[0] = '"' + password + '"';
                        }
                    }
                    break;

                case AccessPoint.SECURITY_PSK:
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    if (!TextUtils.isEmpty(mPasswordView)) {
                        String password = mPasswordView;
                        if (password.matches("[0-9A-Fa-f]{64}")) {
                            config.preSharedKey = password;
                        } else {
                            config.preSharedKey = '"' + password + '"';
                        }
                    }
                    break;

                case AccessPoint.SECURITY_EAP:
                case AccessPoint.SECURITY_EAP_SUITE_B:
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
                    if (mAccessPoint != null && mAccessPoint.isFils256Supported()) {
                        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.FILS_SHA256);
                    }
                    if (mAccessPoint != null && mAccessPoint.isFils384Supported()) {
                        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.FILS_SHA384);
                    }
                    if (mAccessPoint.getSecurity() == AccessPoint.SECURITY_EAP_SUITE_B) {
                        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SUITE_B_192);
                        config.requirePMF = true;
                        config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.GCMP_256);
                        config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GCMP_256);
                        config.allowedGroupManagementCiphers.set(WifiConfiguration.GroupMgmtCipher
                                .BIP_GMAC_256);
                        // allowedSuiteBCiphers will be set according to certificate type
                    }
                    config.enterpriseConfig = new WifiEnterpriseConfig();
//                    int eapMethod = mEapMethodSpinner.getSelectedItemPosition();
//                    int phase2Method = mPhase2Spinner.getSelectedItemPosition();
//                    config.enterpriseConfig.setEapMethod(eapMethod);
//                    switch (eapMethod) {
//                        case WifiEnterpriseConfig.Eap.PEAP:
//                            // PEAP supports limited phase2 values
//                            // Map the index from the mPhase2PeapAdapter to the one used
//                            // by the API which has the full list of PEAP methods.
//                            switch (phase2Method) {
//                                case WIFI_PEAP_PHASE2_MSCHAPV2:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2);
//                                    break;
//                                case WIFI_PEAP_PHASE2_GTC:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);
//                                    break;
//                                case WIFI_PEAP_PHASE2_SIM:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.SIM);
//                                    break;
//                                case WIFI_PEAP_PHASE2_AKA:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.AKA);
//                                    break;
//                                case WIFI_PEAP_PHASE2_AKA_PRIME:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.AKA_PRIME);
//                                    break;
//                                default:
//                                    Log.e(TAG, "Unknown phase2 method" + phase2Method);
//                                    break;
//                            }
//                            break;
//                        case WifiEnterpriseConfig.Eap.SIM:
//                        case WifiEnterpriseConfig.Eap.AKA:
//                        case WifiEnterpriseConfig.Eap.AKA_PRIME:
//                            selectedSimCardNumber = mSimCardSpinner.getSelectedItemPosition() + 1;
//                            config.enterpriseConfig.setSimNum(selectedSimCardNumber);
//                            break;
//                        case WifiEnterpriseConfig.Eap.TTLS:
//                            // The default index from mPhase2TtlsAdapter maps to the API
//                            switch (phase2Method) {
//                                case WIFI_TTLS_PHASE2_PAP:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);
//                                    break;
//                                case WIFI_TTLS_PHASE2_MSCHAP:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAP);
//                                    break;
//                                case WIFI_TTLS_PHASE2_MSCHAPV2:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2);
//                                    break;
//                                case WIFI_TTLS_PHASE2_GTC:
//                                    config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);
//                                    break;
//                                default:
//                                    Log.e(TAG, "Unknown phase2 method" + phase2Method);
//                                    break;
//                            }
//                            break;
//                        default:
//                            break;
//                    }

//                    String caCert = (String) mEapCaCertSpinner.getSelectedItem();
//                    config.enterpriseConfig.setCaCertificateAliases(null);
//                    config.enterpriseConfig.setCaPath(null);
//                    config.enterpriseConfig.setDomainSuffixMatch(mEapDomainView.getText().toString());
//                    if (caCert.equals(mUnspecifiedCertString)
//                            || caCert.equals(mDoNotValidateEapServerString)) {
//                        // ca_cert already set to null, so do nothing.
//                    } else if (caCert.equals(mUseSystemCertsString)) {
//                        config.enterpriseConfig.setCaPath(SYSTEM_CA_STORE_PATH);
//                    } else if (caCert.equals(mMultipleCertSetString)) {
//                        if (mAccessPoint != null) {
//                            if (!mAccessPoint.isSaved()) {
//                                Log.e(TAG, "Multiple certs can only be set "
//                                        + "when editing saved network");
//                            }
//                            config.enterpriseConfig.setCaCertificateAliases(
//                                    mAccessPoint
//                                            .getConfig()
//                                            .enterpriseConfig
//                                            .getCaCertificateAliases());
//                        }
//                    } else {
//                        config.enterpriseConfig.setCaCertificateAliases(new String[]{caCert});
//                    }
//
//                    // ca_cert or ca_path should not both be non-null, since we only intend to let
//                    // the use either their own certificate, or the system certificates, not both.
//                    // The variable that is not used must explicitly be set to null, so that a
//                    // previously-set value on a saved configuration will be erased on an update.
//                    if (config.enterpriseConfig.getCaCertificateAliases() != null
//                            && config.enterpriseConfig.getCaPath() != null) {
//                        Log.e(TAG, "ca_cert ("
//                                + config.enterpriseConfig.getCaCertificateAliases()
//                                + ") and ca_path ("
//                                + config.enterpriseConfig.getCaPath()
//                                + ") should not both be non-null");
//                    }
//
//                    String clientCert = (String) mEapUserCertSpinner.getSelectedItem();
//                    if (clientCert.equals(mUnspecifiedCertString)
//                            || clientCert.equals(mDoNotProvideEapUserCertString)) {
//                        // Note: |clientCert| should not be able to take the value |unspecifiedCert|,
//                        // since we prevent such configurations from being saved.
//                        clientCert = "";
//                    }
//                    config.enterpriseConfig.setClientCertificateAlias(clientCert);
//                    if (eapMethod == WifiEnterpriseConfig.Eap.SIM || eapMethod == WifiEnterpriseConfig.Eap.AKA || eapMethod == WifiEnterpriseConfig.Eap.AKA_PRIME) {
//                        config.enterpriseConfig.setIdentity("");
//                        config.enterpriseConfig.setAnonymousIdentity("");
//                    } else if (eapMethod == WifiEnterpriseConfig.Eap.PWD) {
//                        config.enterpriseConfig.setIdentity(mEapIdentityView.getText().toString());
//                        config.enterpriseConfig.setAnonymousIdentity("");
//                    } else {
//                        config.enterpriseConfig.setIdentity(mEapIdentityView.getText().toString());
//                        config.enterpriseConfig.setAnonymousIdentity(
//                                mEapAnonymousView.getText().toString());
//                    }

//                    if (mPasswordView.isShown()) {
//                        // For security reasons, a previous password is not displayed to user.
//                        // Update only if it has been changed.
//                        if (mPasswordView.length() > 0) {
//                            config.enterpriseConfig.setPassword(mPasswordView);
//                        }
//                    } else {
//                        // clear password
//                        config.enterpriseConfig.setPassword(mPasswordView );
//                    }
                    if (!TextUtils.isEmpty(mPasswordView)){
                        config.enterpriseConfig.setPassword(mPasswordView );
                    }
                    if (mAccessPoint != null && (mAccessPoint.isFils256Supported()
                            || mAccessPoint.isFils384Supported())) {
                        config.enterpriseConfig.setFieldValue(WifiEnterpriseConfig.EAP_ERP, "1");
                    }
                    break;

                case AccessPoint.SECURITY_DPP:
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.DPP);
                    config.requirePMF = true;
                    break;
                case AccessPoint.SECURITY_SAE:
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE);
                    config.requirePMF = true;
                    if (mPasswordView.length() != 0) {
                        String password = mPasswordView;
                        config.preSharedKey = '"' + password + '"';
                    }
                    break;

                case AccessPoint.SECURITY_OWE:
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE);
                    config.requirePMF = true;
                    break;

                default:
                    return null;
            }
        }
//        config.setIpConfiguration(
//                new IpConfiguration(mIpAssignment, mProxySettings,
//                        mStaticIpConfiguration, mHttpProxy));
//        if (mMeteredSettingsSpinner != null) {
//            config.meteredOverride = mMeteredSettingsSpinner.getSelectedItemPosition();
//        }
//
//        if (mPrivacySettingsSpinner != null) {
//            final int macValue =
//                    WifiPrivacyPreferenceController.translatePrefValueToMacRandomizedValue(
//                            mPrivacySettingsSpinner.getSelectedItemPosition());
//            config.macRandomizationSetting = macValue;
//        }
        return config;
    }


}
