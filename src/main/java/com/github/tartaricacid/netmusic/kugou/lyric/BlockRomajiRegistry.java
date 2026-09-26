package com.github.tartaricacid.netmusic.kugou.lyric;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 方块音响专用的罗马音侧通道。
 父模组 LyricRecord 只有 lyrics + transLyrics，没有第三行位置，
 故用静态 Map 把 type=0 罗马音从 MusicToClientMessageMixin 传到 MusicPlayerRendererMixin。
 写入：onHandle（client 主线程）解析完 KRC 后写入；
 读取：renderLyric（client 渲染线程）按 BlockPos 读；
 清理：TileEntity 停止播放时由 MusicPlayerRendererMixin 调 remove。*/
public final class BlockRomajiRegistry {

    private static final ConcurrentHashMap<BlockPos, Int2ObjectSortedMap<String>> MAP =
            new ConcurrentHashMap<>();

    private BlockRomajiRegistry() {}

    /**
 写入 / 覆盖。总是写入：传空 map 也覆盖旧值（避免"上一首歌的 romaji 残留到新歌"的 bug）。
 传 null 等同于传空 map。renderer 用 isEmpty() 区分"这首歌 KRC 真的没 type=0"
 与"还没收到任何数据"。
*/
    public static void put(BlockPos pos, Int2ObjectSortedMap<String> romaji) {
        if (pos == null) return;
        MAP.put(pos, romaji == null ? new Int2ObjectRBTreeMap<>() : romaji);
    }

    public static Int2ObjectSortedMap<String> get(BlockPos pos) {
        if (pos == null) return null;
        return MAP.get(pos);
    }

    public static void remove(BlockPos pos) {
        if (pos != null) MAP.remove(pos);
    }

    public static void clearAll() {
        MAP.clear();
    }

    public static Map<BlockPos, Int2ObjectSortedMap<String>> snapshot() {
        return Collections.unmodifiableMap(MAP);
    }
}
