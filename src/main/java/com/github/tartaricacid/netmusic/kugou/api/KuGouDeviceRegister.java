package com.github.tartaricacid.netmusic.kugou.api;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.util.CryptoUtils;
import com.github.tartaricacid.netmusic.kugou.util.HttpUtils;
import com.github.tartaricacid.netmusic.kugou.util.KuGouSignature;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * 酷狗设备注册模块
 * 参照 KuGou server/module/register_dev.js 实现
 */
public final class KuGouDeviceRegister {

    private static final Gson GSON = new Gson();

    // RSA 公钥（酷狗概念版，PEM 去掉头尾和换行）
    private static final String RSA_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01" +
            "rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLg" +
            "FVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/" +
            "q6ifSioBszvb3QiwIDAQAB";

    private static final String REGISTER_URL = "https://userservice.kugou.com/risk/v2/r_register_dev";

    private KuGouDeviceRegister() {}

    /**
     * 设备注册结果
     */
    public static class DeviceInfo {
        public final String dfid;
        public final String mid;
        public final String guid;

        public DeviceInfo(String dfid, String mid, String guid) {
            this.dfid = dfid;
            this.mid = mid;
            this.guid = guid;
        }

        public boolean isValid() {
            return dfid != null && !dfid.isEmpty() && !"-".equals(dfid);
        }
    }

    /**
     * 注册设备，获取 dfid
     * 参照 KuGou server/module/register_dev.js 实现
     *
     * @param token  登录 token (可为空)
     * @param userid 用户 ID (可为空)
     * @param mid    已有 mid (可为空，用于计算)
     * @param guid   设备 GUID (可为空，会生成)
     */
    public static DeviceInfo registerDevice(String token, String userid, String mid, String guid) throws Exception {
        // 生成或使用已有的标识
        String devGuid = (guid != null && !guid.isEmpty()) ? guid : generateGuid();
        String devMid = (mid != null && !mid.isEmpty()) ? mid : calculateMid(devGuid);

        // 构建设备信息 JSON
        Map<String, Object> deviceInfo = buildDeviceInfo(devGuid, devMid);

        // AES 加密设备信息（作为 POST body 发送）
        AesEncryptResult aesResult = playlistAesEncrypt(GSON.toJson(deviceInfo));

        // RSA 加密 AES 密钥（空字符串视为未登录，用 0 占位确保 JSON 有效）
        String p = rsaEncrypt2("{\"aes\":\"" + aesResult.key + "\",\"uid\":" + parseUid(userid) +
                ",\"token\":\"" + (token != null && !token.isEmpty() ? token : "") + "\"}");

        // 构建 URL query 参数 (part, platid, p  + 默认安卓参数 + 签名)
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("part", 1);
        queryParams.put("platid", 1);
        queryParams.put("p", p);
        queryParams.put("appid", KuGouSignature.APPID);
        queryParams.put("clientver", KuGouSignature.CLIENTVER);
        queryParams.put("clienttime", System.currentTimeMillis() / 1000);
        queryParams.put("dfid", "-");
        queryParams.put("mid", devMid);
        queryParams.put("uuid", "-");
        // 签名：注意 JS 中 signatureAndroidParams(params, data) 的 data 是 POST body
        queryParams.put("signature", KuGouSignature.signatureAndroidParams(queryParams, aesResult.str));

        // 设置请求头（与 JS 的 createRequest 一致）
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
        headers.put("dfid", "-");
        headers.put("mid", devMid);
        headers.put("clienttime", String.valueOf(queryParams.get("clienttime")));
        headers.put("kg-rc", "1");
        headers.put("kg-thash", "5d816a0");
        headers.put("kg-rec", "1");
        headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

        // POST: query params 在 URL，body 是原始 AES 密文，响应为二进制
        HttpUtils.BinaryHttpResponse response = HttpUtils.postRawBinary(REGISTER_URL, headers, queryParams, aesResult.str);

        if (!response.isOk()) {
            throw new RuntimeException("Device register HTTP error: " + response.statusCode);
        }

        // 解密响应：二进制 → base64 字符串 → AES 解密
        String responseBase64 = Base64.getEncoder().encodeToString(response.body);
        String decryptedJson = playlistAesDecrypt(responseBase64, aesResult.key);

        KuGouLogger.info("[NetMusicKuGou] Device register response: {}", decryptedJson);

        JsonObject body = GSON.fromJson(decryptedJson, JsonObject.class);
        int status = body.has("status") ? body.get("status").getAsInt() : 0;

        if (status == 1 && body.has("data")) {
            JsonElement dataElement = body.get("data");
            String dfid = null;

            // ⚠️ 酷狗服务端可能返回 data 为 Object 或 Array，需兼容处理
            if (dataElement.isJsonObject()) {
                JsonObject data = dataElement.getAsJsonObject();
                dfid = data.has("dfid") ? data.get("dfid").getAsString() : null;
            } else if (dataElement.isJsonArray()) {
                // 某些情况下 data 返回为数组，取第一个元素
                JsonArray dataArray = dataElement.getAsJsonArray();
                if (!dataArray.isEmpty() && dataArray.get(0).isJsonObject()) {
                    JsonObject firstItem = dataArray.get(0).getAsJsonObject();
                    dfid = firstItem.has("dfid") ? firstItem.get("dfid").getAsString() : null;
                }
            }

            KuGouLogger.info("[NetMusicKuGou] Device register parsed: status={}, dfid={}", status, dfid);

            if (dfid != null && !dfid.isEmpty()) {
                KuGouConfig.dfid = dfid;
                KuGouConfig.mid = devMid;
                KuGouConfig.guid = devGuid;
                return new DeviceInfo(dfid, devMid, devGuid);
            }
        }

        throw new RuntimeException("Device register unexpected response. status=" + status + ", decrypted: " + decryptedJson);
    }

    private static String parseUid(String userid) {
        if (userid == null || userid.isEmpty()) return "0";
        try {
            Long.parseLong(userid); // 验证是数字
            return userid;
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    /**
     * 生成设备 GUID (UUID v4 格式)
     */
    public static String generateGuid() {
        return CryptoUtils.generateGuid();
    }

    /**
     * 计算 MID (基于 GUID 的 MD5 → BigInteger)
     */
    public static String calculateMid(String guid) {
        String md5 = CryptoUtils.md5(guid);
        java.math.BigInteger result = java.math.BigInteger.ZERO;
        java.math.BigInteger base = java.math.BigInteger.valueOf(16);
        int len = md5.length();
        for (int i = 0; i < len; i++) {
            int digit = Character.digit(md5.charAt(i), 16);
            java.math.BigInteger digitVal = java.math.BigInteger.valueOf(digit);
            java.math.BigInteger power = base.pow(len - 1 - i);
            result = result.add(digitVal.multiply(power));
        }
        return result.toString();
    }

    /**
     * 构建设备信息
     */
    private static Map<String, Object> buildDeviceInfo(String guid, String mid) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("availableRamSize", 4983533568L);
        info.put("availableRomSize", 48114719L);
        info.put("availableSDSize", 48114717L);
        info.put("basebandVer", "");
        info.put("batteryLevel", 100);
        info.put("batteryStatus", 3);
        info.put("brand", "Redmi");
        info.put("buildSerial", "unknown");
        info.put("device", "marble");
        info.put("imei", guid);
        info.put("imsi", "");
        info.put("manufacturer", "Xiaomi");
        info.put("uuid", guid);
        info.put("accelerometer", false);
        info.put("accelerometerValue", "");
        info.put("gravity", false);
        info.put("gravityValue", "");
        info.put("gyroscope", false);
        info.put("gyroscopeValue", "");
        info.put("light", false);
        info.put("lightValue", "");
        info.put("magnetic", false);
        info.put("magneticValue", "");
        info.put("orientation", false);
        info.put("orientationValue", "");
        info.put("pressure", false);
        info.put("pressureValue", "");
        info.put("step_counter", false);
        info.put("step_counterValue", "");
        info.put("temperature", false);
        info.put("temperatureValue", "");
        return info;
    }

    /**
     * playlist AES 加密（自动生成 6 位随机 key）
     * 参照 JS: playlistAesEncrypt(data)
     */
    static AesEncryptResult playlistAesEncrypt(String data) throws Exception {
        String rawKey = CryptoUtils.randomString(6).toLowerCase();
        return playlistAesEncrypt(data, rawKey);
    }

    /**
     * playlist AES 加密
     * key = md5(rawKey).substring(0, 16), iv = md5(rawKey).substring(16, 32)
     */
    static AesEncryptResult playlistAesEncrypt(String data, String rawKey) throws Exception {
        String md5Hex = CryptoUtils.md5(rawKey);
        String keyStr = md5Hex.substring(0, 16);
        String ivStr = md5Hex.substring(16);

        SecretKeySpec keySpec = new SecretKeySpec(keyStr.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ivStr.getBytes(StandardCharsets.UTF_8));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

        return new AesEncryptResult(rawKey, Base64.getEncoder().encodeToString(encrypted));
    }

    /**
     * playlist AES 解密
     */
    static String playlistAesDecrypt(String base64Data, String rawKey) throws Exception {
        String md5Hex = CryptoUtils.md5(rawKey);
        String keyStr = md5Hex.substring(0, 16);
        String ivStr = md5Hex.substring(16);

        SecretKeySpec keySpec = new SecretKeySpec(keyStr.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ivStr.getBytes(StandardCharsets.UTF_8));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(base64Data));

        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * RSA PKCS1 v1.5 加密 (对应 JS 的 rsaEncrypt2)
     */
    public static String rsaEncrypt2(String data) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(RSA_PUBLIC_KEY);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pubKey = kf.generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder();
        for (byte b : encrypted) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /**
     * RSA 无填充加密 (对应 JS 的 cryptoRSAEncrypt)
     * 数据会被零填充到 key 长度，然后取 raw encrypt 结果的十六进制大写
     */
    public static String cryptoRsaEncrypt(String data) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(RSA_PUBLIC_KEY);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pubKey = kf.generatePublic(spec);

        java.security.interfaces.RSAPublicKey rsaKey = (java.security.interfaces.RSAPublicKey) pubKey;
        int keyLength = rsaKey.getModulus().bitLength() / 8;

        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[keyLength];
        System.arraycopy(dataBytes, 0, padded, 0, Math.min(dataBytes.length, keyLength));

        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        byte[] encrypted = cipher.doFinal(padded);

        StringBuilder hex = new StringBuilder();
        for (byte b : encrypted) hex.append(String.format("%02x", b));
        return hex.toString().toUpperCase();
    }

    /**
     * AES 加密（用于登录） = md5(key).substring(0,32) as key, last 16 as IV
     */
    public static AesEncryptResult aesEncrypt(String data, String rawKey) throws Exception {
        String md5Hex = CryptoUtils.md5(rawKey);
        String keyStr = md5Hex; // 32 hex chars → 32 bytes UTF-8 → AES-256
        String ivStr = md5Hex.substring(md5Hex.length() - 16);

        SecretKeySpec keySpec = new SecretKeySpec(keyStr.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ivStr.getBytes(StandardCharsets.UTF_8));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder();
        for (byte b : encrypted) hex.append(String.format("%02x", b));
        return new AesEncryptResult(rawKey, hex.toString());
    }

    /**
     * AES 解密（用于登录）
     */
    public static String aesDecrypt(String hexData, String rawKey) throws Exception {
        String md5Hex = CryptoUtils.md5(rawKey);
        String keyStr = md5Hex;
        String ivStr = md5Hex.substring(md5Hex.length() - 16);

        SecretKeySpec keySpec = new SecretKeySpec(keyStr.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ivStr.getBytes(StandardCharsets.UTF_8));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        byte[] hexBytes = new byte[hexData.length() / 2];
        for (int i = 0; i < hexBytes.length; i++) {
            hexBytes[i] = (byte) Integer.parseInt(hexData.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] decrypted = cipher.doFinal(hexBytes);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    public static class AesEncryptResult {
        public final String key;
        public final String str;

        public AesEncryptResult(String key, String str) {
            this.key = key;
            this.str = str;
        }
    }
}
