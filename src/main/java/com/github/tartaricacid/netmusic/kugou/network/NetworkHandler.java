package com.github.tartaricacid.netmusic.kugou.network;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.network.message.AddCdRefreshInfoMessage;
import com.github.tartaricacid.netmusic.kugou.network.message.KuGouSearchMessage;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge 网络注册入口。
 * <p>
 * 在 mod 事件总线上监听 {@link RegisterPayloadHandlersEvent}，
 * 通过 {@link PayloadRegistrar} 注册 {@code CustomPacketPayload}。
 */
public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NetMusicKuGou.MOD_ID)
                .versioned(PROTOCOL_VERSION);
        // KuGouSearchMessage：客户端发起搜索 + 服务端回传结果（双向）
        registrar.playBidirectional(KuGouSearchMessage.TYPE, KuGouSearchMessage.STREAM_CODEC, KuGouSearchMessage::handle);
        // AddCdRefreshInfoMessage：客户端 → 服务端（写 fileHash/albumId 到 CD）
        registrar.playToServer(AddCdRefreshInfoMessage.TYPE, AddCdRefreshInfoMessage.STREAM_CODEC, AddCdRefreshInfoMessage::handle);
        KuGouLogger.info("Network Handler initialized!");
    }

    /**
     * 把 payload 发给指定服务端玩家（player → server 端使用）。
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * 把 payload 发到服务端（client 端使用）。
     */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
