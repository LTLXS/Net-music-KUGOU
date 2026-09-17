package com.github.tartaricacid.netmusic.kugou.mixin.compat.display;

import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.netmusicdisplay.source.NetMusicAllInOneSource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 让 NetMusicDisplay 的"全部合一"展示源（歌名 + 主歌词 + 翻译）在播放酷狗 CD 时显示酷狗歌词。
 * 第一行为主歌词，第二行为翻译（若有）。
 */
@Mixin(NetMusicAllInOneSource.class)
public class NetMusicAllInOneSourceDisplayCompat {
    @Inject(method = "provideText", at = @At("HEAD"), remap = false, cancellable = true, require = 0)
    private void kugou$handleAllInOne(DisplayLinkContext context, DisplayTargetStats stats,
                                      CallbackInfoReturnable<List<MutableComponent>> cir) {
        try {
            BlockPos sourcePos = context.getSourcePos();
            KuGouDisplayCompat.KuGouLyricContext ctx =
                    KuGouDisplayCompat.getKuGouContext(sourcePos, context.level());
            if (ctx == null) return;
            List<MutableComponent> lines = new ArrayList<>();
            int remainingSeconds = Math.max(0, ctx.currentTime / 20);
            int min = remainingSeconds / 60;
            int sec = remainingSeconds % 60;
            lines.add(Component.literal(String.format("▶ %s [%d:%02d]", ctx.songName, min, sec)));
            String line = KuGouDisplayCompat.currentLyricLine(ctx.record, ctx.progress);
            lines.add(Component.literal((line != null && !line.isEmpty()) ? line : "~"));
            if (KuGouDisplayCompat.hasTranslation(ctx.record)) {
                String trans = KuGouDisplayCompat.currentTransLine(ctx.record, ctx.progress);
                lines.add(Component.literal((trans != null && !trans.isEmpty()) ? trans : "~"));
            }
            cir.setReturnValue(lines);
        } catch (Throwable ignored) {
        }
    }
}
