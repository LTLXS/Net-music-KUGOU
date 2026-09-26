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
 在父模组 NetMusicSound 构造器尾部检查是否有 addon 注入的歌词缓存，有则替换 this.lyricRecord。
 配合 MusicToClientMessageMixin：onHandle(HEAD) 解析 CD NBT LRC 存入 LyricInjectCache，
 父模组后台线程 new NetMusicSound 时由本 Mixin(TAIL) 取缓存替换 lyricRecord。
 只注入构造器尾部，不注入 tick()：1.5.1 发布版的 NetMusicSound 未 override tick()，
 写 @Inject(method="tick") 会使整个 Mixin 因找不到目标而失效，连带歌词注入丢失。*/
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
