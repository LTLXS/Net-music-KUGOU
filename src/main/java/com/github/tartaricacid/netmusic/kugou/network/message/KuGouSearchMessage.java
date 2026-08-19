package com.github.tartaricacid.netmusic.kugou.network.message;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.client.gui.KuGouSearchScreen;
import com.github.tartaricacid.netmusic.kugou.network.NetworkHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 ↔ 服务端：酷狗音乐搜索结果传输。
 * <p>
 * 客户端 → 服务端：携带 keyword + page，results 为空列表。
 * 服务端 → 客户端：携带 keyword + page + 搜索结果列表。
 */
public record KuGouSearchMessage(
        String keyword,
        int page,
        List<KuGouSearchScreen.SearchResult> results
) implements CustomPacketPayload {

    public static final Type<KuGouSearchMessage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NetMusicKuGou.MOD_ID, "kugou_search"));

    /**
     * 简化构造器：客户端发起搜索时用，results 默认空。
     */
    public KuGouSearchMessage(String keyword, int page) {
        this(keyword, page, new ArrayList<>());
    }

    /**
     * 单条 {@link KuGouSearchScreen.SearchResult} 的编解码器。
     * 5 个字段（songName / singerName / duration / fileHash / albumId），
     * 父模组烧录机实际只用到这些。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, KuGouSearchScreen.SearchResult> SEARCH_RESULT_CODEC =
            StreamCodec.of(
                    (buf, r) -> {
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.songName);
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.singerName);
                        ByteBufCodecs.VAR_INT.encode(buf, r.duration);
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.fileHash);
                        ByteBufCodecs.STRING_UTF8.encode(buf, r.albumId);
                    },
                    buf -> new KuGouSearchScreen.SearchResult(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf)
                    )
            );

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final StreamCodec<RegistryFriendlyByteBuf, List<KuGouSearchScreen.SearchResult>> SEARCH_RESULT_LIST_CODEC =
            SEARCH_RESULT_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, KuGouSearchMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, KuGouSearchMessage::keyword,
            ByteBufCodecs.VAR_INT, KuGouSearchMessage::page,
            SEARCH_RESULT_LIST_CODEC, KuGouSearchMessage::results,
            KuGouSearchMessage::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 处理函数：服务端执行搜索并回复；客户端把结果喂给 {@link KuGouSearchScreen}。
     */
    public static void handle(KuGouSearchMessage message, IPayloadContext context) {
        if (context.flow().isServerbound()) {
            // 客户端 → 服务端：发起搜索
            context.enqueueWork(() -> {
                if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                    return;
                }
                try {
                    List<KuGouApiClient.Song> songs = KuGouApiClient.search(message.keyword, message.page, 10).get();
                    List<KuGouSearchScreen.SearchResult> results = new ArrayList<>();
                    for (KuGouApiClient.Song song : songs) {
                        results.add(new KuGouSearchScreen.SearchResult(
                                song.name,
                                song.singer,
                                song.duration,
                                song.hash != null ? song.hash : "",
                                song.albumId != null ? song.albumId : ""
                        ));
                    }
                    NetworkHandler.sendToPlayer(player, new KuGouSearchMessage(message.keyword, message.page, results));
                } catch (Exception e) {
                    KuGouLogger.error("Failed to search songs", e);
                }
            });
        } else {
            // 服务端 → 客户端：填到屏幕
            context.enqueueWork(() -> {
                var screen = net.minecraft.client.Minecraft.getInstance().screen;
                if (screen instanceof KuGouSearchScreen searchScreen) {
                    searchScreen.setSearchResults(message.results);
                }
            });
        }
    }
}
