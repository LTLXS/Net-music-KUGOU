package com.github.tartaricacid.netmusic.kugou.compat.display;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * NetMusicDisplay 兼容层 mixin 的总开关插件。
 * <p>
 * NetMusicDisplay 是 <b>可选</b> 依赖：当用户没装该 mod 时，对 {@code com.netmusicdisplay.*} 类
 * 的 mixin 必须全部跳过，否则会因找不到目标类导致 {@code ClassNotFoundException} 直接让游戏崩。
 * <p>
 * 检测方式：在 mixin 加载早期（{@code onLoad}）通过 {@code Class.forName} 试探
 * {@code com.netmusicdisplay.source.LyricCache}，结果缓存到 {@link #displayModPresent}，
 * 之后所有 {@link #shouldApplyMixin} 都直接读这个缓存。
 * <p>
 * 注意：{@code onLoad} 在 mod 主类构造之前触发，{@code ModList.get()} 可能还没就绪，
 * 所以<b>不</b>用 FML API，改用 {@code Class.forName}（它会走 classloader 试探性查找，
 * 不强制初始化），这样即使没装 NetMusicDisplay 也不会抛异常。
 */
public class DisplayCompatMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("NetMusicKuGou-DisplayCompat");
    private static final String DISPLAY_LYRIC_CACHE = "com.netmusicdisplay.source.LyricCache";
    private static final String NML_LYRIC_SOURCE =
            "com.gly091020.netMusicListNeoforge.create.musicPlayerSource.LyricSource";

    private static boolean displayModPresent = false;
    private static boolean probed = false;
    private static Boolean nmlPresent = null;

    /**
     * 探测 netMusicList 自带的 Create 显示源 {@code LyricSource} 是否存在。
     * 同样用 {@code Class.forName}（不强制初始化）而非 ModList，因为 onLoad 早于 mod 主类构造。
     */
    private static synchronized boolean probeNetMusicList() {
        if (nmlPresent != null) return nmlPresent;
        boolean ok;
        try {
            Class.forName(NML_LYRIC_SOURCE, false, DisplayCompatMixinPlugin.class.getClassLoader());
            ok = true;
        } catch (Throwable ignored) {
            ok = false;
        }
        nmlPresent = ok;
        LOGGER.info("[DisplayCompat] netMusicList LyricSource {}, {}",
                ok ? "detected" : "not present",
                ok ? "enabling its override mixin" : "skipping its override mixin");
        return ok;
    }

    private static synchronized boolean probeDisplayMod() {
        if (probed) return displayModPresent;
        try {
            Class.forName(DISPLAY_LYRIC_CACHE, false,
                    DisplayCompatMixinPlugin.class.getClassLoader());
            displayModPresent = true;
        } catch (Throwable ignored) {
            displayModPresent = false;
        }
        probed = true;
        if (displayModPresent) {
            LOGGER.info("[DisplayCompat] NetMusicDisplay detected, enabling display compatibility mixins");
        } else {
            LOGGER.info("[DisplayCompat] NetMusicDisplay not present, all display compatibility mixins will be skipped");
        }
        return displayModPresent;
    }

    @Override
    public void onLoad(String mixinPackage) {
        probeDisplayMod();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 接管 netMusicList 自带显示源的 mixin：只在 netMusicList 真的装了时才应用，
        // 否则目标类不存在会直接崩游戏。
        if (mixinClassName.contains("NetMusicList")) {
            return probeNetMusicList();
        }
        // 兼容层 mixin 文件名都以 "Display" 开头，方便统一识别
        if (!mixinClassName.endsWith("DisplayCompat") && !mixinClassName.contains(".Display")) {
            return true;
        }
        return probeDisplayMod();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
