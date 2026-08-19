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

        KuGouSearchScreen.SearchResult result = this.netmusickugou$lastKuGouResult;
        String url;
        try {
            url = KuGouApiClient.getSongUrl(result.fileHash, result.albumId).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            KuGouLogger.error("Failed to get song URL for KuGou burn", e);
            this.tips = Component.literal("获取歌曲URL失败: " + e.getMessage());
            ci.cancel();
            return;
        }

        if (url == null || url.isEmpty()) {
            this.tips = Component.literal("获取歌曲URL失败");
            ci.cancel();
            return;
        }

        ItemMusicCD.SongInfo songInfo = new ItemMusicCD.SongInfo();
        songInfo.songName = result.songName;
        songInfo.songUrl = url;
        songInfo.songTime = result.duration;
        songInfo.artists = Lists.newArrayList(result.singerName);
        songInfo.readOnly = this.readOnlyButton != null && this.readOnlyButton.selected();

        NetworkHandler.sendToServer(new SetMusicIDMessage(songInfo));
        // 把 fileHash / albumId 写入静态缓存，供服务端 CDBurnerMenuMixin 读取。
        // （集成服务器模式下客户端/服务端共享 JVM，静态变量可传递数据）
        BurnDataCache.set(result.fileHash, result.albumId);
        KuGouLogger.info("KuGou song burned: {} (hash={})", result.songName, result.fileHash);

        this.netmusickugou$lastKuGouResult = null;
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
