package com.github.tartaricacid.netmusic.kugou.config;

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

    public String getDisplayName() {
        return displayName;
    }
}