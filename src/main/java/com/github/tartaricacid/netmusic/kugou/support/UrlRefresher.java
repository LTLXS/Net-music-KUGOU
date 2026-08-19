package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 周期巡检器：扫描玩家物品栏和末影箱里的所有音乐 CD，
 * 发现烧入的 songUrl 已经失效（酷狗返回 403）就重新拉一个 URL 写回去。
 * <p>
 * 触发方式：{@code NetMusicKuGou.urlRefreshScheduler} 每 N 小时跑一次 {@link #scanAll()}。
 * <p>
 * 注意：只有本 mod 烧进去的 CD（{@code CdNbtHelper.readOriginalInfo} 能读到 fileHash 的）才会被处理。
 * 没有本 mod 烧入标识的 CD、或用户手动编辑的 CD 都会被跳过，避免误改。
 */
public class UrlRefresher {

    /**
     * 扫描当前服务器上所有在线玩家
     */
    public void scanAll() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                int refreshed = scanPlayer(player);
                if (refreshed > 0) {
                    KuGouLogger.info("[UrlRefresher] Refreshed {} CD(s) for player {}",
                            refreshed, player.getName().getString());
                }
            } catch (Exception e) {
                KuGouLogger.error("[UrlRefresher] Scan failed for player {}: {}",
                        player.getName().getString(), e.getMessage(), e);
            }
        }
    }

    /**
     * 扫描单个玩家的背包 + 末影箱。返回成功续期的 CD 数量。
     */
    public int scanPlayer(ServerPlayer player) {
        AtomicInteger refreshed = new AtomicInteger(0);
        scanInventory(player.getInventory());
        scanEnderChest(player);
        return refreshed.get();
    }

    private void scanInventory(Inventory inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (CdNbtHelper.isMusicCd(stack)) {
                if (tryRefreshOne(stack)) {
                    // setItem 会把内存里的 stack 写回原 slot
                    // 这里 inv 本身就是背包引用，不需要额外 set
                }
            }
        }
    }

    private void scanEnderChest(ServerPlayer player) {
        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            ItemStack stack = ender.getItem(i);
            if (CdNbtHelper.isMusicCd(stack)) {
                tryRefreshOne(stack);
            }
        }
    }

    /**
     * 检查单个 CD：
     * - 没有 fileHash 记录 → 跳过
     * - URL 还能 200 → 跳过
     * - URL 403（失效）→ 重新调 getSongUrl，写回 NBT
     * <p>
     * 此方法会阻塞调用线程（HEAD + getSongUrl），适合在事件处理时同步使用。
     * 正常情况下（URL 没失效）只跑一次 HEAD 探测就 return，开销很低。
     * @return true 表示成功续期了 URL
     */
    public boolean tryRefreshOne(ItemStack cd) {
        var infoOpt = CdNbtHelper.readOriginalInfo(cd);
        if (infoOpt.isEmpty()) {
            return false;
        }
        CdAddonData info = infoOpt.get();
        String currentUrl = CdNbtHelper.readSongUrl(cd);
        if (currentUrl == null || currentUrl.isEmpty()) {
            return false;
        }
        if (!isExpired(currentUrl)) {
            return false;
        }
        KuGouLogger.info("[UrlRefresher] CD URL expired, refreshing: hash={}, oldUrl={}",
                info.fileHash(), currentUrl);
        try {
            String newUrl = KuGouApiClient.getSongUrl(info.fileHash(),
                    info.albumId() == null ? "" : info.albumId()).get();
            if (newUrl == null || newUrl.isEmpty()) {
                KuGouLogger.warn("[UrlRefresher] Failed to fetch new URL for hash={} (KuGou returned empty)", info.fileHash());
                return false;
            }
            if (newUrl.equals(currentUrl)) {
                KuGouLogger.info("[UrlRefresher] KuGou returned the same URL for hash={} (likely also expired). Will retry next round.", info.fileHash());
                return false;
            }
            CdNbtHelper.updateSongUrl(cd, newUrl);
            // 重置刻录时间，方便下次巡检识别"刚续期过"
            CdNbtHelper.updateData(cd, d -> new CdAddonData(
                    d.fileHash(), d.albumId(), System.currentTimeMillis(), d.lrc(), d.lrcTrans()
            ));
            KuGouLogger.info("[UrlRefresher] CD URL refreshed: hash={} -> newUrl={}", info.fileHash(), newUrl);
            return true;
        } catch (Exception e) {
            KuGouLogger.error("[UrlRefresher] Exception while refreshing hash={}: {}",
                    info.fileHash(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 用 HEAD 请求探测 URL 状态码。
     * - 2xx：URL 仍然有效
     * - 4xx（特别是 403）：失效
     * - 405：CDN 不接受 HEAD，用带 Range 的 GET 只取首字节
     * - 其它 / 异常：返回 false（保守判断有效，避免误刷新）
     */
    private boolean isExpired(String url) {
        int timeoutMs = ClientConfig.URL_REFRESH_CHECK_TIMEOUT_SECONDS.get() * 1000;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, timeoutMs);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "NetMusic-KuGou/1.0");
            int code = conn.getResponseCode();
            if (code == 405 || code == 501) {
                return isExpiredViaRangeGet(url, timeoutMs);
            }
            return code == 403 || code == 410;
        } catch (Exception e) {
            // 网络异常时保守返回 false（不要因为一时断网就把好 URL 标记成失效）
            KuGouLogger.debug("[UrlRefresher] HEAD probe failed for {}: {}", url, e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Range GET 兜底：只请求 1 字节，看响应码
     */
    private boolean isExpiredViaRangeGet(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setRequestProperty("User-Agent", "NetMusic-KuGou/1.0");
            int code = conn.getResponseCode();
            // 立即断开，避免下载整个文件
            try {
                if (conn.getInputStream() != null) {
                    conn.getInputStream().close();
                }
            } catch (Exception ignored) {}
            return code == 403 || code == 410;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (conn instanceof HttpsURLConnection) {
            // Kugou 的 CDN 偶发证书链问题，给个全信任的 SSLContext 避免误判
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, TRUST_ALL_MANAGERS, new java.security.SecureRandom());
                ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
            } catch (Exception ignored) {}
        }
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static final TrustManager[] TRUST_ALL_MANAGERS = new TrustManager[]{
            new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
    };
}
