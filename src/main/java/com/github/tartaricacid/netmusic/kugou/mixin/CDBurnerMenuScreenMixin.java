package com.github.tartaricacid.netmusic.kugou.mixin;

import com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
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

@Mixin(value = CDBurnerMenuScreen.class, remap = false)
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
        netmusickugou$updateSearchUi();
    }

    @Unique
    private void netmusickugou$initCommon() {
        // [酷狗] [搜索] 放在制作唱片和物品栏之间的空白区域
        int rowY = this.topPos + 68;
        this.netmusickugou$providerButton = Button.builder(netmusickugou$getProviderLabel(), button -> netmusickugou$toggleProvider())
                .pos(this.leftPos + 8, rowY)
                .size(50, 20)
                .build();
        this.addRenderableWidget(this.netmusickugou$providerButton);

        this.netmusickugou$searchButton = Button.builder(Component.literal("搜索"), button -> netmusickugou$openSearch())
                .pos(this.leftPos + 60, rowY)
                .size(50, 20)
                .build();
        this.addRenderableWidget(this.netmusickugou$searchButton);

        netmusickugou$updateSearchUi();
    }

    @Unique
    private Component netmusickugou$getProviderLabel() {
        return Component.literal(ClientConfig.getProvider().getDisplayName());
    }

    @Unique
    private void netmusickugou$toggleProvider() {
        ClientConfig.setProvider(ClientConfig.getProvider().next());
        this.netmusickugou$lastKuGouResult = null;
        if (this.netmusickugou$providerButton != null) {
            this.netmusickugou$providerButton.setMessage(netmusickugou$getProviderLabel());
        }
        netmusickugou$updateSearchUi();
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
            return;
        }

        if (netmusickugou$burning) {
            this.tips = Component.literal("正在获取歌曲URL，请稍候...");
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

        // ⚠️ 绝不允许在渲染线程里 .get() / 阻塞！异步获取 URL，拿到后再发 SetMusicIDMessage。
        netmusickugou$burning = true;
        this.tips = Component.literal("正在获取歌曲URL，请稍候...");
        final CDBurnerMenuScreenMixin self = this;

        KuGouApiClient.getSongUrl(result.fileHash, result.albumId)
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((url, throwable) -> {
                    // 异步完成后，切回主线程更新 UI + 发包
                    Minecraft minecraft = Minecraft.getInstance();
                    minecraft.execute(() -> {
                        try {
                            if (throwable != null) {
                                KuGouLogger.error("Failed to get song URL for KuGou burn", throwable);
                                self.tips = Component.literal("获取歌曲URL失败: " + throwable.getMessage());
                                return;
                            }
                            if (url == null || url.isEmpty()) {
                                self.tips = Component.literal("获取歌曲URL失败");
                                return;
                            }

                            ItemMusicCD.SongInfo songInfo = new ItemMusicCD.SongInfo();
                            songInfo.songName = result.songName;
                            songInfo.songUrl = url;
                            songInfo.songTime = result.duration;
                            songInfo.artists = Lists.newArrayList(result.singerName);
                            songInfo.readOnly = readOnly;

                            String urlPreview = url.length() < 80 ? url : url.substring(0, 80) + "...";
                            KuGouLogger.info("SetMusicIDMessage send: song={}, urlLen={}, prefix={}",
                                    result.songName, url.length(), urlPreview);

                            NetworkHandler.sendToServer(new SetMusicIDMessage(songInfo));
                            BurnDataCache.set(result.fileHash, result.albumId);
                            KuGouLogger.info("KuGou song burned: {} (hash={})", result.songName, result.fileHash);

                            self.netmusickugou$lastKuGouResult = null;
                            self.tips = Component.literal("刻录成功 (URL len=" + url.length() + ")");
                        } finally {
                            netmusickugou$burning = false;
                        }
                    });
                });

        ci.cancel();
    }

    @Unique
    private void netmusickugou$updateSearchUi() {
        if (this.textField == null) {
            return;
        }
        boolean showSearch = ClientConfig.getProvider() == ProviderType.KUGOU;
        if (this.netmusickugou$searchButton != null) {
            this.netmusickugou$searchButton.visible = showSearch;
            this.netmusickugou$searchButton.active = showSearch;
        }
    }
}
