package com.github.tartaricacid.netmusic.kugou.lyric;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块音响专用的罗马音侧通道。
 * <p>
 * 父模组的 {@code LyricRecord} 只有 lyrics + transLyrics 两个字段，没有"第三行"位置。
 * 所以我们用这个静态 {@code Map<BlockPos, romaji>} 把 type=0 罗马音从
 * {@link MusicToClientMessageMixin} 直接传到 {@link MusicPlayerRendererMixin}。
 * <p>
 * <b>写入</b>：{@link MusicToClientMessageMixin#onHandle}（client 主线程）在解析完 KRC
 * 之后写入。
 * <p>
 * <b>读取</b>：{@link MusicPlayerRendererMixin#renderLyric}（client 渲染线程）按 BlockPos 读。
 * <p>
 * <b>清理</b>：当 TileEntity 停止播放时，{@code MusicPlayerRendererMixin} 会同步调用
 * {@link #remove}（基于父模组 {@code te.lyricRecord = null} 同一时机）。
 * 不主动清理的情况下，大小上限 ≈ 维度内活跃音乐方块数，实践上不会爆。
 */
public final class BlockRomajiRegistry {

    private static final ConcurrentHashMap<BlockPos, Int2ObjectSortedMap<String>> MAP =
            new ConcurrentHashMap<>();

    private BlockRomajiRegistry() {}

    /**
     * 写入 / 覆盖。<b>总是</b>写入：传空 map 也覆盖旧值（避免"上一首歌的 romaji 残留到新歌"的 bug）。
     * 传 null 等同于传空 map。renderer 用 {@code isEmpty()} 区分"这首歌 KRC 真的没 type=0"
     * 与"还没收到任何数据"。
     */
    public static void put(BlockPos pos, Int2ObjectSortedMap<String> romaji) {
        if (pos == null) return;
        MAP.put(pos, romaji == null ? new Int2ObjectRBTreeMap<>() : romaji);
    }

    /** 读取（peek，不消费）。pos 不存在时返回空 map（<b>非 null</b>，方便调用方统一判空）。 */
    public static Int2ObjectSortedMap<String> get(BlockPos pos) {
        if (pos == null) return null;
        return MAP.get(pos);
    }

    /** 清理：TileEntity 停止播放时由 MusicPlayerRendererMixin 调。 */
    public static void remove(BlockPos pos) {
        if (pos != null) MAP.remove(pos);
    }

    public static void clearAll() {
        MAP.clear();
    }

    /** 测试 / 诊断用。 */
    public static Map<BlockPos, Int2ObjectSortedMap<String>> snapshot() {
        return Collections.unmodifiableMap(MAP);
    }
}
