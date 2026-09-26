package com.github.tartaricacid.netmusic.kugou;

import com.github.tartaricacid.netmusic.kugou.audio.AudioStreamHandlerInjector;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.api.KuGouVipApi;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.config.ServerConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfigScreen;
import com.github.tartaricacid.netmusic.kugou.network.NetworkHandler;
import com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper;
import com.github.tartaricacid.netmusic.kugou.support.CdReplayHelper;
import com.github.tartaricacid.netmusic.kugou.support.KuGouPrefetch;
import com.github.tartaricacid.netmusic.kugou.support.LoginStateManager;
import com.github.tartaricacid.netmusic.kugou.support.UrlRefresher;
import com.github.tartaricacid.netmusic.kugou.support.UrlRefreshScheduler;
import com.github.tartaricacid.netmusic.kugou.support.VipRetryScheduler;
import com.github.tartaricacid.netmusic.kugou.lyric.BlockRomajiRegistry;
import com.github.tartaricacid.netmusic.kugou.lyric.KuGouMaidLyricCache;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 酷狗音乐源插件主入口（Forge 1.20.1）。
 本类只负责装配：注册配置/网络/事件，并把具体职责转发给各自的组件类：
 - KuGouConfigScreen —— Cloth Config 配置界面
 - LoginStateManager —— 登录态持久化
 - AudioStreamHandlerInjector —— 酷狗音频处理器反射注入
 - CdReplayHelper —— CD URL 续期回写 / 延迟重播
 - VipRetryScheduler / UrlRefreshScheduler —— 周期任务调度
*/
@Mod(NetMusicKuGou.MOD_ID)
public class NetMusicKuGou {
    public static final String MOD_ID = "netmusic_kugou";
    public static SimpleChannel CHANNEL;

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("NETMUSICCANNEEDKUGOU");

    public NetMusicKuGou() {
        KuGouLogger.init();

        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            KuGouLogger.error("Failed to create config dir: {}", e.getMessage());
        }

        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "NETMUSICCANNEEDKUGOU/netmusic-kugou-client.toml");
        // 注意：必须用 COMMON 而非 SERVER 类型。
        // SERVER 类型 config 在客户端主菜单/登录界面/配置界面（尚未启动 integrated server）时不会被加载，
        // 其 ConfigValue.config 为 null，此时调用 set() 会抛 NPE（"Cannot set config value without assigned Config object present"）。
        // 该崩溃曾出现在 KuGouLoginScreen.finishLogin 与 KuGouConfigScreen 保存按钮。
        // COMMON 类型在客户端与服务器两侧都会尽早加载，配置界面与登录流程可安全读写，专用服务器也仍可用。
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ServerConfig.SPEC, "NETMUSICCANNEEDKUGOU/netmusic-kugou-server.toml");

        CHANNEL = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(MOD_ID, "channel"))
                .networkProtocolVersion(() -> "1")
                .clientAcceptedVersions(s -> true)
                .serverAcceptedVersions(s -> true)
                .simpleChannel();

        NetworkHandler.register();

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);

        if (FMLEnvironment.dist.isClient()) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
            // 之前只注册了 mod event bus，导致 onClientLoggingIn 从未触发，injectKuGouAudioStreamHandler 没执行。
            // 注意：必须在父模组的 AudioStreamHandlerManager.init() 之后调用 inject()，
            // 否则会把 HANDLERS 提前变成 ImmutableList，导致父模组的 init() 排序时崩。
            // onClientLoggingIn 在玩家登录世界时触发，远晚于 FMLClientSetupEvent，完美满足时序要求。
        }

        // 适配专用服务器(dedicated server)：Forge 事件总线必须两侧都注册。
        // 否则专用服务器上 onServerStarted / onPlayerLoggedIn / onRightClickJukebox /
        // onServerStopped / onServerStopping 这些服务端事件永远不会被注册，
        // 导致 CD URL 续期 / 预取逻辑在专用服务器上完全失效。
        // 这些服务端方法内部已用 isClient() / 配置开关 / isLoggedIn() 做了安全守卫，不会触碰任何客户端 API。
        MinecraftForge.EVENT_BUS.register(NetMusicKuGou.class);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (FMLEnvironment.dist.isClient() && ModList.get().isLoaded("cloth_config")) {
                ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                                (mc, parent) -> KuGouConfigScreen.create(mc, parent)));
            }
        });
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 应用配置里的日志级别（setup 在配置加载之后才执行，此时读取不会再抛 "before config is loaded"）
            KuGouLogger.setLevel(ServerConfig.getLogLevel());
            LoginStateManager.loadState();
            KuGouPrefetch.setOnRefreshChangedCallback(
                    (level, pos, oldUrl, newUrl) -> CdReplayHelper.scheduleReplayWithNewUrl(level, pos, oldUrl, newUrl));

            // 注意：injectKuGouAudioStreamHandler() 不能在这里调用！
            // 正确做法：在 onClientLoggingIn（玩家登录世界时）触发 inject()，
            // 我们用反射把 HANDLERS 替换成新的 ImmutableList（包含我们的 handler）。

            KuGouApiClient.ensureDeviceRegistered()
                    .thenAccept(ready -> {
                        KuGouLogger.info("Device registration: {}", ready ? "success" : "failed");
                        if (ready && ClientConfig.AUTO_RECEIVE_VIP.get() && KuGouConfig.isLoggedIn()) {
                            VipRetryScheduler.start();
                        }
                    });

            KuGouLogger.info("NetMusicNeedKuGou setup complete!");
        });
    }

    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (FMLEnvironment.dist.isClient() && !AudioStreamHandlerInjector.isInjected()) {
            AudioStreamHandlerInjector.inject();
        }

        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        if (!ClientConfig.AUTO_RECEIVE_VIP.get()) {
            return;
        }
        if (!KuGouConfig.isLoggedIn()) {
            return;
        }
        if (!KuGouVipApi.shouldRetryToday()) {
            return;
        }
        VipRetryScheduler.triggerAutoReceiveVip();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 专用服务器适配：若服务端配置了 VIP Cookie，则将其解析为酷狗登录态，
        // 使 CD URL 续期 / 预取在专用服务器上也能运行（无需客户端扫码登录）。
        applyServerVipCookie();
        UrlRefreshScheduler.start();
    }

    /**
 把服务端配置的 VIP Cookie 应用到 KuGouConfig 登录态。
 专用服务器没有客户端扫码流程，因此通过配置文件里的 cookie 来"登录"。
*/
    private static void applyServerVipCookie() {
        String cookie = ServerConfig.getVipCookie();
        if (cookie == null || cookie.isBlank()) {
            return;
        }
        KuGouConfig.applyCookieString(cookie);
        LoginStateManager.saveState();
        KuGouLogger.info("Applied server-side VIP Cookie. Logged in: {}", KuGouConfig.isLoggedIn());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ClientConfig.URL_REFRESH_ENABLED.get()) {
            return;
        }
        if (!KuGouConfig.isLoggedIn()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                UrlRefresher refresher = new UrlRefresher();
                int refreshed = refresher.scanPlayer(player);
                if (refreshed > 0) {
                    KuGouLogger.info("[UrlRefresh] On-login scan refreshed {} CD(s) for player {}",
                            refreshed, player.getName().getString());
                }
            } catch (Throwable t) {
                KuGouLogger.error("[UrlRefresh] On-login scan crashed for player {}",
                        player.getName().getString(), t);
            }
        });
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LoginStateManager.saveState();
        VipRetryScheduler.shutdown();
        UrlRefreshScheduler.shutdown();
        KuGouPrefetch.shutdown();
        KuGouMaidLyricCache.clearAll();
        BlockRomajiRegistry.clearAll();
        KuGouLogger.info("NetMusicNeedKuGou stopped!");
        KuGouLogger.shutdown();
    }

    @SubscribeEvent
    public static void onRightClickJukebox(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!ClientConfig.URL_REFRESH_ENABLED.get()) {
            return;
        }
        if (!KuGouConfig.isLoggedIn()) {
            return;
        }
        boolean isJukeboxLike = false;
        BlockState bs = event.getLevel().getBlockState(event.getPos());
        if (bs.getBlock() instanceof JukeboxBlock) {
            if (bs.hasProperty(JukeboxBlock.HAS_RECORD)
                    && bs.getValue(JukeboxBlock.HAS_RECORD)) {
                return;
            }
            isJukeboxLike = true;
        } else {
            BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
            if (be instanceof com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer mp) {
                IItemHandler inv = mp.getPlayerInv();
                if (inv != null && !inv.getStackInSlot(0).isEmpty()) {
                    return;
                }
                isJukeboxLike = true;
            }
        }
        if (!isJukeboxLike) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!CdNbtHelper.isMusicCd(held)) {
            return;
        }
        if (CdNbtHelper.readOriginalInfo(held).isEmpty()) {
            return;
        }

        final BlockPos pos = event.getPos();
        final UUID playerId = event.getEntity().getUUID();
        final ItemStack cdSnap = held.copy();
        final String curUrl = CdNbtHelper.readSongUrl(cdSnap);

        KuGouPrefetch.asyncPrefetch(pos, playerId, cdSnap);

        KuGouLogger.info(
                "[UrlRefresh] Async-prefetch submitted for CD insert at pos={}, player={}, curUrlLen={}",
                pos, event.getEntity().getName().getString(),
                curUrl == null ? 0 : curUrl.length());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LoginStateManager.saveState();
    }
}
