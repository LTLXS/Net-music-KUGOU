package com.github.tartaricacid.netmusic.kugou.mixin.compat.display;

import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.netmusicdisplay.source.LyricCache;
import com.netmusicdisplay.source.NetMusicAllInOneSource;
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

@Mixin(value = NetMusicAllInOneSource.class, remap = false)
public class NetMusicAllInOneSourceDisplayCompat {

    @Inject(method = "provideText", at = @At("HEAD"), remap = false, cancellable = true, require = 0)
    private void kugou$handleAllInOne(DisplayLinkContext context, DisplayTargetStats stats,
                                       CallbackInfoReturnable<List<MutableComponent>> cir) {
        try {
            KuGouDisplayCompat.KuGouLyricContext ctx =
                    KuGouDisplayCompat.getKuGouContext(context.getSourcePos(), context.level());
            if (ctx == null) return;

            List<MutableComponent> lines = new ArrayList<>();

            if (ctx.record == null) {
                lines.add(Component.literal(String.format("\u25B6 %s [--:--]", ctx.songName)));
                lines.add(Component.translatable("netmusic_kugou.display.lyric_loading"));
                cir.setReturnValue(lines);
                return;
            }

            int seconds = Math.max(0, ctx.currentTime / 20);
            int mm = seconds / 60;
            int ss = seconds % 60;
            lines.add(Component.literal(String.format("\u25B6 %s [%d:%02d]", ctx.songName, mm, ss)));

            String lyric = LyricCache.getCurrentLyricLine(ctx.record, ctx.progress);
            lines.add(Component.literal((lyric == null || lyric.isEmpty()) ? "~" : lyric));

            if (LyricCache.hasTranslation(ctx.record)) {
                String trans = LyricCache.getCurrentTransLyricLine(ctx.record, ctx.progress);
                lines.add(Component.literal((trans == null || trans.isEmpty()) ? "~" : trans));
            }

            cir.setReturnValue(lines);
        } catch (Throwable ignored) {
        }
    }
}
