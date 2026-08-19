package com.github.tartaricacid.netmusic.kugou;

import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.api.KuGouLoginApi;
import com.github.tartaricacid.netmusic.kugou.api.KuGouVipApi;
import com.github.tartaricacid.netmusic.kugou.client.gui.KuGouLoginScreen;
import com.github.tartaricacid.netmusic.kugou.config.AudioQuality;
import com.github.tartaricacid.netmusic.kugou.config.ButtonEntry;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.config.ProviderType;
import com.github.tartaricacid.netmusic.kugou.init.InitDataComponent;
import com.github.tartaricacid.netmusic.kugou.network.NetworkHandler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
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
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod(NetMusicKuGou.MOD_ID)
@EventBusSubscriber(modid = NetMusicKuGou.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class NetMusicKuGou {
    public static final String MOD_ID = "netmusic_kugou";

    private static final Gson GSON = new Gson();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get().resolve("NETMUSICCANNEEDKUGOU");
    private static final Path STATE_FILE = CONFIG_DIR.resolve("netmusic-kugou-state.json");

    /**
     * 周期重试 VIP 领取的调度器。
     * <p>
     * 仅在客户端构造；daemon 线程保证不会阻塞游戏进程退出。
     * 调度器会在第一次自动领取失败后按 {@link ClientConfig#VIP_RETRY_INTERVAL_MINUTES} 的间隔持续重试，
     * 直到服务端返回 SUCCESS / ALREADY_CLAIMED，或日期跨日。
     */
    private static ScheduledExecutorService vipScheduler;
    private static ScheduledExecutorService urlRefreshScheduler;

    /**
     * 周期扫描玩家物品栏、检查并自动续期失效 CD URL 的调度器。
     * <p>
     * 由 {@link #startUrlRefreshScheduler()} 在服务端起来时启动，
     * 任务抛到 MinecraftServer 主线程执行（修改 ItemStack NBT 必须在主线程）。
     * 调度器本身只负责"到点了发个信号"，主线程内仍串行处理所有玩家。
     * <p>
     * <b>非 final</b>：重进游戏存档时 {@code onServerStopped} 会 shutdown 旧实例，
     * 下一轮 {@code onServerStarted} 触发 {@link #startUrlRefreshScheduler()} 时若继续
     * 在已 terminated 的 executor 上 {@code scheduleAtFixedRate} 会抛
     * {@link java.util.concurrent.RejectedExecutionException}，导致服务端崩溃。本字段允许在启动时重建。
     */

    public NetMusicKuGou(IEventBus modEventBus, ModContainer modContainer) {
        // 入口：构造器接收 IEventBus 和 ModContainer

        // 初始化独立日志系统
        KuGouLogger.init();

        // 确保配置目录存在
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            KuGouLogger.error("Failed to create config dir: {}", e.getMessage());
        }

        // 注册客户端配置
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "NETMUSICCANNEEDKUGOU/netmusic-kugou-client.toml");

        // 注册 DeferredRegister（无方块/物品/容器/BlockEntity，父模组的烧录机已足够）
        InitDataComponent.DATA_COMPONENT_TYPES.register(modEventBus);

        // 网络包注册（通过 RegisterPayloadHandlersEvent）
        modEventBus.addListener(NetworkHandler::register);

        // FMLCommonSetupEvent 仍在 modEventBus 上分发
        modEventBus.addListener(this::setup);

        // 游戏事件（ServerStarted/Stopping, PlayerLoggedIn, RightClickBlock 等）由
        // @EventBusSubscriber(bus = Bus.GAME) 自动注册到 NeoForge.EVENT_BUS，
        // 无需手动 register(this)。手动 register(this) 会因重复注册导致崩溃。

        if (FMLEnvironment.dist.isClient() && ModList.get().isLoaded("cloth_config")) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (java.util.function.Supplier<IConfigScreenFactory>) () ->
                            (mc, parent) -> createConfigScreen(net.minecraft.client.Minecraft.getInstance(), parent));
        }

        vipScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NetMusicKuGou-VipRetry");
            t.setDaemon(true);
            return t;
        });
        urlRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NetMusicKuGou-UrlRefresh");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 使用 ClothConfig Builder 创建配置界面
     */
    private Screen createConfigScreen(net.minecraft.client.Minecraft client, Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("NetMusic Need KuGou Config"))
                .setSavingRunnable(ClientConfig.SPEC::save);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // ========== 分类 1：酷狗登录 ==========
        ConfigCategory loginCat = builder.getOrCreateCategory(Component.literal("酷狗登录"));

        String loginStatusText = KuGouConfig.isLoggedIn()
                ? "✔ 已登录 (UserID: " + KuGouConfig.userid + ")"
                : "✘ 未登录";
        loginCat.addEntry(entryBuilder.startTextDescription(Component.literal(loginStatusText))
                .build());

        loginCat.addEntry(ButtonEntry.of(Component.empty(), Component.literal("扫码登录"), () -> {
            client.setScreen(new KuGouLoginScreen(parent));
        }));

        loginCat.addEntry(ButtonEntry.of(Component.empty(), Component.literal("退出登录"), () -> {
            KuGouLoginApi.logout();
            client.setScreen(createConfigScreen(client, parent));
        }));

        // ========== 分类 2：音乐源 ==========
        ConfigCategory sourceCat = builder.getOrCreateCategory(Component.literal("音乐源"));

        sourceCat.addEntry(entryBuilder.startEnumSelector(
                        Component.literal("音乐源"),
                        ProviderType.class,
                        ClientConfig.getProvider())
                .setDefaultValue(ProviderType.NETEASE)
                .setTooltip(Component.literal("选择音乐数据来源"))
                .setSaveConsumer(ClientConfig::setProvider)
                .build());

        sourceCat.addEntry(entryBuilder.startStrField(Component.literal("VIP Cookie"), ClientConfig.getVipCookie())
                .setDefaultValue("")
                .setTooltip(Component.literal("VIP Cookie 用于解锁付费歌曲（扫码登录后自动填入）"))
                .setSaveConsumer(ClientConfig.VIP_COOKIE::set)
                .build());

        sourceCat.addEntry(entryBuilder.startEnumSelector(
                        Component.literal("播放音质"),
                        AudioQuality.class,
                        ClientConfig.getAudioQuality())
                .setDefaultValue(AudioQuality.HQ)
                .setTooltip(Component.literal("选择歌曲播放音质（128=标准, 320=HQ, flac=无损, super=DSD）"))
                .setSaveConsumer(ClientConfig::setAudioQuality)
                .build());

        // ========== 分类 3：VIP ==========
        ConfigCategory vipCat = builder.getOrCreateCategory(Component.literal("VIP"));

        vipCat.addEntry(entryBuilder.startBooleanToggle(Component.literal("自动领取每日VIP"),
                        ClientConfig.AUTO_RECEIVE_VIP.get())
                .setDefaultValue(false)
                .setTooltip(Component.literal("启动时自动调用概念版接口领取每日VIP"))
                .setSaveConsumer(ClientConfig.AUTO_RECEIVE_VIP::set)
                .build());

        vipCat.addEntry(entryBuilder.startIntSlider(Component.literal("失败后重试间隔(分钟)"),
                        ClientConfig.VIP_RETRY_INTERVAL_MINUTES.get(), 1, 1440)
                .setDefaultValue(10)
                .setTooltip(Component.literal("首次自动领取失败后，每隔该分钟数自动重试一次。\n"
                        + "服务器返回 20002 (今日已领) 后会停止重试直到次日。"))
                .setSaveConsumer(ClientConfig.VIP_RETRY_INTERVAL_MINUTES::set)
                .build());

        StringBuilder statusBuilder = new StringBuilder();
        statusBuilder.append("上次状态: ").append(KuGouVipApi.lastClaimStatus);
        if (!KuGouVipApi.lastClaimDate.isEmpty()) {
            statusBuilder.append(" (").append(KuGouVipApi.lastClaimDate).append(")");
        }
        if (KuGouVipApi.lastVipResultMessage != null && !KuGouVipApi.lastVipResultMessage.isEmpty()) {
            statusBuilder.append("\n").append(KuGouVipApi.lastVipResultMessage);
        }
        vipCat.addEntry(entryBuilder.startTextDescription(Component.literal(statusBuilder.toString()))
                .build());

        vipCat.addEntry(ButtonEntry.of(Component.empty(), Component.literal("立即领取VIP"), () -> {
            if (!KuGouConfig.isLoggedIn()) {
                KuGouLogger.warn("Cannot manually claim VIP: not logged in");
                return;
            }
            KuGouLogger.info("Manually triggered VIP claim by user");
            triggerAutoReceiveVip();
        }));

        // ========== 分类 4：歌词显示 ==========
        ConfigCategory lyricCat = builder.getOrCreateCategory(Component.literal("歌词显示"));

        lyricCat.addEntry(entryBuilder.startTextDescription(Component.literal(
                "控制歌词翻译 / 罗马音（音译）的显示。\n"
                        + "方块音响、女仆气泡共用这两个开关。\n"
                        + "示例（酷狗歌曲同时有 type=1 翻译 + type=0 罗马音时）：\n"
                        + "\n"
                        + "  原文：    君が代\n"
                        + "  翻译：    君王之治\n"
                        + "  罗马音：  kimi ga yo\n"
                        + "\n"
                        + "  -- 只开'翻译'时：原文 + 翻译 = 2 行\n"
                        + "  -- 只开'罗马音'时：原文 + 罗马音 = 2 行\n"
                        + "  -- 两个都开时：原文 + 翻译 + 罗马音 = 3 行"))
                .build());

        lyricCat.addEntry(entryBuilder.startBooleanToggle(Component.literal("显示翻译"),
                        ClientConfig.LYRIC_SHOW_TRANSLATION.get())
                .setDefaultValue(true)
                .setTooltip(Component.literal("开启后，酷狗 type=1 的中文翻译会作为第二行显示。\n"
                        + "（仅当歌曲本身有翻译数据时才有效）"))
                .setSaveConsumer(ClientConfig.LYRIC_SHOW_TRANSLATION::set)
                .build());

        lyricCat.addEntry(entryBuilder.startBooleanToggle(Component.literal("显示罗马音 / 音译"),
                        ClientConfig.LYRIC_SHOW_ROMAJI.get())
                .setDefaultValue(false)
                .setTooltip(Component.literal("开启后，酷狗 type=0 的罗马音 / 音译会作为第三行显示。\n"
                        + "（仅当歌曲本身有罗马音数据时才有效）\n"
                        + "如果'翻译'和'罗马音'都开且歌曲两类数据都有，\n"
                        + "歌词区域会显示三行：原文 + 翻译 + 罗马音。"))
                .setSaveConsumer(ClientConfig.LYRIC_SHOW_ROMAJI::set)
                .build());

        return builder.build();
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 从配置文件加载持久化的登录状态
            loadState();

            // 异步注册设备
            KuGouApiClient.ensureDeviceRegistered()
                    .thenAccept(ready -> {
                        KuGouLogger.info("Device registration: {}", ready ? "success" : "failed");

                        if (ready && ClientConfig.AUTO_RECEIVE_VIP.get() && KuGouConfig.isLoggedIn()) {
                            startVipRetryScheduler();
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
    public static void onClientLoggingIn(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
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
        triggerAutoReceiveVip();
    }

    private static void triggerAutoReceiveVip() {
        String today = KuGouVipApi.toBeijingDateString(-1L);
        KuGouVipApi.receiveDailyVip(KuGouConfig.userid, today)
                .thenAccept(receiveResult -> {
                    KuGouLogger.info("[NetMusicKuGou] receiveDailyVip result: {}", receiveResult);
                    KuGouVipApi.upgradeVipReward(KuGouConfig.userid)
                            .thenAccept(upgraded ->
                                    KuGouLogger.info("[NetMusicKuGou] VIP upgrade: {}",
                                            upgraded ? "success" : "skipped/failed"))
                            .exceptionally(e -> {
                                KuGouLogger.error("[NetMusicKuGou] upgradeVipReward threw an exception", e);
                                return null;
                            });
                })
                .exceptionally(e -> {
                    KuGouLogger.error("[NetMusicKuGou] receiveDailyVip threw an exception", e);
                    return null;
                });
    }

    private static void startVipRetryScheduler() {
        // shutdown 后重建实例，避免 RejectedExecutionException
        if (vipScheduler.isShutdown()) {
            vipScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NetMusicKuGou-VipRetry");
                t.setDaemon(true);
                return t;
            });
        }
        int minutes = ClientConfig.VIP_RETRY_INTERVAL_MINUTES.get();
        vipScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!FMLEnvironment.dist.isClient()) {
                    return;
                }
                if (!ClientConfig.AUTO_RECEIVE_VIP.get() || !KuGouConfig.isLoggedIn()) {
                    return;
                }
                if (KuGouVipApi.lastClaimStatus == KuGouVipApi.ClaimStatus.IN_PROGRESS) {
                    return;
                }
                if (!KuGouVipApi.shouldRetryToday()) {
                    return;
                }
                KuGouLogger.info("[NetMusicKuGou] Periodic VIP retry (status={}, date={})",
                        KuGouVipApi.lastClaimStatus, KuGouVipApi.lastClaimDate);
                triggerAutoReceiveVip();
            } catch (Throwable t) {
                KuGouLogger.error("[NetMusicKuGou] Periodic VIP retry crashed", t);
            }
        }, minutes, minutes, TimeUnit.MINUTES);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        startUrlRefreshScheduler();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        if (!ClientConfig.URL_REFRESH_ENABLED.get()) {
            return;
        }
        if (!KuGouConfig.isLoggedIn()) {
            return;
        }
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                com.github.tartaricacid.netmusic.kugou.support.UrlRefresher refresher =
                        new com.github.tartaricacid.netmusic.kugou.support.UrlRefresher();
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
        saveState();
        shutdownVipScheduler();
        shutdownUrlRefreshScheduler();
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
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock()
                instanceof net.minecraft.world.level.block.JukeboxBlock)) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper.isMusicCd(held)) {
            return;
        }
        if (com.github.tartaricacid.netmusic.kugou.support.CdNbtHelper.readOriginalInfo(held).isEmpty()) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState jukeboxState =
                event.getLevel().getBlockState(event.getPos());
        if (jukeboxState.hasProperty(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD)
                && jukeboxState.getValue(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD)) {
            return;
        }

        try {
            com.github.tartaricacid.netmusic.kugou.support.UrlRefresher refresher =
                    new com.github.tartaricacid.netmusic.kugou.support.UrlRefresher();
            boolean refreshed = refresher.tryRefreshOne(held);
            if (refreshed) {
                KuGouLogger.info("[UrlRefresh] Refreshed CD on jukebox insert at {} for player {}",
                        event.getPos(), event.getEntity().getName().getString());
            }
        } catch (Throwable t) {
            KuGouLogger.warn("[UrlRefresh] On-insert probe failed, letting insert proceed: {}", t.getMessage());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        saveState();
    }

    private static void shutdownVipScheduler() {
        if (vipScheduler != null && !vipScheduler.isShutdown()) {
            vipScheduler.shutdown();
            try {
                if (!vipScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    vipScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                vipScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void startUrlRefreshScheduler() {
        if (urlRefreshScheduler.isShutdown()) {
            KuGouLogger.info("[NetMusicKuGou] UrlRefresh scheduler was terminated (likely world reload); re-creating");
            urlRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NetMusicKuGou-UrlRefresh");
                t.setDaemon(true);
                return t;
            });
        }
        int hours = ClientConfig.URL_REFRESH_INTERVAL_HOURS.get();
        urlRefreshScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!ClientConfig.URL_REFRESH_ENABLED.get()) {
                    return;
                }
                net.minecraft.server.MinecraftServer server =
                        net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server == null) {
                    return;
                }
                server.execute(() -> {
                    try {
                        com.github.tartaricacid.netmusic.kugou.support.UrlRefresher refresher =
                                new com.github.tartaricacid.netmusic.kugou.support.UrlRefresher();
                        refresher.scanAll();
                    } catch (Throwable t) {
                        KuGouLogger.error("[UrlRefresh] Scan crashed", t);
                    }
                });
            } catch (Throwable t) {
                KuGouLogger.error("[UrlRefresh] Scheduler tick crashed", t);
            }
        }, hours, hours, TimeUnit.HOURS);
    }

    private static void shutdownUrlRefreshScheduler() {
        if (urlRefreshScheduler != null && !urlRefreshScheduler.isShutdown()) {
            urlRefreshScheduler.shutdown();
            try {
                if (!urlRefreshScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    urlRefreshScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                urlRefreshScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void loadState() {
        if (!Files.exists(STATE_FILE)) {
            return;
        }

        try {
            String json = Files.readString(STATE_FILE);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root.has("token")) KuGouConfig.token = root.get("token").getAsString();
            if (root.has("userid")) KuGouConfig.userid = root.get("userid").getAsString();
            if (root.has("dfid")) KuGouConfig.dfid = root.get("dfid").getAsString();
            if (root.has("mid")) KuGouConfig.mid = root.get("mid").getAsString();
            if (root.has("guid")) KuGouConfig.guid = root.get("guid").getAsString();
            if (root.has("vipType")) KuGouConfig.vipType = root.get("vipType").getAsString();
            if (root.has("vipToken")) KuGouConfig.vipToken = root.get("vipToken").getAsString();
            if (root.has("cookies")) {
                JsonObject cookiesObj = root.getAsJsonObject("cookies");
                for (String key : cookiesObj.keySet()) {
                    KuGouConfig.addCookie(key, cookiesObj.get(key).getAsString());
                }
            }
            KuGouLogger.info("Login state loaded. Logged in: {}", KuGouConfig.isLoggedIn());
        } catch (Exception e) {
            KuGouLogger.error("Failed to load login state", e);
        }
    }

    public static void saveState() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonObject root = new JsonObject();
            root.addProperty("token", KuGouConfig.token != null ? KuGouConfig.token : "");
            root.addProperty("userid", KuGouConfig.userid != null ? KuGouConfig.userid : "");
            root.addProperty("dfid", KuGouConfig.dfid != null ? KuGouConfig.dfid : "");
            root.addProperty("mid", KuGouConfig.mid != null ? KuGouConfig.mid : "");
            root.addProperty("guid", KuGouConfig.guid != null ? KuGouConfig.guid : "");
            root.addProperty("vipType", KuGouConfig.vipType != null ? KuGouConfig.vipType : "");
            root.addProperty("vipToken", KuGouConfig.vipToken != null ? KuGouConfig.vipToken : "");

            JsonObject cookiesObj = new JsonObject();
            for (var entry : KuGouConfig.cookies.entrySet()) {
                cookiesObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("cookies", cookiesObj);

            Files.writeString(STATE_FILE, GSON.toJson(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            KuGouLogger.error("Failed to save login state", e);
        }
    }
}
