package com.github.tartaricacid.netmusic.kugou.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 酷狗登录状态与持久化配置。
 * 注意：不直接依赖 ClothConfig2，避免未安装时触发类加载错误。
 * ClothConfig 配置页面逻辑已移至 KuGouConfigScreen。
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
}