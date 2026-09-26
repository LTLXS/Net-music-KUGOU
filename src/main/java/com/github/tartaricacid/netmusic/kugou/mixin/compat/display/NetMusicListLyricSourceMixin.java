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
 接管 netMusicList 自带的 Create 显示链接源
 com.gly091020.netMusicListNeoforge.create.musicPlayerSource.LyricSource。
 它自行从 lyricRecord / getCurrentTime() / songTime 推算进度，不走 KuGouDisplayCompat，
 本插件的进度修复（总时长取权威 songTimeSec、重放归零）不会生效。
 策略：酷狗歌曲且歌词就绪时用 getKuGouContext 的 ctx.progress 取行并 cancel 原实现；
 非酷狗或歌词未就绪时不 cancel，交还原逻辑。
 依赖注意：取行用 currentLyricLine，避免引用可选类 com.netmusicdisplay.source.LyricCache
 （缺失该模组的环境会抛 NoClassDefFoundError）。*/
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
