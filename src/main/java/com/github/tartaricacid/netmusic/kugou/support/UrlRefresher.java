package com.github.tartaricacid.netmusic.kugou.support;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

public class UrlRefresher {

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
                tryRefreshOne(stack);
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

    public boolean forceRefreshOne(ItemStack cd) {
        var infoOpt = CdNbtHelper.readOriginalInfo(cd);
        if (infoOpt.isEmpty()) return false;
        CdAddonData info = infoOpt.get();
        String currentUrl = CdNbtHelper.readSongUrl(cd);
        if (currentUrl == null || currentUrl.isEmpty()) return false;

        KuGouLogger.info("[UrlRefresher] Force-refreshing CD URL: hash={}, oldUrlPrefix={}",
                info.fileHash(),
                currentUrl.length() < 80 ? currentUrl : currentUrl.substring(0, 80) + "...");
        try {
            String newUrl = KuGouApiClient.getSongUrl(info.fileHash(),
                    info.albumId() == null ? "" : info.albumId()).get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (newUrl == null || newUrl.isEmpty()) {
                KuGouLogger.warn("[UrlRefresher] Force-refresh failed for hash={} (KuGou returned empty)", info.fileHash());
                return false;
            }
            if (newUrl.equals(currentUrl)) {
                KuGouLogger.info("[UrlRefresher] Force-refresh got same URL for hash={}, keeping as-is", info.fileHash());
                CdNbtHelper.updateData(cd, d -> new CdAddonData(
                        d.fileHash(), d.albumId(), System.currentTimeMillis(), d.lrc(), d.lrcTrans()
                ));
                return false;
            }
            CdNbtHelper.updateSongUrl(cd, newUrl);
            CdNbtHelper.updateData(cd, d -> new CdAddonData(
                    d.fileHash(), d.albumId(), System.currentTimeMillis(), d.lrc(), d.lrcTrans()
            ));
            KuGouLogger.info("[UrlRefresher] Force-refresh OK: hash={} newUrlPrefix={}", info.fileHash(),
                    newUrl.length() < 80 ? newUrl : newUrl.substring(0, 80) + "...");
            return true;
        } catch (Exception e) {
            KuGouLogger.error("[UrlRefresher] Force-refresh exception for hash={}: {}",
                    info.fileHash(), e.getMessage(), e);
            return false;
        }
    }

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
                info.fileHash(),
                currentUrl.length() < 120 ? currentUrl : currentUrl.substring(0, 120) + "...");
        String newUrl;
        try {
            newUrl = KuGouApiClient.getSongUrl(info.fileHash(),
                    info.albumId() == null ? "" : info.albumId()).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            KuGouLogger.error("[UrlRefresher] Exception while fetching hash={}: {}",
                    info.fileHash(), e.getMessage(), e);
            return false;
        }
        if (newUrl == null || newUrl.isEmpty()) {
            KuGouLogger.warn("[UrlRefresher] Failed to fetch new URL for hash={} (KuGou returned empty)", info.fileHash());
            return false;
        }
        if (newUrl.equals(currentUrl)) {
            KuGouLogger.info("[UrlRefresher] KuGou returned the same URL for hash={} (likely also expired). Will retry next round.", info.fileHash());
            return false;
        }
        // 探测与网络请求已在调用线程（调度线程）完成；NBT 回写必须在主线程执行
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> {
                try {
                    CdNbtHelper.updateSongUrl(cd, newUrl);
                    CdNbtHelper.updateData(cd, d -> new CdAddonData(
                            d.fileHash(), d.albumId(), System.currentTimeMillis(), d.lrc(), d.lrcTrans()
                    ));
                    KuGouLogger.info("[UrlRefresher] CD URL refreshed: hash={} -> newUrlPrefix={}", info.fileHash(),
                            newUrl.length() < 80 ? newUrl : newUrl.substring(0, 80) + "...");
                } catch (Exception e) {
                    KuGouLogger.error("[UrlRefresher] Exception while writing hash={}: {}",
                            info.fileHash(), e.getMessage(), e);
                }
            });
        }
        return true;
    }

    private boolean isExpired(String url) {
        java.util.regex.Matcher m = TIMESTAMP_URL.matcher(url);
        if (m.find()) {
            try {
                String ymd = m.group(1);
                String hm = m.group(2);
                int year = Integer.parseInt(ymd.substring(0, 4));
                int month = Integer.parseInt(ymd.substring(4, 6));
                int day = Integer.parseInt(ymd.substring(6, 8));
                int hour = Integer.parseInt(hm.substring(0, 2));
                int min = Integer.parseInt(hm.substring(2, 4));
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.clear();
                cal.set(year, month - 1, day, hour, min, 0);
                long age = System.currentTimeMillis() - cal.getTimeInMillis();
                if (age > TIMESTAMP_URL_MAX_VALID_MS) {
                    KuGouLogger.debug("[UrlRefresher] Timestamp URL older than {}ms (age={}ms), treat as expired",
                            TIMESTAMP_URL_MAX_VALID_MS, age);
                    return true;
                }
            } catch (Exception ignored) {}
        }

        int timeoutMs = ClientConfig.URL_REFRESH_CHECK_TIMEOUT_SECONDS.get() * 1000;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, timeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "NetMusic-KuGou/1.0");
            int code = conn.getResponseCode();
            if (code == 405 || code == 501) {
                return isExpiredViaRangeGet(url, timeoutMs);
            }
            if (code == 403 || code == 410 || code == 400) return true;
            if (code >= 301 && code <= 307) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) return true;
                String lower = location.toLowerCase();
                if (lower.contains("403") || lower.contains("expire") || lower.contains("copyright")
                        || lower.contains("login") || lower.contains("error") || lower.contains("forbidden")) {
                    return true;
                }
                if (!lower.contains("kugou") && !lower.contains(".mp3") && !lower.contains(".flac") && !lower.contains(".m4a")) {
                    return true;
                }
                return isExpiredViaRangeGet(url, timeoutMs);
            }
            if (code >= 200 && code < 300) {
                String ct = conn.getContentType();
                if (ct != null) {
                    String lowerCt = ct.toLowerCase();
                    boolean isAudioOrStream = lowerCt.startsWith("audio/")
                            || lowerCt.contains("application/octet-stream")
                            || lowerCt.contains("binary")
                            || lowerCt.contains("x-sc-download")
                            || lowerCt.contains("x-tif");
                    if (!isAudioOrStream) {
                        KuGouLogger.debug("[UrlRefresher] URL {} 2xx but Content-Type={} (not audio/*), treat as expired", url, ct);
                        return true;
                    }
                }
                return false;
            }
            return false;
        } catch (Exception e) {
            KuGouLogger.debug("[UrlRefresher] HEAD probe failed for {}: {}", url, e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final java.util.regex.Pattern TIMESTAMP_URL =
            java.util.regex.Pattern.compile("/(20\\d{6})/(\\d{4})/");
    private static final long TIMESTAMP_URL_MAX_VALID_MS = 10L * 60L * 1000L;

    private boolean isExpiredViaRangeGet(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.setRequestProperty("User-Agent", "NetMusic-KuGou/1.0");
            int code = conn.getResponseCode();
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
        if (conn instanceof HttpsURLConnection && TRUST_ALL_SSL_CONTEXT != null) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(TRUST_ALL_SSL_CONTEXT.getSocketFactory());
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

    /** 全信任 SSLContext 单例：避免每次探测都重建（含 SecureRandom），仅类加载时创建一次。 */
    private static final SSLContext TRUST_ALL_SSL_CONTEXT;
    static {
        SSLContext sc = null;
        try {
            sc = SSLContext.getInstance("TLS");
            sc.init(null, TRUST_ALL_MANAGERS, new java.security.SecureRandom());
        } catch (Exception e) {
            KuGouLogger.warn("[UrlRefresher] failed to init trust-all SSLContext: {}", e.getMessage());
        }
        TRUST_ALL_SSL_CONTEXT = sc;
    }
}
