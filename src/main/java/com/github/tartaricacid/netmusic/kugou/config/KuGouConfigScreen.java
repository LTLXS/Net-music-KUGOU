package com.github.tartaricacid.netmusic.kugou.config;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.api.KuGouLoginApi;
import com.github.tartaricacid.netmusic.kugou.api.KuGouVipApi;
import com.github.tartaricacid.netmusic.kugou.client.gui.KuGouLoginScreen;
import com.github.tartaricacid.netmusic.kugou.support.VipRetryScheduler;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Cloth Config 配置界面构建（原内联在 {@code NetMusicKuGou#createConfigScreen}，现已抽出独立成类）。
 */
public final class KuGouConfigScreen {
    private KuGouConfigScreen() {
    }

    public static Screen create(net.minecraft.client.Minecraft client, Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("netmusic_kugou.config.title"))
                .setSavingRunnable(ClientConfig.SPEC::save);

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory loginCat = builder.getOrCreateCategory(Component.translatable("netmusic_kugou.config.category.kugou_login"));

        String loginStatusText = KuGouConfig.isLoggedIn()
                ? String.format(Component.translatable("netmusic_kugou.config.login.logged_in").getString(), KuGouConfig.userid)
                : Component.translatable("netmusic_kugou.config.login.not_logged_in").getString();
        loginCat.addEntry(entryBuilder.startTextDescription(Component.literal(loginStatusText))
                .build());

        loginCat.addEntry(ButtonEntry.of(Component.empty(), Component.translatable("netmusic_kugou.config.login.scan_qr"), () -> {
            client.setScreen(new KuGouLoginScreen(parent));
        }));

        loginCat.addEntry(ButtonEntry.of(Component.empty(), Component.translatable("netmusic_kugou.config.login.logout"), () -> {
            KuGouLoginApi.logout();
            client.setScreen(KuGouConfigScreen.create(client, parent));
        }));

        ConfigCategory sourceCat = builder.getOrCreateCategory(Component.translatable("netmusic_kugou.config.category.music_source"));

        ProviderType[] providerValues = ClientConfig.isQqModLoaded()
                ? ProviderType.values()
                : new ProviderType[]{ProviderType.NETEASE, ProviderType.KUGOU};
        ProviderType currentProvider = ClientConfig.getProvider();
        if (!ClientConfig.isQqModLoaded() && currentProvider == ProviderType.QQ) {
            currentProvider = ProviderType.NETEASE;
        }
        sourceCat.addEntry(entryBuilder.startSelector(
                        Component.translatable("netmusic_kugou.config.category.music_source"), providerValues, currentProvider)
                .setDefaultValue(ProviderType.NETEASE)
                .setTooltip(Component.translatable("netmusic_kugou.config.source.tooltip"))
                .setNameProvider(type -> type.getDisplayName())
                .setSaveConsumer(ClientConfig::setProvider)
                .build());

        sourceCat.addEntry(entryBuilder.startStrField(Component.translatable("netmusic_kugou.config.vip_cookie"), ClientConfig.getVipCookie())
                .setDefaultValue("")
                .setTooltip(Component.translatable("netmusic_kugou.config.vip_cookie.tooltip"))
                .setSaveConsumer(ClientConfig.VIP_COOKIE::set)
                .build());

        sourceCat.addEntry(entryBuilder.startSelector(
                Component.translatable("netmusic_kugou.config.audio_quality"),
                AudioQuality.values(),
                ClientConfig.getAudioQuality())
                .setDefaultValue(AudioQuality.HQ)
                .setTooltip(Component.translatable("netmusic_kugou.config.audio_quality.tooltip"))
                .setNameProvider(q -> q.getDisplayName())
                .setSaveConsumer(ClientConfig::setAudioQuality)
                .build());

        ConfigCategory vipCat = builder.getOrCreateCategory(Component.translatable("netmusic_kugou.config.category.vip"));

        vipCat.addEntry(entryBuilder.startBooleanToggle(Component.translatable("netmusic_kugou.config.auto_receive_vip"),
                        ClientConfig.AUTO_RECEIVE_VIP.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("netmusic_kugou.config.auto_receive_vip.tooltip"))
                .setSaveConsumer(ClientConfig.AUTO_RECEIVE_VIP::set)
                .build());

        vipCat.addEntry(entryBuilder.startIntSlider(Component.translatable("netmusic_kugou.config.retry_interval"),
                        ClientConfig.VIP_RETRY_INTERVAL_MINUTES.get(), 1, 1440)
                .setDefaultValue(10)
                .setTooltip(Component.translatable("netmusic_kugou.config.retry_interval.tooltip"))
                .setSaveConsumer(ClientConfig.VIP_RETRY_INTERVAL_MINUTES::set)
                .build());

        StringBuilder statusBuilder = new StringBuilder();
        statusBuilder.append(Component.translatable("netmusic_kugou.config.last_status").getString()).append(KuGouVipApi.lastClaimStatus);
        if (!KuGouVipApi.lastClaimDate.isEmpty()) {
            statusBuilder.append(" (").append(KuGouVipApi.lastClaimDate).append(")");
        }
        if (KuGouVipApi.lastVipResultMessage != null && !KuGouVipApi.lastVipResultMessage.isEmpty()) {
            statusBuilder.append("\n").append(KuGouVipApi.lastVipResultMessage);
        }
        vipCat.addEntry(entryBuilder.startTextDescription(Component.literal(statusBuilder.toString()))
                .build());

        vipCat.addEntry(ButtonEntry.of(Component.empty(), Component.translatable("netmusic_kugou.config.claim_vip_now"), () -> {
            if (!KuGouConfig.isLoggedIn()) {
                KuGouLogger.warn("Cannot manually claim VIP: not logged in");
                return;
            }
            KuGouLogger.info("Manually triggered VIP claim by user");
            VipRetryScheduler.triggerAutoReceiveVip();
        }));

        ConfigCategory lyricCat = builder.getOrCreateCategory(Component.translatable("netmusic_kugou.config.category.lyric_display"));

        lyricCat.addEntry(entryBuilder.startTextDescription(Component.translatable("netmusic_kugou.config.lyric_display_desc"))
                .build());

        lyricCat.addEntry(entryBuilder.startBooleanToggle(Component.translatable("netmusic_kugou.config.show_translation"),
                        ClientConfig.LYRIC_SHOW_TRANSLATION.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("netmusic_kugou.config.show_translation.tooltip"))
                .setSaveConsumer(ClientConfig.LYRIC_SHOW_TRANSLATION::set)
                .build());

        lyricCat.addEntry(entryBuilder.startBooleanToggle(Component.translatable("netmusic_kugou.config.show_romaji"),
                        ClientConfig.LYRIC_SHOW_ROMAJI.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("netmusic_kugou.config.show_romaji.tooltip"))
                .setSaveConsumer(ClientConfig.LYRIC_SHOW_ROMAJI::set)
                .build());

        return builder.build();
    }
}
