package com.github.tartaricacid.netmusic.kugou.mixin.compat.display;

import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.netmusicdisplay.source.NetMusicTransLyricSource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 NetMusicDisplay 的"翻译歌词"展示源在播放酷狗 CD 时显示酷狗翻译。
 * 无翻译时回退到主歌词行（与 1.21.1 行为一致）。
 */
@Mixin(NetMusicTransLyricSource.class)
public class NetMusicTransLyricSourceDisplayCompat {
    @Inject(method = "provideLine", at = @At("HEAD"), remap = false, cancellable = true, require = 0)
    private void kugou$handleTransLyric(DisplayLinkContext context, DisplayTargetStats stats,
                                        CallbackInfoReturnable<MutableComponent> cir) {
        try {
            BlockPos sourcePos = context.getSourcePos();
            KuGouDisplayCompat.KuGouLyricContext ctx =
                    KuGouDisplayCompat.getKuGouContext(sourcePos, context.level());
            if (ctx == null) return;
            String trans = KuGouDisplayCompat.currentTransLine(ctx.record, ctx.progress);
            if (trans == null || trans.isEmpty()) {
                String line = KuGouDisplayCompat.currentLyricLine(ctx.record, ctx.progress);
                cir.setReturnValue(Component.literal(line != null ? line : Component.translatable("netmusic_kugou.display.lyric_loading").getString()));
            } else {
                cir.setReturnValue(Component.literal(trans));
            }
        } catch (Throwable ignored) {
        }
    }
}
