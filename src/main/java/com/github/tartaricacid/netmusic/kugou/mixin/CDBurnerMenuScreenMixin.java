package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.compat.netmusiclist.NetMusicListCompat;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.client.gui.KuGouSearchScreen;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
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

    @Inject(method = "m_7856_", at = @At("TAIL"), require = 0, remap = false)
    private void netmusickugou$init(CallbackInfo ci) {
        netmusickugou$initCommon();
    }

    @Inject(method = "m_6574_", at = @At("TAIL"), require = 0, remap = false)
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
            netmusickugou$hookAttempts = 0;
            // 延迟到下一帧执行，避免与 QQ 模组 resize 注入的时序竞争
            Minecraft.getInstance().execute(() -> {
                if (!netmusickugou$qqHooked) {
                    netmusickugou$hookQqButtons();
                }
            });
        } else {
            netmusickugou$refreshSearchUi();
        }
    }

    @Unique
    private Button netmusickugou$qqProviderButton;
    @Unique
    private boolean netmusickugou$qqHooked = false;
    @Unique
    private int netmusickugou$hookAttempts = 0;

    @Unique
    private void netmusickugou$initCommon() {
        // 若未安装 QQ 模组但配置仍停留在 QQ，自动切回网易云，避免显示不可用的 QQ 渠道
        if (!netmusickugou$isQqModLoaded() && ClientConfig.getProvider() == ProviderType.QQ) {
            ClientConfig.setProvider(ProviderType.NETEASE);
        }

        if (netmusickugou$isQqModLoaded()) {
            // QQ 模组已加载：必须等 QQ 自己的 init/resize（把按钮加进界面）执行完后再替换，
            // 否则本帧同步调用时按钮尚未生成，findQqButton 会找不到。用下一帧 execute 保证时序。
            netmusickugou$qqHooked = false;
            netmusickugou$hookAttempts = 0;
            Minecraft.getInstance().execute(() -> {
                if (!netmusickugou$qqHooked) {
                    netmusickugou$hookQqButtons();
                }
            });
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

        netmusickugou$refreshSearchUi();
    }

    @Unique
    private boolean netmusickugou$isQqModLoaded() {
        try {
            return net.minecraftforge.fml.ModList.get() != null
                    && net.minecraftforge.fml.ModList.get().isLoaded("netmusiccanneedqq");
        } catch (Throwable t) {
            return false;
        }
    }

    @Unique
    private void netmusickugou$hookQqButtons() {
        if (netmusickugou$qqHooked) return;
        if (netmusickugou$hookAttempts >= 20) {
            KuGouLogger.warn("QQ hook exceeded max attempts, giving up");
            return;
        }
        netmusickugou$hookAttempts++;
        try {
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

            // 若旧按钮对象已不在当前屏幕 widget 列表里（屏幕实例被复用时），重置字段
            if (this.netmusickugou$providerButton != null && !netmusickugou$isWidgetInScreen(this.netmusickugou$providerButton)) {
                this.netmusickugou$providerButton = null;
            }

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

            netmusickugou$refreshSearchUi();

            boolean ok = this.netmusickugou$providerButton != null
                    && this.netmusickugou$searchButton != null;
            if (ok) {
                netmusickugou$qqHooked = true;
                KuGouLogger.info("QQ hook succeeded after {} attempt(s)", netmusickugou$hookAttempts);
            } else {
                KuGouLogger.warn("QQ hook attempt {} failed (provider={}, search={}), will retry",
                        netmusickugou$hookAttempts,
                        this.netmusickugou$providerButton != null,
                        this.netmusickugou$searchButton != null);
                Minecraft.getInstance().execute(() -> {
                    if (!netmusickugou$qqHooked) {
                        netmusickugou$hookQqButtons();
                    }
                });
            }
        } catch (Throwable t) {
            KuGouLogger.warn("QQ hook failed: {}", t.getMessage());
            Minecraft.getInstance().execute(() -> {
                if (!netmusickugou$qqHooked) {
                    netmusickugou$hookQqButtons();
                }
            });
        }
    }

    @Unique
    private void netmusickugou$onSearchClicked() {
        if (ClientConfig.getProvider() == ProviderType.KUGOU) {
            netmusickugou$openSearch();
        }
    }

    @Unique
    private void netmusickugou$removeWidget(Object widget) {
        try {
            this.renderables.remove(widget);
        } catch (Throwable ignored) {}
        try {
            this.children().remove(widget);
        } catch (Throwable ignored) {}
    }

    @Unique
    private boolean netmusickugou$isWidgetInScreen(Object widget) {
        if (widget == null) return false;
        return this.renderables.contains(widget) || this.children().contains(widget);
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
        netmusickugou$refreshSearchUi();
    }

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

    @Inject(method = "handleCraftButton", at = @At("HEAD"), cancellable = true, require = 0)
    private void netmusickugou$handleCraftButton(CallbackInfo ci) {
        if (ClientConfig.getProvider() != ProviderType.KUGOU || this.netmusickugou$lastKuGouResult == null) {
            // 冲突规避：装了网络音乐机时，保留酷狗按钮（否则无法搜索酷狗），
            // 仅在选了酷狗源、但输入槽是普通 CD（非「音乐列表」物品）时给个烧多首的提示。
            if (NetMusicListCompat.isNetMusicListLoaded() && ClientConfig.getProvider() == ProviderType.KUGOU) {
                ItemStack in = this.getMenu().getSlot(0).getItem();
                if (in != null && !in.isEmpty() && !NetMusicListCompat.isMusicListItem(in)) {
                    this.tips = Component.translatable("netmusic_kugou.cd.use_list_cd_hint");
                }
            }
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

        netmusickugou$burning = true;
        this.tips = Component.translatable("netmusic_kugou.cd.fetching_info");

        CompletableFuture<String> urlFuture = KuGouApiClient.getSongUrl(result.fileHash, result.albumId)
                .orTimeout(15, TimeUnit.SECONDS);

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

                        BurnDataCache.set(result.fileHash, result.albumId, lrc, lrcTrans);
                        NetworkHandler.CHANNEL.sendToServer(new SetMusicIDMessage(songInfo));
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
    private void netmusickugou$refreshSearchUi() {
        if (this.textField == null) {
            return;
        }

        if (netmusickugou$isQqModLoaded()) {
            Button qqSearchBtn = netmusickugou$findQqButton(new String[]{"搜索歌曲", "搜索"}, new String[]{"search", "Search"});
            if (qqSearchBtn != null) {
                boolean isQq = ClientConfig.getProvider() == ProviderType.QQ;
                qqSearchBtn.visible = isQq;
                qqSearchBtn.active = isQq;

                if (ClientConfig.getProvider() == ProviderType.KUGOU) {
                    // 若旧按钮对象已不在当前屏幕列表里（屏幕复用），重置并重新创建
                    if (this.netmusickugou$searchButton != null && !netmusickugou$isWidgetInScreen(this.netmusickugou$searchButton)) {
                        this.netmusickugou$searchButton = null;
                    }
                    if (this.netmusickugou$searchButton == null) {
                        int sx, sy, sw, sh;
                        if (qqSearchBtn != null) {
                            sx = qqSearchBtn.getX();
                            sy = qqSearchBtn.getY();
                            sw = qqSearchBtn.getWidth();
                            sh = qqSearchBtn.getHeight();
                        } else if (this.netmusickugou$providerButton != null) {
                            sx = this.netmusickugou$providerButton.getX() + this.netmusickugou$providerButton.getWidth() + 4;
                            sy = this.netmusickugou$providerButton.getY();
                            sw = 50;
                            sh = 20;
                            KuGouLogger.warn("Refresh UI: QQ search button not found, fallback next to provider button");
                        } else {
                            sx = this.leftPos + 60;
                            sy = this.topPos + 68;
                            sw = 50;
                            sh = 20;
                            KuGouLogger.warn("Refresh UI: neither QQ search nor provider button found, using default position");
                        }
                        this.netmusickugou$searchButton = Button.builder(
                                Component.translatable("netmusic_kugou.cd.kugou_search"),
                                b -> netmusickugou$onSearchClicked())
                                .pos(sx, sy).size(sw, sh).build();
                        this.addRenderableWidget(this.netmusickugou$searchButton);
                        KuGouLogger.info("Refresh UI: added kugou search button at ({},{}) size {}x{}", sx, sy, sw, sh);
                    }
                    this.netmusickugou$searchButton.visible = true;
                    this.netmusickugou$searchButton.active = true;
                } else {
                    if (this.netmusickugou$searchButton != null) {
                        netmusickugou$removeWidget(this.netmusickugou$searchButton);
                        this.netmusickugou$searchButton = null;
                    }
                }
            } else {
                KuGouLogger.warn("Refresh UI: QQ search button NOT found!");
            }
        } else {
            if (this.netmusickugou$searchButton != null) {
                boolean isKugou = ClientConfig.getProvider() == ProviderType.KUGOU;
                this.netmusickugou$searchButton.visible = isKugou;
                this.netmusickugou$searchButton.active = isKugou;
            }
        }
    }
}
