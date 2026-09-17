package com.github.tartaricacid.netmusic.kugou.compat.display;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 控制 display 兼容 mixin 是否启用：仅当 NetMusicDisplay 模组存在时才应用，
 * 否则全部跳过（避免对未安装 NetMusicDisplay 的环境产生影响）。
 */
public class DisplayCompatMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("NetMusicKuGou-DisplayCompat");
    private static final String DISPLAY_LYRIC_CACHE = "com.netmusicdisplay.source.LyricCache";
    private static boolean netMusicDisplayPresent;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            Class.forName(DISPLAY_LYRIC_CACHE, false, DisplayCompatMixinPlugin.class.getClassLoader());
            netMusicDisplayPresent = true;
            LOGGER.info("[DisplayCompat] NetMusicDisplay detected, enabling display compatibility mixins");
        } catch (Throwable t) {
            netMusicDisplayPresent = false;
            LOGGER.info("[DisplayCompat] NetMusicDisplay not present, all display compatibility mixins will be skipped");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String mixinClassName, String targetClassName) {
        // 非 display 兼容的 mixin 一律正常应用；只有以 DisplayCompat 结尾（或在 compat.display 包内）的
        // 才需要在 NetMusicDisplay 缺失时整体跳过。
        if (!mixinClassName.endsWith("DisplayCompat") && !mixinClassName.contains(".Display")) {
            return true;
        }
        return netMusicDisplayPresent;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
