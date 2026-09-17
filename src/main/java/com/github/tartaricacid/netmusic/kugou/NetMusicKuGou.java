package com.github.tartaricacid.netmusic.kugou;

import com.github.tartaricacid.netmusic.kugou.audio.AudioStreamHandlerInjector;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.api.KuGouVipApi;
import com.github.tartaricacid.netmusic.kugou.compat.netmusiclist.NetMusicListCompat;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfigScreen;
import com.github.tartaricacid.netmusic.kugou.init.InitDataComponent;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 酷狗音乐源插件主入口（NeoForge 1.21.1）。
 * <p>本类只负责装配：注册配置/网络/事件，并把具体职责转发给各自的组件类：
 * <ul>
 *   <li>{@link KuGouConfigScreen} —— Cloth Config 配置界面</li>
 *   <li>{@link LoginStateManager} —— 登录态持久化</li>
 *   <li>{@link AudioStreamHandlerInjector} —— 酷狗音频处理器反射注入</li>
 *   <li>{@link CdReplayHelper} —— CD URL 续期回写 / 延迟重播</li>
 *   <li>{@link VipRetryScheduler} / {@link UrlRefreshScheduler} —— 周期任务调度</li>
 * </ul>
 */
@Mod(NetMusicKuGou.MOD_ID)
@EventBusSubscriber(modid = NetMusicKuGou.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class NetMusicKuGou {
    public static final String MOD_ID = "netmusic_kugou";

    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("NETMUSICCANNEEDKUGOU");

    public NetMusicKuGou(IEventBus modEventBus, ModContainer modContainer) {
        KuGouLogger.init();

        // 确保配置目录存在
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            KuGouLogger.error("Failed to create config dir: {}", e.getMessage());
        }

        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "NETMUSICCANNEEDKUGOU/netmusic-kugou-client.toml");

        InitDataComponent.DATA_COMPONENT_TYPES.register(modEventBus);

        modEventBus.addListener(NetworkHandler::register);

        modEventBus.addListener(this::setup);

        // 无需手动 register(this)。手动 register(this) 会因重复注册导致崩溃。

        if (FMLEnvironment.dist.isClient() && ModList.get().isLoaded("cloth_config")) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (java.util.function.Supplier<IConfigScreenFactory>) () ->
                            (mc, parent) -> KuGouConfigScreen.create(net.minecraft.client.Minecraft.getInstance(), parent));
        }
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            LoginStateManager.loadState();

            // 若 netMusicList 已加载，把酷狗注册为它的音乐源（纯反射 + 动态代理，编译期零依赖）
            if (FMLEnvironment.dist.isClient()
                    && NetMusicListCompat.isNetMusicListLoaded()) {
                NetMusicListCompat.tryInit();
            }

            // 注册异步预取 -> URL 变更后的 "延迟1 tick 重新 setPlayToClient" 回调
            KuGouPrefetch.setOnRefreshChangedCallback(
                    (level, pos, oldUrl, newUrl) -> CdReplayHelper.scheduleReplayWithNewUrl(level, pos, oldUrl, newUrl));

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

    /**
     * 客户端进入世界（加入 singleplayer / 多人服）时立即触发一次 VIP 领取。
     * <p>
     * <b>必须 static</b>：{@link NetMusicKuGou} 类带 {@code @EventBusSubscriber}，
     * NeoForge 的 {@code AutomaticEventSubscriber} 要求 {@code @SubscribeEvent} 方法为 static。
     */
    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // ===== 第一步：注入酷狗专属 AudioStreamHandler（优先级最高，避免 DirectHttpHandler 用网易云 UA 拉酷狗 403）=====
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
        UrlRefreshScheduler.start();
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
        // 注意：不能只靠 instanceof JukeboxBlock 判定——绝大多数情况下用户放的是 NetMusic 自带的"方块音响"！
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

        // ⚠️ 这里绝对不能同步 forceRefreshOne！RightClickBlock 在服务端主线程，同步 HTTP 2-4 秒会卡爆炸
        // → 玩家体感"插CD卡一下"、方块音响 use() 流程被判定超时、CD 没入槽、必须右键好几次才成功。
        // 改成：提交异步预取 + 存 ConcurrentHashMap；紧接着的 setPlayToClient HEAD 里 tryTake(最多等200ms)，
        // 预取没在 200ms 内完成就先用旧 URL 起播，后台完事后通过 callback 延迟 1 tick 重新 setPlayToClient 切新 URL。
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
