package com.github.tartaricacid.netmusic.kugou.api.model;

public final class LyricCandidate {
    public final String id;
    public final String accessKey;
    public final String singer;
    public final String songName;
    public final int score;
    public final int duration;
    public final double matchScore;

    public LyricCandidate(String id, String accessKey, String singer, String songName, int score) {
        this(id, accessKey, singer, songName, score, 0, 0.0);
    }

    public LyricCandidate(String id, String accessKey, String singer, String songName,
                           int score, int duration, double matchScore) {
        this.id = id;
        this.accessKey = accessKey;
        this.singer = singer;
        this.songName = songName;
        this.score = score;
        this.duration = duration;
        this.matchScore = matchScore;
    }
}
