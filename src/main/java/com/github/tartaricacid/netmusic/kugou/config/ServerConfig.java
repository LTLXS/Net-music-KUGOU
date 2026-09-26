package com.github.tartaricacid.netmusic.kugou.config;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 服务端配置：ModConfig.Type.SERVER 类型在专用服务器(dedicated server)和客户端都会被加载，
 因此这里的 VIP Cookie 值在任何环境下都能被读到——这正是让专用服务器"用 VIP Cookie 登录"的关键。
*/
public final class ServerConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> VIP_COOKIE;

    public static final ForgeConfigSpec.EnumValue<KuGouLogger.LogLevel> LOG_LEVEL;

    static {
        BUILDER.comment("Server-side configuration. Loaded on both dedicated servers and clients, "
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
