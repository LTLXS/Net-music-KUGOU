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

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NetMusicKuGou.MOD_ID)
                .versioned(PROTOCOL_VERSION);
        registrar.playBidirectional(KuGouSearchMessage.TYPE, KuGouSearchMessage.STREAM_CODEC, KuGouSearchMessage::handle);
        registrar.playToServer(AddCdRefreshInfoMessage.TYPE, AddCdRefreshInfoMessage.STREAM_CODEC, AddCdRefreshInfoMessage::handle);
        KuGouLogger.info("Network Handler initialized!");
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
