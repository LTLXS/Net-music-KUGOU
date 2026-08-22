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
 * <p>
 * <b>为什么需要这个？</b>
 * 之前我们在两个服务端主线程入口（{@code RightClickBlock -> onRightClickJukebox} 和
 * {@code TileEntityMusicPlayer.setPlayToClient HEAD}）里都同步调用 {@code forceRefreshOne → getSongUrl().get(30s)}，
 * 会把 Minecraft Server Thread 卡死几百毫秒到几秒。实际后果：
 * <ol>
 *   <li>插 CD 时整个游戏"卡一下"，方块音响 use() 流程被 Minecraft 判定超时，CD 没真正入槽 → 必须反复右键好几次才能播</li>
 *   <li>setPlayToClient 阻塞太久 → 所有玩家看到的方块音响播放消息延迟好几秒 → "等很久才响"</li>
 *   <li>同一个 CD 被 forceRefreshOne 连续跑两遍（RightClickBlock 一次 + setPlayToClient 一次），浪费时间</li>
 * </ol>
 * <p>
 * <b>使用流程（两段式并行）：</b>
 * <ol>
 *   <li>{@code onRightClickJukebox} 在服务器主线程 <b>立刻返回</b>，只是把
 *       {@code asyncPrefetch(hash, albumId, cd)} 扔进本类的后台线程池，
 *       结果按 {@code PrefetchKey(pos, playerUUID)} 存进 {@link #PENDING}。</li>
 *   <li>几 ms 后方块音响 use() 正常把 CD 塞进 playerInv slot 0，触发 {@code setPlayToClient HEAD}。
 *       这里先 {@link #tryTake(BlockPos, UUID)} 找刚才预取的 future：
 *       <ul>
 *         <li>预取已完成 → 直接拿新 URL，0 毫秒额外延迟。</li>
 *         <li>预取还在跑 → 最多 {@link #MAX_JOIN_MS}（默认 200ms）等一下，等不到就直接用 CD NBT 里的旧 URL 先起播，
 *             预取在后台 finish 后发现 URL 真的变了会回调：延迟 1 tick 让 TileEntity 重新 setPlayToClient 用新 URL 无缝切歌。</li>
 *       </ul>
 *   </li>
 * </ol>
 * <p>
 * 这样 99% 的 HTTP 耗时都不在服务器主线程上；最坏情况（预取完全没命中）也只会在 setPlayToClient 里挡 ~200ms，
 * 比之前 2~5s 的卡顿体感好很多。
 */
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
     * Key：我们只做"同一次右键动作 → 紧接着的 setPlayToClient"这一次命中，
     * 所以 key 只需要 BlockPos（右键的方块位置）就能对上，
     * 因为同一时刻同一台方块音响不可能有两个人同时插 CD。
     */
    public record PrefetchKey(net.minecraft.core.BlockPos pos, java.util.UUID playerId) {}

    private static final ConcurrentHashMap<PrefetchKey, Entry> PENDING = new ConcurrentHashMap<>();

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

    // =================================================================== API

    /**
     * 异步预取：后台线程去 forceRefresh 一个 URL，结果缓存进 {@link #PENDING}。
     * <b>本函数绝对不做任何阻塞 I/O，调用方（RightClickBlock 服务端主线程）可以立即返回。</b>
     */
    public static void asyncPrefetch(net.minecraft.core.BlockPos pos,
                                     java.util.UUID playerId,
                                     net.minecraft.world.item.ItemStack cd) {
        if (pos == null || cd == null) return;
        java.util.Optional<CdAddonData> infoOpt = CdNbtHelper.readOriginalInfo(cd);
        if (infoOpt.isEmpty()) return;
        CdAddonData info = infoOpt.get();
        if (info.fileHash() == null || info.fileHash().isEmpty()) return;

        // 清理过期条目（顺便自维护 map 大小）
        evictExpired();

        PrefetchKey key = new PrefetchKey(pos, playerId);
        long now = System.currentTimeMillis();
        Entry old = PENDING.get(key);
        if (old != null && now - old.createdAtMs < 500L) {
            // 500ms 内重复右键 → 已经在跑了，不重复提交
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
     * setPlayToClient HEAD 调用：从 {@link #PENDING} 取预取结果，最多等 {@link #MAX_JOIN_MS}，
     * 并把"刷新完成后延迟 1 tick 重新 setPlayToClient"的回调注册到 future 上。
     *
     * @return 可取用的 songUrl（空串 = 预取没完成 / 预取失败，让调用方 fallback 到 CD 原始 URL）
     */
    public static PrefetchResult tryTake(net.minecraft.core.BlockPos pos,
                                         java.util.UUID playerId,
                                         net.minecraft.world.level.Level level,
                                         String currentUrl) {
        if (pos == null) return PrefetchResult.none();
        evictExpired();

        PrefetchKey key = new PrefetchKey(pos, playerId);
        Entry e = PENDING.get(key);
        // 玩家用 slot 移动 CD 的话，playerId 可能对不上 → 再尝试一把 "any player same pos" key（遍历找第一个 pos 相等的）
        if (e == null) {
            for (var kv : PENDING.entrySet()) {
                if (kv.getKey().pos().equals(pos)) {
                    e = kv.getValue();
                    key = kv.getKey(); // 之后用这个 key 移除
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
                    if (finalUrl.equals(oldFallback)) return; // URL 没变不用切
                    OnRefreshChangedCallback cb = callback;
                    if (cb == null) return;
                    com.github.tartaricacid.netmusic.kugou.KuGouLogger.info(
                            "[KuGouPrefetch] Late refresh OK: pos={}, oldLen={} -> newLen={}, will schedule re-setPlayToClient after 1 tick",
                            posSafe, oldFallback.length(), finalUrl.length());
                    cb.onUrlChanged(lvlSafe, posSafe, oldFallback, finalUrl);
                    // 顺带把 CD NBT 也补上：之前 RightClick 用的是 snapshot，实际手上的 cd 没被写回；
                    // 但 cb.onUrlChanged 会从 TileEntity slot 0 拿真实 stack 写 NBT，所以这里不需要额外处理。
                    // （eSafe.cdSnapshot 只是快照副本，写回去也影响不了真实 world）
                } catch (Throwable t) {
                    com.github.tartaricacid.netmusic.kugou.KuGouLogger.warn(
                            "[KuGouPrefetch] late refresh cb failed: {}", t.getMessage());
                }
            });
        }

        // 无论成功/失败/超时，只要我们走到这里说明已经用这条 entry 了，从 map 删掉避免泄露
        PENDING.remove(key);

        return new PrefetchResult(url, completedNow, e.fileHash, e.albumId);
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

    /** @see #tryTake */
    public record PrefetchResult(String url, boolean completedSynchronously, String fileHash, String albumId) {
        private static final PrefetchResult NONE = new PrefetchResult("", false, "", "");
        public static PrefetchResult none() { return NONE; }
        public boolean hasFreshUrl() { return url != null && !url.isEmpty(); }
    }
}
