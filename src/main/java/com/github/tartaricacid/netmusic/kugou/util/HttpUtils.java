package com.github.tartaricacid.netmusic.kugou.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * 通用 HTTP 请求工具类，封装 GET/POST 请求和响应解析
 */
public final class HttpUtils {

    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    /**
     * per-connection 全信任 SSLContext 缓存。
     * <p>
     * 按需给单个 connection 装 SSLContext，不污染 JVM 全局 SSLSocketFactory。
     */
    private static volatile SSLContext TRUST_ALL_CONTEXT;

    private static SSLContext getTrustAllContext() {
        SSLContext local = TRUST_ALL_CONTEXT;
        if (local == null) {
            try {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };
                local = SSLContext.getInstance("TLS");
                local.init(null, trustAll, new SecureRandom());
                TRUST_ALL_CONTEXT = local;
            } catch (Exception e) {
                return null;
            }
        }
        return local;
    }

    /**
     * 如果 conn 是 HTTPS，给它装上 trust-all SSLContext。
     * 调用方应在 {@code new URL(url).openConnection()} 后立即调用。
     */
    private static void applyTrustAllIfHttps(HttpURLConnection conn) {
        if (conn instanceof HttpsURLConnection) {
            SSLContext sc = getTrustAllContext();
            if (sc != null) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
            }
        }
    }

    private HttpUtils() {}

    /**
     * GET 请求
     */
    public static HttpResponse get(String url, Map<String, String> headers, Map<String, Object> params) throws IOException {
        String fullUrl = buildUrl(url, params);
        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        applyTrustAllIfHttps(conn);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        applyHeaders(conn, headers);
        return execute(conn);
    }

    /**
     * POST 请求 (form-urlencoded)
     */
    public static HttpResponse postForm(String url, Map<String, String> headers, Map<String, Object> params) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        applyTrustAllIfHttps(conn);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        applyHeaders(conn, headers);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        if (params != null && !params.isEmpty()) {
            String body = params.entrySet().stream()
                    .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(String.valueOf(e.getValue())))
                    .collect(Collectors.joining("&"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return execute(conn);
    }

    /**
     * POST 请求：原始字符串 body + URL query 参数
     * 用于设备注册等需要 raw body 的 API
     */
    public static HttpResponse postRaw(String url, Map<String, String> headers, Map<String, Object> queryParams, String rawBody) throws IOException {
        String fullUrl = buildUrl(url, queryParams);
        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        applyTrustAllIfHttps(conn);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        applyHeaders(conn, headers);
        conn.setRequestProperty("Content-Type", "application/octet-stream");

        if (rawBody != null && !rawBody.isEmpty()) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(rawBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        return execute(conn);
    }

    /**
     * POST 请求：原始字符串 body + URL query 参数，返回二进制 bytes
     * 用于设备注册等响应为二进制密文的 API
     */
    public static BinaryHttpResponse postRawBinary(String url, Map<String, String> headers, Map<String, Object> queryParams, String rawBody) throws IOException {
        String fullUrl = buildUrl(url, queryParams);
        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        applyTrustAllIfHttps(conn);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        applyHeaders(conn, headers);
        conn.setRequestProperty("Content-Type", "application/octet-stream");

        if (rawBody != null && !rawBody.isEmpty()) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(rawBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        return executeBinary(conn);
    }

    /**
     * 执行请求，返回二进制响应
     */
    private static BinaryHttpResponse executeBinary(HttpURLConnection conn) throws IOException {
        conn.connect();
        int code = conn.getResponseCode();

        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            conn.disconnect();
            return new BinaryHttpResponse(code, new byte[0]);
        }

        String encoding = conn.getContentEncoding();
        if ("gzip".equalsIgnoreCase(encoding)) {
            is = new GZIPInputStream(is);
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int n;
        while ((n = is.read(data)) != -1) {
            buffer.write(data, 0, n);
        }

        conn.disconnect();
        return new BinaryHttpResponse(code, buffer.toByteArray());
    }

    public static class BinaryHttpResponse {
        public final int statusCode;
        public final byte[] body;

        public BinaryHttpResponse(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public boolean isOk() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /**
     * POST JSON body
     */
    public static HttpResponse postJson(String url, Map<String, String> headers, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        applyTrustAllIfHttps(conn);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        applyHeaders(conn, headers);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        if (jsonBody != null && !jsonBody.isEmpty()) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        return execute(conn);
    }

    private static void applyHeaders(HttpURLConnection conn, Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * 构建完整 URL（公开方法，用于调试日志）
     */
    public static String buildFullUrl(String baseUrl, Map<String, Object> params) {
        return buildUrl(baseUrl, params);
    }

    private static String buildUrl(String baseUrl, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return baseUrl;
        String query = params.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(String.valueOf(e.getValue())))
                .collect(Collectors.joining("&"));
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + query;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static HttpResponse execute(HttpURLConnection conn) throws IOException {
        conn.connect();
        int code = conn.getResponseCode();

        // 读取 Set-Cookie
        Map<String, String> respCookies = new HashMap<>();
        Map<String, List<String>> headerFields = conn.getHeaderFields();
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                for (String cookieStr : entry.getValue()) {
                    String[] parts = cookieStr.split(";")[0].split("=", 2);
                    if (parts.length == 2) {
                        respCookies.put(parts[0].trim(), parts[1].trim());
                    }
                }
            }
        }

        // 读取响应体
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            conn.disconnect();
            return new HttpResponse(code, "", respCookies);
        }

        // 先读原始字节，再判断是否需要 gzip 解压
        ByteArrayOutputStream byteBuf = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            byteBuf.write(buf, 0, n);
        }
        is.close();
        byte[] rawBytes = byteBuf.toByteArray();

        String encoding = conn.getContentEncoding();

        // 检测 gzip：Content-Encoding 声明 或 magic bytes (0x1f 0x8b)
        boolean isGzip = "gzip".equalsIgnoreCase(encoding)
                || (rawBytes.length > 2 && (rawBytes[0] & 0xff) == 0x1f && (rawBytes[1] & 0xff) == 0x8b);

        String body;
        if (isGzip && rawBytes.length > 0) {
            try {
                body = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(new java.io.ByteArrayInputStream(rawBytes)),
                        StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));
            } catch (Exception e) {
                // gzip 解压失败，回退到直接解码
                body = new String(rawBytes, StandardCharsets.UTF_8);
            }
        } else {
            body = new String(rawBytes, StandardCharsets.UTF_8);
        }

        conn.disconnect();
        return new HttpResponse(code, body, respCookies);
    }

    public static class HttpResponse {
        public final int statusCode;
        public final String body;
        public final Map<String, String> cookies;

        public HttpResponse(int statusCode, String body, Map<String, String> cookies) {
            this.statusCode = statusCode;
            this.body = body;
            this.cookies = cookies;
        }

        public boolean isOk() {
            return statusCode >= 200 && statusCode < 300;
        }

        public JsonElement asJson() {
            return JsonParser.parseString(body);
        }
    }
}
