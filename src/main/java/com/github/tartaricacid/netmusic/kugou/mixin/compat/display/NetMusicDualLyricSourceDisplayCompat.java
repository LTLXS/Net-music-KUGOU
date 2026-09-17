package com.github.tartaricacid.netmusic.kugou.mixin.compat.display;

import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.netmusicdisplay.source.NetMusicDualLyricSource;
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

@Mixin(NetMusicDualLyricSource.class)
public class NetMusicDualLyricSourceDisplayCompat {
    @Inject(method = "provideText", at = @At("HEAD"), remap = false, cancellable = true, require = 0)
    private void kugou$handleDualLyric(DisplayLinkContext context, DisplayTargetStats stats,
                                        CallbackInfoReturnable<List<MutableComponent>> cir) {
        try {
            BlockPos sourcePos = context.getSourcePos();
            KuGouDisplayCompat.KuGouLyricContext ctx =
                    KuGouDisplayCompat.getKuGouContext(sourcePos, context.level());
            if (ctx == null) return;
            List<MutableComponent> lines = new ArrayList<>();
            String line = KuGouDisplayCompat.currentLyricLine(ctx.record, ctx.progress);
            lines.add(Component.literal((line != null && !line.isEmpty()) ? line : "~"));
            if (KuGouDisplayCompat.hasTranslation(ctx.record)) {
                String trans = KuGouDisplayCompat.currentTransLine(ctx.record, ctx.progress);
                lines.add(Component.literal((trans != null && !trans.isEmpty()) ? trans : "~"));
            } else {
                lines.add(Component.translatable("netmusic_kugou.display.no_translation"));
            }
            cir.setReturnValue(lines);
        } catch (Throwable ignored) {
        }
    }
}
