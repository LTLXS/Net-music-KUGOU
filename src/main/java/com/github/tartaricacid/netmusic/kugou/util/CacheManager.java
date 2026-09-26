package com.github.tartaricacid.netmusic.kugou.util;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.audio.KuGouAudioStreamHandler;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.google.common.net.HttpHeaders;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 歌曲音频磁盘缓存。
 首次播放时由 KuGouAudioStreamHandler 在后台把 mp3 下载到磁盘（key = URL 的 SHA-256 前 16 位），
 之后同 URL 的播放直接读盘，省流量并规避酷狗 CDN 对重复拉取的风控 403。
 超过 CACHE_MAX_MB（0 = 不限制）后按文件修改时间淘汰最久未播放的歌曲。
*/
public final class CacheManager {
    private CacheManager() {
    }

    private static final Path CACHE_DIR = FMLPaths.CONFIGDIR.get()
            .resolve("NETMUSICCANNEEDKUGOU").resolve("songs");

    static {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (Throwable ignored) {
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 防止同一 key 并发重复下载 */
    private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();

    public static String cacheKeyForUrl(String url) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static Path fileFor(String key) {
        return CACHE_DIR.resolve(key + ".mp3");
    }

    public static boolean isCached(String key) {
        try {
            Path f = fileFor(key);
            return Files.exists(f) && Files.size(f) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static InputStream openCached(String key) throws IOException {
        Path f = fileFor(key);
        try {
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (Throwable ignored) {
        }
        return new BufferedInputStream(Files.newInputStream(f));
    }

    public static void startSongDownload(String url, String key) {
        if (!ClientConfig.CACHE_ENABLED.get()) return;
        if (isCached(key)) return;
        if (!INFLIGHT.add(key)) return;
        CompletableFuture.runAsync(() -> {
            try {
                Path finalPath = fileFor(key);
                Path tmp = CACHE_DIR.resolve(key + ".mp3.tmp");
                Files.deleteIfExists(tmp);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header(HttpHeaders.USER_AGENT, KuGouAudioStreamHandler.KUGOU_USER_AGENT)
                        .header(HttpHeaders.REFERER, KuGouAudioStreamHandler.KUGOU_REFERER)
                        .header(HttpHeaders.ACCEPT, KuGouAudioStreamHandler.KUGOU_ACCEPT)
                        .GET().build();
                HttpResponse<Path> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
                if (resp.statusCode() >= 200 && resp.statusCode() < 300 && Files.size(tmp) > 0) {
                    Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    KuGouLogger.info("[CacheManager] cached key={} ({} bytes)", key, Files.size(finalPath));
                    enforceLimit();
                } else {
                    Files.deleteIfExists(tmp);
                    KuGouLogger.warn("[CacheManager] download non-2xx for key={}: {}", key, resp.statusCode());
                }
            } catch (Throwable t) {
                KuGouLogger.warn("[CacheManager] download failed key={}: {}", key, t.getMessage());
            } finally {
                INFLIGHT.remove(key);
            }
        });
    }

    public static void enforceLimit() {
        long max = ClientConfig.CACHE_MAX_MB.get() * 1024L * 1024L;
        if (max <= 0) return;
        try {
            List<Path> files;
            try (Stream<Path> s = Files.list(CACHE_DIR)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".mp3"))
                        .sorted(Comparator.comparingLong(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (Throwable e) {
                                return 0L;
                            }
                        }))
                        .collect(Collectors.toList());
            }
            long total = 0;
            for (Path p : files) total += Files.size(p);
            for (Path p : files) {
                if (total <= max) break;
                total -= Files.size(p);
                Files.deleteIfExists(p);
            }
        } catch (Throwable t) {
            KuGouLogger.warn("[CacheManager] enforceLimit failed: {}", t.getMessage());
        }
    }

    public static void clearAll() {
        try (Stream<Path> s = Files.list(CACHE_DIR)) {
            List<Path> files = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".mp3") || n.endsWith(".mp3.tmp");
            }).collect(Collectors.toList());
            for (Path p : files) Files.deleteIfExists(p);
        } catch (Throwable t) {
            KuGouLogger.warn("[CacheManager] clearAll failed: {}", t.getMessage());
        }
    }

    public static long totalSize() {
        try (Stream<Path> s = Files.list(CACHE_DIR)) {
            final long[] t = {0};
            s.filter(p -> p.getFileName().toString().endsWith(".mp3"))
                    .forEach(p -> {
                        try {
                            t[0] += Files.size(p);
                        } catch (Throwable ignored) {
                        }
                    });
            return t[0];
        } catch (Throwable e) {
            return 0;
        }
    }
}
