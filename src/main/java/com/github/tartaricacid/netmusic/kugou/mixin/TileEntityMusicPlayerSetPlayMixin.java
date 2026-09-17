package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.compat.display.KuGouDisplayCompat;
import com.github.tartaricacid.netmusic.kugou.lyric.LrcConverter;
import com.github.tartaricacid.netmusic.kugou.support.CdAddonData;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.kugou.support.KuGouPrefetch;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.UUID;

@Mixin(TileEntityMusicPlayer.class)
public class TileEntityMusicPlayerSetPlayMixin {

    @Inject(method = "setPlayToClient", at = @At("HEAD"), remap = false)
    private void kugou$refreshBeforeSetPlayToClient(ItemMusicCD.SongInfo info, CallbackInfo ci) {
        final long t0 = System.currentTimeMillis();
        UUID playerId = null;
        try {
            TileEntityMusicPlayer self = (TileEntityMusicPlayer) (Object) this;
            if (self.getLevel() != null && self.getLevel().isClientSide()) return;
            IItemHandler inv = self.getPlayerInv();
            if (inv == null) return;
            ItemStack cd = inv.getStackInSlot(0);
            if (cd == null || cd.isEmpty()) return;
            if (!CdNbtHelper.isMusicCd(cd)) return;
            // 优先按当前播放歌曲的 songUrl 取逐曲酷狗元数据（netMusicList 列表CD 多曲互不覆盖），
            // 回退到顶层 NetMusicKuGouCdAddon（普通单曲 CD）。
            String playUrl = (info != null && info.songUrl != null && !info.songUrl.isEmpty())
                    ? info.songUrl : CdNbtHelper.readSongUrl(cd);
            CdAddonData addon = CdNbtHelper.getSongAddon(cd, playUrl);
            if (!addon.hasFileHash()) {
                Optional<CdAddonData> top = CdNbtHelper.readOriginalInfo(cd);
                if (top.isPresent()) addon = top.get();
            }
            if (addon.fileHash() == null || addon.fileHash().isEmpty()) return;

            BlockPos pos = self.getBlockPos();
            Level level = self.getLevel();

            String oldCdUrl = playUrl;
            String oldInfoUrl = (info != null) ? info.songUrl : null;

            KuGouPrefetch.PrefetchResult res = KuGouPrefetch.tryTake(pos, playerId, level, oldCdUrl);

            String pickedUrl = "";
            boolean fromPrefetch = false;
            if (res.hasFreshUrl()) {
                pickedUrl = res.url();
                fromPrefetch = true;
            } else {
                pickedUrl = (oldCdUrl == null) ? "" : oldCdUrl;
                if (!res.entryFound() && !KuGouPrefetch.isOnReplayCooldown(pos) && level != null && pos != null && cd != null) {
                    UUID dummyOwner = new UUID(0L, pos.asLong());
                    KuGouPrefetch.submitAsyncRefreshThenMaybeReplay(level, pos, dummyOwner, cd, pickedUrl);
                }
            }

            if (fromPrefetch && pickedUrl != null && !pickedUrl.isEmpty()) {
                if (!pickedUrl.equals(oldCdUrl)) {
                    CdNbtHelper.updateSongUrl(cd, pickedUrl);
                    CdNbtHelper.updateData(cd, d -> new CdAddonData(
                            d.fileHash(), d.albumId(), System.currentTimeMillis(), d.lrc(), d.lrcTrans()));
                }
            }

            if (info != null && pickedUrl != null && !pickedUrl.isEmpty()) {
                if (!pickedUrl.equals(info.songUrl)) {
                    KuGouLogger.info(
                            "[SetPlayPrep] Overwrite info.songUrl: len{} -> len{}, source={}, cost={}ms, hash={}",
                            info.songUrl == null ? 0 : info.songUrl.length(),
                            pickedUrl.length(),
                            fromPrefetch ? "prefetch(hit)" : "cd-fallback(async-submitted)",
                            (System.currentTimeMillis() - t0),
                            addon.fileHash());
                    info.songUrl = pickedUrl;
                } else {
                    KuGouLogger.info(
                            "[SetPlayPrep] info.songUrl already matches picked URL (len={}, source={}, cost={}ms)",
                            pickedUrl.length(),
                            fromPrefetch ? "prefetch(hit)" : "cd-fallback(async-submitted)",
                            (System.currentTimeMillis() - t0));
                }
            } else if (info != null && (pickedUrl == null || pickedUrl.isEmpty())) {
                KuGouLogger.error(
                        "[SetPlayPrep] NO URL (hash={}). info.songUrl len={}, cdUrl len={}, fallback cost={}ms",
                        addon.fileHash(),
                        info.songUrl == null ? 0 : info.songUrl.length(),
                        oldCdUrl == null ? 0 : oldCdUrl.length(),
                        (System.currentTimeMillis() - t0));
            }

            try {
                String lrcText = addon.hasLrc() ? addon.lrc() : null;
                String transJson = addon.hasLrc() ? addon.lrcTrans() : null;
                if (lrcText == null || lrcText.isEmpty()) {
                    CdNbtHelper.Lyric stored = CdNbtHelper.readLyric(cd);
                    if (stored != null && stored.lrcText != null && !stored.lrcText.isEmpty()) {
                        lrcText = stored.lrcText;
                        transJson = CdNbtHelper.readLyricTranslation(cd);
                    }
                }
                if (lrcText != null && !lrcText.isEmpty()) {
                    LrcConverter.KuGouLyricData data = LrcConverter.toLyricData(
                            lrcText, transJson,
                            info != null ? info.songName : null);
                    if (data != null && data.record != null) {
                        KuGouDisplayCompat.putAll(pos, addon.fileHash(), data.record);
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            KuGouLogger.error(
                    "[NetMusicKuGou] TileEntityMusicPlayerSetPlayMixin crashed, let original flow continue: {}",
                    t.getMessage(), t);
        }
    }
}
