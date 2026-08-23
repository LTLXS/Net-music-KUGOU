package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.github.tartaricacid.netmusic.kugou.lyric.BurnDataCache;
import com.github.tartaricacid.netmusic.kugou.lyric.LrcConverter;
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
 * 从 {@link BurnDataCache} 取出 fileHash/albumId/歌词，同步写入 CD NBT。
 * <p>
 * 歌词由客户端在刻录时并行拉取，通过 BurnDataCache 传递到服务端，
 * 刻录完成时 CD 上已带有歌词，播放时无需等待。
 */
@Mixin(value = CDBurnerMenu.class, remap = false)
public class CDBurnerMenuMixin {

    @Inject(method = "setSongInfo", at = @At("TAIL"), remap = false)
    private void netmusickugou$afterSetSongInfo(ItemMusicCD.SongInfo setSongInfo, CallbackInfo ci) {
        try {
            String[] data = BurnDataCache.take();
            if (data == null || data[0] == null || data[0].isEmpty()) {
                return;
            }
            String fileHash = data[0];
            String albumId = data[1];
            String lrc = data[2];
            String lrcTrans = data[3];

            ItemStack cd = ((AbstractContainerMenu) (Object) this).getSlot(1).getItem();
            if (!CdNbtHelper.isMusicCd(cd)) {
                KuGouLogger.warn("CDBurnerMenuMixin: no CD in output slot after burn");
                return;
            }

            // 写入原曲识别信息（供 UrlRefresher 续期用）
            CdNbtHelper.writeOriginalInfo(cd, fileHash, albumId);

            // 同步写入歌词（已在客户端刻录时拉取完成）
            if (lrc != null && !lrc.isEmpty()) {
                String song = setSongInfo.songName != null ? setSongInfo.songName : "";
                CdNbtHelper.writeLyric(cd, lrc, song);
                if (lrcTrans != null && !lrcTrans.isEmpty()) {
                    CdNbtHelper.writeLyricTranslation(cd, lrcTrans);
                }
                KuGouLogger.info("CDBurnerMenuMixin: lyric written at burn time ({} chars)", lrc.length());

                // 解析后立即填入 KuGouDisplayCompat：让 NetMusicDisplay 在播放器第一次
                // setPlayToClient 之前就能取到 LyricRecord（Create DisplayLink 在服务端
                // 调 provideLine 时，setPlayToClient 不一定已被触发）。
                try {
                    LrcConverter.KuGouLyricData lyricData = LrcConverter.toLyricData(lrc, lrcTrans, song);
                    if (lyricData != null && lyricData.record != null) {
                        KuGouDisplayCompat.putLyricByHash(fileHash, lyricData.record);
                    }
                } catch (Throwable ignored) {
                }
            } else {
                KuGouLogger.info("CDBurnerMenuMixin: no lyric at burn time, will fallback at play time");
            }

        } catch (Exception e) {
            KuGouLogger.warn("CDBurnerMenuMixin: error after setSongInfo: {}", e.getMessage());
        }
    }
}
