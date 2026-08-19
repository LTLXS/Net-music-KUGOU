package com.github.tartaricacid.netmusic.kugou.config;

public enum ProviderType {
    NETEASE("网易云"),
    KUGOU("酷狗");

    private final String displayName;

    ProviderType(String displayName) {
        this.displayName = displayName;
    }

    public ProviderType next() {
        return this == NETEASE ? KUGOU : NETEASE;
    }

    public String getDisplayName() {
        return displayName;
    }
}