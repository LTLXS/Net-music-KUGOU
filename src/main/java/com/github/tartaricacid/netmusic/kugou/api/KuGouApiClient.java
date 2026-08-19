package com.github.tartaricacid.netmusic.kugou.api;

import com.github.tartaricacid.netmusic.kugou.config.AudioQuality;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.util.CryptoUtils;
import com.github.tartaricacid.netmusic.kugou.util.HttpUtils;
import com.github.tartaricacid.netmusic.kugou.util.KuGouSignature;
import com.google.gson.*;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 统一酷狗 API 客户端
 * 封装设备注册、搜索、获取歌曲 URL、登录功能
 */
public final class KuGouApiClient {

    private static final Gson GSON = new Gson();

    // 设备注册状态
    private static volatile boolean deviceReady = false;
    private static final Object DEVICE_LOCK = new Object();

    private KuGouApiClient() {}

    // ==================== 初始化 ====================

    /**
     * 确保设备已注册。从配置加载已有的凭证，若无效则重新注册。
     */
    public static CompletableFuture<Boolean> ensureDeviceRegistered() {
        if (deviceReady && KuGouConfig.dfid != null && !KuGouConfig.dfid.isEmpty() && !"-".equals(KuGouConfig.dfid)) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            synchronized (DEVICE_LOCK) {
                if (deviceReady && KuGouConfig.dfid != null && !KuGouConfig.dfid.isEmpty() && !"-".equals(KuGouConfig.dfid)) {
                    return true;
                }
                try {
                    // 如果没有 GUID，重新生成
                    if (KuGouConfig.guid == null || KuGouConfig.guid.isEmpty()) {
                        KuGouConfig.guid = KuGouDeviceRegister.generateGuid();
                    }
                    if (KuGouConfig.mid == null || KuGouConfig.mid.isEmpty()) {
                        KuGouConfig.mid = KuGouDeviceRegister.calculateMid(KuGouConfig.guid);
                    }

                    KuGouDeviceRegister.DeviceInfo devInfo = KuGouDeviceRegister.registerDevice(
                            KuGouConfig.token, KuGouConfig.userid, KuGouConfig.mid, KuGouConfig.guid);

                    if (devInfo.isValid()) {
                        KuGouConfig.dfid = devInfo.dfid;
                        KuGouConfig.mid = devInfo.mid;
                        KuGouConfig.guid = devInfo.guid;
                        KuGouConfig.markDirty();
                        deviceReady = true;
                        return true;
                    }
                } catch (Exception e) {
                    KuGouLogger.error("[NetMusicKuGou] Device register failed: {}", e.getMessage(), e);
                }
                return false;
            }
        });
    }

    // ==================== 搜索 ====================

    /**
     * 搜索歌曲
     * 使用 mobilecdn.kugou.com 公开 API，无需签名和注册
     * gateway.kugou.com 的 v3/search/song 已失效 (error_code: 152)
     */
    public static CompletableFuture<List<Song>> search(String keyword, int page, int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("format", "json");
                params.put("keyword", keyword);
                params.put("page", page);
                params.put("pagesize", pageSize);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                String searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song";
                HttpUtils.HttpResponse response = HttpUtils.get(searchUrl, headers, params);

                if (!response.isOk()) {
                    KuGouLogger.warn("[NetMusicKuGou] Search HTTP error: {}", response.statusCode);
                    return Collections.emptyList();
                }

                return parseSearchResult(response.body);
            } catch (IOException e) {
                KuGouLogger.error("[NetMusicKuGou] Search failed: {}", e.getMessage(), e);
                return Collections.emptyList();
            }
        });
    }

    private static List<Song> parseSearchResult(String jsonStr) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return Collections.emptyList();

            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            if (status != 1) return Collections.emptyList();

            JsonObject data = root.getAsJsonObject("data");
            if (data == null) return Collections.emptyList();

            JsonArray info = data.has("info") ? data.getAsJsonArray("info") :
                    (data.has("lists") ? data.getAsJsonArray("lists") : null);
            if (info == null) return Collections.emptyList();

            List<Song> songs = new ArrayList<>();
            for (JsonElement elem : info) {
                JsonObject item = elem.getAsJsonObject();
                String hash = getStr(item, "hash");
                // mobilecdn 公开 API 不返回 id，用 hash 替代
                String id = getStr(item, "id");
                if (id.isEmpty()) id = hash;
                String name = getStr(item, "songname").isEmpty() ? getStr(item, "filename") : getStr(item, "songname");
                String singer = getStr(item, "singername");
                String album = getStr(item, "album_name");
                String albumId = getStr(item, "album_id");
                int duration = item.has("duration") && !item.get("duration").isJsonNull()
                        ? item.get("duration").getAsInt() : 0;

                if (name.isEmpty()) name = getStr(item, "filename");
                if (singer.isEmpty()) singer = getStr(item, "author_name");

                songs.add(new Song(id, name, singer, album, hash, albumId, duration));
            }
            return songs;
        } catch (JsonSyntaxException e) {
            KuGouLogger.warn("[NetMusicKuGou] Parse search result failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 获取歌曲 URL ====================

    /**
     * 音质降级链（从高到低）。与 AudioQuality 枚举对应，顺序很关键。
     * 必须是 AudioQuality 中能实际映射到酷狗协议 quality 字段的成员。
     */
    private static final AudioQuality[] QUALITY_LADDER = new AudioQuality[]{
            AudioQuality.SUPER_DSD,   // "super"
            AudioQuality.SQ_FLAC,     // "flac"
            AudioQuality.HIGH,        // "high"
            AudioQuality.HQ,          // "320"
            AudioQuality.STANDARD     // "128"
    };

    /**
     * 给定用户请求的音质，返回从该音质往下一直到 128 的降级数组。
     * 如果传入 null，默认从 HQ 开始。
     */
    private static AudioQuality[] getQualityFallbackOrder(AudioQuality requested) {
        int startIdx = QUALITY_LADDER.length - 1;  // 默认到 STANDARD
        if (requested != null) {
            for (int i = 0; i < QUALITY_LADDER.length; i++) {
                if (QUALITY_LADDER[i] == requested) {
                    startIdx = i;
                    break;
                }
            }
        }
        AudioQuality[] result = new AudioQuality[startIdx + 1];
        System.arraycopy(QUALITY_LADDER, 0, result, 0, startIdx + 1);
        return result;
    }

    /**
     * 获取歌曲播放 URL（使用配置的默认音质）
     */
    public static CompletableFuture<String> getSongUrl(String hash, String albumId) {
        return getSongUrl(hash, albumId, ClientConfig.getAudioQuality());
    }

    /**
     * 获取歌曲播放 URL（指定音质）
     * <p>
     * 自动降级策略：从用户选定的音质开始，按 super → flac → high → 320 → 128 的顺序逐级尝试。
     * 每个音质依次走公开 API → v5/url。如果所有音质都拿不到，最后兜底走 v3/yiting（不带 quality，由服务端决定）。
     */
    public static CompletableFuture<String> getSongUrl(String hash, String albumId, AudioQuality quality) {
        return CompletableFuture.supplyAsync(() -> {
            AudioQuality[] ladder = getQualityFallbackOrder(quality);

            // 步骤 1: 按降级链依次尝试 public + v5/url
            for (int i = 0; i < ladder.length; i++) {
                AudioQuality q = ladder[i];
                boolean isLastInLadder = (i == ladder.length - 1);

                String url = fetchPublicSongUrl(hash, q);
                if (!url.isEmpty()) {
                    return url;
                }

                url = fetchAuthenticatedSongUrl(hash, q);
                if (!url.isEmpty()) {
                    return url;
                }

                if (!isLastInLadder) {
                    KuGouLogger.info("[NetMusicKuGou] Quality {} unavailable for hash={}, falling back to {}",
                            q.getValue(), hash, ladder[i + 1].getValue());
                }
            }

            // 步骤 2: 最后兜底 v3/yiting（不带 quality，让服务端自己挑）
            KuGouLogger.warn("[NetMusicKuGou] All qualities exhausted for hash={}, trying gateway v3", hash);
            return fetchGatewaySongUrl(hash);
        });
    }

    /**
     * 方式1: 公开移动端 API（无需签名，仅支持免费歌曲）
     */
    private static String fetchPublicSongUrl(String hash, AudioQuality quality) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("cmd", "playInfo");
            params.put("hash", hash.toLowerCase());
            // 必须把 quality 一并发给 m.kugou.com，否则接口默认返回 128kbps
            if (quality != null) {
                params.put("quality", quality.getValue());
            }

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36");
            headers.put("Referer", "https://m.kugou.com/");

            HttpUtils.HttpResponse response = HttpUtils.get(
                    "http://m.kugou.com/app/i/getSongInfo.php", headers, params);

            if (!response.isOk() || response.body.isEmpty()) return "";
            return parseSongUrl(response.body);
        } catch (IOException e) {
            KuGouLogger.warn("[NetMusicKuGou] Public API failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 方式2: trackercdn /v5/url 接口（KuGou官方使用的VIP歌曲接口）
     * 必须携带 dfid + 完整参数 + key签名 + signature，否则返回"err signature"（error_code 20006）
     */
    private static String fetchAuthenticatedSongUrl(String hash, AudioQuality quality) {
        try {
            String dfid = KuGouConfig.dfid;
            if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) {
                KuGouLogger.warn("[NetMusicKuGou] Auth API (v5/url) skipped: no dfid");
                return "";
            }

            int cltime = (int) (System.currentTimeMillis() / 1000);
            String mid = KuGouConfig.mid != null ? KuGouConfig.mid : "";
            String userid = KuGouConfig.userid != null ? KuGouConfig.userid : "0";
            // ⚠️ 必须与 VIP 查询（getVipInfo）保持一致：概念版 = Lite 凭证
            // 对应 KuGou config.json: liteAppid=3116, liteClientver=11440
            String appid = "3116";
            int clientver = 11440;

            // 音质映射：与KuGou保持一致
            String qualityStr = (quality != null) ? quality.getValue() : "320";
            // 特效音质前缀处理（piano/dj/acappella等）
            if (!qualityStr.equals("128") && !qualityStr.equals("320") &&
                !qualityStr.equals("flac") && !qualityStr.equals("high") && !qualityStr.equals("super")) {
                qualityStr = "magic_" + qualityStr;
            }

            // 构建完整参数列表（与KuGou server/module/song_url.js 一致）
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("album_id", 0);
            params.put("area_code", 1);
            params.put("hash", hash.toLowerCase());
            params.put("ssa_flag", "is_fromtrack");
            params.put("version", clientver);
            params.put("page_id", 151369488);
            params.put("quality", qualityStr);
            params.put("album_audio_id", 0);
            params.put("behavior", "play");
            params.put("pid", 2);
            params.put("cmd", 26);
            params.put("pidversion", 3001);
            params.put("IsFreePart", 0);
            params.put("ppage_id", "463467626,350369493,788954147");
            params.put("cdnBackup", 1);
            params.put("module", "");
            params.put("clientver", clientver);

            // 基础认证参数
            params.put("dfid", dfid);
            params.put("mid", mid);
            params.put("uuid", "-");
            params.put("appid", appid);
            params.put("clienttime", String.valueOf(cltime));
            if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                params.put("token", KuGouConfig.token);
            }
            if (userid != null && !userid.isEmpty() && !"0".equals(userid)) {
                params.put("userid", userid);
            }

            // signKey 签名（encryptKey: true）
            // 来自 helper.js signKey(): MD5(hash + salt + appid + mid + userid)
            // 非lite模式的salt = "57ae12eb6890223e355ccfcb74edf70d"
            String signSalt = "57ae12eb6890223e355ccfcb74edf70d";
            String key = CryptoUtils.md5(hash.toLowerCase() + signSalt + appid + mid + userid);
            params.put("key", key);

            // ⚠️ 关键：还要再算一个 signature 并加进 params，否则 gateway 会返回 error_code 20006 "err signature"
            // 来自 helper.js signatureAndroidParams：MD5(ANDROID_SECRET + sortedKey=Value + data + ANDROID_SECRET)，data=""（GET 无 body）
            // Lite 凭证配套的 salt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
            params.put("signature", KuGouSignature.signatureAndroidParams(params, ""));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
            headers.put("x-router", "trackercdn.kugou.com");
            headers.put("dfid", dfid);
            headers.put("mid", mid);
            headers.put("clienttime", String.valueOf(cltime));
            headers.put("kg-rc", "1");
            headers.put("kg-thash", "5d816a0");
            headers.put("kg-rec", "1");
            headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

            // Cookie 用于 VIP 认证
            StringBuilder cookieSb = new StringBuilder();
            if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                cookieSb.append("token=").append(KuGouConfig.token).append("; ");
            }
            if (userid != null && !userid.isEmpty() && !"0".equals(userid)) {
                cookieSb.append("userid=").append(userid).append("; ");
            }
            if (KuGouConfig.vipType != null && !KuGouConfig.vipType.isEmpty()) {
                cookieSb.append("vip_type=").append(KuGouConfig.vipType).append("; ");
            }
            if (KuGouConfig.vipToken != null && !KuGouConfig.vipToken.isEmpty()) {
                cookieSb.append("vip_token=").append(KuGouConfig.vipToken).append("; ");
            }
            for (var entry : KuGouConfig.cookies.entrySet()) {
                cookieSb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
            }
            String cookieStr = cookieSb.toString().trim();
            if (!cookieStr.isEmpty()) {
                headers.put("Cookie", cookieStr);
            }

            HttpUtils.HttpResponse response = HttpUtils.get(
                    "https://gateway.kugou.com/v5/url", headers, params);

            if (!response.isOk() || response.body.isEmpty()) return "";
            return parseV5UrlResponse(response.body);

        } catch (Exception e) {
            KuGouLogger.error("[NetMusicKuGou] v5/url API exception: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * 方式3: gateway v3/yiting 接口（带签名和完整 Cookie）
     */
    private static String fetchGatewaySongUrl(String hash) {
        try {
            String dfid = KuGouConfig.dfid;
            if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) return "";

            int cltime = (int) (System.currentTimeMillis() / 1000);
            String secret = "OIlwieks28dk2k092lksi2UIkp";

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("hash", hash.toLowerCase());
            params.put("dfid", dfid);
            params.put("mid", KuGouConfig.mid != null ? KuGouConfig.mid : "");
            params.put("appid", "1010");
            params.put("platid", "4");
            params.put("version", "10063");
            params.put("clienttime", String.valueOf(cltime));
            params.put("srcappid", "2919");
            params.put("clientver", "12000");
            params.put("uuid", "-");

            // 构造签名
            List<String> keys = new ArrayList<>(params.keySet());
            Collections.sort(keys);
            StringBuilder sigInput = new StringBuilder(secret);
            for (String k : keys) sigInput.append(k).append(params.get(k));
            sigInput.append(secret);
            String signature = CryptoUtils.md5(sigInput.toString());
            params.put("signature", signature);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
            headers.put("x-router", "yiting.kugou.com");
            headers.put("kg-rc", "1");
            headers.put("kg-thash", "5d816a0");
            headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

            // Cookie
            StringBuilder cookieSb = new StringBuilder();
            if (KuGouConfig.token != null) cookieSb.append("token=").append(KuGouConfig.token).append("; ");
            if (KuGouConfig.userid != null) cookieSb.append("userid=").append(KuGouConfig.userid).append("; ");
            for (var entry : KuGouConfig.cookies.entrySet())
                cookieSb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
            String cookieStr = cookieSb.toString().trim();
            if (!cookieStr.isEmpty()) headers.put("Cookie", cookieStr);

            HttpUtils.HttpResponse response = HttpUtils.get(
                    "https://gateway.kugou.com/v3/yiting/song/info", headers, params);

            if (!response.isOk() || response.body.isEmpty()) return "";
            return parseYitingResponse(response.body);

        } catch (Exception e) {
            KuGouLogger.warn("[NetMusicKuGou] Gateway yiting API failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 解析 trackercdn /v5/url 响应
     * 返回格式: {"status":1,"url":"https://..."} 或带 data/info 嵌套结构
     */
    private static String parseV5UrlResponse(String jsonStr) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return "";

            // 检查 status 或 error_code
            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            if (status == 0 || status == -1) {
                // 可能有 error_code
                if (root.has("error_code")) {
                    int errCode = root.get("error_code").getAsInt();
                    if (errCode != 0) {
                        KuGouLogger.warn("[NetMusicKuGou] v5/url returned error_code={}", errCode);
                        return "";
                    }
                }
            }

            // 递归解析 URL（与 KuGou resolveUrlFromResponse 逻辑一致）
            String url = resolveUrlRecursive(root);
            if (!url.isEmpty()) return url;

            return "";
        } catch (JsonSyntaxException e) {
            KuGouLogger.warn("[NetMusicKuGou] Parse v5/url response failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 递归从JSON对象中提取URL（模拟KuGou resolveUrlFromResponse）
     */
    private static String resolveUrlRecursive(JsonObject obj) {
        // 优先级: url > play_url > playUrl
        String[] urlFields = {"url", "play_url", "playUrl"};
        for (String field : urlFields) {
            if (obj.has(field) && !obj.get(field).isJsonNull()) {
                JsonElement el = obj.get(field);
                if (el.isJsonPrimitive()) {
                    String u = el.getAsString();
                    if (!u.isEmpty() && (u.startsWith("http://") || u.startsWith("https://"))) {
                        return u;
                    }
                } else if (el.isJsonArray()) {
                    for (JsonElement item : el.getAsJsonArray()) {
                        if (item.isJsonPrimitive()) {
                            String u = item.getAsString();
                            if (!u.isEmpty() && (u.startsWith("http://") || u.startsWith("https://"))) {
                                return u;
                            }
                        }
                    }
                }
            }
        }

        // 递归查找子节点 data / info
        if (obj.has("data")) {
            JsonElement dataEl = obj.get("data");
            if (dataEl.isJsonObject()) {
                String result = resolveUrlRecursive(dataEl.getAsJsonObject());
                if (!result.isEmpty()) return result;
            } else if (dataEl.isJsonArray() && dataEl.getAsJsonArray().size() > 0) {
                JsonElement first = dataEl.getAsJsonArray().get(0);
                if (first.isJsonObject()) {
                    String result = resolveUrlRecursive(first.getAsJsonObject());
                    if (!result.isEmpty()) return result;
                }
            } else if (dataEl.isJsonPrimitive()) {
                String u = dataEl.getAsString();
                if (!u.isEmpty() && (u.startsWith("http://") || u.startsWith("https://"))) {
                    return u;
                }
            }
        }
        if (obj.has("info")) {
            JsonElement infoEl = obj.get("info");
            if (infoEl.isJsonObject()) {
                String result = resolveUrlRecursive(infoEl.getAsJsonObject());
                if (!result.isEmpty()) return result;
            }
        }

        // 尝试 urls 数组（多音质）
        if (obj.has("urls")) {
            JsonArray urlsArr = obj.getAsJsonArray("urls");
            for (JsonElement ue : urlsArr) {
                if (ue.isJsonObject()) {
                    String result = resolveUrlRecursive(ue.getAsJsonObject());
                    if (!result.isEmpty()) return result;
                }
            }
        }

        return "";
    }

    /**
     * 解析 gateway v3/yiting/song/info 响应
     * 返回格式: {"error_code":0,"data":[{"url":"https://..."}]}
     */
    private static String parseYitingResponse(String jsonStr) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return "";
            int errCode = root.has("error_code") ? root.get("error_code").getAsInt() : -1;
            if (errCode != 0) return "";

            if (!root.has("data")) return "";
            JsonElement dataEl = root.get("data");

            // data 可能是对象或数组；任何字段都先 getStr()（守卫 isJsonPrimitive），
            // 避免酷狗在 url/play_url 等字段返回对象/数组时 getAsString() 抛 IllegalStateException。
            if (dataEl.isJsonArray()) {
                JsonArray arr = dataEl.getAsJsonArray();
                for (JsonElement item : arr) {
                    if (item.isJsonObject()) {
                        JsonObject obj = item.getAsJsonObject();
                        String u = getStr(obj, "url");
                        if (!u.isEmpty()) return u;
                        u = getStr(obj, "play_url");
                        if (!u.isEmpty()) return u;
                    }
                }
            } else if (dataEl.isJsonObject()) {
                JsonObject dataObj = dataEl.getAsJsonObject();
                String u = getStr(dataObj, "url");
                if (!u.isEmpty()) return u;
                u = getStr(dataObj, "play_url");
                if (!u.isEmpty()) return u;
                // 嵌套的 audio_list
                if (dataObj.has("audio_list")) {
                    JsonArray audioList = dataObj.getAsJsonArray("audio_list");
                    for (JsonElement item : audioList) {
                        if (item.isJsonObject()) {
                            JsonObject audio = item.getAsJsonObject();
                            String u2 = getStr(audio, "url");
                            if (!u2.isEmpty()) return u2;
                        }
                    }
                }
            }
            return "";
        } catch (JsonSyntaxException e) {
            KuGouLogger.warn("[NetMusicKuGou] Parse yiting response failed: {}", e.getMessage());
            return "";
        }
    }

    // ==================== VIP 状态查询 ====================

    /**
     * 查询当前账号的 VIP 信息
     * 对应 KuGou server/module/user_vip_detail.js
     * 调用 kugouvip.kugou.com/v1/get_union_vip?busi_type=concept
     *
     * @return VIP 信息 JSON 字符串，格式如：
     *         {"status":1,"data":{"tvip":{"is_vip":0,...},"svip":{"is_vip":1,"vip_end_time":"...",...}}}
     */
    public static CompletableFuture<String> getVipInfo() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!KuGouConfig.isLoggedIn()) {
                    return "{\"status\":0,\"errmsg\":\"未登录\"}";
                }

                String dfid = KuGouConfig.dfid;
                if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) {
                    return "{\"status\":0,\"errmsg\":\"未注册设备\"}";
                }

                int cltime = (int) (System.currentTimeMillis() / 1000);
                // ⚠️ 优先使用 cookie 中的 KUGOU_API_MID（与 KuGou request.js 第35行一致）
                String kugouApiMid = KuGouConfig.cookies.get("KUGOU_API_MID");
                String mid = (kugouApiMid != null && !kugouApiMid.isEmpty())
                        ? kugouApiMid
                        : (KuGouConfig.mid != null ? KuGouConfig.mid : "");
                String userid = KuGouConfig.userid != null ? KuGouConfig.userid : "0";

                // ⚠️ busi_type=concept 必须使用 Lite 版 appid/clientver！
                // 对应 KuGou config.json: liteAppid=3116, liteClientver=11440
                // 错误值(1005/20489)会导致 error_code:20017 "params invalid"
                String appid = "3116";        // liteAppid
                int clientver = 11440;         // liteClientver

                // 构建参数（完全对齐 KuGou request.js defaultParams + 业务参数）
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("dfid", dfid);
                params.put("mid", mid);
                params.put("uuid", "-");
                params.put("appid", appid);
                params.put("clientver", clientver);
                params.put("clienttime", String.valueOf(cltime));
                if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                    params.put("token", KuGouConfig.token);
                }
                if (!"0".equals(userid) && !userid.isEmpty()) {
                    params.put("userid", userid);
                }
                // 业务参数 — concept=概念版(必须配Lite凭证)
                params.put("busi_type", "concept");

                // Android 签名 (对齐 KuGou helper.js signatureAndroidParams 第24-31行)
                // ⚠️ salt 必须与 appid/clientver 配套！
                //   普通版(1005/20489) → "OIlwieks28dk2k092lksi2UIkp"
                //   Lite版(3116/11440)  → "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
                String sigSalt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";  // Lite salt
                List<String> keys = new ArrayList<>(params.keySet());
                Collections.sort(keys);
                StringBuilder paramsString = new StringBuilder();
                for (String k : keys) {
                    paramsString.append(k).append("=").append(params.get(k));
                }
                // MD5(salt + sorted_params + data + salt), data="" for GET
                String signature = CryptoUtils.md5(sigSalt + paramsString.toString() + "" + sigSalt);
                params.put("signature", signature);

                // Headers（完全对齐 KuGou request.js 第41行）
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("dfid", dfid);
                headers.put("mid", mid);
                headers.put("clienttime", String.valueOf(cltime));
                headers.put("kg-rc", "1");
                headers.put("kg-thash", "5d816a0");
                headers.put("kg-rec", "1");
                headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

                // ⚠️ 关键：完整 Cookie header（对齐 fetchAuthenticatedSongUrl 的做法）
                // 缺少 Cookie 会导致 error_code:20017 "params invalid"
                StringBuilder cookieSb = new StringBuilder();
                // KUGOU_API_PLATFORM=lite — 标识概念版API（支持特殊渠道VIP）
                cookieSb.append("KUGOU_API_PLATFORM=lite; ");
                if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                    cookieSb.append("token=").append(KuGouConfig.token).append("; ");
                }
                if (userid != null && !userid.isEmpty() && !"0".equals(userid)) {
                    cookieSb.append("userid=").append(userid).append("; ");
                }
                if (KuGouConfig.vipType != null && !KuGouConfig.vipType.isEmpty()) {
                    cookieSb.append("vip_type=").append(KuGouConfig.vipType).append("; ");
                }
                if (KuGouConfig.vipToken != null && !KuGouConfig.vipToken.isEmpty()) {
                    cookieSb.append("vip_token=").append(KuGouConfig.vipToken).append("; ");
                }
                // 所有其他 cookies
                for (var entry : KuGouConfig.cookies.entrySet()) {
                    cookieSb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
                }
                String cookieStr = cookieSb.toString().trim();
                if (!cookieStr.isEmpty()) {
                    headers.put("Cookie", cookieStr);
                }

                HttpUtils.HttpResponse response = HttpUtils.get(
                        "https://kugouvip.kugou.com/v1/get_union_vip", headers, params);

                String body = response.body != null ? response.body : "";

                if (response.isOk() && !body.isEmpty()) {
                    return body;
                }
                return "{\"status\":0,\"errmsg\":\"HTTP " + response.statusCode + "\"}";
            } catch (Exception e) {
                KuGouLogger.error("[NetMusicKuGou] getVipInfo failed: {}", e.getMessage(), e);
                return "{\"status\":0,\"errmsg\":\"" + e.getMessage() + "\"}";
            }
        });
    }

    // ==================== 服务器时间 ====================

    /**
     * 获取酷狗服务器的当前时间戳（毫秒）。
     * <p>
     * 对应 KuGou server/module/server_now.js。
     * 该接口支持 GET 方式（与 getVipInfo 同样的签名套路），
     * 无需登录、未登录也可调用（用于在领取前确认 server 时区日期）。
     *
     * @return 成功返回毫秒时间戳；失败返回 -1
     */
    public static CompletableFuture<Long> getServerNow() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String dfid = KuGouConfig.dfid;
                if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) {
                    dfid = "-";
                }
                String kugouApiMid = KuGouConfig.cookies.get("KUGOU_API_MID");
                String mid = (kugouApiMid != null && !kugouApiMid.isEmpty())
                        ? kugouApiMid
                        : (KuGouConfig.mid != null ? KuGouConfig.mid : "");
                String userid = KuGouConfig.userid != null ? KuGouConfig.userid : "0";

                int cltime = (int) (System.currentTimeMillis() / 1000);

                // 使用 Lite 版凭证（appid=3116），因为同 /usercenter.kugou.com 下也走 lite
                String appid = "3116";
                int clientver = 11440;
                String sigSalt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";

                // 构建 query 参数（与 getVipInfo 同样的 key 集合）
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("dfid", dfid);
                params.put("mid", mid);
                params.put("uuid", "-");
                params.put("appid", appid);
                params.put("clientver", clientver);
                params.put("clienttime", String.valueOf(cltime));
                params.put("plat", "3");  // 3=PC，server_now.js 用 plat=3
                if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                    params.put("token", KuGouConfig.token);
                }
                if (!"0".equals(userid) && !userid.isEmpty()) {
                    params.put("userid", userid);
                }

                // Android 签名（GET：data 段为空）
                List<String> keys = new ArrayList<>(params.keySet());
                Collections.sort(keys);
                StringBuilder paramsString = new StringBuilder();
                for (String k : keys) {
                    paramsString.append(k).append("=").append(params.get(k));
                }
                String signature = CryptoUtils.md5(sigSalt + paramsString.toString() + "" + sigSalt);
                params.put("signature", signature);

                // Headers
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("dfid", dfid);
                headers.put("mid", mid);
                headers.put("clienttime", String.valueOf(cltime));
                headers.put("x-router", "usercenter.kugou.com");
                headers.put("kg-rc", "1");
                headers.put("kg-thash", "5d816a0");
                headers.put("kg-rec", "1");
                headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

                // Cookie（如有登录态）
                StringBuilder cookieSb = new StringBuilder();
                cookieSb.append("KUGOU_API_PLATFORM=lite; ");
                if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                    cookieSb.append("token=").append(KuGouConfig.token).append("; ");
                }
                if (userid != null && !userid.isEmpty() && !"0".equals(userid)) {
                    cookieSb.append("userid=").append(userid).append("; ");
                }
                for (var entry : KuGouConfig.cookies.entrySet()) {
                    cookieSb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
                }
                String cookieStr = cookieSb.toString().trim();
                if (!cookieStr.isEmpty()) {
                    headers.put("Cookie", cookieStr);
                }

                HttpUtils.HttpResponse response = HttpUtils.get(
                        "https://usercenter.kugou.com/v1/server_now", headers, params);

                String body = response.body != null ? response.body.trim() : "";

                if (!response.isOk() || body.isEmpty()) {
                    return -1L;
                }
                // 响应通常是 {"status":1,"data":1717353600000,"error_code":0}
                // 也可能直接是数字 1717353600000
                // 也可能 {"data":null,"status":0,"error_code":20008} 表示调用失败
                try {
                    if (body.startsWith("{")) {
                        JsonObject root = GSON.fromJson(body, JsonObject.class);
                        if (root == null) return -1L;
                        // status != 1 表示 server 报错，直接返回 -1
                        if (root.has("status") && root.get("status").getAsInt() != 1) {
                            return -1L;
                        }
                        if (root.has("data") && !root.get("data").isJsonNull()) {
                            JsonElement dataEl = root.get("data");
                            if (dataEl.isJsonPrimitive() && dataEl.getAsJsonPrimitive().isNumber()) {
                                return dataEl.getAsLong();
                            }
                            // 兜底：data 可能是字符串形式的数字
                            return Long.parseLong(dataEl.getAsString().replaceAll("[^0-9]", ""));
                        }
                    } else {
                        return Long.parseLong(body.replaceAll("[^0-9]", ""));
                    }
                } catch (NumberFormatException | IllegalStateException e) {
                    KuGouLogger.warn("[NetMusicKuGou] Failed to parse server_now body: {}", body);
                }
                return -1L;
            } catch (Exception e) {
                KuGouLogger.error("[NetMusicKuGou] getServerNow failed: {}", e.getMessage(), e);
                return -1L;
            }
        });
    }

    /**
     * 解析 VIP 信息为可读文本
     */
    public static String parseVipStatus(String jsonStr) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return "解析失败";
            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            if (status != 1) {
                String err = root.has("errmsg") ? root.get("errmsg").getAsString() :
                             root.has("error_code") ? "错误码:" + root.get("error_code").getAsString() : "未知错误(status=" + status + ")";
                return "查询失败: " + err;
            }

            if (!root.has("data")) return "无VIP数据";

            JsonObject data = root.getAsJsonObject("data");
            StringBuilder sb = new StringBuilder();
            sb.append("\n========== 酷狗VIP状态 ==========\n");

            // ⚠️ 实际API响应中，VIP信息在 data.busi_vip[] 数组中
            // 每项包含: product_type(svip/tvip), is_vip, vip_end_time, vip_begin_time, vip_clearday
            boolean foundSvip = false, foundTvip = false;
            boolean hasActiveSvip = false, hasActiveTvip = false;

            if (data.has("busi_vip") && data.get("busi_vip").isJsonArray()) {
                JsonArray busiVip = data.getAsJsonArray("busi_vip");
                for (JsonElement el : busiVip) {
                    if (!el.isJsonObject()) continue;
                    JsonObject item = el.getAsJsonObject();
                    String productType = item.has("product_type") ? item.get("product_type").getAsString() : "";
                    int isVip = item.has("is_vip") ? item.get("is_vip").getAsInt() : 0;

                    if ("svip".equals(productType)) {
                        foundSvip = true;
                        if (isVip == 1) {
                            hasActiveSvip = true;
                            sb.append("[概念会员 SVIP] ✓ 已开通\n");
                            if (item.has("vip_begin_time"))
                                sb.append("  开通时间: ").append(item.get("vip_begin_time").getAsString()).append("\n");
                            if (item.has("vip_end_time"))
                                sb.append("  到期时间: ").append(item.get("vip_end_time").getAsString()).append("\n");
                            if (item.has("vip_clearday"))
                                sb.append("  结算日期: ").append(item.get("vip_clearday").getAsString()).append("\n");
                            if (item.has("vip_limit_quota") && item.get("vip_limit_quota").isJsonObject()) {
                                JsonObject quota = item.getAsJsonObject("vip_limit_quota");
                                if (quota.has("total"))
                                    sb.append("  下载数量: ").append(quota.get("total").getAsInt()).append(" 首\n");
                            }
                        } else {
                            // 区分「从未开通」和「已过期」
                            String endTime = item.has("vip_end_time") ? item.get("vip_end_time").getAsString() : "";
                            if (!endTime.isEmpty()) {
                                sb.append("[概念会员 SVIP] ✗ 已过期 (到期: ").append(endTime).append(")\n");
                            } else {
                                sb.append("[概念会员 SVIP] ✗ 未开通\n");
                            }
                        }
                    } else if ("tvip".equals(productType)) {
                        foundTvip = true;
                        if (isVip == 1) {
                            hasActiveTvip = true;
                            sb.append("[畅听会员 TVIP] ✓ 已开通\n");
                            if (item.has("vip_begin_time"))
                                sb.append("  开通时间: ").append(item.get("vip_begin_time").getAsString()).append("\n");
                            if (item.has("vip_end_time"))
                                sb.append("  到期时间: ").append(item.get("vip_end_time").getAsString()).append("\n");
                            if (item.has("vip_clearday"))
                                sb.append("  结算日期: ").append(item.get("vip_clearday").getAsString()).append("\n");
                        } else {
                            // 区分「从未开通」和「已过期」
                            String endTime = item.has("vip_end_time") ? item.get("vip_end_time").getAsString() : "";
                            if (!endTime.isEmpty()) {
                                sb.append("[畅听会员 TVIP] ✗ 已过期 (到期: ").append(endTime).append(")\n");
                            } else {
                                sb.append("[畅听会员 TVIP] ✗ 未开通\n");
                            }
                        }
                    }
                }
            }

            if (!foundSvip) sb.append("[概念会员 SVIP] - 无信息\n");
            if (!foundTvip) sb.append("[畅听会员 TVIP] - 无信息\n");

            // 账号基本信息
            sb.append("----------------------------------\n");
            sb.append("账号ID: ").append(data.has("userid") ? data.get("userid").getAsString() : KuGouConfig.userid).append("\n");

            boolean hasAnyVip = hasActiveSvip || hasActiveTvip;
            if (!hasAnyVip) {
                sb.append("\n⚠ 当前VIP已过期或未开通，无法播放付费歌曲！");
                // 显示最近一次领取操作的结果
                String vipMsg = KuGouVipApi.lastVipResultMessage;
                if (vipMsg != null && !vipMsg.isEmpty()) {
                    sb.append("\n  📋 ").append(vipMsg);
                } else {
                    sb.append("\n  概念版每日免费VIP，重启游戏后自动续领（每日限额）。");
                }
            } else {
                sb.append("\n✓ VIP状态正常，可播放付费歌曲");
            }

            sb.append("\n==================================");
            return sb.toString();
        } catch (Exception e) {
            return "VIP信息解析异常: " + e.getMessage();
        }
    }

    private static String parseSongUrl(String jsonStr) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return "";
            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            if (status != 1) return "";

            if (root.has("url") && !root.get("url").isJsonNull()) {
                JsonElement urlEl = root.get("url");
                if (urlEl.isJsonPrimitive()) {
                    String url = urlEl.getAsString();
                    if (!url.isEmpty()) return url;
                }
            }

            // 备选 backup_url
            if (root.has("backup_url") && !root.get("backup_url").isJsonNull()) {
                JsonElement backupEl = root.get("backup_url");
                if (backupEl.isJsonPrimitive()) {
                    String url = backupEl.getAsString();
                    if (!url.isEmpty()) return url;
                }
            }
            return "";
        } catch (JsonSyntaxException e) {
            KuGouLogger.error("[NetMusicKuGou] Parse song URL failed: {}", e.getMessage());
            return "";
        }
    }

    // ==================== 歌词 ====================

    /**
     * 搜索结果中的歌曲信息。
     * <p>
     * 来自 {@link #search} 返回的 mobilecdn.kugou.com 解析结果。
     * mobilecdn 公开 API 不返回 id，所以 {@code id == hash}。
     */
    public static final class Song {
        public final String id;
        public final String name;
        public final String singer;
        public final String album;
        public final String hash;
        public final String albumId;
        public final int duration;

        public Song(String id, String name, String singer, String album, String hash, String albumId, int duration) {
            this.id = id;
            this.name = name;
            this.singer = singer;
            this.album = album;
            this.hash = hash;
            this.albumId = albumId;
            this.duration = duration;
        }
    }

    /**
     * 歌词搜索候选
     * <p>
     * 对应 KuGou server/module/search_lyric.js 返回的 candidates[0]。
     * KuGou 在 search_lyric.js 中给出 id + accesskey，再去 /download 拿真正的内容。
     */
    public static final class LyricCandidate {
        public final String id;
        public final String accessKey;
        public final String singer;
        public final String songName;
        public final int score;

        public LyricCandidate(String id, String accessKey, String singer, String songName, int score) {
            this.id = id;
            this.accessKey = accessKey;
            this.singer = singer;
            this.songName = songName;
            this.score = score;
        }
    }

    /**
     * 歌词下载结果
     * <p>
     * lyricContent 已经是 LRC 文本（KRC fmt 会被自动解码为 LRC）。
     * format 透传 fmt（"lrc" / "krc" / ...）。
     * <p>
     * languageJson 是酷狗 language 字段 base64 解码后的 JSON 字符串，含 type=0 原音/type=1 中文翻译。
     * 无翻译时为 null。
     */
    public static final class LyricContent {
        public final String lyricContent;
        public final String format;
        public final String languageJson;

        public LyricContent(String lyricContent, String format) {
            this(lyricContent, format, null);
        }

        public LyricContent(String lyricContent, String format, String languageJson) {
            this.lyricContent = lyricContent;
            this.format = format;
            this.languageJson = languageJson;
        }
    }

    /**
     * 搜索歌词候选。
     * <p>
     * 对应 KuGou server/module/search_lyric.js：访问 {@code https://lyrics.kugou.com/v1/search}，
     * 该接口<strong>不使用</strong>默认签名、<strong>不携带</strong>默认参数（dfid/mid/appid...）。
     * 仅需 {@code hash + keyword (+ lrctxt)}。返回 200 时取 score 最高的一条候选。
     *
     * @param hash     酷狗 hash（歌曲级，{@code getSongUrl} 用的同一个）
     * @param keyword  搜索关键字（一般用 "歌手 - 歌名"）
     * @param duration 时长（毫秒），用于 KuGou 匹配更准的歌词，传 0 跳过
     * @return 最佳候选；找不到时返回 null
     */
    public static CompletableFuture<LyricCandidate> searchLyric(String hash, String keyword, int duration) {
        return CompletableFuture.supplyAsync(() -> {
            if (hash == null || hash.isEmpty() || keyword == null || keyword.isEmpty()) {
                return null;
            }
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("hash", hash.toLowerCase());
                params.put("keyword", keyword);
                params.put("lrctxt", 1);
                // 注：duration 不是 KuGou 必传参数，省略可避免与少数短音频长度不一致时被过滤
                if (duration > 0) {
                    params.put("duration", duration);
                }

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36");
                headers.put("Accept", "application/json, text/plain, */*");

                // 注意：使用 http://lyrics.kugou.com/search（非 /v1/search，后者已失效返回空）
                HttpUtils.HttpResponse response = HttpUtils.get(
                        "http://lyrics.kugou.com/search", headers, params);

                if (!response.isOk() || response.body == null || response.body.isEmpty()) {
                    return null;
                }
                return parseSearchLyricResult(response.body);
            } catch (Exception e) {
                KuGouLogger.error("[NetMusicKuGou] search_lyric failed: {}", e.getMessage(), e);
                return null;
            }
        });
    }

    private static LyricCandidate parseSearchLyricResult(String jsonStr) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return null;
            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            // /search 接口成功返回 200，/v1/search 返回 1（已失效）
            if (status != 200 && status != 1) return null;

            JsonArray candidates = root.has("candidates") ? root.getAsJsonArray("candidates") : null;
            if (candidates == null || candidates.isEmpty()) return null;

            // 取 score 最高的一项
            LyricCandidate best = null;
            for (JsonElement elem : candidates) {
                if (!elem.isJsonObject()) continue;
                JsonObject item = elem.getAsJsonObject();
                String id = getStr(item, "id");
                String accessKey = getStr(item, "accesskey");
                if (id.isEmpty() || accessKey.isEmpty()) continue;
                LyricCandidate c = new LyricCandidate(
                        id,
                        accessKey,
                        getStr(item, "singer"),
                        getStr(item, "song"),
                        item.has("score") ? item.get("score").getAsInt() : 0);
                if (best == null || c.score > best.score) {
                    best = c;
                }
            }
            return best;
        } catch (JsonSyntaxException e) {
            KuGouLogger.warn("[NetMusicKuGou] parse search_lyric failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 下载歌词正文。
     * <p>
     * 对应 KuGou server/module/lyric.js：访问 {@code https://lyrics.kugou.com/download}，
     * 该接口<strong>使用</strong>完整 Android 签名（与 /v5/url 一样带 dfid/mid/appid/... + signature）。
     * 服务端返回 JSON 里的 {@code content} 字段是 base64 编码的歌词（fmt=krc 时是 KRC 二进制，
     * fmt=lrc 时是 LRC 文本）。
     *
     * @param id         searchLyric 返回的 id
     * @param accessKey  searchLyric 返回的 accesskey
     * @param fmt        格式：{@code "lrc"} 或 {@code "krc"}，KRC 会被自动解码为 LRC 文本
     * @return 歌词内容（已解码的 LRC 文本 + 原始 fmt）；失败时返回 null
     */
    public static CompletableFuture<LyricContent> getLyric(String id, String accessKey, String fmt) {
        return CompletableFuture.supplyAsync(() -> {
            if (id == null || id.isEmpty() || accessKey == null || accessKey.isEmpty()) {
                return null;
            }
            try {
                String dfid = KuGouConfig.dfid;
                if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) {
                    dfid = "-";
                }
                String kugouApiMid = KuGouConfig.cookies.get("KUGOU_API_MID");
                String mid = (kugouApiMid != null && !kugouApiMid.isEmpty())
                        ? kugouApiMid
                        : (KuGouConfig.mid != null ? KuGouConfig.mid : "");
                String userid = KuGouConfig.userid != null ? KuGouConfig.userid : "0";
                int cltime = (int) (System.currentTimeMillis() / 1000);

                // 概念版 Lite 凭证
                String appid = "3116";
                int clientver = 11440;

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("ver", 1);
                params.put("client", "android");
                params.put("id", id);
                params.put("accesskey", accessKey);
                params.put("fmt", fmt != null ? fmt : "lrc");
                // 默认参数（与 /v5/url 一致）
                params.put("dfid", dfid);
                params.put("mid", mid);
                params.put("uuid", "-");
                params.put("appid", appid);
                params.put("clientver", clientver);
                params.put("clienttime", String.valueOf(cltime));
                if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                    params.put("token", KuGouConfig.token);
                }
                if (!"0".equals(userid) && !userid.isEmpty()) {
                    params.put("userid", userid);
                }

                // Android 签名（GET，data 段为空）
                String sigSalt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
                String signature = KuGouSignature.signatureAndroidParams(params, "");
                params.put("signature", signature);

                // Headers
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-1078-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("dfid", dfid);
                headers.put("mid", mid);
                headers.put("clienttime", String.valueOf(cltime));
                headers.put("kg-rc", "1");
                headers.put("kg-thash", "5d816a0");
                headers.put("kg-rec", "1");
                headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

                // Cookie（与 VIP 查询保持一致）
                StringBuilder cookieSb = new StringBuilder();
                cookieSb.append("KUGOU_API_PLATFORM=lite; ");
                if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                    cookieSb.append("token=").append(KuGouConfig.token).append("; ");
                }
                if (!"0".equals(userid) && !userid.isEmpty()) {
                    cookieSb.append("userid=").append(userid).append("; ");
                }
                if (KuGouConfig.vipType != null && !KuGouConfig.vipType.isEmpty()) {
                    cookieSb.append("vip_type=").append(KuGouConfig.vipType).append("; ");
                }
                if (KuGouConfig.vipToken != null && !KuGouConfig.vipToken.isEmpty()) {
                    cookieSb.append("vip_token=").append(KuGouConfig.vipToken).append("; ");
                }
                for (var entry : KuGouConfig.cookies.entrySet()) {
                    cookieSb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
                }
                String cookieStr = cookieSb.toString().trim();
                if (!cookieStr.isEmpty()) {
                    headers.put("Cookie", cookieStr);
                }

                HttpUtils.HttpResponse response = HttpUtils.get(
                        "https://lyrics.kugou.com/download", headers, params);

                if (!response.isOk() || response.body == null || response.body.isEmpty()) {
                    return null;
                }
                return parseLyricDownloadResponse(response.body, fmt != null ? fmt : "lrc");
            } catch (Exception e) {
                KuGouLogger.error("[NetMusicKuGou] getLyric failed: {}", e.getMessage(), e);
                return null;
            }
        });
    }

    private static LyricContent parseLyricDownloadResponse(String jsonStr, String requestedFmt) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return null;
            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            // /download 接口成功返回 200
            if (status != 200 && status != 1) return null;

            String content = null;
            String fmt = requestedFmt;
            String languageB64 = null;

            // KuGou 优先读取 info.content（标准化）字段
            if (root.has("info") && root.get("info").isJsonObject()) {
                JsonObject info = root.getAsJsonObject("info");
                content = getStr(info, "content");
                fmt = getStr(info, "fmt");
                if (fmt.isEmpty()) {
                    fmt = requestedFmt;
                }
                // 酷狗翻译（音译/中文翻译）存在 info.language 字段（base64 编码的 JSON）
                languageB64 = getStr(info, "language");
            }
            if (content == null || content.isEmpty()) {
                content = getStr(root, "content");
                fmt = getStr(root, "fmt");
                if (fmt.isEmpty()) {
                    fmt = requestedFmt;
                }
                languageB64 = getStr(root, "language");
            }
            if (content == null || content.isEmpty()) {
                return null;
            }

            // fmt=krc：解码二进制为 LRC 文本（解码失败时 KrcDecoder 返回 null）
            // fmt=lrc 或其它：直接 base64 解码为字符串
            String lyricText;
            String languageJson = null;
            if ("krc".equalsIgnoreCase(fmt)) {
                lyricText = com.github.tartaricacid.netmusic.kugou.lyric.KrcDecoder.decodeToLrc(content);
                // KuGou 路径：KRC 解码后的 stripped text 里 [language:base64] 行
                // 是翻译字段（不是 /lyrics/download 响应的 top-level language 字段！）
                if (lyricText != null) {
                    int langStart = lyricText.indexOf("[language:");
                    if (langStart >= 0) {
                        int langEnd = lyricText.indexOf(']', langStart);
                        if (langEnd > langStart) {
                            String langLine = lyricText.substring(langStart, langEnd + 1);
                            String base64 = langLine.substring("[language:".length(), langLine.length() - 1);
                            try {
                                byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64);
                                languageJson = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
                            } catch (IllegalArgumentException ex) {
                                KuGouLogger.warn(
                                        "[NetMusicKuGou] [language:] line base64 decode failed: {}",
                                        ex.getMessage());
                            }
                        }
                    }
                }
            } else {
                try {
                    lyricText = new String(
                            java.util.Base64.getDecoder().decode(content),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    // 不是 base64 的话，按原始字符串返回
                    lyricText = content;
                }
            }
            if (lyricText == null || lyricText.isEmpty()) {
                // KRC 解码失败时即使 language 字段有也丢弃（不是合法 KRC 歌词）
                return null;
            }

            // 翻译：languageB64 解码 → JSON 文本，传给 LyricConverter 解析
            // (注：fmt=krc 路径下 languageJson 已从 stripped text 的 [language:] 行抽出)
            if (languageJson == null && languageB64 != null && !languageB64.isEmpty()) {
                try {
                    byte[] decoded = java.util.Base64.getDecoder().decode(languageB64);
                    languageJson = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                    if (languageJson.isEmpty()) {
                        languageJson = null;
                    }
                } catch (IllegalArgumentException e) {
                    // 不是 base64
                    KuGouLogger.warn(
                            "[NetMusicKuGou] language not base64, treating as raw JSON: {}",
                            languageB64.substring(0, Math.min(200, languageB64.length())));
                    languageJson = languageB64;
                }
            } else {
                KuGouLogger.info(
                        "[NetMusicKuGou] /lyrics/download: NO language field in response");
            }
            return new LyricContent(lyricText, fmt, languageJson);
        } catch (JsonSyntaxException e) {
            KuGouLogger.warn("[NetMusicKuGou] parse lyric download failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    private static String getStr(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonPrimitive()) return elem.getAsString();
        }
        return "";
    }
}
