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
     * 检查单个 CD（插入唱片机/右键点歌时调用）：
     * <b>不做 isExpired 判断,无条件刷新 URL</b>。
     * 因为 fs.youthandroid2.kugou.com/YYYYMMDDHHMM/... 这类时间戳 URL 过期后,
     * 服务端会返回 302 跳错误页 / 200 text/html / 400,不会按预期 403,
     * 导致 isExpired 经常误判"仍然有效"→不刷新→播放失败。
     * 插入唱片机/点歌属于低频操作,直接刷新最稳。
     *
     * @return true 表示成功续期了 URL（或刷新后与原 URL 相同,但调用方一般会认为 OK）
     */
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
                KuGouLogger.info("[UrlRefresher] Force-refresh got same URL for hash={}, keeping as-is (assume still valid)", info.fileHash());
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

    /**
     * 检查单个 CD（周期巡检背包/末影箱调用）：
     * - 没有 fileHash 记录 → 跳过
     * - URL 内置时间戳超过 10 分钟 → 直接刷新
     * - HEAD / Range-GET 判定为过期（403 / 410 / 302 跳非音频页 / 2xx 但 Content-Type!=audio）→ 刷新
     * <p>
     * 此方法会阻塞调用线程（HEAD + getSongUrl），适合在调度线程里调用。
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
                info.fileHash(),
                currentUrl.length() < 120 ? currentUrl : currentUrl.substring(0, 120) + "...");
        try {
            String newUrl = KuGouApiClient.getSongUrl(info.fileHash(),
                    info.albumId() == null ? "" : info.albumId()).get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (newUrl == null || newUrl.isEmpty()) {
                KuGouLogger.warn("[UrlRefresher] Failed to fetch new URL for hash={} (KuGou returned empty)", info.fileHash());
                return false;
            }
            if (newUrl.equals(currentUrl)) {
                KuGouLogger.info("[UrlRefresher] KuGou returned the same URL for hash={} (likely also expired). Will retry next round.", info.fileHash());
                return false;
            }
            CdNbtHelper.updateSongUrl(cd, newUrl);
            CdNbtHelper.updateData(cd, d -> new CdAddonData(
                    d.fileHash(), d.albumId(), System.currentTimeMillis(), d.lrc(), d.lrcTrans()
            ));
            KuGouLogger.info("[UrlRefresher] CD URL refreshed: hash={} -> newUrlPrefix={}", info.fileHash(),
                    newUrl.length() < 80 ? newUrl : newUrl.substring(0, 80) + "...");
            return true;
        } catch (Exception e) {
            KuGouLogger.error("[UrlRefresher] Exception while refreshing hash={}: {}",
                    info.fileHash(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 用多种规则判断 URL 是否已过期：
     * <ol>
     *   <li>快速规则：若 URL 匹配 {@code /YYYYMMDDHHMM/} 时间戳路径，超过 10 分钟直接算过期</li>
     *   <li>HEAD 请求：
     *     <ul>
     *       <li>403 / 410 / 400 → 过期</li>
     *       <li>302：跳转后 Location 明显不是音频页（跳转域名不含 kugou / url 不含路径 hash）→ 过期</li>
     *       <li>2xx：如果 Content-Type 不是 audio/* / application/octet-stream → 过期（大概率是 200 HTML 错误页）</li>
     *     </ul>
     *   </li>
     * </ol>
     */
    private boolean isExpired(String url) {
        // 1) 快速过期判断：fs.youthandroid2.kugou.com / YYYYMMDDHHMM 路径 token 型 URL
        java.util.regex.Matcher m = TIMESTAMP_URL.matcher(url);
        if (m.find()) {
            try {
                String ymd = m.group(1);  // YYYYMMDD
                String hm = m.group(2);   // HHMM
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
            conn.setInstanceFollowRedirects(false);  // 我们自己判断 302
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "NetMusic-KuGou/1.0");
            int code = conn.getResponseCode();
            if (code == 405 || code == 501) {
                return isExpiredViaRangeGet(url, timeoutMs);
            }
            if (code == 403 || code == 410 || code == 400) return true;
            if (code >= 301 && code <= 307) {
                // 30x:如果 Location 明显跳转到错误页/非音频域,算过期;否则跟过去判断
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) return true;
                // 重定向到明显的登录、版权、error 页面
                String lower = location.toLowerCase();
                if (lower.contains("403") || lower.contains("expire") || lower.contains("copyright")
                        || lower.contains("login") || lower.contains("error") || lower.contains("forbidden")) {
                    return true;
                }
                if (!lower.contains("kugou") && !lower.contains(".mp3") && !lower.contains(".flac") && !lower.contains(".m4a")) {
                    // 跳转到非酷狗域且后缀不是音频,几乎可以确定是过期跳转广告/错误页
                    return true;
                }
                // 否则跟着跳转再试一次 Range GET
                return isExpiredViaRangeGet(url, timeoutMs);
            }
            if (code >= 200 && code < 300) {
                // 2xx: 检查 Content-Type,如果不是 audio/* / application/octet-stream 就极可能是 200 HTML 错误页
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

    /** 对 fs.youthandroid2.kugou.com/YYYYMMDDHHMM/... 这类 URL 的时间戳正则 */
    private static final java.util.regex.Pattern TIMESTAMP_URL =
            java.util.regex.Pattern.compile("/(20\\d{6})/(\\d{4})/");
    /** 时间戳 URL 最大有效期（10 分钟） */
    private static final long TIMESTAMP_URL_MAX_VALID_MS = 10L * 60L * 1000L;

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
