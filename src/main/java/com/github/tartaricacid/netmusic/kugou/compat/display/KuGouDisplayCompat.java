package com.github.tartaricacid.netmusic.kugou.compat.display;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.support.CdAddonData;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
     * source mixin 在调用前 set，LyricCache mixin 读取后立即 clear。
     */
    private static final ThreadLocal<BlockPos> CURRENT_POS = new ThreadLocal<>();

    /** fileHash → LyricRecord。本进程内同源同 hash 复用。 */
    private static final ConcurrentHashMap<String, LyricRecord> LYRIC_BY_HASH = new ConcurrentHashMap<>();

    /**
     * pos → fileHash（最近一次写入的）。同一个音乐机可能切换歌曲，
     * 用 put 覆盖。
     */
    private static final ConcurrentHashMap<BlockPos, String> HASH_BY_POS = new ConcurrentHashMap<>();

    /**
     * 音乐机当前正在播放的歌曲信息。
     * <p>
     * 当播放列表切歌、netMusicList 模式，或槽位 0 的 CD 与实际播放歌曲不一致时，
     * 这个字段保证显示牌跟随"正在播放的歌曲"而不是"槽位 0 的 CD"。
     * 每次 setPlayToClient / MusicToClientMessage 都会刷新它。
     */
    private static final ConcurrentHashMap<BlockPos, ActiveSongInfo> ACTIVE_SONG_BY_POS = new ConcurrentHashMap<>();

    /**
     * 每首歌「播放会话」的总时长（tick）与起始剩余 tick：pos → 值。
     * <p>
     * 进度双路自适应（见 {@link #computeProgress}）：
     * <ul>
     *   <li>本地 {@code currentTime} 递减（服务端，音乐机 BE 有 ticker）→
     *       {@code progress = 会话总时长 - currentTime}，与网易云原生一致。</li>
     *   <li>{@code currentTime} 冻结（客户端无 ticker，只在开始/结束同步）→ 墙钟推算。</li>
     * </ul>
     * 「会话总时长」取歌曲开始那一刻的精确值：收到 {@code MusicToClientMessage}
     * （{@link #markPlayStart}）/ 服务端 {@code setPlayToClient}（{@link #notePlayStart}）
     * 记下；并以 {@code currentTime} 跳变兜底重新测算。切歌/重放/自然循环都从 0 计时，
     * 暂停恢复保留进度。
     */
    private static final ConcurrentHashMap<BlockPos, Integer> SESSION_TOTAL_BY_POS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Integer> SESSION_START_BY_POS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Integer> LAST_CT_BY_POS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Boolean> PENDING_RESET_BY_POS = new ConcurrentHashMap<>();

    /**
     * 墙钟锚点：歌曲开始时记录「当前墙钟毫秒」与「那一刻的 currentTime」。
     * 用于客户端冻结场景（客户端 BE 没有 ticker，currentTime 只在歌曲开始/结束时同步一次，
     * 期间冻结）——此时进度改用墙钟推算；服务端场景 currentTime 会本地递减，用精确差值。
     */
    private static final ConcurrentHashMap<BlockPos, Long> ANCHOR_MS_BY_POS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Integer> ANCHOR_CT_BY_POS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Integer> DIAG_COUNT_BY_POS = new ConcurrentHashMap<>();

    /**
     * 每次 {@link #notePlayStart} 的墙钟时刻。用于「重放窗口」放宽：重放发起后的极短时间内，
     * 即使父模组异步 resolve 还没把 {@code isPlay} 置回 true，也先返回上下文让翻牌板立刻回到第 0 行，
     * 避免「掐掉重放歌词不回正」（翻牌板在重放窗口里冻在上一句）。
     */
    private static final ConcurrentHashMap<BlockPos, Long> REPLAY_AT_BY_POS = new ConcurrentHashMap<>();
    /** 重放窗口长度（毫秒）：覆盖 netMusicList 解析器偶发的 9~17s 异步 resolve 延迟；
     *  超过则视为真正停止，翻牌板应冻结在最后一句（不再误当重放）。 */
    private static final long REPLAY_WINDOW_MS = 8000L;

    /**
     * 当前正在播放的歌曲信息（不依赖槽位 0）。
     */
    public static final class ActiveSongInfo {
        public final String fileHash;
        public final String songName;
        public final int songTime; // 秒

        public ActiveSongInfo(String fileHash, String songName, int songTime) {
            this.fileHash = fileHash;
            this.songName = songName;
            this.songTime = songTime;
        }
    }

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
     * 注册音乐机当前正在播放的歌曲信息。
     * <p>
     * 来源：服务端 setPlayToClient 注入 / 客户端 MusicToClientMessage 注入。
     */
    public static void registerActiveSong(BlockPos pos, String fileHash, String songName, int songTime) {
        if (pos == null || fileHash == null || fileHash.isEmpty()) return;
        ACTIVE_SONG_BY_POS.put(pos, new ActiveSongInfo(fileHash, songName, songTime));
        registerPos(pos, fileHash);
    }

    /**
     * 从 {@code netmusiclib://source/kugou?id=hash&duration=...} 中提取 hash（即 id 参数）。
     * 失败返回 null。
     */
    public static String extractHashFromNetmusiclibUrl(String url) {
        if (url == null || !url.startsWith("netmusiclib://source/kugou")) return null;
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query == null || query.isEmpty()) return null;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && "id".equals(kv[0])) {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 服务端 Mixin 在 setPlayToClient 命中预取并写好歌词后调：
     * 把 fileHash 和 LyricRecord 同时落库，方便 LyricCache mixin 查询。
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

        if (!player.isPlay()) {
            // isPlay 为 false 是「翻牌板冻结、重放歌词不回正」的头号原因：
            // 它只在 setPlayToClient 的【异步回调】里被置 true，回调不跑就永远是 false。
            // 但重放刚发起时（notePlayStart 在 REPLAY_WINDOW_MS 内），即使 isPlay 还没回 true，
            // 我们也先返回上下文：computeProgress 会据此把进度归 0，翻牌板立刻回到第 0 行，
            // 不再冻在上一句。超过窗口仍 !isPlay 才视为真正停止，翻牌板冻结（正确行为）。
            long sinceReplay = System.currentTimeMillis() - REPLAY_AT_BY_POS.getOrDefault(sourcePos, 0L);
            if (sinceReplay > REPLAY_WINDOW_MS) {
                int n = DIAG_COUNT_BY_POS.getOrDefault(sourcePos, 0) + 1;
                DIAG_COUNT_BY_POS.put(sourcePos, n);
                if (n % 60 == 0) {
                    KuGouLogger.info("[KuGouProg] SKIP isPlay=false pos={} ct={} n={}",
                            sourcePos, player.getCurrentTime(), n);
                }
                return null;
            }
        } else {
            // isPlay 已为 true：说明已经真正起播（重放窗口结束）。清除重放标记，
            // 这样之后若用户手动停止/播完导致 isPlay 再次变 false，翻牌板能正确冻结在最后一句，
            // 而不会误判成"刚重放"又跳回第 0 行。
            REPLAY_AT_BY_POS.remove(sourcePos);
        }

        // === 优先使用「当前正在播放的歌曲」===
        // 这能修正：播放列表切歌、netMusicList 模式、槽位 0 与实际播放歌曲不一致
        // 以及重播时歌词不复原的问题。
        ActiveSongInfo active = ACTIVE_SONG_BY_POS.get(sourcePos);
        if (active != null && active.fileHash != null && !active.fileHash.isEmpty()) {
            LyricRecord record = getLyricByHash(active.fileHash);
            int currentTime = player.getCurrentTime();
            int progress = computeProgress(sourcePos, active.songTime, currentTime, player.isPlay(), level.isClientSide());
            return new KuGouLyricContext(record, progress,
                    active.songName != null ? active.songName : "", currentTime);
        }

        // === 回退：槽位 0 的 CD ===
        ItemStack cd = player.getPlayerInv().getStackInSlot(0);
        if (cd.isEmpty()) return null;

        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null || info.songUrl == null || info.songName == null) return null;

        Optional<CdAddonData> optData = CdNbtHelper.readOriginalInfo(cd);
        if (optData.isEmpty()) return null;

        String fileHash = optData.get().fileHash();
        if (fileHash == null || fileHash.isEmpty()) return null;

        // 确保 pos → fileHash 映射最新（切歌后覆盖）
        registerPos(sourcePos, fileHash);

        LyricRecord record = getLyricByHash(fileHash);

        int currentTime = player.getCurrentTime();
        int progress = computeProgress(sourcePos, info.songTime, currentTime, player.isPlay(), level.isClientSide());

        return new KuGouLyricContext(record, progress, info.songName, currentTime);
    }

    /**
     * 标记某台音乐机「当前歌曲开始播放」——<b>服务端</b>在 {@code setPlayToClient} 时调用。
     * <p>
     * 记录本机（服务端）该音乐机的精确总时长 {@code totalTicks}（即服务器刚写入
     * {@code currentTime} 的初值 = {@code songTime*20+64}），并打一个「待重置」标记，
     * 下一次计算进度时就会以这个精确总时长开启新会话，从而切歌 / 重放 / 自然循环都从 0 计时。
     * <p>
     * <b>必须在服务端落库</b>：显示源（NetMusicDisplay 的 provideText/provideLine）由 Create
     * 在服务端求值，{@link #getKuGouContext} 也跑在服务端、读的是服务端在跑的 {@code currentTime}。
     * 如果落库写在客户端（如 {@link #markPlayStart}），那是另一个 JVM 的静态 Map，服务端根本读不到，
     * 进度就只能退回「首次观测到的 currentTime」当总时长 → 整体偏慢、重放也难归零。
     *
     * @param totalTicks 歌曲总时长（tick），调用方传 {@code songTime*20+64}。
     */
    public static void notePlayStart(BlockPos pos, int totalTicks) {
        if (pos == null) return;
        SESSION_TOTAL_BY_POS.put(pos, totalTicks);
        PENDING_RESET_BY_POS.put(pos, Boolean.TRUE);
        REPLAY_AT_BY_POS.put(pos, System.currentTimeMillis());
        // 打点：确认「重放时 setPlayToClient 到底有没有被调用」。
        // 注意这只代表【发起】播放，不代表起播成功——isPlay 要等异步 resolve 回调才会置 true。
        KuGouLogger.info("[KuGouProg] notePlayStart pos={} totalTicks={}", pos, totalTicks);
    }

    /**
     * 客户端辅助标记（仅写入客户端 JVM 的静态 Map，对服务端进度计算无效，保留作冗余/将来客户端求值用）。
     *
     * @param durationSec 歌曲时长（秒），来自消息的 {@code timeSecond}。
     */
    public static void markPlayStart(BlockPos pos, int durationSec) {
        if (pos == null) return;
        int total = (durationSec > 0) ? durationSec * 20 + 64 : 0;
        SESSION_TOTAL_BY_POS.put(pos, total);
        PENDING_RESET_BY_POS.put(pos, Boolean.TRUE);
    }

    /**
     * 计算歌词进度（已播放 tick）。
     * <p>
     * 双路自适应，不依赖"到底在哪一侧执行"：
     * <ul>
     *   <li><b>本地 currentTime 在递减</b>（服务端：音乐机 BE 有 ticker，每 tick -1）→
     *       进度 = {@code 会话总时长 - currentTime}，精确、与网易云原生一致。</li>
     *   <li><b>currentTime 冻结</b>（客户端：BE 无 ticker，只在歌曲开始/结束时同步一次）→
     *       进度 = {@code (now - 歌曲开始墙钟)/50}，靠收到消息时落的锚点推算。</li>
     * </ul>
     * 会话总时长 / 墙钟锚点在以下任一情况重新确定：收到 {@code MusicToClientMessage}
     * （{@link #markPlayStart}）/ 服务端 {@code setPlayToClient}（{@link #notePlayStart}）
     * 记下精确总时长与"待重置"；或 {@code currentTime} 跳变上升（服务器归零/切歌/自然循环）；
     * 或本机首次观测到该音乐机。暂停恢复时 currentTime 不变 → 不重置 → 进度保留。
     */
    private static int computeProgress(BlockPos pos, int songTimeSec, int currentTime,
                                       boolean isPlaying, boolean clientSide) {
        boolean reset = false;
        Boolean pend = PENDING_RESET_BY_POS.get(pos);
        Integer last = LAST_CT_BY_POS.get(pos);
        if (pend != null && pend) {
            reset = true;
            PENDING_RESET_BY_POS.remove(pos);
        }
        if (last == null) {
            reset = true; // 首次观测
        } else if (currentTime > last + 5) {
            reset = true; // 跳变上升 = 服务器归零/切歌/自然循环
        }
        // 重放/重启安全网：正常播放时 currentTime 落到 <16 之前 isPlay 已变 false，
        // 因此"isPlay 为 true 且 currentTime 已到 0"只可能出现在「重放却没重置计时器」的场景，
        // 此时强制归零，避免"重放三次歌词回不正"。
        if (isPlaying && currentTime <= 1) {
            reset = true;
        }
        if (reset) {
            // 权威总时长：一律优先用歌曲真实时长 songTimeSec*20+64（两侧通用）。
            //
            // 【关键】绝不能用「本次求值时观测到的 currentTime」当总时长！
            // 显示牌可能在歌曲开始很久之后才第一次成功求值：期间 isPlay=false，
            // getKuGouContext 直接返回 null，但服务端 ticker 仍让 currentTime 每 tick 递减。
            // 实测日志：真值 total=3424（songTimeSec=168），首次求值时 ct 已掉到 2615，
            // 若拿 2615 当总时长，progress 就从 0 重新开始 → 翻牌板永久滞后 40 秒，
            // 重放时也按错误基准归零（"歌词不回正"）。
            int songTotal = (songTimeSec > 0) ? songTimeSec * 20 + 64 : 0;
            int newStart;
            if (songTotal > 0) {
                newStart = songTotal;
                SESSION_TOTAL_BY_POS.put(pos, songTotal);
            } else {
                // 拿不到真实时长（如 netMusicList 侧 duration=0）时的退化路径
                Integer total = SESSION_TOTAL_BY_POS.get(pos);
                if (total != null && total > 0) {
                    newStart = total;
                } else {
                    newStart = Math.max(currentTime, 0);
                    SESSION_TOTAL_BY_POS.put(pos, newStart);
                }
            }
            SESSION_START_BY_POS.put(pos, newStart);
            ANCHOR_CT_BY_POS.put(pos, currentTime);
            ANCHOR_MS_BY_POS.put(pos, System.currentTimeMillis());
            // 只在「消息驱动（切歌/重放/自然循环）」的重置打日志，避免安全网每 tick 刷屏
            if (pend != null && pend) {
                KuGouLogger.info("[KuGouProg] reset side={} pos={} ct={} newStart={} songTotal={} songTimeSec={}",
                        clientSide ? "CLIENT" : "SERVER",
                        pos, currentTime, newStart, songTotal, songTimeSec);
            }
        }
        LAST_CT_BY_POS.put(pos, currentTime);

        Integer base = SESSION_START_BY_POS.get(pos);
        int start = (base != null) ? base : currentTime;
        Integer anchorCt = ANCHOR_CT_BY_POS.get(pos);
        long anchorMs = ANCHOR_MS_BY_POS.getOrDefault(pos, 0L);

        int progress;
        if (clientSide) {
            // 客户端：currentTime 冻结，用墙钟推算（锚点是收到 MusicToClientMessage 的时刻）
            progress = (anchorMs > 0)
                    ? (int) ((System.currentTimeMillis() - anchorMs) / 50L)
                    : (start - currentTime);
        } else {
            // 服务端：currentTime 每 tick 递减，用精确差值
            progress = start - currentTime;
        }
        // 注意：这里【不要】再用 Math.min(progress, 总时长) 截断。
        // 一旦存的总时长偏小（info.songTime / resolved.songTime 与真实音频不符），
        // 进度会被永久卡死在上限，翻牌板就冻在某个早期行"跟不上"。
        // 歌词本身是用 findFloorKey(progress) 查表的，超过末尾只是停到最后一行，无害。
        progress = Math.max(0, progress);

        // 诊断：每 30 次采样一次
        int diag = DIAG_COUNT_BY_POS.getOrDefault(pos, 0) + 1;
        DIAG_COUNT_BY_POS.put(pos, diag);
        if (diag % 30 == 0) {
            KuGouLogger.info("[KuGouProg] sample side={} pos={} ct={} anchorCt={} start={} progress={} total={}",
                    clientSide ? "CLIENT" : "SERVER",
                    pos, currentTime, anchorCt, start, progress, SESSION_TOTAL_BY_POS.get(pos));
        }
        return progress;
    }

    /**
     * 按进度 tick 取歌词原文行。
     * <p>
     * 刻意<b>不</b>用 {@code com.netmusicdisplay.source.LyricCache#getCurrentLyricLine}：
     * 那个类是 NetMusicDisplay 的（可选依赖），引用它会让「只装 netMusicList、没装 NetMusicDisplay」
     * 的环境在加载本类时抛 NoClassDefFoundError。这里直接用 fastutil 排序表查，零额外依赖。
     */
    public static String currentLyricLine(LyricRecord record, int progress) {
        if (record == null) return null;
        return lineAt(record.getLyrics(), progress);
    }

    /** 按进度 tick 取歌词翻译行（无翻译返回 null）。 */
    public static String currentTransLine(LyricRecord record, int progress) {
        if (record == null) return null;
        return lineAt(record.getTransLyrics(), progress);
    }

    /** 该歌词是否带翻译行。 */
    public static boolean hasTranslation(LyricRecord record) {
        if (record == null) return false;
        Int2ObjectSortedMap<String> trans = record.getTransLyrics();
        return trans != null && !trans.isEmpty();
    }

    /**
     * 取「key 不大于 progress」的最大那一行。
     * <p>
     * 这里刻意遍历而不调 {@code findFloorKey}：该方法是 fastutil 新版本 API，
     * 当前依赖的 fastutil 里 {@code Int2ObjectSortedMap} 并没有它（编译直接报错）。
     * 歌词行数通常只有几百，且显示源每秒最多求几次值，线性遍历开销可忽略。
     */
    private static String lineAt(Int2ObjectSortedMap<String> map, int progress) {
        if (map == null || map.isEmpty()) return null;
        int bestKey = Integer.MIN_VALUE;
        String best = null;
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            int k = e.getKey();
            if (k <= progress && k >= bestKey) {
                bestKey = k;
                best = e.getValue();
            }
        }
        // progress 比所有 key 都小时返回 null（歌词还没到第一句）
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
