package com.github.tartaricacid.netmusic.kugou.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> VIP_COOKIE;

    public static final ModConfigSpec.ConfigValue<String> AUDIO_QUALITY;

    public static final ModConfigSpec.BooleanValue AUTO_RECEIVE_VIP;

    // VIP 失败后的重试间隔（分钟）
    public static final ModConfigSpec.IntValue VIP_RETRY_INTERVAL_MINUTES;

    public static final ModConfigSpec.BooleanValue URL_REFRESH_ENABLED;
    public static final ModConfigSpec.IntValue URL_REFRESH_INTERVAL_HOURS;
    public static final ModConfigSpec.IntValue URL_REFRESH_CHECK_TIMEOUT_SECONDS;

    public static final ModConfigSpec.BooleanValue LYRIC_SHOW_TRANSLATION;
    public static final ModConfigSpec.BooleanValue LYRIC_SHOW_ROMAJI;

    static {
        BUILDER.push("music_source");

        PROVIDER = BUILDER
                .comment("Music provider: NETEASE or KUGOU")
                .define("provider", "NETEASE");

        VIP_COOKIE = BUILDER
                .comment("VIP cookie for premium songs")
                .define("vipCookie", "");

        BUILDER.pop();

        BUILDER.push("audio_quality");

        AUDIO_QUALITY = BUILDER
                .comment("Audio quality: 128, 320, flac, high, super")
                .define("quality", "320");

        BUILDER.pop();

        BUILDER.push("vip");

        AUTO_RECEIVE_VIP = BUILDER
                .comment("Automatically claim daily VIP on startup (concept version only)")
                .define("autoReceiveVip", false);

        VIP_RETRY_INTERVAL_MINUTES = BUILDER
                .comment("If the first auto claim attempt fails, retry every N minutes within the game session. "
                        + "Server returns error_code 20002 when the daily quota is exhausted, which stops the retry until tomorrow.")
                .defineInRange("vipRetryIntervalMinutes", 10, 1, 1440);

        BUILDER.pop();

        BUILDER.push("url_refresh");

        URL_REFRESH_ENABLED = BUILDER
                .comment("Periodically scan the player's inventory for burned CDs whose stored audio URL has expired "
                        + "(Kugou returns 403 on expired signed URLs), and automatically re-fetch a new URL. "
                        + "Only works for CDs burned AFTER this option is enabled (fileHash + albumId are stored in NBT).")
                .define("enabled", true);

        URL_REFRESH_INTERVAL_HOURS = BUILDER
                .comment("How often (in hours) to scan and refresh expired CD URLs.")
                .defineInRange("intervalHours", 4, 1, 168);

        URL_REFRESH_CHECK_TIMEOUT_SECONDS = BUILDER
                .comment("HTTP timeout for the HEAD probe used to detect expired URLs.")
                .defineInRange("checkTimeoutSeconds", 5, 1, 60);

        BUILDER.pop();

        BUILDER.push("lyric_display");

        LYRIC_SHOW_TRANSLATION = BUILDER
                .comment("Display the Chinese translation line (酷狗 type=1) when available.\n"
                        + "Works for both the block music player and the maid chat bubble.\n"
                        + "Default: true")
                .define("showTranslation", true);

        LYRIC_SHOW_ROMAJI = BUILDER
                .comment("Display the romaji/phonetic line (酷狗 type=0) when available.\n"
                        + "If both showTranslation and showRomaji are on and the song has both,\n"
                        + "the lyrics will show THREE lines: original + translation + romaji.\n"
                        + "Default: false")
                .define("showRomaji", false);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static ProviderType getProvider() {
        try {
            ProviderType type = ProviderType.valueOf(PROVIDER.get().toUpperCase());
            // 若未安装 netmusiccanneedqq 模组，QQ 渠道不可用，回退到网易云
            if (type == ProviderType.QQ && !isQqModLoaded()) {
                return ProviderType.NETEASE;
            }
            return type;
        } catch (IllegalArgumentException e) {
            return ProviderType.NETEASE;
        }
    }

    public static boolean isQqModLoaded() {
        try {
            return net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded("netmusiccanneedqq");
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setProvider(ProviderType provider) {
        PROVIDER.set(provider.name());
    }

    public static String getVipCookie() {
        return VIP_COOKIE.get();
    }

    public static AudioQuality getAudioQuality() {
        return AudioQuality.fromValue(AUDIO_QUALITY.get());
    }

    public static void setAudioQuality(AudioQuality quality) {
        AUDIO_QUALITY.set(quality.getValue());
    }
}