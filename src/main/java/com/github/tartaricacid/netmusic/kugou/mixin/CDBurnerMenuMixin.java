package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.lyric.BurnDataCache;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.inventory.CDBurnerMenu;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端 Mixin：在父模组 {@link CDBurnerMenu#setSongInfo} 刻录完成后，
 * 从 {@link BurnDataCache} 取出 fileHash/albumId，写入 CD NBT 并拉取歌词。
 * <p>
 * 数据来源：客户端 {@code CDBurnerMenuScreenMixin.handleCraftButton()} 在发
 * {@code SetMusicIDMessage} 前把 hash 写入 {@link BurnDataCache}。
 * 集成服务器模式下客户端/服务端共享 JVM，静态变量可直接传递。
 */
@Mixin(value = CDBurnerMenu.class, remap = false)
public class CDBurnerMenuMixin {

    @Inject(method = "setSongInfo", at = @At("TAIL"), remap = false)
    private void netmusickugou$afterSetSongInfo(ItemMusicCD.SongInfo setSongInfo, CallbackInfo ci) {
        try {
            String[] data = BurnDataCache.take();
            if (data == null || data[0] == null || data[0].isEmpty()) {
                return; // 不是酷狗刻录（网易云路径不经过缓存）
            }
            String fileHash = data[0];
            String albumId = data[1];

            // 取输出槽（slot 1）里刚烧好的 CD
            ItemStack cd = ((AbstractContainerMenu) (Object) this).getSlot(1).getItem();
            if (!CdNbtHelper.isMusicCd(cd)) {
                KuGouLogger.warn("CDBurnerMenuMixin: no CD in output slot after burn");
                return;
            }

            // 写入原曲识别信息（供 UrlRefresher 续期用）
            CdNbtHelper.writeOriginalInfo(cd, fileHash, albumId);

            // 异步拉取歌词
            fetchAndStoreLyric(cd, fileHash, albumId);

        } catch (Exception e) {
            KuGouLogger.warn("CDBurnerMenuMixin: error after setSongInfo: {}", e.getMessage());
        }
    }

    private static void fetchAndStoreLyric(ItemStack cd, String fileHash, String albumId) {
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null) return;

        String singer = (info.artists == null || info.artists.isEmpty())
                ? "" : String.join(", ", info.artists);
        String song = info.songName == null ? "" : info.songName;
        String keyword = (singer.isEmpty() ? "" : singer + " - ") + song;
        if (keyword.isEmpty()) return;
        int duration = info.songTime * 1000;

        KuGouApiClient.searchLyric(fileHash, keyword, duration)
                .thenAccept(candidate -> {
                    if (candidate == null) {
                        KuGouLogger.warn("CDBurnerMenuMixin: no lyric candidate for hash={}", fileHash);
                        return;
                    }

                    // 先试 KRC（拿翻译字段 language）—— KRC fmt 才返回 language。
                    // 如果 KRC 成功就用 KRC 内容（已解码为 LRC）+ 翻译；
                    // 如果 KRC 失败（body 为空）则 fallback 到 LRC fmt（无翻译）。
                    KuGouApiClient.getLyric(candidate.id, candidate.accessKey, "krc")
                            .thenAccept(krcContent -> {
                                if (krcContent != null && krcContent.lyricContent != null && !krcContent.lyricContent.isEmpty()) {
                                    CdNbtHelper.writeLyric(cd, krcContent.lyricContent, song);
                                    if (krcContent.languageJson != null) {
                                        CdNbtHelper.writeLyricTranslation(cd, krcContent.languageJson);
                                    }
                                } else {
                                    // KRC 失败 fallback 到 LRC（无翻译）
                                    KuGouApiClient.getLyric(candidate.id, candidate.accessKey, "lrc")
                                            .thenAccept(lrcContent -> {
                                                if (lrcContent != null && lrcContent.lyricContent != null && !lrcContent.lyricContent.isEmpty()) {
                                                    CdNbtHelper.writeLyric(cd, lrcContent.lyricContent, song);
                                                } else {
                                                    KuGouLogger.warn("CDBurnerMenuMixin: lyric body empty for hash={}", fileHash);
                                                }
                                            });
                                }
                            })
                            .exceptionally(e -> {
                                KuGouLogger.warn("CDBurnerMenuMixin: getLyric(krc) failed: {}", e.getMessage());
                                return null;
                            });
                })
                .exceptionally(e -> {
                    KuGouLogger.warn("CDBurnerMenuMixin: searchLyric failed: {}", e.getMessage());
                    return null;
                });
    }
}
