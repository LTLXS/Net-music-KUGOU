package com.github.tartaricacid.netmusic.kugou.network.message;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AddCdRefreshInfoMessage(
        String fileHash,
        String albumId
) {

    public static AddCdRefreshInfoMessage decode(FriendlyByteBuf buf) {
        String fileHash = buf.readUtf();
        String albumId = buf.readUtf();
        return new AddCdRefreshInfoMessage(fileHash, albumId);
    }

    public static void encode(AddCdRefreshInfoMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.fileHash != null ? msg.fileHash : "");
        buf.writeUtf(msg.albumId != null ? msg.albumId : "");
    }

    public static void handle(AddCdRefreshInfoMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER) {
            return;
        }
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) ctx.get().getSender();
            if (player == null) return;
            if (msg.fileHash == null || msg.fileHash.isEmpty()) {
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            ItemStack cd = ItemStack.EMPTY;
            if (menu != null && menu.slots.size() > 0) {
                cd = menu.getSlot(0).getItem();
            }
            if (!CdNbtHelper.isMusicCd(cd)) {
                cd = player.getMainHandItem();
            }
            if (!CdNbtHelper.isMusicCd(cd)) {
                KuGouLogger.warn("AddCdRefreshInfoMessage: no music CD found in slot 0 / main hand, skipping");
                return;
            }
            ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
            if (info == null || info.songUrl == null || info.songUrl.isEmpty()) {
                KuGouLogger.warn("AddCdRefreshInfoMessage: CD in slot 0 has no songUrl yet, skipping");
                return;
            }
            CdNbtHelper.writeOriginalInfo(cd, msg.fileHash, msg.albumId);
            fetchAndStoreLyric(cd, msg);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void fetchAndStoreLyric(ItemStack cd, AddCdRefreshInfoMessage msg) {
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
        if (info == null) return;

        String singer = (info.artists == null || info.artists.isEmpty())
                ? ""
                : String.join(", ", info.artists);
        String song = info.songName == null ? "" : info.songName;
        String keyword = (singer.isEmpty() ? "" : singer + " - ") + song;
        if (keyword.isEmpty()) return;
        int duration = info.songTime * 1000;

        KuGouApiClient.searchLyricCandidates(msg.fileHash, keyword, duration, song, singer)
                .thenAccept(list -> {
                    if (list == null || list.isEmpty()) {
                        KuGouLogger.warn("AddCdRefreshInfo: no lyric candidate for hash={}, keyword={}",
                                msg.fileHash, keyword);
                        return;
                    }
                    KuGouApiClient.getLyricWithFallback(list, "krc")
                            .thenAccept(content -> {
                                if (content != null && content.lyricContent != null && !content.lyricContent.isEmpty()) {
                                    CdNbtHelper.writeLyric(cd, content.lyricContent, song);
                                    if (content.languageJson != null && !content.languageJson.isEmpty()) {
                                        CdNbtHelper.writeLyricTranslation(cd, content.languageJson);
                                    }
                                } else {
                                    KuGouLogger.warn("AddCdRefreshInfo: lyric body empty for hash={} (tried {} candidates, krc+lrc)",
                                            msg.fileHash, list.size());
                                }
                            })
                            .exceptionally(e -> {
                                KuGouLogger.warn("AddCdRefreshInfo: getLyric failed: {}", e.getMessage());
                                return null;
                            });
                })
                .exceptionally(e -> {
                    KuGouLogger.warn("AddCdRefreshInfo: searchLyric failed: {}", e.getMessage());
                    return null;
                });
    }
}
