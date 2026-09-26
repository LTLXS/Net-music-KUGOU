package com.github.tartaricacid.netmusic.kugou.config;

import net.minecraft.network.chat.Component;
import java.util.Locale;

public enum AudioQuality {
    STANDARD("128", "标准品质 (MP3 128kbps)"),
    HQ("320", "HQ 高品质 (MP3 320kbps)"),
    SQ_FLAC("flac", "SQ 无损品质 (FLAC)"),
    HIGH("high", "Hi-Res 品质"),
    VIPER_TAPE("viper_tape", "蝰蛇母带"),
    SUPER_DSD("super", "DSD 臻品音质");

    /** 蝰蛇母带（viper_tape）需要转码才能播放，默认关闭 */
    public boolean needsOptIn() {
        return this == VIPER_TAPE;
    }

    private final String value;
    private final String displayName;

    AudioQuality(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public String getValue() {
        return value;
    }

    public Component getDisplayName() {
        return Component.translatable("netmusic_kugou.audio_quality." + name().toLowerCase(Locale.ROOT));
    }

    public static AudioQuality fromValue(String value) {
        if (value == null || value.isEmpty()) return HQ;
        for (AudioQuality q : values()) {
            if (q.value.equalsIgnoreCase(value)) return q;
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return HQ;
        }
    }

    @Override
    public String toString() {
        return "netmusic_kugou.audio_quality." + name().toLowerCase(Locale.ROOT);
    }
}
