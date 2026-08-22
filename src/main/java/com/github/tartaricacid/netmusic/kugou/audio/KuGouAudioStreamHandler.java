package com.github.tartaricacid.netmusic.kugou.audio;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.audio.ChunkedAudioStream;
import com.github.tartaricacid.netmusic.client.audio.MusicBufferedInputStream;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.util.Mp3Util;
import com.google.common.net.HttpHeaders;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;

/**
 * 酷狗专属音频流处理器（高优先级，比父模组的 NetEaseHttpHandler / DirectHttpHandler 先跑）。
 * <p>
 * <b>为什么需要它而不是复用 DirectHttpHandler？</b>
 * <p>
 * DirectHttpHandler 给所有 HTTP 请求硬编码用了 {@code NetEaseMusic.getUserAgent()}（网易云专属 UA，
 * 形如 "CloudMusic/1.2.3 ..."），把这个 UA 发去酷狗 fs.youthandroid2.kugou.com CDN：
 * <ol>
 *   <li>命中边缘热点资源 → 放行，返回 200 audio/mpeg（这时能播，所以用户感觉"有时候可以播"）</li>
 *   <li>冷源回源 / 边缘节点开启严格校验 → 直接 403 或返回 200 text/html 错误页（AudioSystem 识别不了格式，
 *       抛 UnsupportedAudioFileException，NetMusicSound 降级用 error.ogg 兜底，实际静默没声音）</li>
 * </ol>
 * 本 Handler 只拦截 host 含 {@code kugou.com} 的 URL，把 Request 伪装成官方酷狗 Android 客户端
 * （clientver=11430，和 KuGouApiClient / EchoMusic 的签名、参数选择保持一致），避免被 CDN 风控拦截。
 * <p>
 * <b>注册时机</b>：必须在 AudioStreamHandlerManager.init() 完成 {@code HANDLERS.sort} + ImmutableList.copyOf
 * <b>之前</b>调用 {@code registerHandler}，否则会被拒绝。NetMusicKuGou 在 {@code AddReloadListenerEvent}
 * / {@code ClientModConstructorEvent} 早期阶段注册即可。
 */
public class KuGouAudioStreamHandler implements IAudioStreamHandler {

    public static final String KUGOU_USER_AGENT =
            "KuGouAndroidPlayer/11430 (Linux;Android 13;Pixel 7 Build/TQ3A.230605.011) " +
                    "Dalvik/2.1.0 (Like Mozilla/5.0) okhttp/4.11.0";
    public static final String KUGOU_REFERER = "https://www.kugou.com/";
    public static final String KUGOU_ACCEPT =
            "audio/webm,audio/ogg,audio/wav,audio/mpeg,audio/mp3,audio/mp4,audio/flac,audio/aac,audio/*;q=0.9,*/*;q=0.8";
    public static final String KUGOU_ACCEPT_LANG = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7";

    /** 单个分片的读超时，避免卡死音频下载线程。分片失败 ChunkedAudioStream 会重试，这里别给太长。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public boolean canHandle(URL url) {
        if (url == null) return false;
        String host = url.getHost();
        if (host == null) return false;
        String protocol = url.getProtocol();
        // 只拦截 kugou.com（含所有子域：fs.youthandroid2.kugou.com / webfs.kugou.com / ...）
        return (host.equalsIgnoreCase("kugou.com") || host.endsWith(".kugou.com"))
                && ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol));
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        final String urlStr = url.toString();
        String urlPreview = urlStr.length() < 120 ? urlStr : urlStr.substring(0, 120) + "...";
        KuGouLogger.info("[KuGouAudio] Begin download stream: {}", urlPreview);
        long t0 = System.currentTimeMillis();

        try {
            Function<Long, HttpRequest> requestFactory = start -> {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(urlStr))
                        .timeout(READ_TIMEOUT)
                        .header(HttpHeaders.USER_AGENT, KUGOU_USER_AGENT)
                        .header(HttpHeaders.REFERER, KUGOU_REFERER)
                        .header(HttpHeaders.ACCEPT, KUGOU_ACCEPT)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, KUGOU_ACCEPT_LANG)
                        // Range 头是 ChunkedAudioStream 的核心：支持分片 + 断点续传 + 失败重试
                        .header(HttpHeaders.RANGE, "bytes=%d-".formatted(start))
                        .GET();
                return b.build();
            };

            // 复用父模组 ChunkedAudioStream（分片下载、失败重试）+ MusicBufferedInputStream（mark/reset）
            // + Mp3Util.skipID3（跳过 ID3v2 头避免 AudioSystem 误判）。所有成熟逻辑直接复用，只换 Request 头。
            ChunkedAudioStream stream = new ChunkedAudioStream(requestFactory);
            BufferedInputStream bufferedInputStream = new MusicBufferedInputStream(stream);
            Mp3Util.skipID3(bufferedInputStream);
            AudioInputStream ais = AudioSystem.getAudioInputStream(bufferedInputStream);

            long dt = System.currentTimeMillis() - t0;
            KuGouLogger.info(
                    "[KuGouAudio] Stream OK in {}ms: format={}, sampleRate={}, channels={}, url={}",
                    dt,
                    ais.getFormat().getEncoding(),
                    ais.getFormat().getSampleRate(),
                    ais.getFormat().getChannels(),
                    urlPreview);
            return ais;
        } catch (UnsupportedAudioFileException | IOException e) {
            // ===== 播放失败一定打详细错误日志，别让它"静默失败"======
            long dt = System.currentTimeMillis() - t0;
            KuGouLogger.error(
                    "[KuGouAudio] Stream FAILED after {}ms: {} | url={}",
                    dt, e.getMessage(), urlPreview, e);
            // 额外尝试一次 "HEAD" 拿状态码 / Content-Type，把真实 HTTP 错误原因写进日志，
            // 方便用户回看"这次到底是 403 / 302 错页 / 400 / 5xx"。
            probeFailureReason(urlStr);
            throw e;
        } catch (Throwable t) {
            long dt = System.currentTimeMillis() - t0;
            KuGouLogger.error(
                    "[KuGouAudio] Stream CRASHED after {}ms: {} | url={}",
                    dt, t.getMessage(), urlPreview, t);
            if (t instanceof IOException io) throw io;
            throw new IOException("KuGou audio stream unexpected failure: " + t.getMessage(), t);
        }
    }

    private static void probeFailureReason(String urlStr) {
        try {
            URL u = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty(HttpHeaders.USER_AGENT, KUGOU_USER_AGENT);
            c.setRequestProperty(HttpHeaders.REFERER, KUGOU_REFERER);
            int code = c.getResponseCode();
            String ct = c.getContentType();
            String loc = c.getHeaderField(HttpHeaders.LOCATION);
            KuGouLogger.warn(
                    "[KuGouAudio] Failure probe: status={}, Content-Type={}, Location={}, urlPrefix={}",
                    code, ct, loc,
                    urlStr.length() < 120 ? urlStr : urlStr.substring(0, 120) + "...");
        } catch (Throwable probeErr) {
            KuGouLogger.warn("[KuGouAudio] Failure probe itself failed: {}", probeErr.getMessage());
        }
    }

    @Override
    public int getPriority() {
        // 比 NetEaseHttpHandler(10) 和 DirectHttpHandler(0) 都高，优先处理 *.kugou.com
        return 100;
    }
}
