package com.github.tartaricacid.netmusic.kugou.util;

import java.util.Map;
import java.util.TreeMap;

/**
 * 酷狗API 签名算法工具类
 * 参照 KuGou 桌面应用 server/util/helper.js 实现
 */
public final class KuGouSignature {

    public static final String ANDROID_SECRET = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";

    public static final String WEB_SECRET = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";

    public static final String REGISTER_SECRET = "1014";

    public static final String KEY_SECRET = "185672dd44712f60bb1736df5a377e82";

    public static final int APPID = 3116;
    public static final int CLIENTVER = 11440;

    private KuGouSignature() {}

    // 用于调试的最后一次签名输入（多线程访问，需要 volatile）
    private static volatile String lastSignatureInput = "";

    public static String getLastSignatureInput() {
        return lastSignatureInput;
    }

    public static String signatureAndroidParams(Map<String, Object> params, String data) {
        String paramsStr = new TreeMap<>(params).entrySet().stream()
                .map(e -> {
                    Object val = e.getValue();
                    String valStr = val instanceof Map ? mapToString((Map<?, ?>) val) : String.valueOf(val);
                    return e.getKey() + "=" + valStr;
                })
                .collect(java.util.stream.Collectors.joining(""));
        lastSignatureInput = ANDROID_SECRET + paramsStr + (data != null ? data : "") + ANDROID_SECRET;
        return CryptoUtils.md5(lastSignatureInput);
    }

    public static String signatureWebParams(Map<String, Object> params) {
        String paramsStr = new TreeMap<>(params).entrySet().stream()
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                .collect(java.util.stream.Collectors.joining(""));
        return CryptoUtils.md5(WEB_SECRET + paramsStr + WEB_SECRET);
    }

    public static String signatureRegisterParams(String... values) {
        String sorted = java.util.Arrays.stream(values)
                .sorted()
                .collect(java.util.stream.Collectors.joining(""));
        return CryptoUtils.md5(REGISTER_SECRET + sorted + REGISTER_SECRET);
    }

    public static String signKey(String hash, String mid, long userid, int appid) {
        return CryptoUtils.md5(hash + KEY_SECRET + appid + mid + userid);
    }

    public static String signParamsKey(long data, int appid, int clientver) {
        return CryptoUtils.md5(appid + ANDROID_SECRET + clientver + data);
    }

    public static String signatureV2(Map<String, Object> params) {
        String paramsStr = new TreeMap<>(params).entrySet().stream()
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                .collect(java.util.stream.Collectors.joining(""));
        return CryptoUtils.md5(paramsStr + "kgcloudv2").toUpperCase();
    }

    private static String mapToString(Map<?, ?> map) {
        return new com.google.gson.Gson().toJson(map);
    }
}
