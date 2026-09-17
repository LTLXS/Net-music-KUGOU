package com.github.tartaricacid.netmusic.kugou.network.message;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：把 fileHash / albumId 写进 CD 的 DataComponent。
 * <p>
 * 用法：在 SetMusicIDMessage（netmusic 自带）发完之后立刻发这个包，
 * 服务端会在玩家打开的容器 slot 0 找到刚烧好的 CD 并附加识别信息。
 * 之所以要单独一个包，是因为我们不能改 netmusic 自带的 SetMusicIDMessage。
 */
public record AddCdRefreshInfoMessage(
        String fileHash,
        String albumId
) implements CustomPacketPayload {

    public static final Type<AddCdRefreshInfoMessage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NetMusicKuGou.MOD_ID, "add_cd_refresh_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AddCdRefreshInfoMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AddCdRefreshInfoMessage::fileHash,
            ByteBufCodecs.STRING_UTF8, AddCdRefreshInfoMessage::albumId,
            AddCdRefreshInfoMessage::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AddCdRefreshInfoMessage msg, IPayloadContext context) {
        if (!context.flow().isServerbound()) {
            return;
        }
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                return;
            }
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
            // 防御一下：必须真的烧过（songUrl 非空）才写
            ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(cd);
            if (info == null || info.songUrl == null || info.songUrl.isEmpty()) {
                KuGouLogger.warn("AddCdRefreshInfoMessage: CD in slot 0 has no songUrl yet, skipping");
                return;
            }
            CdNbtHelper.writeOriginalInfo(cd, msg.fileHash, msg.albumId);

            fetchAndStoreLyric(cd, msg);
        });
    }

    /**
     * 异步拉取酷狗歌词并写入 CD DataComponent。
     * <p>
     * 流程：searchLyric(hash, keyword) → getLyric(id, accesskey) → writeLyric(cd)
     * 任何步骤失败只记日志不抛异常，不影响主刻录流程。
     */
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
                    // krc 全候选失败时回退 lrc（代价是丢翻译，但至少有歌词）。
                    KuGouApiClient.getLyricWithFallback(list, "krc")
                            .thenAccept(content -> {
                                if (content != null && content.lyricContent != null && !content.lyricContent.isEmpty()) {
                                    CdNbtHelper.writeLyric(cd, content.lyricContent, song);
                                    // 翻译只在 krc 路径下存在；lrc 回退时 languageJson 为 null，不写
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
