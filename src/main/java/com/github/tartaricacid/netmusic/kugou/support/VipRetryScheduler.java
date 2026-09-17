package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.api.KuGouVipApi;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 周期重试每日 VIP 领取的调度器（仅在客户端构造；daemon 线程保证不会阻塞游戏进程退出）。
 * <p>调度器会在第一次自动领取失败后按 {@link ClientConfig#VIP_RETRY_INTERVAL_MINUTES} 的间隔持续重试，
 * 直到服务端返回 SUCCESS / ALREADY_CLAIMED，或日期跨日。</p>
 */
public final class VipRetryScheduler {
    private static ScheduledExecutorService vipScheduler;

    private VipRetryScheduler() {
    }

    public static void start() {
        if (vipScheduler == null || vipScheduler.isShutdown()) {
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

    public static void shutdown() {
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

    public static void triggerAutoReceiveVip() {
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
}
