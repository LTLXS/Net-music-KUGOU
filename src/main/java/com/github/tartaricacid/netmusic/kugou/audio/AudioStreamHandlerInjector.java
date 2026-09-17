package com.github.tartaricacid.netmusic.kugou.audio;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.google.common.collect.ImmutableList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 把酷狗专属的 {@link KuGouAudioStreamHandler} 反射注入父模组
 * {@code AudioStreamHandlerManager.HANDLERS}（优先级最高，避免 DirectHttpHandler 用网易云 UA 拉酷狗 403）。
 * <p>
 * 为什么不用官方 {@code registerHandler}？因为父模组 {@code AudioStreamHandlerManager.init()} 内部会在注册完
 * 自带的 5 个 handler 后立刻 {@code HANDLERS = ImmutableList.copyOf(HANDLERS);}，之后再调用官方
 * {@code registerHandler} 会被内部 {@code if (HANDLERS instanceof ImmutableCollection) return error;} 直接拒绝。
 * 我们完全不知道 NetMusic 父模组的 init 何时触发，所以在第一次 ClientLoggingIn 时反射改写 HANDLERS 字段最稳。
 */
public final class AudioStreamHandlerInjector {
    private static volatile boolean injected = false;

    private AudioStreamHandlerInjector() {
    }

    public static boolean isInjected() {
        return injected;
    }

    public static synchronized void inject() {
        if (injected) return;
        try {
            Class<?> mgr = Class.forName("com.github.tartaricacid.netmusic.client.api.AudioStreamHandlerManager");
            Field handlersField = mgr.getDeclaredField("HANDLERS");
            handlersField.setAccessible(true);
            try {
                Field modifiersField = Field.class.getDeclaredField("modifiers");
                modifiersField.setAccessible(true);
                modifiersField.setInt(handlersField, handlersField.getModifiers() & ~Modifier.FINAL);
            } catch (Throwable ignore) { /* 某些 JDK 实现不让改 modifiers，继续尝试直接 set。 */ }

            Object current = handlersField.get(null);
            List<Object> newList;
            if (current instanceof List<?> lst) {
                newList = new ArrayList<>(lst.size() + 1);
                for (Object h : lst) {
                    newList.add(h);
                }
            } else {
                newList = new ArrayList<>(1);
            }
            Object ourHandler = Class.forName("com.github.tartaricacid.netmusic.kugou.audio.KuGouAudioStreamHandler")
                    .getDeclaredConstructor().newInstance();
            newList.add(ourHandler);
            newList.sort((h1, h2) -> {
                try {
                    Method m = h1.getClass().getMethod("getPriority");
                    int p1 = (int) m.invoke(h1);
                    int p2 = (int) m.invoke(h2);
                    return Integer.compare(p2, p1);
                } catch (Throwable t) {
                    return 0;
                }
            });
            List<Object> immutable = ImmutableList.copyOf(newList);
            handlersField.set(null, immutable);
            injected = true;
            KuGouLogger.info(
                    "[KuGouAudio] Injected KuGouAudioStreamHandler(priority=100). HANDLERS size now={}, first handler={}",
                    immutable.size(),
                    immutable.isEmpty() ? "none" : immutable.get(0).getClass().getName());
        } catch (Throwable t) {
            KuGouLogger.error("[KuGouAudio] Failed to inject KuGouAudioStreamHandler. KuGou CDN will keep using NetEase UA (may 403 randomly): {}",
                    t.getMessage(), t);
        }
    }
}
