package com.github.tartaricacid.netmusic.kugou.config;

import net.minecraft.network.chat.Component;
import java.util.Locale;

public enum ProviderType {
    NETEASE("网易云"),
    KUGOU("酷狗"),
    QQ("QQ");

    private final String displayName;

    ProviderType(String displayName) {
        this.displayName = displayName;
    }

    public ProviderType next() {
        ProviderType[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }

    public Component getDisplayName() {
        return Component.translatable("netmusic_kugou.provider." + name().toLowerCase(Locale.ROOT));
    }
}