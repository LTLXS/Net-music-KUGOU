package com.github.tartaricacid.netmusic.kugou.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 酷狗 URL 异步预取缓存。
 */
public final class KuGouPrefetch {
    private KuGouPrefetch() {}

    public static final long MAX_JOIN_MS = 200L;
    private static final long TTL_MS = 20_000L;

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(0);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "NetMusicKuGou-Prefetch-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public record PrefetchKey(net.minecraft.core.BlockPos pos, java.util.UUID playerId) {}

    private static final ConcurrentHashMap<PrefetchKey, Entry> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<net.minecraft.core.BlockPos, Long> REPLAY_COOLDOWN = new ConcurrentHashMap<>();
    private static final long REPLAY_COOLDOWN_MS = 30_000L;

    private record Entry(CompletableFuture<String> urlFuture, long createdAtMs,
                         net.minecraft.world.item.ItemStack cdSnapshot,
                         String fileHash, String albumId) {}

    public interface OnRefreshChangedCallback {
        void onUrlChanged(net.minecraft.world.level.Level level,
                          net.minecraft.core.BlockPos pos,
                          String oldUrl, String newUrl);
    }
    private static volatile OnRefreshChangedCallback callback;

    public static void setOnRefreshChangedCallback(OnRefreshChangedCallback cb) {
        callback = cb;
    }

    public static boolean isOnReplayCooldown(net.minecraft.core.BlockPos pos) {
        if (pos == null) return false;
        Long last = REPLAY_COOLDOWN.get(pos);
        if (last == null) return false;
        if (System.currentTimeMillis() - last > REPLAY_COOLDOWN_MS) {
            REPLAY_COOLDOWN.remove(pos);
            return false;
        }
        return true;
    }

    private static void markReplayed(net.minecraft.core.BlockPos pos) {
        if (pos != null) {
            REPLAY_COOLDOWN.put(pos, System.currentTimeMillis());
        }
    }

    public static void asyncPrefetch(net.minecraft.core.BlockPos pos,
                                     java.util.UUID playerId,
                                     net.minecraft.world.item.ItemStack cd) {
        if (pos == null || cd == null) return;
        java.util.Optional<CdAddonData> infoOpt = CdNbtHelper.readOriginalInfo(cd);
        if (infoOpt.isEmpty()) return;
        CdAddonData info = infoOpt.get();
        if (info.fileHash() == null || info.fileHash().isEmpty()) return;

        evictExpired();

        PrefetchKey key = new PrefetchKey(pos, playerId);
        long now = System.currentTimeMillis();
        Entry old = PENDING.get(key);
        if (old != null && now - old.createdAtMs < 500L) {
            return;
        }

        net.minecraft.world.item.ItemStack snap = cd.copy();

        CompletableFuture<String> fut = CompletableFuture.supplyAsync(() -> {
            try {
                UrlRefresher r = new UrlRefresher();
                r.forceRefreshOne(snap);
                String url = CdNbtHelper.readSongUrl(snap);
                return (url == null) ? "" : url;
            } catch (Throwable t) {
                com.github.tartaricacid.netmusic.kugou.KuGouLogger.warn(
                        "[KuGouPrefetch] async for hash={} failed: {}", info.fileHash(), t.getMessage());
                return "";
            }
        }, EXEC);

        PENDING.put(key, new Entry(fut, now, snap, info.fileHash(), info.albumId()));
    }

    public static PrefetchResult tryTake(net.minecraft.core.BlockPos pos,
                                         java.util.UUID playerId,
                                         net.minecraft.world.level.Level level,
                                         String currentUrl) {
        if (pos == null) return PrefetchResult.none();
        evictExpired();

        PrefetchKey key = new PrefetchKey(pos, playerId);
        Entry e = PENDING.get(key);
        if (e == null) {
            for (var kv : PENDING.entrySet()) {
                if (kv.getKey().pos().equals(pos)) {
                    e = kv.getValue();
                    key = kv.getKey();
                    break;
                }
            }
        }
        if (e == null) return PrefetchResult.none();

        CompletableFuture<String> fut = e.urlFuture;
        String url = "";
        boolean completedNow = fut.isDone();
        if (!completedNow) {
            try {
                url = fut.get(MAX_JOIN_MS, TimeUnit.MILLISECONDS);
                completedNow = true;
            } catch (java.util.concurrent.TimeoutException timeout) {
                url = "";
            } catch (Exception ex) {
                com.github.tartaricacid.netmusic.kugou.KuGouLogger.warn(
                        "[KuGouPrefetch] tryTake join failed: {}", ex.getMessage());
                url = "";
            }
        } else {
            try { url = fut.getNow(""); } catch (Exception ignore) { url = ""; }
        }

        if (!completedNow) {
            final String oldFallback = (currentUrl == null) ? "" : currentUrl;
            final net.minecraft.world.level.Level lvlSafe = level;
            final net.minecraft.core.BlockPos posSafe = pos;
            final Entry eSafe = e;
            fut.whenComplete((finalUrl, th) -> {
                try {
                    if (th != null) return;
                    if (finalUrl == null || finalUrl.isEmpty()) return;
                    if (finalUrl.equals(oldFallback)) return;
                    if (isOnReplayCooldown(posSafe)) {
                        com.github.tartaricacid.netmusic.kugou.KuGouLogger.info(
                                "[KuGouPrefetch] Skip replay: on cooldown for pos={}", posSafe);
                        return;
                    }
                    markReplayed(posSafe);
                    OnRefreshChangedCallback cb = callback;
                    if (cb == null) return;
                    com.github.tartaricacid.netmusic.kugou.KuGouLogger.info(
                            "[KuGouPrefetch] Late refresh OK: pos={}, oldLen={} -> newLen={}, will schedule re-setPlayToClient after 1 tick",
                            posSafe, oldFallback.length(), finalUrl.length());
                    cb.onUrlChanged(lvlSafe, posSafe, oldFallback, finalUrl);
                } catch (Throwable t) {
                    com.github.tartaricacid.netmusic.kugou.KuGouLogger.warn(
                            "[KuGouPrefetch] late refresh cb failed: {}", t.getMessage());
                }
            });
        }

        PENDING.remove(key);

        return new PrefetchResult(url, completedNow, e.fileHash, e.albumId, true);
    }

    public static void submitAsyncRefreshThenMaybeReplay(
            net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos pos,
            java.util.UUID playerId,
            net.minecraft.world.item.ItemStack cd,
            String currentUrl) {
        asyncPrefetch(pos, playerId, cd);
        PrefetchKey key = new PrefetchKey(pos, playerId);
        Entry e = PENDING.get(key);
        if (e == null) return;
        final String oldUrl = (currentUrl == null) ? "" : currentUrl;
        final net.minecraft.core.BlockPos posSafe = pos;
        final net.minecraft.world.level.Level lvlSafe = level;
        e.urlFuture.whenComplete((newUrl, th) -> {
            PENDING.remove(key);
            if (th != null) return;
            if (newUrl == null || newUrl.isEmpty()) return;
            if (newUrl.equals(oldUrl)) return;
            if (isOnReplayCooldown(posSafe)) {
                com.github.tartaricacid.netmusic.kugou.KuGouLogger.info(
                        "[KuGouPrefetch] Skip replay: on cooldown for pos={}", posSafe);
                return;
            }
            markReplayed(posSafe);
            OnRefreshChangedCallback cb = callback;
            if (cb == null) return;
            try {
                cb.onUrlChanged(lvlSafe, posSafe, oldUrl, newUrl);
            } catch (Throwable t) {
                com.github.tartaricacid.netmusic.kugou.KuGouLogger.warn(
                        "[KuGouPrefetch] submitAsync cb failed: {}", t.getMessage());
            }
        });
    }

    private static void evictExpired() {
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(kv -> now - kv.getValue().createdAtMs > TTL_MS);
    }

    /** 服务端停止时关闭预取线程池，避免 daemon 线程残留导致类/资源无法卸载。 */
    public static void shutdown() {
        EXEC.shutdown();
        try {
            if (!EXEC.awaitTermination(2, TimeUnit.SECONDS)) {
                EXEC.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXEC.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record PrefetchResult(String url, boolean completedSynchronously, String fileHash, String albumId, boolean entryFound) {
        private static final PrefetchResult NONE = new PrefetchResult("", false, "", "", false);
        public static PrefetchResult none() { return NONE; }
        public boolean hasFreshUrl() { return url != null && !url.isEmpty(); }
    }
}
