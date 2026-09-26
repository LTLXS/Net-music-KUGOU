package com.github.tartaricacid.netmusic.kugou.api;

import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.util.HttpUtils;
import com.github.tartaricacid.netmusic.kugou.util.KuGouSignature;
import com.github.tartaricacid.netmusic.kugou.util.CryptoUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 酷狗登录 API
 支持密码登录、手机验证码登录、二维码登录
*/
public final class KuGouLoginApi {

    private static final Gson GSON = new Gson();
    private static final String PWD_LOGIN_URL = "https://gateway.kugou.com/v9/login_by_pwd";
    // 账密登录多端点回退
    private static final String[] PWD_LOGIN_URLS = {
            "https://gateway.kugou.com/v9/login_by_pwd",
            "https://login.user.kugou.com/v9/login_by_pwd"
    };
    private static final String CELLPHONE_LOGIN_URL = "https://loginserviceretry.kugou.com/v7/login_by_verifycode";
    // 手机登录多端点回退（gateway 需配合 x-router 头）
    private static final String[] CELLPHONE_LOGIN_URLS = {
            "https://loginserviceretry.kugou.com/v7/login_by_verifycode",
            "https://gateway.kugou.com/v7/login_by_verifycode",
            "http://loginserviceretry.kugou.com/v7/login_by_verifycode"
    };
    private static final String SMS_SEND_URL = "http://login.user.kugou.com/v7/send_mobile_code";
    // 发送验证码多端点回退（gateway 需配合 x-router 头；该域名 https 直连证书 SAN 不匹配，故用 http）
    private static final String[] SMS_SEND_URLS = {
            "http://login.user.kugou.com/v7/send_mobile_code",
            "https://gateway.kugou.com/v7/send_mobile_code"
    };
    private static final String QR_KEY_URL = "https://login-user.kugou.com/v2/qrcode";
    private static final String QR_CHECK_URL = "https://login-user.kugou.com/v2/get_userinfo_qrcode";

    // T1, T2, T3 常量
    private static final String T1 = "562a6f12a6e803453647d16a08f5f0c2ff7eee692cba2ab74cc4c8ab47fc467561a7c6b586ce7dc46a63613b246737c03a1dc8f8d162d8ce1d2c71893d19f1d4b797685a4c6d3d81341cbde65e488c4829a9b4d42ef2df470eb102979fa5adcdd9b4eecfea8b909ff7599abeb49867640f10c3c70fc444effca9d15db44a9a6c907731e2bb0f22cd9b3536380169995693e5f0e2424e3378097d3813186e3fe96bbe7023808a0981b4e2b6135a76faac";
    private static final String T2 = "31c4daf4cf480169ccea1cb7d4a209295865a9d2b788510301694db229b87807469ea0d41b4d4b9173c2151da7294aeebfc9738df154bbdf11a4e117bb5dff6a3af8ce5ce333e681c1f29a44038f27567d58992eb81283e080778ac77db1400fdf49b7cf7e26be2e5af4da7830cc3be4";
    private static final String T3 = "MCwwLDAsMCwwLDAsMCwwLDA=";

    // lite（概念版）登录 t1/t2 的固定 AES key/iv
    private static final String LITE_T1_KEY = "5e4ef500e9597fe004bd09a46d8add98";
    private static final String LITE_T1_IV = "04bd09a46d8add98";
    private static final String LITE_T2_KEY = "fd14b35e3f81af3817a20ae7adae7020";
    private static final String LITE_T2_IV = "17a20ae7adae7020";


    private KuGouLoginApi() {}

    /**
 账号密码登录
 body 为 JSON（含 t1/t2/t3 与 dev），
 默认参数放 URL query，signature = signatureAndroidParams(queryParams, bodyJson)。
 探针验证：表单编码（且缺 dev、签名不带 body）会被服务端拒绝为 20010；
 忠实复刻格式能真正走到账号校验（返回 20028「请验证」等业务码）。
*/
    public static CompletableFuture<LoginResult> loginByPassword(String username, String password) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 确保设备已注册
                Boolean ready = KuGouApiClient.ensureDeviceRegistered().get();
                if (!ready) {
                    return new LoginResult(false, "Device registration failed");
                }

                HttpUtils.HttpResponse response = null;
                String randomKey = null;
                Exception lastError = null;
                for (String endpoint : PWD_LOGIN_URLS) {
                    for (int attempt = 0; attempt < 2 && response == null; attempt++) {
                        try {
                            PwdLoginPayload payload = buildPwdLoginPayload(username, password);
                            randomKey = payload.randomKey;
                            Map<String, String> headers = new LinkedHashMap<>();
                            headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
                            headers.put("x-router", "login.user.kugou.com");
                            headers.put("Content-Type", "application/json; charset=UTF-8");
                            headers.put("dfid", payload.dfid);
                            headers.put("mid", payload.mid);
                            headers.put("clienttime", String.valueOf(payload.clienttime));
                            headers.put("kg-rc", "1");
                            headers.put("kg-thash", "5d816a0");
                            headers.put("kg-rec", "1");
                            headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
                            response = HttpUtils.postRaw(endpoint, headers, payload.params, payload.bodyJson);
                        } catch (Exception e) {
                            lastError = e;
                        }
                    }
                    if (response != null) {
                        break;
                    }
                }

                if (response == null) {
                    return new LoginResult(false, "Login error: "
                            + (lastError != null ? lastError.getMessage() : "all endpoints failed"));
                }
                if (!response.isOk()) {
                    return new LoginResult(false, "Login HTTP error: " + response.statusCode);
                }

                return parseLoginResponse(response.body, randomKey);
            } catch (Exception e) {
                return new LoginResult(false, "Login error: " + e.getMessage());
            }
        });
    }

    /** 账密登录请求体与签名参数的一次性构造结果 */
    private static final class PwdLoginPayload {
        final String bodyJson;
        final Map<String, Object> params;
        final String randomKey;
        final String dfid;
        final String mid;
        final long clienttime;

        PwdLoginPayload(String bodyJson, Map<String, Object> params, String randomKey,
                        String dfid, String mid, long clienttime) {
            this.bodyJson = bodyJson;
            this.params = params;
            this.randomKey = randomKey;
            this.dfid = dfid;
            this.mid = mid;
            this.clienttime = clienttime;
        }
    }

    private static PwdLoginPayload buildPwdLoginPayload(String username, String password) throws Exception {
        long clientTimeMs = System.currentTimeMillis();
        String randomKey = CryptoUtils.randomString(16).toLowerCase();
        String dfid = KuGouConfig.dfid != null && !KuGouConfig.dfid.isEmpty() ? KuGouConfig.dfid : "-";
        String mid = KuGouConfig.mid != null && !KuGouConfig.mid.isEmpty() ? KuGouConfig.mid : "-";
        String guid = KuGouConfig.guid != null && !KuGouConfig.guid.isEmpty() ? KuGouConfig.guid : "-";
        String dev = CryptoUtils.md5(guid).substring(0, 10).toUpperCase();

        String encryptData = GSON.toJson(new PwdEncrypt(password, clientTimeMs));
        KuGouDeviceRegister.AesEncryptResult aesResult =
                KuGouDeviceRegister.aesEncrypt(encryptData, randomKey);
        String pk = KuGouDeviceRegister.cryptoRsaEncrypt(
                GSON.toJson(new PkEncrypt(clientTimeMs, randomKey)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plat", 1);
        body.put("support_multi", 1);
        body.put("clienttime_ms", clientTimeMs);
        body.put("t1", T1);
        body.put("t2", T2);
        body.put("t3", T3);
        body.put("dev", dev);
        body.put("username", username);
        body.put("params", aesResult.str);
        body.put("pk", pk);
        String bodyJson = GSON.toJson(body);

        // 签名参数 = 默认参数（不含 signature）
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dfid", dfid);
        params.put("mid", mid);
        params.put("uuid", "-");
        params.put("appid", KuGouSignature.APPID);
        params.put("clientver", KuGouSignature.CLIENTVER);
        params.put("clienttime", clientTimeMs / 1000);
        params.put("signature", KuGouSignature.signatureAndroidParams(params, bodyJson));

        return new PwdLoginPayload(bodyJson, params, randomKey, dfid, mid, clientTimeMs / 1000);
    }


    /**
 发送手机验证码
 body = {businessid:5, mobile, plat:3}（JSON），
 默认参数（dfid/mid/uuid/appid/clientver/clienttime）+ 业务参数放 URL query，
 signature = signatureAndroidParams(params, JSON.stringify(body))。
 注意：该接口用表单编码会返回 20006，必须 query + JSON body。
*/
    public static CompletableFuture<LoginResult> sendSmsCode(String mobile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Boolean ready = KuGouApiClient.ensureDeviceRegistered().get();
                if (!ready) {
                    return new LoginResult(false, "Device registration failed");
                }

                long clienttime = System.currentTimeMillis() / 1000;
                String dfid = KuGouConfig.dfid != null ? KuGouConfig.dfid : "-";
                String mid = KuGouConfig.mid != null ? KuGouConfig.mid : "-";

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("businessid", 5);
                body.put("mobile", mobile);
                body.put("plat", 3);
                String bodyJson = GSON.toJson(body);

                // 签名参数 = 默认参数 + 业务参数（不含 signature 本身）
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("dfid", dfid);
                params.put("mid", mid);
                params.put("uuid", "-");
                params.put("appid", KuGouSignature.APPID);
                params.put("clientver", KuGouSignature.CLIENTVER);
                params.put("clienttime", clienttime);
                params.putAll(body);
                params.put("signature", KuGouSignature.signatureAndroidParams(params, bodyJson));

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("Content-Type", "application/json; charset=UTF-8");
                headers.put("dfid", dfid);
                headers.put("mid", mid);
                headers.put("clienttime", String.valueOf(clienttime));

                HttpUtils.HttpResponse response = null;
                Exception lastError = null;
                for (String endpoint : SMS_SEND_URLS) {
                    for (int attempt = 0; attempt < 2 && response == null; attempt++) {
                        try {
                            Map<String, String> h = new LinkedHashMap<>(headers);
                            if (endpoint.contains("gateway.kugou.com")) {
                                h.put("x-router", "login.user.kugou.com");
                            }
                            response = HttpUtils.postRaw(endpoint, h, params, bodyJson);
                        } catch (Exception e) {
                            lastError = e;
                        }
                    }
                    if (response != null) {
                        break;
                    }
                }

                if (response == null) {
                    return new LoginResult(false, "SMS error: "
                            + (lastError != null ? lastError.getMessage() : "all endpoints failed"));
                }
                if (!response.isOk()) {
                    return new LoginResult(false, "SMS HTTP error: " + response.statusCode);
                }

                JsonObject root = GSON.fromJson(response.body, JsonObject.class);
                int status = root.has("status") ? root.get("status").getAsInt() : -1;
                if (status == 1) {
                    return new LoginResult(true, Component.translatable("netmusic_kugou.login.sms_sent").getString());
                }
                String errMsg;
                if (root.has("error_code")) {
                    String errCode = root.get("error_code").getAsString();
                    switch (errCode) {
                        case "20006": errMsg = Component.translatable("netmusic_kugou.login.err_send_rejected").getString(); break;
                        case "20010": errMsg = Component.translatable("netmusic_kugou.login.err_phone_format").getString(); break;
                        case "20011": errMsg = Component.translatable("netmusic_kugou.login.err_phone_unregistered").getString(); break;
                        case "20020": errMsg = Component.translatable("netmusic_kugou.login.err_send_freq").getString(); break;
                        default: errMsg = Component.translatable("netmusic_kugou.login.err_send_failed", errCode).getString();
                    }
                } else if (root.has("error_msg")) {
                    errMsg = root.get("error_msg").getAsString();
                } else if (root.has("msg")) {
                    errMsg = root.get("msg").getAsString();
                } else {
                    String bodyPreview = response.body.length() > 150
                            ? response.body.substring(0, 150) + "..." : response.body;
                    errMsg = Component.translatable("netmusic_kugou.login.err_send_status", status, bodyPreview).getString();
                }
                return new LoginResult(false, errMsg);
            } catch (Exception e) {
                return new LoginResult(false, "SMS error: " + e.getMessage());
            }
        });
    }


    /**
 手机验证码登录
 t1/t2 用固定密钥动态加密（含时间戳/设备信息），不带 t3，
 body 追加 dfid/dev/gitversion；默认参数放 URL query，body 为 JSON，android 签名。
 探针验证：标准版格式（t1=0/t2=0/t3+表单）会被服务端拒绝为 20010，
 lite 格式能走到验证码比对（假验证码返回 20020「验证码错误」）。
*/
    public static CompletableFuture<LoginResult> loginByPhone(String mobile, String code) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Boolean ready = KuGouApiClient.ensureDeviceRegistered().get();
                if (!ready) {
                    return new LoginResult(false, "Device registration failed");
                }

                // loginserviceretry.kugou.com 会解析到多个 IP，个别 IP 不通时会出现连接超时；
                // gateway 网关（x-router 路由）与 http 直连都能打到同一服务，依次回退重试。
                HttpUtils.HttpResponse response = null;
                String randomKey = null;
                Exception lastError = null;
                for (String endpoint : CELLPHONE_LOGIN_URLS) {
                    for (int attempt = 0; attempt < 2 && response == null; attempt++) {
                        try {
                            PhoneLoginPayload payload = buildPhoneLoginPayload(mobile, code);
                            randomKey = payload.randomKey;
                            Map<String, String> headers = new LinkedHashMap<>();
                            headers.put("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
                            headers.put("support-calm", "1");
                            headers.put("Content-Type", "application/json; charset=UTF-8");
                            headers.put("dfid", payload.dfid);
                            headers.put("mid", payload.mid);
                            headers.put("clienttime", String.valueOf(payload.clienttime));
                            if (endpoint.contains("gateway.kugou.com")) {
                                headers.put("x-router", "loginserviceretry.kugou.com");
                            }
                            response = HttpUtils.postRaw(endpoint, headers, payload.params, payload.bodyJson);
                        } catch (Exception e) {
                            lastError = e;
                        }
                    }
                    if (response != null) {
                        break;
                    }
                }

                if (response == null) {
                    return new LoginResult(false, "Phone login error: "
                            + (lastError != null ? lastError.getMessage() : "all endpoints failed"));
                }
                if (!response.isOk()) {
                    return new LoginResult(false, "Login HTTP error: " + response.statusCode);
                }

                return parseLoginResponse(response.body, randomKey);
            } catch (Exception e) {
                return new LoginResult(false, "Phone login error: " + e.getMessage());
            }
        });
    }

    /** 手机登录请求体与签名参数的一次性构造结果 */
    private static final class PhoneLoginPayload {
        final String bodyJson;
        final Map<String, Object> params;
        final String randomKey;
        final String dfid;
        final String mid;
        final long clienttime;

        PhoneLoginPayload(String bodyJson, Map<String, Object> params, String randomKey,
                          String dfid, String mid, long clienttime) {
            this.bodyJson = bodyJson;
            this.params = params;
            this.randomKey = randomKey;
            this.dfid = dfid;
            this.mid = mid;
            this.clienttime = clienttime;
        }
    }

    private static PhoneLoginPayload buildPhoneLoginPayload(String mobile, String code) throws Exception {
        long dateTime = System.currentTimeMillis();
        String randomKey = CryptoUtils.randomString(16).toLowerCase();
        String dfid = KuGouConfig.dfid != null && !KuGouConfig.dfid.isEmpty() ? KuGouConfig.dfid : "-";
        String mid = KuGouConfig.mid != null && !KuGouConfig.mid.isEmpty() ? KuGouConfig.mid : "-";
        String guid = KuGouConfig.guid != null && !KuGouConfig.guid.isEmpty() ? KuGouConfig.guid : "-";
        // 稳定的 dev 标识（由 guid 派生）
        String dev = CryptoUtils.md5(guid).substring(0, 10).toUpperCase();

        // AES 加密手机号和验证码
        String encryptData = GSON.toJson(new PhoneEncrypt(mobile, code));
        KuGouDeviceRegister.AesEncryptResult aesResult =
                KuGouDeviceRegister.aesEncrypt(encryptData, randomKey);

        String maskedMobile = mobile.substring(0, 2) + "*****" + mobile.substring(mobile.length() - 1);

        String pk = KuGouDeviceRegister.cryptoRsaEncrypt(GSON.toJson(new PkEncrypt(dateTime, randomKey)));

        // lite 版 t1/t2：固定 key/iv 的 AES-CBC
        String t1 = KuGouDeviceRegister.aesEncryptWithKey("|" + dateTime, LITE_T1_KEY, LITE_T1_IV);
        String t2 = KuGouDeviceRegister.aesEncryptWithKey(
                guid + "|0f607264fc6318a92b9e13c65db7cd3c|" + "02:00:00:00:00:00" + "|" + dev + "|" + dateTime,
                LITE_T2_KEY, LITE_T2_IV);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plat", 1);
        body.put("support_multi", 1);
        body.put("t1", t1);
        body.put("t2", t2);
        body.put("clienttime_ms", dateTime);
        body.put("mobile", maskedMobile);
        body.put("key", KuGouSignature.signParamsKey(dateTime, KuGouSignature.APPID, KuGouSignature.CLIENTVER));
        body.put("pk", pk);
        body.put("params", aesResult.str);
        body.put("dfid", dfid);
        body.put("dev", dev);
        body.put("gitversion", "5f0b7c4");
        String bodyJson = GSON.toJson(body);

        // 签名参数 = 默认参数（不含 signature）
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dfid", dfid);
        params.put("mid", mid);
        params.put("uuid", "-");
        params.put("appid", KuGouSignature.APPID);
        params.put("clientver", KuGouSignature.CLIENTVER);
        params.put("clienttime", dateTime / 1000);
        params.put("signature", KuGouSignature.signatureAndroidParams(params, bodyJson));

        return new PhoneLoginPayload(bodyJson, params, randomKey, dfid, mid, dateTime / 1000);
    }

    private static final int QR_SRCAPPID = 2919;
    private static final String QR_CODE_URL_PREFIX = "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=" + KuGouSignature.APPID + "&";

    public static CompletableFuture<QrKeyResult> fetchQrKey() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("appid", KuGouSignature.APPID);
                params.put("type", 1);
                params.put("plat", 4);
                params.put("qrcode_txt", QR_CODE_URL_PREFIX);
                params.put("srcappid", QR_SRCAPPID);
                params.put("dfid", KuGouConfig.dfid != null ? KuGouConfig.dfid : "-");
                params.put("mid", KuGouConfig.mid != null ? KuGouConfig.mid : "-");
                params.put("uuid", "-");
                params.put("clientver", KuGouSignature.CLIENTVER);
                params.put("clienttime", System.currentTimeMillis() / 1000);
                params.put("signature", KuGouSignature.signatureWebParams(params));

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11440-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("dfid", KuGouConfig.dfid != null ? KuGouConfig.dfid : "-");
                headers.put("mid", KuGouConfig.mid != null ? KuGouConfig.mid : "-");

                HttpUtils.HttpResponse response = HttpUtils.get(QR_KEY_URL, headers, params);

                if (!response.isOk()) {
                    return new QrKeyResult(null, "QR key HTTP error: " + response.statusCode);
                }

                JsonObject root = GSON.fromJson(response.body, JsonObject.class);
                int status = root.has("status") ? root.get("status").getAsInt() : -1;

                if (status == 1 && root.has("data")) {
                    JsonObject data = root.getAsJsonObject("data");
                    String qrcode = data.has("qrcode") ? data.get("qrcode").getAsString()
                            : (data.has("key") ? data.get("key").getAsString() : null);
                    String qrUrl = QR_CODE_URL_PREFIX + "qrcode=" + qrcode;
                    return new QrKeyResult(qrcode, qrUrl);
                }

                String msg = root.has("error_msg") ? root.get("error_msg").getAsString()
                        : ("获取二维码失败(status=" + status + ")");
                return new QrKeyResult(null, msg);
            } catch (Exception e) {
                return new QrKeyResult(null, "QR key error: " + e.getMessage());
            }
        });
    }

    public static CompletableFuture<QrCheckResult> checkQrCode(String qrKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("plat", 4);
                params.put("appid", KuGouSignature.APPID);
                params.put("srcappid", QR_SRCAPPID);
                params.put("qrcode", qrKey);

                params.put("dfid", KuGouConfig.dfid != null ? KuGouConfig.dfid : "-");
                params.put("mid", KuGouConfig.mid != null ? KuGouConfig.mid : "-");
                params.put("uuid", "-");
                params.put("clientver", KuGouSignature.CLIENTVER);
                params.put("clienttime", System.currentTimeMillis() / 1000);
                params.put("signature", KuGouSignature.signatureWebParams(params));

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11440-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("dfid", KuGouConfig.dfid != null ? KuGouConfig.dfid : "-");
                headers.put("mid", KuGouConfig.mid != null ? KuGouConfig.mid : "-");
                headers.put("clienttime", String.valueOf(params.get("clienttime")));

                HttpUtils.HttpResponse response = HttpUtils.get(QR_CHECK_URL, headers, params);

                if (!response.isOk()) {
                    return new QrCheckResult(-1, "Network error: " + response.statusCode);
                }

                JsonObject root = GSON.fromJson(response.body, JsonObject.class);
                int status = root.has("status") ? root.get("status").getAsInt() : -1;

                if (status != 1 || !root.has("data")) {
                    String err = root.has("error_msg") ? root.get("error_msg").getAsString()
                            : ("API error, status=" + status + ", body=" + response.body.substring(0, Math.min(200, response.body.length())));
                    return new QrCheckResult(-1, err);
                }

                JsonObject data = root.getAsJsonObject("data");
                int qrStatus = data.has("status") ? data.get("status").getAsInt() : -1;

                if (qrStatus == 4) {
                    String token = data.has("token") ? data.get("token").getAsString() : "";
                    String userId = data.has("userid") ? data.get("userid").getAsString() : "";
                    saveLoginState(token, userId, data);
                    return new QrCheckResult(4, "Login success", token, userId);
                }

                return new QrCheckResult(qrStatus, "");
            } catch (Exception e) {
                return new QrCheckResult(-2, "Error: " + e.getMessage());
            }
        });
    }


    private static String jsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return "";
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static LoginResult parseLoginResponse(String jsonStr, String key) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            int status = root.has("status") ? root.get("status").getAsInt() : -1;

            if (status != 1) {
                // 酷狗返回的错误字段是 error_code / error_msg（带下划线）
                String errCode = jsonString(root, "error_code");
                if (errCode.isEmpty()) errCode = jsonString(root, "err_code");
                String msg;
                switch (errCode) {
                    case "20010": msg = Component.translatable("netmusic_kugou.login.err_bad_cred").getString(); break;
                    case "20011": msg = Component.translatable("netmusic_kugou.login.err_no_account").getString(); break;
                    case "20012": msg = Component.translatable("netmusic_kugou.login.err_pwd_freq").getString(); break;
                    case "20013": msg = Component.translatable("netmusic_kugou.login.err_frozen").getString(); break;
                    case "20020": {
                        // 探针实测：验证码登录时 20020 的 data 是「验证码错误」
                        String data = jsonString(root, "data");
                        msg = (!data.isEmpty() && !"null".equals(data)) ? data
                                : Component.translatable("netmusic_kugou.login.err_code_wrong").getString();
                        break;
                    }
                    case "20028": {
                        // 探针实测：账密登录被风控要求二次验证时返回 20028（data 只是「请验证」，没有可跳转的验证链接）。
                        // 滑块/设备验证无法在游戏内完成，必须给出可操作的引导：改用短信验证码或扫码登录。
                        msg = Component.translatable("netmusic_kugou.login.err_need_verify").getString();
                        break;
                    }
                    default:
                        String em = jsonString(root, "error_msg");
                        if (em.isEmpty()) em = jsonString(root, "msg");
                        msg = !em.isEmpty() ? em
                                : (!errCode.isEmpty() ? Component.translatable("netmusic_kugou.login.err_code", errCode).getString()
                                : "Unknown error");
                }
                return new LoginResult(false, msg);
            }

            JsonObject data = root.getAsJsonObject("data");
            if (data == null) {
                return new LoginResult(false, "No data in response");
            }

            if (data.has("secu_params")) {
                try {
                    String secuParams = data.get("secu_params").getAsString();
                    String decrypted = KuGouDeviceRegister.aesDecrypt(secuParams, key);
                    JsonObject tokenData = GSON.fromJson(decrypted, JsonObject.class);

                    String token = tokenData.has("token") ? tokenData.get("token").getAsString() : "";
                    String userId = null;
                    if (tokenData.has("userid")) {
                        userId = tokenData.get("userid").getAsString();
                    } else if (data.has("userid")) {
                        userId = String.valueOf(data.get("userid").getAsLong());
                    }

                    saveLoginState(token, userId, data);
                    return new LoginResult(true, "Login success", token, userId);
                } catch (Exception e) {
                    return new LoginResult(false, "Decrypt secu_params failed: " + e.getMessage());
                }
            }

            if (data.has("token")) {
                String token = data.get("token").getAsString();
                String userId = data.has("userid") ? String.valueOf(data.get("userid").getAsLong()) : "";
                saveLoginState(token, userId, data);
                return new LoginResult(true, "Login success", token, userId);
            }

            return new LoginResult(false, "No token in response");
        } catch (JsonSyntaxException e) {
            return new LoginResult(false, "Parse response failed: " + e.getMessage());
        }
    }

    /** 登录成功后写入登录态；第三方（QQ / 微信）登录复用同一套落库逻辑 */
    public static void saveLoginState(String token, String userId, JsonObject data) {
        KuGouConfig.token = token;
        KuGouConfig.userid = userId;
        KuGouConfig.addCookie("token", token);
        KuGouConfig.addCookie("userid", userId != null ? userId : "0");

        if (data != null) {
            if (data.has("vip_type")) {
                String vipType = String.valueOf(data.get("vip_type").getAsLong());
                KuGouConfig.addCookie("vip_type", vipType);
            }
            if (data.has("vip_token")) {
                String vipToken = data.get("vip_token").getAsString();
                KuGouConfig.addCookie("vip_token", vipToken);
            }
            if (data.has("t1")) {
                String t1 = data.get("t1").getAsString();
                KuGouConfig.addCookie("t1", t1);
            }
        }
        KuGouConfig.markDirty();
    }

    public static void logout() {
        KuGouConfig.token = "";
        KuGouConfig.userid = "";
        KuGouConfig.clearCookies();
        KuGouConfig.markDirty();
    }

    public static class LoginResult {
        public final boolean success;
        public final String message;
        public final String token;
        public final String userid;

        public LoginResult(boolean success, String message) {
            this(success, message, null, null);
        }

        public LoginResult(boolean success, String message, String token, String userid) {
            this.success = success;
            this.message = message;
            this.token = token;
            this.userid = userid;
        }
    }

    public static class QrCheckResult {
        public final int status;  // 0=过期, 1=等待扫码, 2=待确认, 4=成功, -1=网络错误, -2=其他错误
        public final String message;
        public final String token;
        public final String userid;

        public QrCheckResult(int status, String message) {
            this(status, message, null, null);
        }

        public QrCheckResult(int status, String message, String token, String userid) {
            this.status = status;
            this.message = message;
            this.token = token;
            this.userid = userid;
        }
    }

    public static class QrKeyResult {
        public final String qrcode;
        public final String qrUrl;
        public final String error;

        public QrKeyResult(String qrcode, String error) {
            this.qrcode = qrcode;
            this.qrUrl = qrcode != null ? (QR_CODE_URL_PREFIX + "qrcode=" + qrcode) : null;
            this.error = error;
        }

        public boolean isSuccess() {
            return qrcode != null && !qrcode.isEmpty();
        }
    }

    private static class PwdEncrypt {
        String pwd;
        String code = "";
        long clienttime_ms;
        PwdEncrypt(String pwd, long ms) { this.pwd = pwd; this.clienttime_ms = ms; }
    }

    private static class PhoneEncrypt {
        String mobile;
        String code;
        PhoneEncrypt(String m, String c) { this.mobile = m; this.code = c; }
    }

    private static class PkEncrypt {
        long clienttime_ms;
        String key;
        PkEncrypt(long ms, String k) { this.clienttime_ms = ms; this.key = k; }
    }
}
