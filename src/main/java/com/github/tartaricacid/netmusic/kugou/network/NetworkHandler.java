package com.github.tartaricacid.netmusic.kugou.network;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.network.message.AddCdRefreshInfoMessage;
import com.github.tartaricacid.netmusic.kugou.network.message.KuGouSearchMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public class NetworkHandler {

    public static void register() {
        var channel = NetMusicKuGou.CHANNEL;

        channel.registerMessage(0, KuGouSearchMessage.class,
                KuGouSearchMessage::encode, KuGouSearchMessage::decode,
                KuGouSearchMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        channel.registerMessage(1, AddCdRefreshInfoMessage.class,
                AddCdRefreshInfoMessage::encode, AddCdRefreshInfoMessage::decode,
                AddCdRefreshInfoMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        KuGouLogger.info("Network Handler initialized!");
    }

    public static void sendToPlayer(ServerPlayer player, Object message) {
        NetMusicKuGou.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(Object message) {
        NetMusicKuGou.CHANNEL.sendToServer(message);
    }
}
