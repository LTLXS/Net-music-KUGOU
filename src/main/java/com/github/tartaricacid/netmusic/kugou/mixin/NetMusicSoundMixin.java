package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.lyric.LyricInjectCache;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在父模组 {@link NetMusicSound} 构造器尾部检查是否有 addon 注入的歌词缓存，
 * 有则替换 {@code this.lyricRecord}。
 * <p>
 * <b>CD 机重放不复位 —— 根因（由日志证实）：</b>
 * <pre>
 *   [KuGouProg] NetMusicSound 创建 pos=... 歌词=无 行数=0    ← 我们的 LyricInjectCache 没命中
 *   [KuGouProg] CD机状态 ... 剩余行数=48                      ← 但声音带着 48 行歌词
 * </pre>
 * 即：netMusicList 模式下歌词是 <b>netMusicList 自己塞进 NetMusicSound</b> 的，
 * 我们的注入链在这条路上不参与；且重放时会 <b>新建</b> NetMusicSound（不是复用）。
 * <p>
 * 而父模组 {@code LyricRecord.updateCurrentLine(tick)} 是<b>破坏性裁剪</b>（把已过的行从 map 删掉），
 * netMusicList 又极可能<b>按歌缓存并复用同一个 LyricRecord 对象</b>：
 * 第一次播完该对象被裁到只剩末几行，重放时把"已裁剪的同一个对象"再交给新声音 →
 * 新声音一上来首行就是末尾 → CD 机停在最后一句（"不回正"）。
 * <p>
 * <b>对策：</b>在本声音第一次 tick（记录尚未被裁剪）时与"基线"比对：
 * <ul>
 *   <li>行数 &lt; 基线 → 拿到的是被裁剪复用的共享对象 → 用保存的未裁剪副本恢复，CD 机回到第 0 行；</li>
 *   <li>否则视为新歌/首播 → 更新基线。</li>
 * </ul>
 * 另保留"剩余时间回跳"（重放）时的 tick 归零 + 重喂未裁剪歌词作为第二道保险。
 * <p>
 * <b>注意（踩过的坑）：</b>本类所在的 {@code ...kugou.mixin.*} 是 {@code netmusic_kugou.mixins.json}
 * 声明的 mixin 包，Mixin 禁止在其中定义并被直接引用的<b>非 mixin 类</b>（含嵌套类），
 * 否则抛 {@code IllegalClassLoadError} 导致整个 mixin 转换失败（表现为"完全没声音"）。
 * 所以这里一律不新增类，只使用外部包类型 + 平行 Map 保存状态。
 */
@Mixin(value = NetMusicSound.class, remap = false)
public class NetMusicSoundMixin {

    @Shadow(remap = false)
    private int tick;

    /**
     * 缓存父模组 {@link NetMusicSound} 的私有字段，避免在每帧 tick 里反复
     * {@code getDeclaredField + setAccessible}（带安全检查的反射开销很大）。
     */
    private static final Field POS_FIELD;
    private static final Field LYRIC_RECORD_FIELD;
    private static final Field TICK_TIMES_FIELD;

    static {
        Field p = null, l = null, t = null;
        try {
            p = NetMusicSound.class.getDeclaredField("pos");
            p.setAccessible(true);
            l = NetMusicSound.class.getDeclaredField("lyricRecord");
            l.setAccessible(true);
            t = NetMusicSound.class.getDeclaredField("tickTimes");
            t.setAccessible(true);
        } catch (NoSuchFieldException e) {
            KuGouLogger.warn("KuGou lyric: failed to cache NetMusicSound reflection fields: {}", e.getMessage());
        }
        POS_FIELD = p;
        LYRIC_RECORD_FIELD = l;
        TICK_TIMES_FIELD = t;
    }

    private Object readPos() {
        try {
            return POS_FIELD != null ? POS_FIELD.get(this) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 每个位置的"未裁剪歌词基线"，用于识别并修复被裁剪复用的共享 LyricRecord。 */
    private static final ConcurrentHashMap<BlockPos, LyricRecord> BASELINE_BY_POS = new ConcurrentHashMap<>();
    /** 基线对应的歌曲身份（歌名@总时长tick），用于区分换歌与同歌重放。 */
    private static final ConcurrentHashMap<BlockPos, String> BASELINE_SONG_BY_POS = new ConcurrentHashMap<>();

    /** 上一次观测到的"剩余时间"（client TE 同步值），用于检测重放回跳 */
    private int netmusickugou$lastRemain = -1;

    /** 本实例对应的【未裁剪】歌词副本：重放时重新喂给声音，让它从第 0 行开始 */
    private LyricRecord netmusickugou$freshRecord = null;

    /** 本实例是否已做过"基线比对"（每个声音实例只做一次） */
    private boolean netmusickugou$baselineChecked = false;

    /** 本实例的诊断采样计数 */
    private final AtomicInteger netmusickugou$sample = new AtomicInteger(0);

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void netmusickugou$afterInit(CallbackInfo ci) {
        try {
            Object rawPos = readPos();

            LyricRecord cached = (rawPos instanceof BlockPos) ? LyricInjectCache.take((BlockPos) rawPos) : null;
            if (cached != null) {
                writeLyricRecord(cached);
                netmusickugou$freshRecord = cloneLyricRecord(cached);
                KuGouLogger.info("KuGou lyric: replaced NetMusicSound.lyricRecord with {} lines",
                        cached.getLyrics() != null ? cached.getLyrics().size() : 0);
            }

            if (rawPos instanceof BlockPos pos) {
                LyricRecord rec = readLyricRecord();
                KuGouLogger.info("[KuGouProg] NetMusicSound 创建 pos={} 歌词={} 行数={}",
                        pos, (cached != null ? "已注入" : "无"),
                        rec != null && rec.getLyrics() != null ? rec.getLyrics().size() : 0);
            }
        } catch (Exception e) {
            KuGouLogger.warn("KuGou lyric: failed to inject lyric into NetMusicSound: {}", e.getMessage());
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void netmusickugou$tickHead(CallbackInfo ci) {
        try {
            Object rawPos = readPos();
            if (!(rawPos instanceof BlockPos pos)) return;

            Level level = Minecraft.getInstance().level;
            if (level == null) return;
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof TileEntityMusicPlayer te)) return;

            int remain = te.getCurrentTime();

            // === 主修复：首帧基线比对 ===
            // 此时本声音还没调过 updateCurrentLine，记录处于"到手时的原始状态"。
            // 若行数少于基线 → 说明 netMusicList 把"上轮已被裁剪的共享 LyricRecord"又交了回来。
            if (!netmusickugou$baselineChecked) {
                netmusickugou$baselineChecked = true;
                LyricRecord rec = readLyricRecord();
                if (rec != null && rec.getLyrics() != null && !rec.getLyrics().isEmpty()) {
                    int curSize = rec.getLyrics().size();
                    String songId = songIdOf(te);
                    String baseSong = BASELINE_SONG_BY_POS.get(pos);
                    LyricRecord base = BASELINE_BY_POS.get(pos);
                    if (base != null && baseSong != null && baseSong.equals(songId)) {
                        int baseSize = (base.getLyrics() == null) ? 0 : base.getLyrics().size();
                        if (curSize < baseSize) {
                            LyricRecord restored = cloneLyricRecord(base);
                            writeLyricRecord(restored);
                            netmusickugou$freshRecord = cloneLyricRecord(base);
                            KuGouLogger.info("[KuGouProg] 重放歌词已从头恢复: pos={} 到手行数={} -> 基线行数={}",
                                    pos, curSize, baseSize);
                        } else {
                            netmusickugou$freshRecord = cloneLyricRecord(rec);
                        }
                    } else {
                        BASELINE_BY_POS.put(pos, cloneLyricRecord(rec));
                        BASELINE_SONG_BY_POS.put(pos, songId);
                        netmusickugou$freshRecord = cloneLyricRecord(rec);
                        KuGouLogger.info("[KuGouProg] 歌词基线已建立: pos={} 歌={} 行数={}", pos, songId, curSize);
                    }
                }
            }

            // === 第二道保险：剩余时间回跳（重放）→ tick 归零 + 重喂未裁剪歌词 ===
            if (netmusickugou$lastRemain >= 0 && tick > 50 && remain > netmusickugou$lastRemain + 100) {
                LyricRecord fresh = cloneLyricRecord(netmusickugou$freshRecord);
                if (fresh != null) {
                    writeLyricRecord(fresh);
                    tick = 0;
                    KuGouLogger.info("[KuGouProg] CD机重放复位(回跳路径): pos={} remain={} 上帧={}",
                            pos, remain, netmusickugou$lastRemain);
                }
            }
            netmusickugou$lastRemain = remain;

            // === 诊断 ===
            boolean clobbered = (te.lyricRecord != null) && (te.lyricRecord != readLyricRecord());
            if (clobbered) {
                KuGouLogger.warn("[KuGouProg] 歌词被抢写: pos={} te.lyricRecord 来自别的 NetMusicSound "
                        + "(本实例 tick={} remain={})", pos, tick, remain);
            }
            int n = netmusickugou$sample.incrementAndGet();
            if (n % 100 == 1 || n <= 3) {
                LyricRecord rec = readLyricRecord();
                KuGouLogger.info("[KuGouProg] CD机状态 pos={} tick={} remain={} 显示中={} 剩余行数={} 首行key={}",
                        pos, tick, remain, (te.lyricRecord == rec),
                        rec != null && rec.getLyrics() != null ? rec.getLyrics().size() : -1,
                        rec != null && rec.getLyrics() != null && !rec.getLyrics().isEmpty()
                                ? rec.getLyrics().firstIntKey() : -1);
            }
        } catch (Throwable ignored) {
            // 任何反射/读取异常都不应影响播放
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void netmusickugou$onTickTail(CallbackInfo ci) {
        try {
            Object rawPos = readPos();

            LyricRecord cached = (rawPos instanceof BlockPos) ? LyricInjectCache.take((BlockPos) rawPos) : null;
            if (cached != null) {
                writeLyricRecord(cached);
                netmusickugou$freshRecord = cloneLyricRecord(cached);
                KuGouLogger.info("KuGou lyric: late-injected lyricRecord ({} lines) into NetMusicSound at tick",
                        cached.getLyrics() != null ? cached.getLyrics().size() : 0);
            }
        } catch (Exception e) {
            KuGouLogger.warn("KuGou lyric: late inject in tick failed: {}", e.getMessage());
        }
    }

    /** 歌曲身份：歌名 + 时长 tick，用于判断"换歌"还是"同歌重放" */
    private String songIdOf(TileEntityMusicPlayer te) {
        String name = "?";
        try {
            ItemStack cd = te.getPlayerInv().getStackInSlot(0);
            if (!cd.isEmpty()) {
                com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo info =
                        com.github.tartaricacid.netmusic.item.ItemMusicCD.getSongInfo(cd);
                if (info != null && info.songName != null) name = info.songName;
            }
        } catch (Throwable ignored) {
        }
        return name + "@" + readTickTimes();
    }

    /** 读取本声音的 tickTimes（歌曲总时长 tick），作为歌曲身份的一部分 */
    private int readTickTimes() {
        try {
            Object v = TICK_TIMES_FIELD != null ? TICK_TIMES_FIELD.get(this) : null;
            return (v instanceof Integer) ? (Integer) v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void writeLyricRecord(LyricRecord rec) throws Exception {
        if (LYRIC_RECORD_FIELD == null) throw new IllegalStateException("lyricRecord field not cached");
        LYRIC_RECORD_FIELD.set(this, rec);
    }

    private LyricRecord readLyricRecord() throws Exception {
        if (LYRIC_RECORD_FIELD == null) return null;
        return (LyricRecord) LYRIC_RECORD_FIELD.get(this);
    }

    /** 深拷贝（map 层面）一份歌词，使裁剪不影响原件。String 不可变，浅拷贝值即可。 */
    private static LyricRecord cloneLyricRecord(LyricRecord src) {
        if (src == null) return null;
        Int2ObjectSortedMap<String> lyrics = src.getLyrics();
        Int2ObjectSortedMap<String> trans = src.getTransLyrics();
        Int2ObjectSortedMap<String> lc = (lyrics == null) ? null : new Int2ObjectLinkedOpenHashMap<>(lyrics);
        Int2ObjectSortedMap<String> tc = (trans == null) ? null : new Int2ObjectLinkedOpenHashMap<>(trans);
        return new LyricRecord(lc, tc);
    }
}
