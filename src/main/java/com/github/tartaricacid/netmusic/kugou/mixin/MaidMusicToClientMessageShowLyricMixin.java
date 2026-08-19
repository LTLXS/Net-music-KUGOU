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
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.github.tartaricacid.netmusic.client.audio.MusicPlayManager.MUSIC_163_URL;

/**
 * Mixin 到父模组 {@code MaidMusicToClientMessage.showLyric}。
 * <p>
 * 父模组原版：仅当 URL 是网易云时创建 {@code LyricChatBubbleData}。酷狗 URL 直接跳过 → 气泡不存在。
 * <p>
 * <b>本 Mixin 行为</b>：
 * <ol>
 *   <li><b>无论 URL 类型</b>：从女仆背包 CD 上读 LRC NBT 写入 {@code KuGouMaidLyricCache}（JVM 共享），
 *       这样 {@code LyricChatBubbleRenderer} 构造时 {@code LyricChatBubbleRendererMixin} 能拿到</li>
 *   <li><b>仅当 URL 不是网易云</b>：自己创建一个 {@code LyricChatBubbleData(musicId=0)} 并
 *       {@code addChatBubble}（必须在服务端 add，entity data 同步到客户端才能正确显示）</li>
 * </ol>
 */
@Mixin(MaidMusicToClientMessage.class)
public class MaidMusicToClientMessageShowLyricMixin {

    @Inject(method = "showLyric", at = @At("HEAD"), remap = false)
    private static void kugou$onShowLyricPre(EntityMaid maid, String url, String songName, int timeSecond,
                                                 CallbackInfo ci) {
        try {
            // === 步骤 1：预填 LRC 缓存（无论 URL 类型）===
            // 关键：找和 url（当前正在播放的 CD 原始 songUrl）匹配的 CD 来读 LRC。
            // 不能遍历所有 CD —— 其它 CD 的 LRC 会污染当前歌曲。
            // 用 LrcConverter.toLyricData 一次拿到 翻译 + 罗马音 两组。
            CombinedInvWrapper inv = maid.getAvailableInv(false);
            if (inv != null && url != null) {
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (CdNbtHelper.readSongUrl(stack) != null && CdNbtHelper.readSongUrl(stack).equals(url)) {
                        CdNbtHelper.Lyric lyric = CdNbtHelper.readLyric(stack);
                        if (lyric != null && lyric.lrcText != null && !lyric.lrcText.isEmpty()) {
                            String transJson = CdNbtHelper.readLyricTranslation(stack);
                            // 解析 LRC + 翻译 + 罗马音（timePoint 对齐）。歌词失败时不会崩（返回 null），
                            // 这种情况下我们只缓存 raw transJson 和空 romaji，renderer 会兜底。
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

            // === 步骤 2：非网易云 URL 时，自己 addChatBubble（服务端 add 才能正确同步） ===
            // startTick 在 server 端用当前 gameTime 即可，client 端 LyricChatBubbleRendererMixin 会根据
            // 实际 init 时刻（audio 已经开始播放）自动重算 startTick 实现对齐。
            if (url == null || !url.startsWith(MUSIC_163_URL)) {
                long gameTime = maid.level().getGameTime();
                long startTick = gameTime; // client 端会自动重算
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
