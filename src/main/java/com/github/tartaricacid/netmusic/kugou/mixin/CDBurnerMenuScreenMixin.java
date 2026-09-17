package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.client.gui.KuGouSearchScreen;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.compat.netmusiclist.NetMusicListCompat;
import com.github.tartaricacid.netmusic.kugou.config.ProviderType;
import com.github.tartaricacid.netmusic.kugou.lyric.BurnDataCache;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage;
import com.google.common.collect.Lists;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Mixin(value = CDBurnerMenuScreen.class, remap = false, priority = 2000)
public abstract class CDBurnerMenuScreenMixin extends AbstractContainerScreen<AbstractContainerMenu> {
    @Shadow
    private EditBox textField;
    @Shadow
    private Checkbox readOnlyButton;
    @Shadow
    private Component tips;

    @Unique
    private Button netmusickugou$providerButton;
    @Unique
    private Button netmusickugou$searchButton;
    @Unique
    private KuGouSearchScreen.SearchResult netmusickugou$lastKuGouResult;
    @Unique
    private volatile boolean netmusickugou$burning = false;

    protected CDBurnerMenuScreenMixin(AbstractContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0, remap = false)
    private void netmusickugou$init(CallbackInfo ci) {
        netmusickugou$initCommon();
    }

    @Inject(method = "resize", at = @At("TAIL"), require = 0, remap = false)
    private void netmusickugou$resize(Minecraft minecraft, int width, int height, CallbackInfo ci) {
        if (netmusickugou$isQqModLoaded()) {
            if (this.netmusickugou$providerButton != null) {
                netmusickugou$removeWidget(this.netmusickugou$providerButton);
                this.netmusickugou$providerButton = null;
            }
            if (this.netmusickugou$searchButton != null) {
                netmusickugou$removeWidget(this.netmusickugou$searchButton);
                this.netmusickugou$searchButton = null;
            }
            netmusickugou$qqHooked = false;
            netmusickugou$hookQqButtons();
        } else {
            netmusickugou$updateSearchUi();
        }
    }

    @Unique
    private Button netmusickugou$qqProviderButton;
    @Unique
    private boolean netmusickugou$qqHooked = false;

    @Unique
    private void netmusickugou$initCommon() {
        // 放大刻录输入框上限，避免酷狗 32 位 fileHash 被刻录界面输入框的 setMaxLength 截断（否则播放解析时找不到歌曲）
        if (this.textField != null) {
            try {
                this.textField.setMaxLength(4096);
            } catch (Throwable ignored) {
            }
        }
        // netMusicList 已加载：酷狗源走对方的源选择器，needkugou 不再注入任何按钮
        if (NetMusicListCompat.isNetMusicListLoaded()) {
            return;
        }
        // 若未安装 QQ 模组但配置仍停留在 QQ，自动切回网易云，避免显示不可用的 QQ 渠道
        if (!netmusickugou$isQqModLoaded() && ClientConfig.getProvider() == ProviderType.QQ) {
            ClientConfig.setProvider(ProviderType.NETEASE);
        }

        if (netmusickugou$isQqModLoaded()) {
            // QQ 模组存在：每次 init 都重新 hook（super.init() clearWidgets 会清空之前的按钮）
            // 用 priority=2000 确保在 QQ mod init TAIL 之后同步执行
            netmusickugou$qqHooked = false;
            netmusickugou$hookQqButtons();
            // 兜底：若 hook 时找不到按钮（QQ mod TAIL 还没跑完），再延后一帧重试一次
            if (!netmusickugou$qqHooked) {
                Minecraft.getInstance().execute(() -> {
                    if (!netmusickugou$qqHooked) {
                        netmusickugou$hookQqButtons();
                    }
                });
            }
            return;
        }

        int rowY = this.topPos + 68;
        this.netmusickugou$providerButton = Button.builder(netmusickugou$getProviderLabel(),
                button -> netmusickugou$toggleProvider())
                .pos(this.leftPos + 8, rowY)
                .size(50, 20)
                .build();
        this.addRenderableWidget(this.netmusickugou$providerButton);

        this.netmusickugou$searchButton = Button.builder(Component.translatable("netmusic_kugou.gui.search"),
                button -> netmusickugou$openSearch())
                .pos(this.leftPos + 60, rowY)
                .size(50, 20)
                .build();
        this.addRenderableWidget(this.netmusickugou$searchButton);

        netmusickugou$updateSearchUi();
    }

    @Unique
    private boolean netmusickugou$isQqModLoaded() {
        try {
            return net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded("netmusiccanneedqq");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射找到 QQ 模组的两个按钮，移除后用自己的按钮替换 */
    @Unique
    private void netmusickugou$hookQqButtons() {
        if (netmusickugou$qqHooked) return;
        netmusickugou$qqHooked = true;
        try {
            // 以我们自己的 ClientConfig 为权威（避免 QQ mod 默认 NETEASE 把我们也拉回去）
            // 但只有当我们的 provider 不是 NETEASE 时才覆盖（首次进入时用默认值）
            ProviderType ours = ClientConfig.getProvider();
            ProviderType initial = ours;
            try {
                Class<?> qqConfig = Class.forName("yincmewy.netmusiccanneedqq.config.ClientConfig");
                Object qqCurrent = qqConfig.getMethod("getProvider").invoke(null);
                String qqName = qqCurrent != null ? qqCurrent.toString() : "NETEASE";
                ProviderType qqValue = netmusickugou$fromQqProviderName(qqName);
                if (ours == ProviderType.NETEASE && qqValue == ProviderType.QQ) {
                    initial = ProviderType.QQ;
                }
            } catch (Throwable ignored) {}

            ClientConfig.setProvider(initial);
            netmusickugou$syncQqProvider(initial);
            KuGouLogger.info("QQ hook: initial provider = {} (ours={}, qq mod synced)", initial, ours);

            Button qqProviderBtn = netmusickugou$findQqButton(new String[]{"163", "QQ"}, new String[]{"provider", "Provider"});

            if (qqProviderBtn != null) {
                int px = qqProviderBtn.getX(), py = qqProviderBtn.getY();
                int pw = qqProviderBtn.getWidth(), ph = qqProviderBtn.getHeight();
                netmusickugou$removeWidget(qqProviderBtn);
                if (this.netmusickugou$providerButton != null) {
                    netmusickugou$removeWidget(this.netmusickugou$providerButton);
                }
                this.netmusickugou$providerButton = Button.builder(
                        netmusickugou$getProviderLabel(),
                        b -> netmusickugou$toggleProvider())
                        .pos(px, py).size(pw, ph).build();
                this.addRenderableWidget(this.netmusickugou$providerButton);
                KuGouLogger.info("QQ hook: replaced provider button at ({},{})", px, py);
            }

            // QQ/NETEASE 模式时不创建（避免遮挡 QQ 自带搜索按钮，导致 QQ 搜索功能失效）
            Button qqSearchBtn = netmusickugou$findQqButton(new String[]{"搜索歌曲", "搜索"}, new String[]{"search", "Search"});
            if (qqSearchBtn != null) {
                KuGouLogger.info("QQ hook: found QQ search button visible={}, active={}, x={}, y={}, w={}, h={}",
                        qqSearchBtn.visible, qqSearchBtn.active, qqSearchBtn.getX(), qqSearchBtn.getY(), qqSearchBtn.getWidth(), qqSearchBtn.getHeight());
                boolean showQqSearch = ClientConfig.getProvider() == ProviderType.QQ;
                qqSearchBtn.visible = showQqSearch;
                qqSearchBtn.active = showQqSearch;
                KuGouLogger.info("QQ hook: forced QQ search button visible={}", showQqSearch);

                // 只在 KUGOU 模式时才创建「酷狗搜索」按钮覆盖 QQ 搜索按钮位置
                // QQ/NETEASE 模式时不创建，避免遮挡 QQ 自带按钮
                if (ClientConfig.getProvider() == ProviderType.KUGOU) {
                    int sx = qqSearchBtn.getX(), sy = qqSearchBtn.getY();
                    int sw = qqSearchBtn.getWidth(), sh = qqSearchBtn.getHeight();
                    if (this.netmusickugou$searchButton != null) {
                        netmusickugou$removeWidget(this.netmusickugou$searchButton);
                    }
                    this.netmusickugou$searchButton = Button.builder(
                            Component.translatable("netmusic_kugou.cd.kugou_search"),
                            b -> netmusickugou$onSearchClicked())
                            .pos(sx, sy).size(sw, sh).build();
                    this.addRenderableWidget(this.netmusickugou$searchButton);
                    KuGouLogger.info("QQ hook: added kugou search button at ({},{}) next to QQ search", sx, sy);
                } else {
                    // QQ/NETEASE 模式：不创建「酷狗搜索」按钮，确保 QQ 按钮可正常点击
                    if (this.netmusickugou$searchButton != null) {
                        netmusickugou$removeWidget(this.netmusickugou$searchButton);
                        this.netmusickugou$searchButton = null;
                    }
                }
            } else {
                KuGouLogger.warn("QQ hook: QQ search button NOT found!");
            }

            netmusickugou$updateSearchUi();
        } catch (Throwable t) {
            KuGouLogger.warn("QQ hook failed: {}", t.getMessage());
        }
    }

    /** 搜索按钮被点击时：如果是酷狗 provider，打开酷狗搜索；否则不拦截 */
    @Unique
    private void netmusickugou$onSearchClicked() {
        if (ClientConfig.getProvider() == ProviderType.KUGOU) {
            netmusickugou$openSearch();
        }
    }

    /** 反射从 Screen 的 renderables 和 children 列表移除一个 widget */
    @Unique
    private void netmusickugou$removeWidget(Object widget) {
        try {
            this.renderables.remove(widget);
        } catch (Throwable ignored) {}
        try {
            // children 是 private 的，用反射
            java.lang.reflect.Field f = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("children");
            f.setAccessible(true);
            java.util.List<?> children = (java.util.List<?>) f.get(this);
            if (children != null) {
                children.remove(widget);
            }
        } catch (Throwable t) {
            KuGouLogger.warn("removeWidget children failed: {}", t.getMessage());
        }
    }

    @Unique
    private Button netmusickugou$findQqButton(String[] labels, String[] fieldNameHints) {
        Button labelHit = netmusickugou$scanAllButtons(labels, null);
        if (labelHit != null) {
            return labelHit;
        }
        Class<?> c = this.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Button.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(this);
                        if (val instanceof Button) {
                            Button btn = (Button) val;
                            String fname = f.getName();
                            if (fname.startsWith("netmusickugou$")) continue;
                            if (fieldNameHints != null) {
                                for (String hint : fieldNameHints) {
                                    if (fname.contains(hint)) {
                                        KuGouLogger.info("Found button by field name: {} -> {}", fname, btn.getMessage());
                                        return btn;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    @Unique
    private Button netmusickugou$scanAllButtons(String[] labels, String[] ignored) {
        Class<?> c = this.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Button.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(this);
                        if (val instanceof Button) {
                            Button btn = (Button) val;
                            Component msg = btn.getMessage();
                            if (msg == null) continue;
                            String str = msg.getString();
                            for (String label : labels) {
                                if (str.equals(label)) {
                                    KuGouLogger.info("Found button by label: {}", str);
                                    return btn;
                                }
                            }
                        }
                    } catch (Throwable ignored2) {}
                }
            }
            c = c.getSuperclass();
        }
        try {
            for (Object obj : this.renderables) {
                if (obj instanceof Button) {
                    Button btn = (Button) obj;
                    Component msg = btn.getMessage();
                    if (msg == null) continue;
                    String str = msg.getString();
                    for (String label : labels) {
                        if (str.equals(label)) {
                            KuGouLogger.info("Found button via renderables: {}", str);
                            return btn;
                        }
                    }
                }
            }
        } catch (Throwable ignored2) {}
        try {
            java.lang.reflect.Field fChildren = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("children");
            fChildren.setAccessible(true);
            java.util.List<?> children = (java.util.List<?>) fChildren.get(this);
            if (children != null) {
                for (Object obj : children) {
                    if (obj instanceof Button) {
                        Button btn = (Button) obj;
                        Component msg = btn.getMessage();
                        if (msg == null) continue;
                        String str = msg.getString();
                        for (String label : labels) {
                            if (str.equals(label)) {
                                KuGouLogger.info("Found button via children: {}", str);
                                return btn;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored2) {}
        return null;
    }

    @Unique
    private ProviderType netmusickugou$fromQqProviderName(String name) {
        if ("QQ".equalsIgnoreCase(name)) return ProviderType.QQ;
        return ProviderType.NETEASE;
    }

    @Unique
    private Component netmusickugou$getProviderLabel() {
        return ClientConfig.getProvider().getDisplayName();
    }

    @Unique
    private void netmusickugou$toggleProvider() {
        ProviderType current = ClientConfig.getProvider();
        ProviderType next;
        if (netmusickugou$isQqModLoaded()) {
            next = current.next();
        } else {
            next = (current == ProviderType.KUGOU) ? ProviderType.NETEASE : ProviderType.KUGOU;
        }
        ClientConfig.setProvider(next);
        this.netmusickugou$lastKuGouResult = null;
        netmusickugou$syncQqProvider(next);
        Component label = netmusickugou$getProviderLabel();
        if (this.netmusickugou$providerButton != null) {
            this.netmusickugou$providerButton.setMessage(label);
        }
        // 同步设置 QQ 搜索按钮的可见性
        netmusickugou$applyQqSearchVisibility(next);
        netmusickugou$updateSearchUi();
    }

    @Unique
    private void netmusickugou$applyQqSearchVisibility(ProviderType ourType) {
        if (!netmusickugou$isQqModLoaded()) return;
        try {
            Button qqSearchBtn = netmusickugou$findQqButton(new String[]{"搜索歌曲", "搜索"}, new String[]{"search", "Search"});
            if (qqSearchBtn != null) {
                boolean showQqSearch = ourType == ProviderType.QQ;
                qqSearchBtn.visible = showQqSearch;
                qqSearchBtn.active = showQqSearch;
                KuGouLogger.info("applyQqSearchVisibility: provider={}, qqSearchBtn.visible={}", ourType, showQqSearch);
            }
        } catch (Throwable t) {
            KuGouLogger.warn("applyQqSearchVisibility failed: {}", t.getMessage());
        }
    }

    /** 切换酷狗状态时同步 QQ 模组的 ClientConfig */
    @Unique
    private void netmusickugou$syncQqProvider(ProviderType ourType) {
        if (!netmusickugou$isQqModLoaded()) return;
        try {
            Class<?> qqConfig = Class.forName("yincmewy.netmusiccanneedqq.config.ClientConfig");
            Class<?> qqProviderType = Class.forName("yincmewy.netmusiccanneedqq.config.ProviderType");
            String qqName = ourType == ProviderType.QQ ? "QQ" : "NETEASE";
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object qqValue = Enum.valueOf((Class<Enum>) qqProviderType, qqName);
            qqConfig.getMethod("setProvider", qqProviderType).invoke(null, qqValue);
            KuGouLogger.info("Synced QQ mod provider to {}", qqValue);
        } catch (Throwable t) {
            KuGouLogger.warn("Failed to sync QQ mod provider: {}", t.getMessage());
        }
    }

    @Unique
    private void netmusickugou$openSearch() {
        if (ClientConfig.getProvider() != ProviderType.KUGOU || this.textField == null) {
            return;
        }
        String currentText = this.textField.getValue();
        Minecraft.getInstance().setScreen(new KuGouSearchScreen(
                this,
                currentText,
                result -> {
                    this.netmusickugou$lastKuGouResult = result;
                    if (this.textField != null) {
                        this.textField.setValue(result.songName);
                    }
                }));
    }

    @Inject(method = "handleCraftButton", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void netmusickugou$handleCraftButton(CallbackInfo ci) {
        // netMusicList 已加载：交给对方的 handleCraftButton 处理刻录，needkugou 不接管（不 cancel）
        if (NetMusicListCompat.isNetMusicListLoaded()) {
            return;
        }
        if (ClientConfig.getProvider() != ProviderType.KUGOU || this.netmusickugou$lastKuGouResult == null) {
            return;
        }

        if (netmusickugou$burning) {
            this.tips = Component.translatable("netmusic_kugou.cd.fetching_info");
            ci.cancel();
            return;
        }

        Slot inputSlot = this.getMenu().getSlot(0);
        ItemStack cd = inputSlot.getItem();
        if (cd.isEmpty()) {
            this.tips = Component.translatable("gui.netmusic.cd_burner.cd_is_empty");
            ci.cancel();
            return;
        }

        ItemMusicCD.SongInfo existingInfo = ItemMusicCD.getSongInfo(cd);
        if (existingInfo != null && existingInfo.readOnly) {
            this.tips = Component.translatable("gui.netmusic.cd_burner.cd_read_only");
            ci.cancel();
            return;
        }

        final KuGouSearchScreen.SearchResult result = this.netmusickugou$lastKuGouResult;
        final boolean readOnly = this.readOnlyButton != null && this.readOnlyButton.selected();

        // ⚠️ 绝不允许在渲染线程里 .get() / 阻塞！并行获取 URL + 歌词，都拿到后再发包。
        // ⚠️ 不要用 final XxxMixin self = this 这种写法：mixin 类不可 JVM 加载，
        netmusickugou$burning = true;
        this.tips = Component.translatable("netmusic_kugou.cd.fetching_info");

        // 构建 URL 获取 future（必须成功）
        CompletableFuture<String> urlFuture = KuGouApiClient.getSongUrl(result.fileHash, result.albumId)
                .orTimeout(15, TimeUnit.SECONDS);

        // 构建歌词获取 future（best-effort，失败不影响刻录）
        String keyword = result.singerName + " - " + result.songName;
        int durationMs = result.duration * 1000;
        CompletableFuture<String[]> lyricFuture = KuGouApiClient.searchLyricCandidates(
                        result.fileHash, keyword, durationMs, result.songName, result.singerName)
                .thenCompose(list -> KuGouApiClient.getLyricWithFallback(list, "krc"))
                .thenApply(content -> content != null && content.lyricContent != null && !content.lyricContent.isEmpty()
                        ? new String[]{content.lyricContent, content.languageJson}
                        : null)
                .exceptionally(e -> {
                    KuGouLogger.warn("KuGou lyric: fetch during burn failed for {}: {}", result.songName, e.getMessage());
                    return null;
                });

        CompletableFuture.allOf(urlFuture, lyricFuture)
                .whenComplete((v, throwable) -> Minecraft.getInstance().execute(() -> {
                    try {
                        if (throwable != null) {
                            KuGouLogger.error("Failed to get song info for KuGou burn", throwable);
                            this.tips = Component.translatable("netmusic_kugou.cd.fetch_failed", throwable.getMessage());
                            return;
                        }
                        String url = urlFuture.getNow(null);
                        if (url == null || url.isEmpty()) {
                            this.tips = Component.translatable("netmusic_kugou.cd.url_failed");
                            return;
                        }

                        // 歌词可能为 null（拉取失败或无歌词），不影响刻录
                        String[] lyricData = lyricFuture.getNow(null);
                        String lrc = (lyricData != null) ? lyricData[0] : null;
                        String lrcTrans = (lyricData != null && lyricData[1] != null) ? lyricData[1] : null;

                        ItemMusicCD.SongInfo songInfo = new ItemMusicCD.SongInfo();
                        songInfo.songName = result.songName;
                        songInfo.songUrl = url;
                        songInfo.songTime = result.duration;
                        songInfo.artists = Lists.newArrayList(result.singerName);
                        songInfo.readOnly = readOnly;

                        String urlPreview = url.length() < 80 ? url : url.substring(0, 80) + "...";
                        KuGouLogger.info("SetMusicIDMessage send: song={}, urlLen={}, prefix={}, lrc={}",
                                result.songName, url.length(), urlPreview, lrc != null ? "yes" : "no");

                        NetworkHandler.sendToServer(new SetMusicIDMessage(songInfo));
                        BurnDataCache.set(result.fileHash, result.albumId, lrc, lrcTrans);
                        KuGouLogger.info("KuGou song burned: {} (hash={}, lrc={})", result.songName, result.fileHash, lrc != null ? "yes" : "no");

                        this.netmusickugou$lastKuGouResult = null;
                        this.tips = Component.translatable("netmusic_kugou.cd.burn_success");
                    } finally {
                        netmusickugou$burning = false;
                    }
                }));

        ci.cancel();
    }

    @Unique
    private void netmusickugou$updateSearchUi() {
        if (this.textField == null) {
            return;
        }
        if (this.netmusickugou$searchButton != null) {
            boolean showSearch = ClientConfig.getProvider() == ProviderType.KUGOU;
            this.netmusickugou$searchButton.visible = showSearch;
            this.netmusickugou$searchButton.active = showSearch;
        }
    }
}
