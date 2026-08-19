package com.github.tartaricacid.netmusic.kugou.lyric;

/**
 * 刻录时客户端→服务端的 fileHash / albumId 传递缓存。
 * <p>
 * 在单人游戏（集成服务器）中，客户端和服务端共享同一个 JVM，
 * 所以可以用 static 变量传递数据而无需额外的网络消息。
 * <p>
 * 数据流：
 * <ol>
 *   <li>{@link #set(String, String)} — CDBurnerMenuScreenMixin 刻录前写入</li>
 *   <li>{@link #take()} — CDBurnerMenuMixin (server) setSongInfo 后读取并清除</li>
 * </ol>
 */
public final class BurnDataCache {

    private static volatile String fileHash;
    private static volatile String albumId;

    private BurnDataCache() {}

    public static void set(String hash, String album) {
        fileHash = hash;
        albumId = album;
    }

    public static String getFileHash() {
        return fileHash;
    }

    public static String getAlbumId() {
        return albumId;
    }

    /** 一次性消费（取完即清） */
    public static String[] take() {
        String h = fileHash;
        String a = albumId;
        fileHash = null;
        albumId = null;
        return new String[]{h, a};
    }
}
