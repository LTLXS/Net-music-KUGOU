package com.github.tartaricacid.netmusic.kugou.mixin.compat.display;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.netmusicdisplay.source.LyricCache;
import com.netmusicdisplay.source.NetMusicTransLyricSource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 NetMusicTransLyricSource.provideLine 入口拦截酷狗翻译歌词。
 */
@Mixin(value = NetMusicTransLyricSource.class, remap = false)
public class NetMusicTransLyricSourceDisplayCompat {

    @Inject(method = "provideLine", at = @At("HEAD"), remap = false, cancellable = true, require = 0)
    private void kugou$handleTransLyric(DisplayLinkContext context, DisplayTargetStats stats,
                                        CallbackInfoReturnable<MutableComponent> cir) {
        try {
            KuGouDisplayCompat.KuGouLyricContext ctx =
                    KuGouDisplayCompat.getKuGouContext(context.getSourcePos(), context.level());
            if (ctx == null) return;

            if (ctx.record == null) {
                cir.setReturnValue(Component.literal("歌词加载中..."));
                return;
            }
            if (!LyricCache.hasTranslation(ctx.record)) {
                cir.setReturnValue(Component.literal("无翻译歌词"));
                return;
            }
            String line = LyricCache.getCurrentTransLyricLine(ctx.record, ctx.progress);
            cir.setReturnValue((line == null || line.isEmpty())
                    ? Component.literal("~")
                    : Component.literal(line));
        } catch (Throwable ignored) {
        }
    }
}
