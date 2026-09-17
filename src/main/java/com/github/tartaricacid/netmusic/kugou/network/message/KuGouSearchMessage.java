package com.github.tartaricacid.netmusic.kugou.network.message;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.client.gui.KuGouSearchScreen;
import com.github.tartaricacid.netmusic.kugou.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record KuGouSearchMessage(
        String keyword,
        int page,
        List<KuGouSearchScreen.SearchResult> results
) {

    public KuGouSearchMessage(String keyword, int page) {
        this(keyword, page, new ArrayList<>());
    }

    public static KuGouSearchMessage decode(FriendlyByteBuf buf) {
        String keyword = buf.readUtf();
        int page = buf.readVarInt();
        List<KuGouSearchScreen.SearchResult> results = buf.readList(KuGouSearchScreen.SearchResult::fromBuf);
        return new KuGouSearchMessage(keyword, page, results);
    }

    public static void encode(KuGouSearchMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.keyword);
        buf.writeVarInt(msg.page);
        for (KuGouSearchScreen.SearchResult result : msg.results) {
            KuGouSearchScreen.SearchResult.toBuf(result, buf);
        }
    }

    public static void handle(KuGouSearchMessage message, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection() == net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER) {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    // 异步执行搜索，避免 .get() 阻塞服务端主线程（酷狗 HTTP 超时可达 10s）
                    KuGouApiClient.search(message.keyword, message.page, 10).whenComplete((songs, err) -> {
                        if (err != null) {
                            KuGouLogger.error("Failed to search songs", err);
                            return;
                        }
                        try {
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
                            KuGouLogger.error("Failed to build search results", e);
                        }
                    });
                }
            } else {
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    var screen = net.minecraft.client.Minecraft.getInstance().screen;
                    if (screen instanceof KuGouSearchScreen) {
                        KuGouSearchScreen searchScreen = (KuGouSearchScreen) screen;
                        searchScreen.setSearchResults(message.results);
                    }
                });
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
