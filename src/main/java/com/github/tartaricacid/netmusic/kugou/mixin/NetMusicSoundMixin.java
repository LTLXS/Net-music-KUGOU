package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.lyric.LyricInjectCache;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在父模组 {@link NetMusicSound} 构造器尾部检查是否有 addon 注入的歌词缓存，
 * 有则替换 {@code this.lyricRecord}。
 * <p>
 * 配合 {@link MusicToClientMessageMixin} 使用：
 * <ol>
 *   <li>MusicToClientMessageMixin.@Inject(HEAD) onHandle → 读 CD NBT LRC → 解析 → 存 {@link LyricInjectCache}</li>
 *   <li>父模组 onHandle（在 CompletableFuture.runAsync 后台线程）→ new NetMusicSound(...) → 调用本构造器</li>
 *   <li>本 Mixin @Inject(TAIL) → 从 {@link LyricInjectCache} 取缓存 → 替换 this.lyricRecord</li>
 * </ol>
 * <p>
 * 注意：本 Mixin 只注入构造器尾部，<b>绝不</b>注入 {@code tick()}。
 * 因为 1.5.1 发布版的 {@code NetMusicSound} 并未 override {@code tick()}，
 * 一旦写 {@code @Inject(method="tick")} 会导致整个 Mixin 因找不到目标而 FATAL 失效，
 * 连带构造器里的歌词注入也一起丢失（表现为刻录唱片无歌词）。
 */
@Mixin(value = NetMusicSound.class, remap = false)
public class NetMusicSoundMixin {

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void netmusickugou$afterInit(CallbackInfo ci) {
        try {
            java.lang.reflect.Field posField = NetMusicSound.class.getDeclaredField("pos");
            posField.setAccessible(true);
            Object rawPos = posField.get(this);

            LyricRecord cached = (rawPos instanceof BlockPos) ? LyricInjectCache.take((BlockPos) rawPos) : null;
            if (cached != null) {
                java.lang.reflect.Field field = NetMusicSound.class.getDeclaredField("lyricRecord");
                field.setAccessible(true);
                field.set(this, cached);
                KuGouLogger.info("KuGou lyric: replaced NetMusicSound.lyricRecord with {} lines",
                        cached.getLyrics() != null ? cached.getLyrics().size() : 0);
            }
        } catch (Exception e) {
            KuGouLogger.warn("KuGou lyric: failed to inject lyric into NetMusicSound: {}", e.getMessage());
        }
    }
}
