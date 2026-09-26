package com.github.tartaricacid.netmusic.kugou.api.model;

public final class LyricContent {
    public final String lyricContent;
    public final String format;
    public final String languageJson;
    public final boolean hasTranslation;

    public LyricContent(String lyricContent, String format) {
        this(lyricContent, format, null, false);
    }

    public LyricContent(String lyricContent, String format, String languageJson) {
        this(lyricContent, format, languageJson, languageJson != null && !languageJson.isEmpty());
    }

    public LyricContent(String lyricContent, String format, String languageJson, boolean hasTranslation) {
        this.lyricContent = lyricContent;
        this.format = format;
        this.languageJson = languageJson;
        this.hasTranslation = hasTranslation;
    }
}
