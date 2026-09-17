package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.support.UrlRefresher;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 周期扫描玩家物品栏、检查并自动续期失效 CD URL 的调度器。
 * <p>由服务端起来时 {@link #start()} 启动；任务抛到 MinecraftServer 主线程执行（修改 ItemStack NBT 必须在主线程）。
 * 调度器本身只负责"到点了发个信号"，主线程内仍串行处理所有玩家。</p>
 * <p><b>非 final 字段</b>：重进游戏存档时 {@code onServerStopped} 会 shutdown 旧实例，下一轮
 * {@code onServerStarted} 触发 start 时若继续在已 terminated 的 executor 上 schedule 会抛
 * RejectedExecutionException，故本字段允许在启动时重建。</p>
 */
public final class UrlRefreshScheduler {
    private static ScheduledExecutorService urlRefreshScheduler;

    private UrlRefreshScheduler() {
    }

    public static void start() {
        if (urlRefreshScheduler == null || urlRefreshScheduler.isShutdown()) {
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
                        ServerLifecycleHooks.getCurrentServer();
                if (server == null) {
                    return;
                }
                // 在当前调度线程（后台）上跑扫描：探测与 HTTP 请求不再占用服务端主线程；
                // URL 失效时的 NBT 回写由 UrlRefresher 内部抛回主线程执行。
                UrlRefresher refresher = new UrlRefresher();
                refresher.scanAll();
            } catch (Throwable t) {
                KuGouLogger.error("[UrlRefresh] Scheduler tick crashed", t);
            }
        }, hours, hours, TimeUnit.HOURS);
    }

    public static void shutdown() {
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
}
