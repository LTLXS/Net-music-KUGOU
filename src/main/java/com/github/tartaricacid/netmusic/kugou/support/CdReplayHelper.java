package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * CD URL 续期回写：异步预取结束但 setPlayToClient 已带旧 URL 起飞后，把新 URL 写回
 * TileEntity slot 0 的 CD NBT，延迟到服务端主线程重新触发 setPlayToClient，实现"无缝切歌"。
 */
public final class CdReplayHelper {
    private CdReplayHelper() {
    }

    public static void scheduleReplayWithNewUrl(Level level,
                                                BlockPos pos,
                                                String oldUrl,
                                                String newUrl) {
        if (level == null || pos == null || newUrl == null || newUrl.isEmpty()) return;
        if (level.isClientSide()) return; // 只能在服务端操作 BlockEntity + 发包

        MinecraftServer server = level.getServer();
        if (server == null) return;
        // ① 避免与正在进行的 setPlayToClient 同时写入 TileEntity 发生竞争
        server.execute(() -> {
            try {
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer mp)) {
                    KuGouLogger.warn("[KuGouReplay] TileEntity at pos={} not TileEntityMusicPlayer, skip", pos);
                    return;
                }
                IItemHandler inv = mp.getPlayerInv();
                if (inv == null) return;
                ItemStack cd = inv.getStackInSlot(0);
                if (cd == null || cd.isEmpty()) return;
                if (!CdNbtHelper.isMusicCd(cd)) return;

                CdNbtHelper.updateSongUrl(cd, newUrl);
                CdNbtHelper.updateData(cd, d ->
                        new CdAddonData(
                                d.fileHash(), d.albumId(), System.currentTimeMillis(), d.lrc(), d.lrcTrans()));

                // 老URL非空 → 第一次 setPlayToClient 已经用它起播了 → 不要 replay（会导致双重播放）
                if (oldUrl != null && !oldUrl.isEmpty()) {
                    KuGouLogger.info(
                            "[KuGouReplay] Silent URL update only (old URL non-empty, song likely playing): pos={}, oldLen={} newLen={}",
                            pos, oldUrl.length(), newUrl.length());
                    return;
                }

                // 老URL为空 → 第一次播放失败 → 需要 replay
                com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo info =
                        com.github.tartaricacid.netmusic.item.ItemMusicCD.getSongInfo(cd);
                if (info == null) {
                    KuGouLogger.warn("[KuGouReplay] Slot 0 CD has no SongInfo, cannot re-setPlayToClient");
                    return;
                }
                // 无论原 info.songUrl 是什么，强制覆盖为新 URL
                info.songUrl = newUrl;

                // 先停掉当前播放，避免新旧声音重叠（双音问题）
                java.lang.reflect.Method setPlay = com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer.class
                        .getDeclaredMethod("setPlay", boolean.class);
                setPlay.setAccessible(true);
                setPlay.invoke(mp, false);
                java.lang.reflect.Method markDirty = com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer.class
                        .getDeclaredMethod("markDirty");
                markDirty.setAccessible(true);
                markDirty.invoke(mp);

                // 反射调用 TileEntityMusicPlayer.setPlayToClient(SongInfo)
                java.lang.reflect.Method m = com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer.class
                        .getDeclaredMethod("setPlayToClient", com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo.class);
                m.setAccessible(true);
                m.invoke(mp, info);
                KuGouLogger.info(
                        "[KuGouReplay] Re-setPlayToClient after 1 tick for pos={}: oldUrlLen={} newUrlLen={}, song={}",
                        pos,
                        oldUrl == null ? 0 : oldUrl.length(),
                        newUrl.length(),
                        info.songName);
            } catch (Throwable t) {
                KuGouLogger.error("[KuGouReplay] Re-setPlayToClient failed at pos={}: {}", pos, t.getMessage(), t);
            }
        });
    }
}
