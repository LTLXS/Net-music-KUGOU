package com.github.tartaricacid.netmusic.kugou.api;

import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.util.HttpUtils;
import com.github.tartaricacid.netmusic.kugou.util.KuGouSignature;
import com.github.tartaricacid.netmusic.kugou.util.CryptoUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 酷狗登录 API
 * 支持密码登录、手机验证码登录、二维码登录
 * 参照 KuGou server/module/login.js 和 login_cellphone.js
 */
public final class KuGouLoginApi {

    private static final Gson GSON = new Gson();
    private static final String PWD_LOGIN_URL = "https://gateway.kugou.com/v9/login_by_pwd";
    private static final String CELLPHONE_LOGIN_URL = "https://loginserviceretry.kugou.com/v7/login_by_verifycode";
    private static final String SMS_SEND_URL = "http://login.user.kugou.com/v7/send_mobile_code";
    private static final String QR_KEY_URL = "https://login-user.kugou.com/v2/qrcode";
    private static final String QR_CHECK_URL = "https://login-user.kugou.com/v2/get_userinfo_qrcode";

    // T1, T2, T3 常量（从 KuGou 桌面应用同步）
    private static final String T1 = "562a6f12a6e803453647d16a08f5f0c2ff7eee692cba2ab74cc4c8ab47fc467561a7c6b586ce7dc46a63613b246737c03a1dc8f8d162d8ce1d2c71893d19f1d4b797685a4c6d3d81341cbde65e488c4829a9b4d42ef2df470eb102979fa5adcdd9b4eecfea8b909ff7599abeb49867640f10c3c70fc444effca9d15db44a9a6c907731e2bb0f22cd9b3536380169995693e5f0e2424e3378097d3813186e3fe96bbe7023808a0981b4e2b6135a76faac";
    private static final String T2 = "31c4daf4cf480169ccea1cb7d4a209295865a9d2b788510301694db229b87807469ea0d41b4d4b9173c2151da7294aeebfc9738df154bbdf11a4e117bb5dff6a3af8ce5ce333e681c1f29a44038f27567d58992eb81283e080778ac77db1400fdf49b7cf7e26be2e5af4da7830cc3be4";
    private static final String T3 = "MCwwLDAsMCwwLDAsMCwwLDA=";

    private KuGouLoginApi() {}

    // ==================== 密码登录 ====================

    /**
     * 密码登录
     */
    public static CompletableFuture<LoginResult> loginByPassword(String username, String password) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 确保设备已注册
                Boolean ready = KuGouApiClient.ensureDeviceRegistered().get();
                if (!ready) {
                    return new LoginResult(false, "Device registration failed");
                }

                long clientTimeMs = System.currentTimeMillis();
                String randomKey = CryptoUtils.randomString(16).toLowerCase();

                // AES 加密密码
                String encryptData = GSON.toJson(new PwdEncrypt(password, clientTimeMs));
                KuGouDeviceRegister.AesEncryptResult aesResult;
                try {
                    aesResult = KuGouDeviceRegister.aesEncrypt(encryptData, randomKey);
                } catch (Exception e) {
                    return new LoginResult(false, "AES encrypt failed: " + e.getMessage());
                }

                // RSA 加密 key
                String pkData = GSON.toJson(new PkEncrypt(clientTimeMs, randomKey));
                String pk = KuGouDeviceRegister.cryptoRsaEncrypt(pkData);

                // 构建请求参数
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("plat", 1);
                params.put("support_multi", 1);
                params.put("clienttime_ms", clientTimeMs);
                params.put("t1", T1);
                params.put("t2", T2);
                params.put("t3", T3);
                params.put("username", username);
                params.put("params", aesResult.str);
                params.put("pk", pk);

                // 添加默认参数并签名
                params.put("appid", KuGouSignature.APPID);
                params.put("clientver", KuGouSignature.CLIENTVER);
                params.put("clienttime", clientTimeMs / 1000);
                params.put("dfid", KuGouConfig.dfid);
                params.put("mid", KuGouConfig.mid);
                params.put("uuid", "-");
                params.put("signature", KuGouSignature.signatureAndroidParams(params, GSON.toJson(params)));

                // 请求头
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("x-router", "login.user.kugou.com");
                headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                headers.put("dfid", KuGouConfig.dfid);
                headers.put("mid", KuGouConfig.mid);
                headers.put("clienttime", String.valueOf(params.get("clienttime")));
                headers.put("kg-rc", "1");
                headers.put("kg-thash", "5d816a0");
                headers.put("kg-rec", "1");
                headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

                HttpUtils.HttpResponse response = HttpUtils.postForm(PWD_LOGIN_URL, headers, params);

                if (!response.isOk()) {
                    return new LoginResult(false, "Login HTTP error: " + response.statusCode);
                }

                return parseLoginResponse(response.body, randomKey);
            } catch (Exception e) {
                return new LoginResult(false, "Login error: " + e.getMessage());
            }
        });
    }

    // ==================== 手机验证码发送 ====================

    /**
     * 发送手机验证码
     * 参照 KuGou server/module/login_send_verifycode.js
     */
    public static CompletableFuture<LoginResult> sendSmsCode(String mobile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 参照 KuGou server/module/captcha_sent.js
                // POST http://login.user.kugou.com/v7/send_mobile_code
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("businessid", 5);
                params.put("mobile", mobile);
                params.put("plat", 3);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
                headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                if (KuGouConfig.mid != null) {
                    headers.put("Cookie", "mid=" + KuGouConfig.mid);
                }

                HttpUtils.HttpResponse response = HttpUtils.postForm(SMS_SEND_URL, headers, params);

                if (!response.isOk()) {
                    return new LoginResult(false, "SMS HTTP error: " + response.statusCode);
                }

                JsonObject root = GSON.fromJson(response.body, JsonObject.class);
                int status = root.has("status") ? root.get("status").getAsInt() : -1;
                if (status == 1) {
                    return new LoginResult(true, "验证码已发送");
                }
                // 友好的错误提示
                String errMsg;
                if (root.has("error_code")) {
                    String errCode = root.get("error_code").getAsString();
                    switch (errCode) {
                        case "20010": errMsg = "手机号格式错误或不存在"; break;
                        case "20011": errMsg = "该手机号未注册酷狗账号"; break;
                        case "20020": errMsg = "发送频率过高，请稍后再试"; break;
                        default: errMsg = "发送失败(错误码: " + errCode + ")";
                    }
                } else if (root.has("error_msg")) {
                    errMsg = root.get("error_msg").getAsString();
                } else if (root.has("msg")) {
                    errMsg = root.get("msg").getAsString();
                } else {
                    // 显示原始响应帮助调试
                    String bodyPreview = response.body.length() > 150
                            ? response.body.substring(0, 150) + "..." : response.body;
                    errMsg = "发送失败(status=" + status + ", 响应: " + bodyPreview + ")";
                }
                return new LoginResult(false, errMsg);
            } catch (Exception e) {
                return new LoginResult(false, "SMS error: " + e.getMessage());
            }
        });
    }

    // ==================== 手机验证码登录 ====================

    /**
     * 手机验证码登录
     */
    public static CompletableFuture<LoginResult> loginByPhone(String mobile, String code) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Boolean ready = KuGouApiClient.ensureDeviceRegistered().get();
                if (!ready) {
                    return new LoginResult(false, "Device registration failed");
                }

                long dateTime = System.currentTimeMillis();
                String randomKey = CryptoUtils.randomString(16).toLowerCase();

                // AES 加密手机号和验证码
                String encryptData = GSON.toJson(new PhoneEncrypt(mobile, code));
                KuGouDeviceRegister.AesEncryptResult aesResult;
                try {
                    aesResult = KuGouDeviceRegister.aesEncrypt(encryptData, randomKey);
                } catch (Exception e) {
                    return new LoginResult(false, "AES encrypt failed: " + e.getMessage());
                }

                // 手机号脱敏
                String maskedMobile = mobile.substring(0, 2) + "*****" + mobile.substring(mobile.length() - 1);

                // RSA 加密 key
                String pkData = GSON.toJson(new PkEncrypt(dateTime, randomKey));
                String pk = KuGouDeviceRegister.cryptoRsaEncrypt(pkData);

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("plat", 1);
                params.put("support_multi", 1);
                params.put("t1", 0);
                params.put("t2", 0);
                params.put("t3", T3);
                params.put("clienttime_ms", dateTime);
                params.put("mobile", maskedMobile);
                params.put("key", KuGouSignature.signParamsKey(dateTime, KuGouSignature.APPID, KuGouSignature.CLIENTVER));
                params.put("pk", pk);
                params.put("params", aesResult.str);

                params.put("appid", KuGouSignature.APPID);
                params.put("clientver", KuGouSignature.CLIENTVER);
                params.put("clienttime", dateTime / 1000);
                params.put("dfid", KuGouConfig.dfid);
                params.put("mid", KuGouConfig.mid);
                params.put("uuid", "-");
                params.put("signature", KuGouSignature.signatureAndroidParams(params, GSON.toJson(params)));

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi");
                headers.put("support-calm", "1");
                headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                headers.put("dfid", KuGouConfig.dfid);
                headers.put("mid", KuGouConfig.mid);
                headers.put("clienttime", String.valueOf(params.get("clienttime")));
                headers.put("kg-rc", "1");
                headers.put("kg-thash", "5d816a0");
                headers.put("kg-rec", "1");
                headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

                HttpUtils.HttpResponse response = HttpUtils.postForm(CELLPHONE_LOGIN_URL, headers, params);

                if (!response.isOk()) {
                    return new LoginResult(false, "Login HTTP error: " + response.statusCode);
                }

                return parseLoginResponse(response.body, randomKey);
            } catch (Exception e) {
                return new LoginResult(false, "Phone login error: " + e.getMessage());
            }
        });
    }

    // ==================== 二维码登录 ====================

    private static final int QR_SRCAPPID = 2919;
    private static final String QR_CODE_URL_PREFIX = "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=" + KuGouSignature.APPID + "&";

    /**
     * 从酷狗服务器获取二维码 Key
     * 参照 KuGou server/module/login_qr_key.js
     * GET https://login-user.kugou.com/v2/qrcode
     */
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

                // 酷狗返回 status=1 表示成功
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

    /**
     * 检查二维码扫码状态
     * 参照 KuGou server/module/login_qr_check.js
     * 返回: 0=过期, 1=等待扫码, 2=待确认, 4=授权成功
     */
    public static CompletableFuture<QrCheckResult> checkQrCode(String qrKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("plat", 4);
                params.put("appid", KuGouSignature.APPID);
                params.put("srcappid", QR_SRCAPPID);
                params.put("qrcode", qrKey);

                // Web 签名
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

                // 授权成功
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

    // ==================== 内部方法 ====================

    private static LoginResult parseLoginResponse(String jsonStr, String key) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            int status = root.has("status") ? root.get("status").getAsInt() : -1;

            if (status != 1) {
                // 酷狗常见错误码友好提示
                String errCode = root.has("err_code") ? root.get("err_code").getAsString() : "";
                String msg;
                switch (errCode) {
                    case "20010": msg = "账号或密码错误"; break;
                    case "20011": msg = "账号不存在"; break;
                    case "20012": msg = "密码错误次数过多，请稍后再试"; break;
                    case "20013": msg = "账号已被冻结"; break;
                    default:
                        msg = root.has("error_msg") ? root.get("error_msg").getAsString()
                                : (root.has("msg") ? root.get("msg").getAsString()
                                : (!errCode.isEmpty() ? ("错误码: " + errCode)
                                : "Unknown error"));
                }
                return new LoginResult(false, msg);
            }

            JsonObject data = root.getAsJsonObject("data");
            if (data == null) {
                return new LoginResult(false, "No data in response");
            }

            // 解密 secu_params 获取 token
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

            // 部分接口直接返回 token
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

    private static void saveLoginState(String token, String userId, JsonObject data) {
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

    // ==================== 登出 ====================

    public static void logout() {
        KuGouConfig.token = "";
        KuGouConfig.userid = "";
        KuGouConfig.clearCookies();
        KuGouConfig.markDirty();
    }

    // ==================== 数据类 ====================

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

    /**
     * 二维码 Key 获取结果
     */
    public static class QrKeyResult {
        public final String qrcode;   // 酷狗服务器返回的 QR Key
        public final String qrUrl;    // 二维码完整 URL（供用户扫描）
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

    // JSON 序列化辅助类
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
