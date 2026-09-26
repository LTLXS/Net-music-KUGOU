package com.github.tartaricacid.netmusic.kugou.api;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.config.OpenSecrets;
import com.github.tartaricacid.netmusic.kugou.util.CryptoUtils;
import com.github.tartaricacid.netmusic.kugou.util.HttpUtils;
import com.github.tartaricacid.netmusic.kugou.util.KuGouSignature;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 第三方（QQ / 微信）扫码登录。
 生成第三方二维码 → 轮询扫码状态 → 拿到 openid + access_token → 调酷狗 /v6/login_by_openplat 换酷狗登录态。
 模组使用酷狗概念版（lite）参数（appid=3116 / clientver=11440），所以这里统一走 lite 分支：
 QQ 用 qq_lite_appid、微信用 wx_lite_appid，并且 t1 / t2 需要按 lite 密钥做 AES 加密。
*/
public final class KuGouOpenLoginApi {

    private static final Gson GSON = new Gson();

    private static final String OPENPLAT_LOGIN_URL = "https://gateway.kugou.com/v6/login_by_openplat";

    // OpenSecrets.QQ_APPID / OpenSecrets.QQ_APK_SIG_MD5 / OpenSecrets.WX_APPID / OpenSecrets.WX_SECRET 等私密凭据已外部化到 OpenSecrets
    // （从环境变量或 classpath 的 secret.properties 读取，源码不再硬编码，避免提交到公开仓库泄露）。
    private static final String QQ_AUTHORIZE_URL = "https://openmobile.qq.com/oauth2.0/m_authorize";
    private static final String QQ_QR_SHOW_URL = "https://xui.ptlogin2.qq.com/ssl/ptqrshow";
    private static final String QQ_QR_LOGIN_URL = "https://xui.ptlogin2.qq.com/ssl/ptqrlogin";
    private static final String QQ_AID = "716027609";
    private static final String QQ_DAID = "381";

    private static final String WX_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String WX_TICKET_URL = "https://api.weixin.qq.com/cgi-bin/ticket/getticket";
    private static final String WX_QRCONNECT_URL = "https://open.weixin.qq.com/connect/sdk/qrconnect";
    private static final String WX_POLL_URL = "https://long.open.weixin.qq.com/connect/l/qrconnect";
    private static final String WX_ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";

    // ---- lite t1 / t2 固定密钥 ----
    private static final String LITE_T1_KEY = "5e4ef500e9597fe004bd09a46d8add98";
    private static final String LITE_T1_IV = "04bd09a46d8add98";
    private static final String LITE_T2_KEY = "fd14b35e3f81af3817a20ae7adae7020";
    private static final String LITE_T2_IV = "17a20ae7adae7020";
    private static final String T2_MID_SALT = "0f607264fc6318a92b9e13c65db7cd3c";
    private static final String T3 = "MCwwLDAsMCwwLDAsMCwwLDA=";

    private static final String UA_ANDROID = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";
    private static final String UA_BROWSER = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Pattern SRC_PATTERN = Pattern.compile("src = \"([^\"]+)\"");
    private static final Pattern XESCAPE_PATTERN = Pattern.compile("\\\\x([0-9A-Fa-f]{2})");
    private static final Pattern PTUICB_PATTERN = Pattern.compile("ptuiCB\\((.+)\\)");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("'([^']*)'");

    private KuGouOpenLoginApi() {
    }

    public enum Provider {
        QQ, WECHAT
    }

    public enum PollState {
        WAITING, SCANNED, EXPIRED, SUCCESS, ERROR
    }

    /** 二维码会话：QQ 用 qrsig 系列字段，微信只用 uuid */
    public static final class QrTicket {
        public final Provider provider;
        public final Map<String, String> cookies = new LinkedHashMap<>();
        public byte[] image;
        public String error;
        public String qrsig;
        public String ptqrtoken;
        public String ptLoginSig;
        public String ptOpenloginData;
        public String xloginUrl;
        public String uuid;
        public long createdAt;

        private QrTicket(Provider provider) {
            this.provider = provider;
        }

        public boolean isOk() {
            return image != null && image.length > 0 && error == null;
        }
    }

    public static final class PollResult {
        public final PollState state;
        public final String message;
        public final String token;
        public final String userid;

        public PollResult(PollState state, String message) {
            this(state, message, null, null);
        }

        public PollResult(PollState state, String message, String token, String userid) {
            this.state = state;
            this.message = message;
            this.token = token;
            this.userid = userid;
        }
    }


    public static CompletableFuture<QrTicket> createQqQr() {
        return CompletableFuture.supplyAsync(() -> {
            QrTicket ticket = new QrTicket(Provider.QQ);
            try {
                long time = System.currentTimeMillis() / 1000;
                String sign = CryptoUtils.md5(OpenSecrets.QQ_APK_SIG_MD5 + "_" + time);

                Map<String, Object> authParams = new LinkedHashMap<>();
                authParams.put("cancel_display", 1);
                authParams.put("sdkp", "a");
                authParams.put("display", "mobile");
                authParams.put("format", "json");
                authParams.put("sign", sign);
                authParams.put("sdkv", "3.5.11.lite");
                authParams.put("response_type", "token");
                authParams.put("status_os", "11");
                authParams.put("client_id", OpenSecrets.QQ_APPID);
                authParams.put("switch", "1");
                authParams.put("status_version", "30");
                authParams.put("show_download_ui", "true");
                authParams.put("pf", "openmobile_android");
                authParams.put("scope", "all");
                authParams.put("compat_v", "1");
                authParams.put("status_machine", "MEIZU+18+Pro");
                authParams.put("style", "qr");
                authParams.put("time", time);
                authParams.put("redirect_uri", "auth://tauth.qq.com/");

                HttpUtils.HttpResponse authorize = HttpUtils.get(
                        QQ_AUTHORIZE_URL, browserHeaders("https://xui.ptlogin2.qq.com/"), authParams);
                collectCookies(ticket.cookies, authorize.cookies);

                Matcher srcMatcher = SRC_PATTERN.matcher(authorize.body == null ? "" : authorize.body);
                if (!srcMatcher.find()) {
                    ticket.error = "m_authorize 响应异常，未找到 xlogin 地址";
                    return ticket;
                }
                String xloginUrl = unescapeHex(srcMatcher.group(1));
                ticket.xloginUrl = xloginUrl;

                HttpUtils.HttpResponse xlogin = HttpUtils.get(xloginUrl, browserHeaders("https://xui.ptlogin2.qq.com/"), null);
                collectCookies(ticket.cookies, xlogin.cookies);
                ticket.ptLoginSig = ticket.cookies.get("pt_login_sig");

                String xloginQuery = xloginUrl.contains("?") ? xloginUrl.substring(xloginUrl.indexOf('?') + 1) : "";
                ticket.ptOpenloginData = encodeUriComponent(xloginQuery);

                Map<String, Object> qrParams = new LinkedHashMap<>();
                qrParams.put("s", 8);
                qrParams.put("e", 0);
                qrParams.put("appid", QQ_AID);
                qrParams.put("type", 0);
                qrParams.put("t", String.valueOf(Math.random()));
                qrParams.put("daid", QQ_DAID);
                qrParams.put("pt_3rd_aid", OpenSecrets.QQ_APPID);

                Map<String, String> qrHeaders = browserHeaders(xloginUrl);
                qrHeaders.put("Cookie", cookieHeader(ticket.cookies));
                HttpUtils.BinaryHttpResponse qrImage = HttpUtils.getBinary(QQ_QR_SHOW_URL, qrHeaders, qrParams);
                collectCookies(ticket.cookies, qrImage.cookies);

                if (!qrImage.isOk() || qrImage.body == null || qrImage.body.length == 0) {
                    ticket.error = "获取 QQ 二维码失败 (HTTP " + qrImage.statusCode + ")";
                    return ticket;
                }
                ticket.qrsig = ticket.cookies.get("qrsig");
                if (ticket.qrsig == null || ticket.qrsig.isEmpty()) {
                    ticket.error = "未获取到 qrsig";
                    return ticket;
                }
                ticket.ptqrtoken = String.valueOf(hash33(ticket.qrsig));
                ticket.image = qrImage.body;
                ticket.createdAt = System.currentTimeMillis();
                return ticket;
            } catch (Exception e) {
                ticket.error = String.valueOf(e.getMessage());
                return ticket;
            }
        });
    }

    public static CompletableFuture<PollResult> checkQqQr(QrTicket ticket) {
        return CompletableFuture.supplyAsync(() -> {
            if (ticket == null || ticket.qrsig == null) {
                return new PollResult(PollState.ERROR, "QQ 二维码会话无效，请刷新");
            }
            try {
                String cookie = cookieHeader(ticket.cookies);
                if (ticket.ptLoginSig != null && !cookie.contains("pt_login_sig")) {
                    cookie = cookie.isEmpty() ? "pt_login_sig=" + ticket.ptLoginSig
                            : cookie + "; pt_login_sig=" + ticket.ptLoginSig;
                }

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("u1", encodeUriComponent("http://connect.qq.com"));
                params.put("from_ui", 1);
                params.put("type", 1);
                params.put("ptlang", 2052);
                params.put("ptqrtoken", ticket.ptqrtoken);
                params.put("daid", QQ_DAID);
                params.put("aid", QQ_AID);
                params.put("pt_3rd_aid", OpenSecrets.QQ_APPID);
                params.put("pt_openlogin_data", ticket.ptOpenloginData);
                params.put("device", 2);
                params.put("ptopt", 1);
                params.put("pt_uistyle", 35);
                params.put("jsver", "v1.36.0");
                params.put("login_sig", encodeUriComponent(ticket.ptLoginSig == null ? "" : ticket.ptLoginSig));
                params.put("r", String.valueOf(Math.random()));

                Map<String, String> headers = browserHeaders(ticket.xloginUrl);
                headers.put("Cookie", cookie);

                HttpUtils.HttpResponse resp = HttpUtils.get(QQ_QR_LOGIN_URL, headers, params);
                collectCookies(ticket.cookies, resp.cookies);

                if (!resp.isOk()) {
                    // 腾讯 WAF 会按客户端 TLS 指纹（JA3）拦截非浏览器（含游戏 JVM）的请求，
                    // 典型表现为 ptqrlogin 直接返回 403。把真实状态码暴露出来，避免误导成"解析失败"。
                    KuGouLogger.warn("[NetMusicKuGou] QQ ptqrlogin HTTP {} (bodyLen={})",
                            resp.statusCode, resp.body == null ? 0 : resp.body.length());
                    return new PollResult(PollState.ERROR,
                            "QQ 登录轮询被服务器拒绝（HTTP " + resp.statusCode + "）。腾讯会按浏览器特征拦截游戏内请求，请改用酷狗账号或微信登录");
                }

                Matcher cb = PTUICB_PATTERN.matcher(resp.body == null ? "" : resp.body);
                if (!cb.find()) {
                    KuGouLogger.warn("[NetMusicKuGou] QQ ptqrlogin 响应解析失败: {}",
                            resp.body == null ? "" : resp.body);
                    return new PollResult(PollState.ERROR, "ptqrlogin 响应解析失败");
                }
                java.util.List<String> parts = new java.util.ArrayList<>();
                Matcher q = QUOTED_PATTERN.matcher(cb.group(1));
                while (q.find()) {
                    parts.add(q.group(1));
                }
                String code = parts.isEmpty() ? "" : parts.get(0);
                String url = parts.size() > 2 ? parts.get(2) : "";
                String msg = parts.size() > 4 ? parts.get(4) : "";

                if ("66".equals(code)) {
                    return new PollResult(PollState.WAITING, "等待扫码");
                }
                if ("65".equals(code)) {
                    return new PollResult(PollState.EXPIRED, "二维码已失效，请刷新");
                }
                if ("67".equals(code)) {
                    return new PollResult(PollState.SCANNED, "已扫码，请在手机上确认");
                }
                if (!"0".equals(code)) {
                    return new PollResult(PollState.ERROR, msg.isEmpty() ? ("QQ 扫码状态 " + code) : msg);
                }

                String openid = matchGroup(url, "openid=([^&#]+)");
                String accessToken = matchGroup(url, "access_token=([^&#]+)");
                if (openid == null || accessToken == null) {
                    // 少数情况下 url 是跳转地址，需要逐跳跟随拿 openid / access_token
                    String[] pair = followForToken(url, cookie, ticket.xloginUrl);
                    if (pair != null) {
                        openid = pair[0];
                        accessToken = pair[1];
                    }
                }
                if (openid == null || accessToken == null) {
                    return new PollResult(PollState.ERROR, "QQ 登录成功但未拿到 openid / access_token");
                }

                LoginOutcome outcome = loginByOpenplat(openid, accessToken, 1, OpenSecrets.QQ_APPID);
                if (outcome.success) {
                    return new PollResult(PollState.SUCCESS, "登录成功", outcome.token, outcome.userid);
                }
                return new PollResult(PollState.ERROR, outcome.message);
            } catch (Exception e) {
                return new PollResult(PollState.ERROR, String.valueOf(e.getMessage()));
            }
        });
    }


    public static CompletableFuture<QrTicket> createWechatQr() {
        return CompletableFuture.supplyAsync(() -> {
            QrTicket ticket = new QrTicket(Provider.WECHAT);
            try {
                Map<String, Object> tokenParams = new LinkedHashMap<>();
                tokenParams.put("grant_type", "client_credential");
                tokenParams.put("appid", OpenSecrets.WX_APPID);
                tokenParams.put("secret", OpenSecrets.WX_SECRET);
                HttpUtils.HttpResponse tokenResp = HttpUtils.get(WX_TOKEN_URL, null, tokenParams);
                JsonObject tokenJson = parse(tokenResp.body);
                String wxToken = tokenJson != null && tokenJson.has("access_token")
                        ? tokenJson.get("access_token").getAsString() : null;
                if (wxToken == null) {
                    ticket.error = "微信 access_token 获取失败：" + (tokenResp.body == null ? "" : tokenResp.body);
                    return ticket;
                }

                Map<String, Object> ticketParams = new LinkedHashMap<>();
                ticketParams.put("access_token", wxToken);
                ticketParams.put("type", 2);
                HttpUtils.HttpResponse ticketResp = HttpUtils.get(WX_TICKET_URL, null, ticketParams);
                JsonObject ticketJson = parse(ticketResp.body);
                if (ticketJson == null || ticketJson.get("errcode") == null
                        || ticketJson.get("errcode").getAsInt() != 0 || !ticketJson.has("ticket")) {
                    ticket.error = "微信 sdk_ticket 获取失败：" + (ticketResp.body == null ? "" : ticketResp.body);
                    return ticket;
                }
                String sdkTicket = ticketJson.get("ticket").getAsString();

                long timestamp = System.currentTimeMillis();
                String noncestr = CryptoUtils.md5(CryptoUtils.randomString(16));
                String signature = CryptoUtils.sha1("appid=" + OpenSecrets.WX_APPID + "&noncestr=" + noncestr
                        + "&sdk_ticket=" + sdkTicket + "&timestamp=" + timestamp);

                Map<String, Object> qrParams = new LinkedHashMap<>();
                qrParams.put("appid", OpenSecrets.WX_APPID);
                qrParams.put("noncestr", noncestr);
                qrParams.put("timestamp", timestamp);
                qrParams.put("scope", "snsapi_userinfo");
                qrParams.put("signature", signature);
                HttpUtils.HttpResponse qrResp = HttpUtils.get(WX_QRCONNECT_URL, null, qrParams);
                JsonObject qrJson = parse(qrResp.body);
                if (qrJson == null || qrJson.get("errcode") == null || qrJson.get("errcode").getAsInt() != 0) {
                    ticket.error = "微信二维码生成失败：" + (qrResp.body == null ? "" : qrResp.body);
                    return ticket;
                }
                ticket.uuid = qrJson.has("uuid") ? qrJson.get("uuid").getAsString() : null;
                String base64 = null;
                if (qrJson.has("qrcode") && qrJson.get("qrcode").isJsonObject()) {
                    JsonObject qrcode = qrJson.getAsJsonObject("qrcode");
                    if (qrcode.has("qrcodebase64")) {
                        base64 = qrcode.get("qrcodebase64").getAsString();
                    }
                }
                if (ticket.uuid == null || base64 == null || base64.isEmpty()) {
                    ticket.error = "微信二维码返回不完整";
                    return ticket;
                }
                ticket.image = java.util.Base64.getDecoder().decode(base64.trim());
                ticket.createdAt = System.currentTimeMillis();
                return ticket;
            } catch (Exception e) {
                ticket.error = String.valueOf(e.getMessage());
                return ticket;
            }
        });
    }

    public static CompletableFuture<PollResult> checkWechatQr(QrTicket ticket) {
        return CompletableFuture.supplyAsync(() -> {
            if (ticket == null || ticket.uuid == null) {
                return new PollResult(PollState.ERROR, "微信二维码会话无效，请刷新");
            }
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("f", "json");
                params.put("uuid", ticket.uuid);
                HttpUtils.HttpResponse resp = HttpUtils.get(WX_POLL_URL, null, params);
                if (!resp.isOk()) {
                    KuGouLogger.warn("[NetMusicKuGou] WeChat poll HTTP {} (bodyLen={})",
                            resp.statusCode, resp.body == null ? 0 : resp.body.length());
                    return new PollResult(PollState.ERROR,
                            "微信登录轮询被服务器拒绝（HTTP " + resp.statusCode + "）。腾讯会按浏览器特征拦截游戏内请求，请改用酷狗账号登录");
                }
                JsonObject json = parse(resp.body);
                if (json == null) {
                    return new PollResult(PollState.WAITING, "等待扫码");
                }
                int code = json.has("wx_errcode") ? json.get("wx_errcode").getAsInt()
                        : (json.has("status") ? json.get("status").getAsInt() : -1);

                if (code == 405) {
                    String wxCode = json.has("wx_code") ? json.get("wx_code").getAsString() : null;
                    if (wxCode == null) {
                        return new PollResult(PollState.ERROR, "微信已确认但未返回 code");
                    }
                    LoginOutcome outcome = loginByWechatCode(wxCode);
                    if (outcome.success) {
                        return new PollResult(PollState.SUCCESS, "登录成功", outcome.token, outcome.userid);
                    }
                    return new PollResult(PollState.ERROR, outcome.message);
                }
                if (code == 408) {
                    return new PollResult(PollState.WAITING, "等待扫码");
                }
                if (code == 404) {
                    return new PollResult(PollState.SCANNED, "已扫码，请在微信中确认");
                }
                if (code == 403) {
                    return new PollResult(PollState.ERROR, "微信端已拒绝登录");
                }
                if (code == 402) {
                    return new PollResult(PollState.EXPIRED, "二维码已过期，请刷新");
                }
                return new PollResult(PollState.WAITING, "等待扫码");
            } catch (Exception e) {
                return new PollResult(PollState.ERROR, String.valueOf(e.getMessage()));
            }
        });
    }

    /** 用微信临时 code 换 openid + access_token，再换酷狗登录态 */
    private static LoginOutcome loginByWechatCode(String code) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("secret", OpenSecrets.WX_SECRET);
            params.put("appid", OpenSecrets.WX_APPID);
            params.put("code", code);
            params.put("grant_type", "authorization_code");
            HttpUtils.HttpResponse resp = HttpUtils.get(WX_ACCESS_TOKEN_URL, null, params);
            JsonObject json = parse(resp.body);
            if (json == null || !json.has("access_token") || !json.has("openid")) {
                return LoginOutcome.fail("微信授权失败：" + (resp.body == null ? "" : resp.body));
            }
            return loginByOpenplat(json.get("openid").getAsString(),
                    json.get("access_token").getAsString(), 36, null);
        } catch (Exception e) {
            return LoginOutcome.fail(String.valueOf(e.getMessage()));
        }
    }


    /**
 调酷狗 /v6/login_by_openplat 换取酷狗登录态。

 @param partnerId QQ=1，微信=36
 @param thirdAppid QQ 需要传第三方 appid，微信不传
*/
    public static LoginOutcome loginByOpenplat(String openid, String accessToken, int partnerId, String thirdAppid) {
        try {
            Boolean ready = KuGouApiClient.ensureDeviceRegistered().get();
            if (ready == null || !ready) {
                return LoginOutcome.fail("设备注册失败");
            }

            long now = System.currentTimeMillis();
            String randomKey = CryptoUtils.randomString(16).toLowerCase();
            String paramsEnc = KuGouDeviceRegister.aesEncrypt(GSON.toJson(new AccessTokenPayload(accessToken)), randomKey).str;
            String pk = KuGouDeviceRegister.cryptoRsaEncrypt(GSON.toJson(new PkPayload(now, randomKey)));

            String guid = KuGouConfig.guid == null || KuGouConfig.guid.isEmpty()
                    ? "" : CryptoUtils.md5(KuGouConfig.guid);
            String dev = guid.length() >= 10 ? guid.substring(0, 10).toUpperCase() : guid.toUpperCase();
            String t2 = KuGouDeviceRegister.aesEncryptWithKey(
                    guid + "|" + T2_MID_SALT + "|02:00:00:00:00:00|" + dev + "|" + now,
                    LITE_T2_KEY, LITE_T2_IV);
            String t1 = KuGouDeviceRegister.aesEncryptWithKey("|" + now, LITE_T1_KEY, LITE_T1_IV);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dev", dev);
            body.put("force_login", 1);
            body.put("partnerid", partnerId);
            body.put("clienttime_ms", now);
            body.put("t1", t1);
            body.put("t2", t2);
            body.put("t3", T3);
            if (thirdAppid != null) {
                body.put("third_appid", thirdAppid);
            }
            body.put("openid", openid);
            body.put("params", paramsEnc);
            body.put("pk", pk);
            String jsonBody = GSON.toJson(body);

            Map<String, Object> query = new LinkedHashMap<>();
            query.put("appid", KuGouSignature.APPID);
            query.put("clientver", KuGouSignature.CLIENTVER);
            query.put("clienttime", now / 1000);
            query.put("dfid", KuGouConfig.dfid == null || KuGouConfig.dfid.isEmpty() ? "-" : KuGouConfig.dfid);
            query.put("mid", KuGouConfig.mid == null || KuGouConfig.mid.isEmpty() ? "-" : KuGouConfig.mid);
            query.put("uuid", "-");
            if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                query.put("token", KuGouConfig.token);
            }
            if (KuGouConfig.userid != null && !KuGouConfig.userid.isEmpty()) {
                query.put("userid", KuGouConfig.userid);
            }
            query.put("signature", KuGouSignature.signatureAndroidParams(query, jsonBody));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", UA_ANDROID);
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("x-router", "login.user.kugou.com");
            headers.put("dfid", String.valueOf(query.get("dfid")));
            headers.put("mid", String.valueOf(query.get("mid")));
            headers.put("clienttime", String.valueOf(query.get("clienttime")));
            headers.put("kg-rc", "1");
            headers.put("kg-thash", "5d816a0");
            headers.put("kg-rec", "1");
            headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

            String fullUrl = HttpUtils.buildFullUrl(OPENPLAT_LOGIN_URL, query);
            HttpUtils.HttpResponse resp = HttpUtils.postJson(fullUrl, headers, jsonBody);
            if (!resp.isOk()) {
                return LoginOutcome.fail("开放平台登录 HTTP " + resp.statusCode);
            }

            JsonObject root = parse(resp.body);
            if (root == null) {
                return LoginOutcome.fail("开放平台登录响应解析失败");
            }
            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            if (status != 1 || !root.has("data")) {
                String msg = root.has("error_msg") ? root.get("error_msg").getAsString()
                        : (root.has("msg") ? root.get("msg").getAsString() : ("开放平台登录失败 status=" + status));
                return LoginOutcome.fail(msg);
            }

            JsonObject data = root.getAsJsonObject("data");
            String token = null;
            String userid = null;
            if (data.has("secu_params")) {
                String decrypted = KuGouDeviceRegister.aesDecrypt(data.get("secu_params").getAsString(), randomKey);
                JsonObject tokenData = parse(decrypted);
                if (tokenData != null) {
                    if (tokenData.has("token")) token = tokenData.get("token").getAsString();
                    if (tokenData.has("userid")) userid = tokenData.get("userid").getAsString();
                } else {
                    token = decrypted;
                }
            }
            if (token == null && data.has("token")) {
                token = data.get("token").getAsString();
            }
            if (userid == null && data.has("userid")) {
                userid = String.valueOf(data.get("userid").getAsLong());
            }
            if (token == null || token.isEmpty()) {
                return LoginOutcome.fail("开放平台登录未返回 token");
            }

            KuGouLoginApi.saveLoginState(token, userid == null ? "" : userid, data);
            return LoginOutcome.ok(token, userid == null ? "" : userid);
        } catch (Exception e) {
            KuGouLogger.warn("[NetMusicKuGou] login_by_openplat error: {}", e.getMessage());
            return LoginOutcome.fail(String.valueOf(e.getMessage()));
        }
    }


    public static final class LoginOutcome {
        public final boolean success;
        public final String message;
        public final String token;
        public final String userid;

        private LoginOutcome(boolean success, String message, String token, String userid) {
            this.success = success;
            this.message = message;
            this.token = token;
            this.userid = userid;
        }

        static LoginOutcome ok(String token, String userid) {
            return new LoginOutcome(true, "success", token, userid);
        }

        static LoginOutcome fail(String message) {
            return new LoginOutcome(false, message, null, null);
        }
    }

    /** 逐跳跟随重定向，尝试从 URL 里抓 openid / access_token */
    private static String[] followForToken(String url, String cookie, String referer) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        String current = url.startsWith("http") ? url : "https://" + url;
        for (int hop = 0; hop < 10; hop++) {
            String openid = matchGroup(current, "openid=([^&#]+)");
            String accessToken = matchGroup(current, "access_token=([^&#]+)");
            if (openid != null && accessToken != null) {
                return new String[]{openid, accessToken};
            }
            try {
                Map<String, String> headers = browserHeaders(referer);
                headers.put("Cookie", cookie);
                HttpUtils.HttpResponse resp = HttpUtils.getNoRedirect(current, headers, null);
                String loc = resp.location;
                if (loc == null || loc.isEmpty()) {
                    String body = resp.body == null ? "" : resp.body;
                    openid = matchGroup(body, "openid[\"']?[:=][\"']?([^&\"'\\s]+)");
                    accessToken = matchGroup(body, "access_token[\"']?[:=][\"']?([^&\"'\\s]+)");
                    if (openid != null && accessToken != null) {
                        return new String[]{openid, accessToken};
                    }
                    return null;
                }
                current = loc.startsWith("http") ? loc : java.net.URI.create(current).resolve(loc).toString();
                if (!current.startsWith("http")) {
                    openid = matchGroup(current, "openid=([^&#]+)");
                    accessToken = matchGroup(current, "access_token=([^&#]+)");
                    return (openid != null && accessToken != null) ? new String[]{openid, accessToken} : null;
                }
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, String> browserHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA_BROWSER);
        headers.put("Referer", referer == null || referer.isEmpty() ? "https://xui.ptlogin2.qq.com/" : referer);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    static String cookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private static void collectCookies(Map<String, String> target, Map<String, String> incoming) {
        if (incoming == null) {
            return;
        }
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            target.put(e.getKey(), e.getValue());
        }
    }

    /** QQ 的 ptqrtoken 算法：e += (e << 5) + c，最后取 31 位 */
    static int hash33(String str) {
        long e = 0;
        for (int i = 0; i < str.length(); i++) {
            e += (e << 5) + str.charAt(i);
        }
        return (int) (2147483647L & e);
    }

    private static String unescapeHex(String input) {
        Matcher m = XESCAPE_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(input, last, m.start());
            sb.append((char) Integer.parseInt(m.group(1), 16));
            last = m.end();
        }
        sb.append(input.substring(last));
        return sb.toString();
    }

    /** 与 JS 的 encodeURIComponent 对齐（Java 的 URLEncoder 会把空格编码成 '+'） */
    static String encodeUriComponent(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*'
                    || c == '\'' || c == '(' || c == ')') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static String matchGroup(String input, String regex) {
        if (input == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex).matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private static JsonObject parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static class AccessTokenPayload {
        String access_token;

        AccessTokenPayload(String token) {
            this.access_token = token;
        }
    }

    private static class PkPayload {
        long clienttime_ms;
        String key;

        PkPayload(long ms, String key) {
            this.clienttime_ms = ms;
            this.key = key;
        }
    }
}
