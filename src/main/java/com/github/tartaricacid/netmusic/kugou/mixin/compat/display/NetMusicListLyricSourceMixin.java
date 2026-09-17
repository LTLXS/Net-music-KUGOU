package com.github.tartaricacid.netmusic.kugou.mixin.compat.display;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.gly091020.netMusicListNeoforge.create.musicPlayerSource.LyricSource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 接管 netMusicList 自带的 Create 显示链接源
 * {@code com.gly091020.netMusicListNeoforge.create.musicPlayerSource.LyricSource}。
 * <p>
 * <b>为什么要接管它</b>：netMusicList 自己实现了一套 Create 显示源，直接读
 * {@code TileEntityMusicPlayer} 的 {@code lyricRecord} / {@code getCurrentTime()} / {@code songTime}
 * 自行推算进度，<b>完全不走</b>本模组的 {@link KuGouDisplayCompat} 兼容层。
 * 结果就是：玩家把显示链接源选成 netMusicList 的那个时，我们的进度修复（总时长取权威
 * {@code songTimeSec}、重放归零）统统不生效，表现为「掐掉重放歌词不重置」。
 * <p>
 * <b>接管策略</b>：酷狗歌曲且歌词就绪时，用 {@link KuGouDisplayCompat#getKuGouContext} 算出的
 * {@code ctx.progress}（重放会正确归零）取当前行并 cancel 原实现；
 * 非酷狗歌曲 / 歌词未就绪时<b>不</b> cancel，交还 netMusicList 原逻辑，不影响它自己的源。
 * <p>
 * <b>依赖注意</b>：取行用 {@link KuGouDisplayCompat#currentLyricLine}，刻意避开
 * {@code com.netmusicdisplay.source.LyricCache}——那是 NetMusicDisplay 的可选类，
 * 引用它会让「只装 netMusicList、没装 NetMusicDisplay」的环境抛 NoClassDefFoundError。
 */
@Mixin(value = LyricSource.class, remap = false)
public class NetMusicListLyricSourceMixin {

    /** 调用计数，用于抽样打印，避免每次求值都刷日志。 */
    private static int callCount = 0;

    @Inject(method = "provideText", at = @At("HEAD"), remap = false, cancellable = true, require = 0)
    private void kugou$overrideLyric(DisplayLinkContext context, DisplayTargetStats stats,
                                     CallbackInfoReturnable<List<MutableComponent>> cir) {
        try {
            KuGouDisplayCompat.KuGouLyricContext ctx =
                    KuGouDisplayCompat.getKuGouContext(context.getSourcePos(), context.level());
            // ctx == null：不是酷狗歌曲 / 音乐机没在播放 → 放行，交还 netMusicList 原实现
            if (ctx == null || ctx.record == null) {
                if (++callCount % 20 == 0) {
                    KuGouLogger.info("[NMLLyric] PASS-THROUGH (原实现接管) pos={} ctxNull={} recNull={} n={}",
                            context.getSourcePos(), ctx == null,
                            ctx == null ? null : (ctx.record == null), callCount);
                }
                return;
            }

            List<MutableComponent> lines = new ArrayList<>();
            String lyric = KuGouDisplayCompat.currentLyricLine(ctx.record, ctx.progress);
            lines.add(Component.literal((lyric == null || lyric.isEmpty()) ? "~" : lyric));

            String trans = null;
            if (KuGouDisplayCompat.hasTranslation(ctx.record)) {
                trans = KuGouDisplayCompat.currentTransLine(ctx.record, ctx.progress);
                lines.add(Component.literal((trans == null || trans.isEmpty()) ? "~" : trans));
            }

            cir.setReturnValue(lines);

            if (++callCount % 10 == 0) {
                KuGouLogger.info("[NMLLyric] OVERRIDE pos={} progress={} line='{}' trans='{}' n={}",
                        context.getSourcePos(), ctx.progress, lyric, trans, callCount);
            }
        } catch (Throwable t) {
            KuGouLogger.warn("[NMLLyric] failed: {}", t.getMessage());
        }
    }
}
