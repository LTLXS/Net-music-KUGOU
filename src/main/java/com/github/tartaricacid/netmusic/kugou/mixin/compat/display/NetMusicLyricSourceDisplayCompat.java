package com.github.tartaricacid.netmusic.kugou.mixin.compat.display;

import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.netmusicdisplay.source.NetMusicLyricSource;
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
 * 让 NetMusicDisplay 的"单行歌词"展示源在播放酷狗 CD 时显示酷狗歌词。
 * 不再依赖 LyricCacheDisplayCompat（LyricCache 类在 mixin prepare 前就已加载，
 * 用类引用会导致 NetMusicDisplay 缺失时 Mixin 加载崩溃）——这里直接从 KuGouDisplayCompat 取数据。
 */
@Mixin(NetMusicLyricSource.class)
public class NetMusicLyricSourceDisplayCompat {
    @Inject(method = "provideLine", at = @At("HEAD"), remap = false, cancellable = true, require = 0)
    private void kugou$handleLyric(DisplayLinkContext context, DisplayTargetStats stats,
                                   CallbackInfoReturnable<MutableComponent> cir) {
        try {
            BlockPos sourcePos = context.getSourcePos();
            KuGouDisplayCompat.KuGouLyricContext ctx =
                    KuGouDisplayCompat.getKuGouContext(sourcePos, context.level());
            if (ctx == null) return;
            String line = KuGouDisplayCompat.currentLyricLine(ctx.record, ctx.progress);
            if (line == null || line.isEmpty()) {
                cir.setReturnValue(Component.translatable("netmusic_kugou.display.lyric_loading"));
            } else {
                cir.setReturnValue(Component.literal(line));
            }
        } catch (Throwable ignored) {
            // 任何异常都放行原方法，避免把展示源打挂
        }
    }
}
