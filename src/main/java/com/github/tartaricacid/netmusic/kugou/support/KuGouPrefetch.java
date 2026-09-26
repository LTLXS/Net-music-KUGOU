package com.github.tartaricacid.netmusic.kugou.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 酷狗 URL 异步预取缓存。
 在服务端主线程同步 getSongUrl().get(30s) 会阻塞数百毫秒到数秒，
 导致 RightClickBlock 超时、播放消息延迟、同一 CD 被重复刷新。
 两段式并行：onRightClickJukebox 立刻返回，只提交 asyncPrefetch，
 结果按 PrefetchKey(pos, playerUUID) 存入 PENDING；
 setPlayToClient HEAD 调 tryTake：预取完成直接用新 URL，
 未完成最多等 MAX_JOIN_MS（默认 200ms），等不到先用 CD NBT 旧 URL 起播，
 预取完成后若 URL 变化则回调延迟 1 tick 重新 setPlayToClient（无缝切歌）。*/
public final class KuGouPrefetch {
    private KuGouPrefetch() {}

    /** 预取在 setPlayToClient HEAD 里最多还能等多久（别太长，玩家会感知到延迟）。 */
    public static final long MAX_JOIN_MS = 200L;

    /** 单条预取最多存活 20 秒；超过就自动丢弃，避免 map 因为玩家右键取消/切档无限增长。 */
    private static final long TTL_MS = 20_000L;

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(0);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "NetMusicKuGou-Prefetch-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /**
 Key：我们只做"同一次右键动作 → 紧接着的 setPlayToClient"这一次命中，
 所以 key 只需要 BlockPos（右键的方块位置）就能对上，
 因为同一时刻同一台方块音响不可能有两个人同时插 CD。
*/
    public record PrefetchKey(net.minecraft.core.BlockPos pos, java.util.UUID playerId) {}

    private static final ConcurrentHashMap<PrefetchKey, Entry> PENDING = new ConcurrentHashMap<>();

    /** replay 冷却：同一 pos 在冷却期内不重复触发 scheduleReplayWithNewUrl，避免 refresh→replay→refresh 循环。 */
    private static final ConcurrentHashMap<net.minecraft.core.BlockPos, Long> REPLAY_COOLDOWN = new ConcurrentHashMap<>();
    private static final long REPLAY_COOLDOWN_MS = 30_000L;

    private record Entry(CompletableFuture<String> urlFuture, long createdAtMs,
                         net.minecraft.world.item.ItemStack cdSnapshot,
                         String fileHash, String albumId) {}

    /** 异步刷新完成后需要"延迟 1 tick 重新 setPlayToClient"的回调（在 NetMusicKuGou / Mixin 里注册具体实现）。 */
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

    /**
 异步预取：后台线程去 forceRefresh 一个 URL，结果缓存进 PENDING。
 本函数绝对不做任何阻塞 I/O，调用方（RightClickBlock 服务端主线程）可以立即返回。
*/
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

        // 用 snapshot 拷贝，避免外部把 ItemStack NBT 改掉（例如 shrink），后台读取时拿到空 hash
        net.minecraft.world.item.ItemStack snap = cd.copy();

        CompletableFuture<String> fut = CompletableFuture.supplyAsync(() -> {
            try {
                UrlRefresher r = new UrlRefresher();
                // forceRefreshOne 会把新 URL 写进 snap，但我们需要把结果再同步给调用方
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

    /**
 setPlayToClient HEAD 调用：从 PENDING 取预取结果，最多等 MAX_JOIN_MS，
 并把"刷新完成后延迟 1 tick 重新 setPlayToClient"的回调注册到 future 上。

 @return 可取用的 songUrl（空串 = 预取没完成 / 预取失败，让调用方 fallback 到 CD 原始 URL）
*/
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
            // 等最多 MAX_JOIN_MS。注意不能把服务端主线程卡死，所以超时就立刻放弃等待继续用旧 URL。
            try {
                url = fut.get(MAX_JOIN_MS, TimeUnit.MILLISECONDS);
                completedNow = true;
            } catch (java.util.concurrent.TimeoutException timeout) {
                // 预取还在跑 → 不等了，callback 里完成后如果 URL 变了会自动重放
                url = "";
            } catch (Exception ex) {
                com.github.tartaricacid.netmusic.kugou.KuGouLogger.warn(
                        "[KuGouPrefetch] tryTake join failed: {}", ex.getMessage());
                url = "";
            }
        } else {
            try { url = fut.getNow(""); } catch (Exception ignore) { url = ""; }
        }

        // 如果预取没在这次调用里拿到结果 → 在 future 完成后挂回调，检查是否需要延迟 1 tick 切歌
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
                    // 但 cb.onUrlChanged 会从 TileEntity slot 0 拿真实 stack 写 NBT，所以这里不需要额外处理。
                } catch (Throwable t) {
                    com.github.tartaricacid.netmusic.kugou.KuGouLogger.warn(
                            "[KuGouPrefetch] late refresh cb failed: {}", t.getMessage());
                }
            });
        }

        // 无论成功/失败/超时，只要我们走到这里说明已经用这条 entry 了，从 map 删掉避免泄露
        PENDING.remove(key);

        return new PrefetchResult(url, completedNow, e.fileHash, e.albumId, true);
    }

    /** setPlayToClient HEAD 用不到 Prefetch 时（异步 forceRefreshOne 失败），依然可以直接手动提交一个异步刷新 + 变更回调。 */
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
