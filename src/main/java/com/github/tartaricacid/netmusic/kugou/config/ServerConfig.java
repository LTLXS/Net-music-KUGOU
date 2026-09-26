package com.github.tartaricacid.netmusic.kugou.config;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 共用配置：使用 ModConfig.Type.COMMON 类型（不是 SERVER）。
 NeoForge 的 SERVER 配置只有在连入服务器后才会加载，主菜单打开配置界面时尚未加载，调用 .get() 会抛异常；
 COMMON 在客户端启动即加载、专用服务器也加载，且不走服务端同步，因此 VIP Cookie 在两端都能读到
 ——这正是让专用服务器"用 VIP Cookie 登录"的关键。
*/
public final class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> VIP_COOKIE;

    public static final ModConfigSpec.EnumValue<KuGouLogger.LogLevel> LOG_LEVEL;

    static {
        BUILDER.comment("Common configuration. Loaded on both dedicated servers and clients, "
                + "so the same values work everywhere.");
        BUILDER.push("vip");
        VIP_COOKIE = BUILDER
                .comment("VIP cookie used to unlock paid songs and to enable CD URL refresh on a dedicated server "
                        + "(no client QR login required). Paste the same cookie string shown as 'VIP Cookie' in the client config screen. "
                        + "Auto-filled after a client QR login.")
                .define("vipCookie", "");
        BUILDER.pop();
        BUILDER.push("log");
        LOG_LEVEL = BUILDER
                .comment("Log verbosity: MINIMAL = errors and warnings only; NORMAL = + info; DETAILED = everything (incl. debug/verbose).")
                .defineEnum("logLevel", KuGouLogger.LogLevel.NORMAL);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ServerConfig() {
    }

    public static String getVipCookie() {
        return VIP_COOKIE.get();
    }

    public static KuGouLogger.LogLevel getLogLevel() {
        return LOG_LEVEL.get();
    }

    public static void setVipCookie(String value) {
        VIP_COOKIE.set(value);
    }
}
