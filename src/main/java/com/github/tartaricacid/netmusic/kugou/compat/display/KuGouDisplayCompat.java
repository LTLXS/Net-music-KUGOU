package com.github.tartaricacid.netmusic.kugou.compat.display;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.kugou.support.CdAddonData;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NetMusicDisplay 兼容用的上下文/缓存工具。
 * <p>
 * 背景：{@code com.netmusicdisplay.source.LyricCache.extractSongId} 只能从网易云 URL
 * {@code ?id=数字.mp3} 提 ID，对酷狗 URL 返回 -1 → 链路直接断在"仅支持网易云歌词"。
 * 本类提供：(1) 一个 {@link ThreadLocal}，让 source Mixin 在调用 NetMusicDisplay
 * 之前写入当前音乐机 BlockPos；(2) 一个 fileHash → LyricRecord 缓存，由父 mod
 * 服务端 Mixin 写入，供 LyricCache mixin 查询。
 * <p>
 * <b>key 设计</b>：用 fileHash（酷狗原曲 ID）作缓存 key 跨维度/跨玩家复用，
 * 同一首歌在多个音乐机上播放时无需重复解析。
 */
public final class KuGouDisplayCompat {
    private KuGouDisplayCompat() {}

    /**
     * 当前线程的"音乐机位置"上下文。NetMusicDisplay 的 source 在渲染歌词时会
     * 调 {@code LyricCache.extractSongId} / {@code getLyric}，我们用
     * source mixin 在调用前 set，LyricCache mixin 读后立即 clear。
     */
    private static final ThreadLocal<BlockPos> CURRENT_POS = new ThreadLocal<>();

    /** fileHash → LyricRecord。本进程内同源同 hash 复用。 */
    private static final ConcurrentHashMap<String, LyricRecord> LYRIC_BY_HASH = new ConcurrentHashMap<>();

    /**
     * pos → fileHash（最近一次写入的）。同一个音乐机可能切换歌曲，
     * 用 putIfAbsent 保护：已经缓存的 fileHash 不会被覆盖。
     */
    private static final ConcurrentHashMap<BlockPos, String> HASH_BY_POS = new ConcurrentHashMap<>();

    /**
     * 把音乐机位置写入当前线程上下文。source mixin 在调 NetMusicDisplay 前调。
     */
    public static void setCurrentPos(BlockPos pos) {
        CURRENT_POS.set(pos);
    }

    /**
     * 取出当前线程的 BlockPos，<b>不</b>清空。
     */
    public static BlockPos getCurrentPos() {
        return CURRENT_POS.get();
    }

    /**
     * 取出当前线程的 BlockPos 并清空。LyricCache mixin 在读取后立即调，避免 ThreadLocal 泄漏。
     */
    public static BlockPos takeCurrentPos() {
        BlockPos p = CURRENT_POS.get();
        CURRENT_POS.remove();
        return p;
    }

    /**
     * 写入 fileHash → LyricRecord 映射。幂等：相同 hash 覆盖即可（同 hash = 同一首歌）。
     */
    public static void putLyricByHash(String fileHash, LyricRecord record) {
        if (fileHash != null && !fileHash.isEmpty() && record != null) {
            LYRIC_BY_HASH.put(fileHash, record);
        }
    }

    /**
     * 读 LyricRecord。hash 为空/无记录时返回 null。
     */
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

    /**
     * 按音乐机位置查 fileHash。
     */
    public static String getHashByPos(BlockPos pos) {
        if (pos == null) return null;
        return HASH_BY_POS.get(pos);
    }

    /**
     * 服务端 Mixin 在 setPlayToClient 命中预取并写好歌词后调：
     * 把 fileHash 和 LyricRecord 同时落库，方便 LyricCache mixin 查询。
     */
    public static void putAll(BlockPos pos, String fileHash, LyricRecord record) {
        registerPos(pos, fileHash);
        putLyricByHash(fileHash, record);
    }

    /**
     * 尝试为 NetMusicDisplay source 构建酷狗歌词上下文。
     * <p>
     * 流程：从 DisplayLinkContext 拿 sourcePos → level → BlockEntity →
     * 判定 TileEntityMusicPlayer → 取 CD → 判定酷狗歌曲（CD 上有 fileHash）→
     * 查缓存拿 LyricRecord → 计算 progress。
     * <p>
     * 返回值语义：
     * <ul>
     *   <li>返回 null：不是酷狗歌曲或前置条件不满足（非音乐机、无 CD、未播放），
     *       调用方应放行原方法（让 NetMusicDisplay 走网易云路径）</li>
     *   <li>返回非 null 但 record == null：是酷狗歌曲但歌词尚未加载，
     *       调用方应 cancel 并返回"歌词加载中..."</li>
     *   <li>返回非 null 且 record != null：调用方应 cancel 并返回当前歌词行</li>
     * </ul>
     */
    public static KuGouLyricContext getKuGouContext(BlockPos sourcePos, Level level) {
        if (sourcePos == null || level == null) return null;
        BlockEntity be = level.getBlockEntity(sourcePos);
        if (!(be instanceof TileEntityMusicPlayer)) return null;
        TileEntityMusicPlayer player = (TileEntityMusicPlayer) be;

        ItemStack cd = player.getPlayerInv().getStackInSlot(0);
        if (cd.isEmpty()) return null;

        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null || info.songUrl == null || info.songName == null) return null;

        if (!player.isPlay()) return null; // 让原方法返回 "~"

        // 判定是否酷狗歌曲：CD 上有 fileHash 就是
        Optional<CdAddonData> optData = CdNbtHelper.readOriginalInfo(cd);
        if (optData.isEmpty()) return null; // 不是酷狗，放行原方法

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

    /** 酷狗歌词上下文。record 可能为 null（缓存未就绪）。 */
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
