package com.github.tartaricacid.netmusic.kugou.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 酷狗登录状态与持久化配置。
 注意：不直接依赖 ClothConfig2，避免未安装时触发类加载错误。
 ClothConfig 配置页面逻辑已移至 KuGouConfigScreen。
*/
public class KuGouConfig {
    public static final Map<String, String> cookies = new ConcurrentHashMap<>();
    public static volatile String token = "";
    public static volatile String userid = "";
    public static volatile String mid = "";
    public static volatile String dfid = "";
    public static volatile String guid = "";
    public static volatile String vipType = "";
    public static volatile String vipToken = "";

    /** 标记配置已变更，需要持久化 */
    public static volatile boolean dirty = false;

    public static void markDirty() {
        dirty = true;
    }

    public static boolean isLoggedIn() {
        return token != null && !token.isEmpty() && userid != null && !userid.isEmpty();
    }

    public static void addCookie(String name, String value) {
        cookies.put(name, value);
    }

    public static String getCookieString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    public static void clearCookies() {
        cookies.clear();
        token = "";
        userid = "";
        vipType = "";
        vipToken = "";
    }

    /**
 把一个 Cookie 字符串（例如 "token=abc; userid=123; vip_type=2; ..."）解析为酷狗登录态。
 主要用于专用服务器：服务端通过配置文件里的 VIP Cookie 直接"登录"，无需客户端扫码。
*/
    public static void applyCookieString(String cookieStr) {
        if (cookieStr == null || cookieStr.isBlank()) {
            return;
        }
        for (String part : cookieStr.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String k = kv[0].trim();
            String v = kv[1].trim();
            if (k.isEmpty()) {
                continue;
            }
            addCookie(k, v);
            switch (k) {
                case "token" -> token = v;
                case "userid" -> userid = v;
                case "vip_type", "vipType" -> vipType = v;
                case "vip_token", "vipToken" -> vipToken = v;
                case "dfid" -> dfid = v;
                case "mid", "KUGOU_API_MID" -> mid = v;
                case "guid" -> guid = v;
            }
        }
        markDirty();
    }
}
