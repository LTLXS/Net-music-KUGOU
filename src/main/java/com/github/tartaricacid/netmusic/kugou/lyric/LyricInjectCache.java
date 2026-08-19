package com.github.tartaricacid.netmusic.kugou.lyric;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 歌词注入用的跨线程缓存。
 * <p>
 * 父模组 {@code MusicToClientMessage.onHandle} 在 {@code CompletableFuture.runAsync} 的
 * 后台线程中创建 {@code NetMusicSound}，而 Mixin 注入点在调用入口（主线程），
 * 所以不能用 ThreadLocal（线程不共享），用 {@code ConcurrentHashMap<BlockPos, LyricRecord>}。
 * <p>
 * 数据流：
 * <ol>
 *   <li>{@link #set(BlockPos, LyricRecord)} — MusicToClientMessageMixin 在 onHandle 入口写入</li>
 *   <li>{@link #take(BlockPos)} — NetMusicSoundMixin 在构造器尾部读取并清除</li>
 * </ol>
 */
public final class LyricInjectCache {

    private static final ConcurrentHashMap<BlockPos, LyricRecord> CACHE = new ConcurrentHashMap<>();

    private LyricInjectCache() {}

    public static void set(BlockPos pos, LyricRecord record) {
        if (pos != null && record != null) {
            CACHE.put(pos, record);
        }
    }

    /**
     * 取出并清除缓存（一次性消费）。
     *
     * @param pos 播放位置
     * @return 缓存的 LyricRecord，或 null（表示本次播放不需要注入歌词）
     */
    public static LyricRecord take(BlockPos pos) {
        if (pos == null) return null;
        return CACHE.remove(pos);
    }

    /** 清除指定位置的缓存 */
    public static void clear(BlockPos pos) {
        if (pos != null) {
            CACHE.remove(pos);
        }
    }

    /** 清除全部（异常路径用） */
    public static void clearAll() {
        CACHE.clear();
    }
}
