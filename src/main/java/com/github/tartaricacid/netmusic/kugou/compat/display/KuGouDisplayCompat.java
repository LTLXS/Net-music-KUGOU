package com.github.tartaricacid.netmusic.kugou.compat.display;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.kugou.support.CdAddonData;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NetMusicDisplay 兼容用的上下文/缓存工具。
 * <p>
 * 背景：{@code com.netmusicdisplay.source.LyricCache.extractSongId} 只能从网易云 URL
 * {@code ?id=数字.mp3} 提 ID，对酷狗 URL 返回 -1 → 链路直接断在"仅支持网易云歌词"。
 * 本类提供：一个 fileHash → LyricRecord 服务端缓存（由父 mod 服务端 Mixin 写入），
 * 供 display compat source Mixin 在拦截 NetMusicDisplay 的 provideText/provideLine 时查询。
 * <p>
 * <b>key 设计</b>：用 fileHash（酷狗原曲 ID）作缓存 key 跨维度/跨玩家复用，
 * 同一首歌在多个音乐机上播放时无需重复解析。
 */
public final class KuGouDisplayCompat {
    private KuGouDisplayCompat() {}

    /** fileHash → LyricRecord。本进程内同源同 hash 复用。 */
    private static final ConcurrentHashMap<String, LyricRecord> LYRIC_BY_HASH = new ConcurrentHashMap<>();

    /**
     * pos → fileHash（最近一次写入的）。同一个音乐机可能切换歌曲，
     * 用 put 覆盖即可。
     */
    private static final ConcurrentHashMap<BlockPos, String> HASH_BY_POS = new ConcurrentHashMap<>();

    /**
     * 写入 fileHash → LyricRecord 映射。幂等：相同 hash 覆盖即可（同 hash = 同一首歌）。
     * <p>
     * 调用时机：刻录完成（服务端 CDBurnerMenuMixin）。
     */
    public static void putLyricByHash(String fileHash, LyricRecord record) {
        if (fileHash != null && !fileHash.isEmpty() && record != null) {
            LYRIC_BY_HASH.put(fileHash, record);
        }
    }

    public static LyricRecord getLyricByHash(String fileHash) {
        if (fileHash == null || fileHash.isEmpty()) return null;
        return LYRIC_BY_HASH.get(fileHash);
    }

    /**
     * 把音乐机位置登记到 fileHash。同一音乐机可能切歌，用 put 覆盖。
     */
    public static void registerPos(BlockPos pos, String fileHash) {
        if (pos != null && fileHash != null && !fileHash.isEmpty()) {
            HASH_BY_POS.put(pos, fileHash);
        }
    }

    public static String getHashByPos(BlockPos pos) {
        if (pos == null) return null;
        return HASH_BY_POS.get(pos);
    }

    /**
     * 服务端 Mixin 在 setPlayToClient 命中预取并写好歌词后调：
     * 把 fileHash 和 LyricRecord 同时落库，方便 display compat source Mixin 查询。
     */
    public static void putAll(BlockPos pos, String fileHash, LyricRecord record) {
        registerPos(pos, fileHash);
        putLyricByHash(fileHash, record);
    }

    public static KuGouLyricContext getKuGouContext(BlockPos sourcePos, Level level) {
        if (sourcePos == null || level == null) return null;
        BlockEntity be = level.getBlockEntity(sourcePos);
        if (!(be instanceof TileEntityMusicPlayer)) return null;
        TileEntityMusicPlayer player = (TileEntityMusicPlayer) be;

        ItemStack cd = player.getPlayerInv().getStackInSlot(0);
        if (cd.isEmpty()) return null;

        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null || info.songUrl == null || info.songName == null) return null;

        if (!player.isPlay()) return null;

        Optional<CdAddonData> optData = CdNbtHelper.readOriginalInfo(cd);
        if (optData.isEmpty()) return null;

        String fileHash = optData.get().fileHash();
        if (fileHash == null || fileHash.isEmpty()) return null;

        // 确保 pos → fileHash 映射最新（切歌后覆盖）
        registerPos(sourcePos, fileHash);

        LyricRecord record = getLyricByHash(fileHash);

        int totalTime = info.songTime * 20 + 64;
        int currentTime = player.getCurrentTime();
        int progress = Math.max(0, totalTime - currentTime);

        return new KuGouLyricContext(record, progress, info.songName, currentTime);
    }

    public static String currentLyricLine(LyricRecord record, int time) {
        if (record == null) return null;
        Int2ObjectSortedMap<String> lyrics = record.getLyrics();
        if (lyrics == null || lyrics.isEmpty()) return null;
        return lyrics.get(findFloorKey(lyrics, time));
    }

    public static String currentTransLine(LyricRecord record, int time) {
        if (record == null) return null;
        Int2ObjectSortedMap<String> trans = record.getTransLyrics();
        if (trans == null || trans.isEmpty()) return null;
        return trans.get(findFloorKey(trans, time));
    }

    public static boolean hasTranslation(LyricRecord record) {
        if (record == null) return false;
        Int2ObjectSortedMap<String> trans = record.getTransLyrics();
        if (trans == null || trans.isEmpty()) return false;
        for (String v : trans.values()) {
            if (v != null && !v.isEmpty()) return true;
        }
        return false;
    }

    /** 在有序歌词表里做 floor 查找（取 <= targetTick 的最大 key）。复用渲染层逻辑。 */
    private static int findFloorKey(Int2ObjectSortedMap<String> map, int targetTick) {
        if (map == null || map.isEmpty()) return 0;
        int firstKey = map.firstIntKey();
        if (targetTick <= firstKey) return firstKey;
        int lastKey = map.lastIntKey();
        if (targetTick >= lastKey) return lastKey;
        int lo = 0, hi = map.size() - 1, best = firstKey;
        int[] keys = map.keySet().toIntArray();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int k = keys[mid];
            if (k <= targetTick) {
                best = k;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    public static final class KuGouLyricContext {
        public final LyricRecord record;
        public final int progress;
        public final String songName;
        public final int currentTime;

        KuGouLyricContext(LyricRecord record, int progress, String songName, int currentTime) {
            this.record = record;
            this.progress = progress;
            this.songName = songName;
            this.currentTime = currentTime;
        }
    }
}
