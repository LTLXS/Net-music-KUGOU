package com.github.tartaricacid.netmusic.kugou.api.model;

public final class RelateGood {
    public final String hash;
    public final String quality;
    public final int level;

    public RelateGood(String hash, String quality, int level) {
        this.hash = hash;
        this.quality = quality;
        this.level = level;
    }

    @Override
    public String toString() {
        return "{" + level + "/" + quality + "@" + (hash == null ? "?" : hash.substring(0, Math.min(8, hash.length()))) + "...}";
    }
}
