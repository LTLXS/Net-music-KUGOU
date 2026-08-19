package com.github.tartaricacid.netmusic.kugou.lyric;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 女仆气泡歌词的 LRC 字符串缓存。
 * <p>
 * <b>使用流程</b>（基于父模组女仆播放链路）：
 * <ol>
 *   <li><b>服务端</b>：{@code IAsyncSongUrlResolver} 完成后调 {@code MaidMusicToClientMessage.showLyric}
 *       前，{@code MaidMusicToClientMessageShowLyricMixin} 读 CD NBT 上的 LRC → 写本缓存</li>
 *   <li><b>客户端</b>：收到 {@code MaidMusicToClientMessage} → 解析后查看 maid 是否已有
 *       {@code LyricChatBubbleData}（网易云路径会自动创建）；酷狗路径（musicId=0）则本缓存为唯一来源</li>
 *   <li><b>客户端</b>：{@code LyricChatBubbleRenderer} 构造时（{@code LyricChatBubbleRendererMixin}），
 *       看到 {@code songId == 0} 就从本缓存取 LRC 解析注入</li>
 * </ol>
 *
 * <p><b>key 设计</b>：用 {@code maidId + songName} 组合，因为同世界多女仆共享缓存，
 * 防止一个女仆的歌词污染另一个。
 */
public final class KuGouMaidLyricCache {
    private KuGouMaidLyricCache() {}

    private static final ConcurrentHashMap<String, CachedLyric> CACHE = new ConcurrentHashMap<>();

    /**
     * 上次触发全局清理（removeIf）的 songName。同一首歌多次 put 时不再做 O(n) 扫描。
     * 仅在 songName 切换时（即新歌开始）才做一次 removeIf。
     */
    private static volatile String lastClearedSongName = "";

    public static String makeKey(long maidId, String songName) {
        return maidId + "|" + (songName == null ? "" : songName);
    }

    public static void put(String key, String lrcText) {
        put(key, lrcText, null, null);
    }

    public static void put(String key, String lrcText, String transJson) {
        put(key, lrcText, transJson, null);
    }

    /**
     * 写入 LRC 文本 + 翻译 JSON + 罗马音 map。
     */
    public static void put(String key, String lrcText, String transJson, Int2ObjectSortedMap<String> romaji) {
        if (key != null && lrcText != null && !lrcText.isEmpty()) {
            CACHE.put(key, new CachedLyric(lrcText, transJson, romaji));
        }
    }

    public static void put(long maidId, String songName, String lrcText) {
        put(maidId, songName, lrcText, null, null);
    }

    public static void put(long maidId, String songName, String lrcText, String transJson) {
        put(maidId, songName, lrcText, transJson, null);
    }

    /**
     * 写入 LRC 文本 + 翻译 JSON（酷狗 KRC language 字段解码后的 JSON）+ 罗马音 map（酷狗 type=0）。
     * 会清理不匹配当前 songName 的旧 entry。
     */
    public static void put(long maidId, String songName, String lrcText,
                            String transJson, Int2ObjectSortedMap<String> romaji) {
        if (songName != null && !songName.equals(lastClearedSongName)) {
            // 新歌开始：清理与当前 songName 不匹配的所有旧 entry
            // 同一首歌的多次 put（不同 maidId 共享）跳过这次扫描，避免 O(n) 退化
            String suffix = "|" + songName;
            CACHE.entrySet().removeIf(e -> !e.getKey().endsWith(suffix));
            lastClearedSongName = songName;
        }
        put(makeKey(maidId, songName), lrcText, transJson, romaji);
    }

    public static CachedLyric take(String key) {
        return key == null ? null : CACHE.remove(key);
    }

    public static CachedLyric take(long maidId, String songName) {
        return take(makeKey(maidId, songName));
    }

    /**
     * 按 songName 精确匹配（用于 {@code LyricChatBubbleRenderer} 构造时）。
     * <p>不消费（peek）：因为同一个 maid 的同一首歌每次 entity data 同步都会触发
     * {@code LyricChatBubbleRenderer.<init>}，多次注入同一个 LRC 是幂等的，没必要消费。
     * <p><b>精确匹配</b>：用 key 后缀（{@code "maidId|songName"}）。
     */
    public static CachedLyric peekBySongName(String songName) {
        if (songName == null) return null;
        String suffix = "|" + songName;
        for (var entry : CACHE.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static void clearAll() {
        CACHE.clear();
    }

    /**
     * 缓存的歌词条目：LRC 原文 + 翻译 JSON + 罗马音 map。
     */
    public static final class CachedLyric {
        public final String lrcText;
        public final String transJson;
        public final Int2ObjectSortedMap<String> romaji;

        CachedLyric(String lrcText, String transJson) {
            this(lrcText, transJson, null);
        }

        CachedLyric(String lrcText, String transJson, Int2ObjectSortedMap<String> romaji) {
            this.lrcText = lrcText;
            this.transJson = transJson;
            this.romaji = romaji == null ? new Int2ObjectRBTreeMap<>() : romaji;
        }
    }
}
