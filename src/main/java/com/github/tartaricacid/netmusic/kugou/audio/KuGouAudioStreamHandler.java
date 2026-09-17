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

public class KuGouAudioStreamHandler implements IAudioStreamHandler {

    public static final String KUGOU_USER_AGENT =
            "KuGouAndroidPlayer/11430 (Linux;Android 13;Pixel 7 Build/TQ3A.230605.011) " +
                    "Dalvik/2.1.0 (Like Mozilla/5.0) okhttp/4.11.0";
    public static final String KUGOU_REFERER = "https://www.kugou.com/";
    public static final String KUGOU_ACCEPT =
            "audio/webm,audio/ogg,audio/wav,audio/mpeg,audio/mp3,audio/mp4,audio/flac,audio/aac,audio/*;q=0.9,*/*;q=0.8";
    public static final String KUGOU_ACCEPT_LANG = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7";

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public boolean canHandle(URL url) {
        if (url == null) return false;
        String host = url.getHost();
        if (host == null) return false;
        String protocol = url.getProtocol();
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
                        .header(HttpHeaders.RANGE, "bytes=%d-".formatted(start))
                        .GET();
                return b.build();
            };

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
            long dt = System.currentTimeMillis() - t0;
            KuGouLogger.error(
                    "[KuGouAudio] Stream FAILED after {}ms: {} | url={}",
                    dt, e.getMessage(), urlPreview, e);
            probeFailureReason(urlStr);
            throw e;
        } catch (Throwable t) {
            long dt = System.currentTimeMillis() - t0;
            KuGouLogger.error(
                    "[KuGouAudio] Stream CRASHED after {}ms: {} | url={}",
                    dt, t.getMessage(), urlPreview, t);
            if (t instanceof IOException) throw (IOException) t;
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
        return 100;
    }
}
