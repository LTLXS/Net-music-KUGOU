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
 在父模组 NetMusicSound 构造器尾部检查是否有 addon 注入的歌词缓存，有则替换 this.lyricRecord。
 背景：netMusicList 模式下歌词由 netMusicList 写入 NetMusicSound，本插件注入链不参与；
 重放时会新建 NetMusicSound，而父模组 LyricRecord.updateCurrentLine 是破坏性裁剪，
 netMusicList 按歌缓存复用同一 LyricRecord，重放时传入的已是只剩末几行的对象，
 导致 CD 机停在最后一句。
 对策：本声音第一次 tick 时与基线比对，行数 < 基线则用未裁剪副本恢复；
 另在剩余时间回跳（重放）时 tick 归零 + 重喂未裁剪歌词。
 注意：mixin 包内禁止定义会被直接引用的非 mixin 类（含嵌套类），
 否则抛 IllegalClassLoadError 导致 mixin 转换失败；本类只用外部包类型 + 平行 Map 保存状态。*/
@Mixin(value = NetMusicSound.class, remap = false)
public class NetMusicSoundMixin {

    @Shadow(remap = false)
    private int tick;

    /**
 缓存父模组 NetMusicSound 的私有字段，避免在每帧 tick 里反复
 getDeclaredField + setAccessible（带安全检查的反射开销很大）。
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
    private static final ConcurrentHashMap<BlockPos, String> BASELINE_SONG_BY_POS = new ConcurrentHashMap<>();

    /** 上一次观测到的"剩余时间"（client TE 同步值），用于检测重放回跳 */
    private int netmusickugou$lastRemain = -1;

    private LyricRecord netmusickugou$freshRecord = null;

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
