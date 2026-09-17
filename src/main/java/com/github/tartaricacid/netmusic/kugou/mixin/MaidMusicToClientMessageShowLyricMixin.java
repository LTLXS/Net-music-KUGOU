package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.compat.tlm.chatbubble.LyricChatBubbleData;
import com.github.tartaricacid.netmusic.compat.tlm.message.MaidMusicToClientMessage;
import com.github.tartaricacid.netmusic.kugou.lyric.KuGouMaidLyricCache;
import com.github.tartaricacid.netmusic.kugou.lyric.LrcConverter;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.github.tartaricacid.netmusic.client.audio.MusicPlayManager.MUSIC_163_URL;

@Mixin(MaidMusicToClientMessage.class)
public class MaidMusicToClientMessageShowLyricMixin {

    @Inject(method = "showLyric", at = @At("HEAD"), remap = false)
    private static void kugou$onShowLyricPre(EntityMaid maid, String url, String songName, int timeSecond,
                                                 CallbackInfo ci) {
        try {
            CombinedInvWrapper inv = maid.getAvailableInv(false);
            if (inv != null && url != null) {
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (CdNbtHelper.readSongUrl(stack) != null && CdNbtHelper.readSongUrl(stack).equals(url)) {
                        CdNbtHelper.Lyric lyric = CdNbtHelper.readLyric(stack);
                        if (lyric != null && lyric.lrcText != null && !lyric.lrcText.isEmpty()) {
                            String transJson = CdNbtHelper.readLyricTranslation(stack);
                            LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(
                                    lyric.lrcText, transJson,
                                    lyric.songName != null ? lyric.songName : songName);
                            Int2ObjectSortedMap<String> romaji =
                                    data != null ? data.romaji : new Int2ObjectRBTreeMap<>();
                            KuGouMaidLyricCache.put(maid.getId(), songName, lyric.lrcText, transJson, romaji);
                            KuGouLogger.info(
                                    "[NetMusicKuGou] showLyric pre-cached maid #{} songName='{}' ({} chars from CD slot {} matching url, transJson={}, romajiLines={})",
                                    maid.getId(), songName, lyric.lrcText.length(), i,
                                    transJson != null ? "yes" : "no", romaji.size());
                            break;
                        }
                    }
                }
            }

            if (url == null || !url.startsWith(MUSIC_163_URL)) {
                long gameTime = maid.level().getGameTime();
                long startTick = gameTime;
                int existTick = timeSecond * 20 + 20 + 60;
                LyricChatBubbleData bubbleData = new LyricChatBubbleData(0L, songName, existTick, startTick);
                maid.getChatBubbleManager().addChatBubble(bubbleData);
                KuGouLogger.info(
                        "[NetMusicKuGou] showLyric created LyricChatBubbleData(musicId=0) for maid #{} songName='{}' startTick={} existTick={} url='{}'",
                        maid.getId(), songName, startTick, existTick, url);
            }
        } catch (Throwable t) {
            KuGouLogger.error("[NetMusicKuGou] showLyric pre-cache/create failed", t);
        }
    }
}
