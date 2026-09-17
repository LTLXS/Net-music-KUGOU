package com.github.tartaricacid.netmusic.kugou.mixin.compat.display;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.netmusicdisplay.source.LyricCache;
import com.netmusicdisplay.source.NetMusicLyricSource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 NetMusicLyricSource.provideLine 入口拦截酷狗歌曲：
 * <ul>
 *   <li>非酷狗歌曲：放行原方法（走网易云路径）</li>
 *   <li>酷狗歌曲且歌词就绪：cancel 并返回当前歌词行</li>
 *   <li>酷狗歌曲但歌词未就绪：cancel 并返回"歌词加载中..."</li>
 * </ul>
 * 不再依赖 LyricCacheDisplayCompat（LyricCache 类在 mixin prepare 前就
 * 被 DisplayWindow.updateModuleReads 加载，无法 mixin）。
 */
@Mixin(value = NetMusicLyricSource.class, remap = false)
public class NetMusicLyricSourceDisplayCompat {

    @Inject(method = "provideLine", at = @At("HEAD"), remap = false, cancellable = true, require = 0)
    private void kugou$handleLyric(DisplayLinkContext context, DisplayTargetStats stats,
                                    CallbackInfoReturnable<MutableComponent> cir) {
        try {
            KuGouDisplayCompat.KuGouLyricContext ctx =
                    KuGouDisplayCompat.getKuGouContext(context.getSourcePos(), context.level());
            if (ctx == null) return;

            if (ctx.record == null) {
                cir.setReturnValue(Component.translatable("netmusic_kugou.display.lyric_loading"));
                return;
            }
            String line = LyricCache.getCurrentLyricLine(ctx.record, ctx.progress);
            cir.setReturnValue((line == null || line.isEmpty())
                    ? Component.literal("~")
                    : Component.literal(line));
        } catch (Throwable ignored) {
        }
    }
}
