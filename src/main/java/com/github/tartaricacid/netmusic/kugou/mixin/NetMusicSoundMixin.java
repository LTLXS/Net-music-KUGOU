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
 * 额外的 {@code tick()} 注入：当 CD 上没有 LRC 时，{@link MusicToClientMessageMixin#fetchLyricOnTheFly}
 * 会在后台异步拉取歌词。拉取完成后写入 {@link LyricInjectCache}，本 Mixin 的 tick() 注入会
 * 在下一个 tick 补设 {@code lyricRecord}，使歌词延迟几秒出现而不是整首歌不显示。
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

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void netmusickugou$onTickTail(CallbackInfo ci) {
        try {
            java.lang.reflect.Field posField = NetMusicSound.class.getDeclaredField("pos");
            posField.setAccessible(true);
            Object rawPos = posField.get(this);

            LyricRecord cached = (rawPos instanceof BlockPos) ? LyricInjectCache.take((BlockPos) rawPos) : null;
            if (cached != null) {
                java.lang.reflect.Field field = NetMusicSound.class.getDeclaredField("lyricRecord");
                field.setAccessible(true);
                field.set(this, cached);
                KuGouLogger.info("KuGou lyric: late-injected lyricRecord ({} lines) into NetMusicSound at tick",
                        cached.getLyrics() != null ? cached.getLyrics().size() : 0);
            }
        } catch (Exception e) {
            KuGouLogger.warn("KuGou lyric: late inject in tick failed: {}", e.getMessage());
        }
    }
}
