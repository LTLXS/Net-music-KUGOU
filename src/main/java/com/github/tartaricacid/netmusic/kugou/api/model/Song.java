package com.github.tartaricacid.netmusic.kugou.api.model;

public final class Song {
    public final String id;
    public final String name;
    public final String singer;
    public final String album;
    public final String hash;
    public final String albumId;
    public final int duration;

    public final String image;

    public Song(String id, String name, String singer, String album, String hash, String albumId, int duration) {
        this(id, name, singer, album, hash, albumId, duration, null);
    }

    public Song(String id, String name, String singer, String album, String hash, String albumId,
                int duration, String image) {
        this.id = id;
        this.name = name;
        this.singer = singer;
        this.album = album;
        this.hash = hash;
        this.albumId = albumId;
        this.duration = duration;
        this.image = image;
    }
}
