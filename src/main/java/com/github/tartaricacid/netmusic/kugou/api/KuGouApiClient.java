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
     * 搜索歌曲。
     * <p>
     * 优先用 complexsearch.kugou.com(带 android 签名),失败时自动回退到 mobilecdn 公开 API。
     * 无论走哪个分支,都保证不返回 null,并输出诊断日志。
     */
    public static CompletableFuture<List<Song>> search(String keyword, int page, int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            List<Song> result = Collections.emptyList();
            try {
                KuGouLogger.info("[NetMusicKuGou] Search keyword='{}', page={}, pagesize={}", keyword, page, pageSize);
                String dfid = KuGouConfig.dfid;
                boolean hasDevice = (dfid != null && !dfid.isEmpty() && !"-".equals(dfid));
                if (hasDevice) {
                    result = searchViaComplexSearch(keyword, page, pageSize);
                }
                if (result.isEmpty()) {
                    KuGouLogger.info(
                            "[NetMusicKuGou] Complex search empty (dfid={}), fallback to mobilecdn",
                            hasDevice ? dfid : "none");
                    result = searchViaMobileCdn(keyword, page, pageSize);
                }
                KuGouLogger.info("[NetMusicKuGou] Search final result count: {}", result.size());
                return result;
            } catch (Throwable t) {
                KuGouLogger.error("[NetMusicKuGou] Search outer exception: {}", t.toString(), t);
                // 任何异常都最后再试一次 mobilecdn(不抛异常)
                try {
                    result = searchViaMobileCdn(keyword, page, pageSize);
                    KuGouLogger.info("[NetMusicKuGou] After exception fallback result: {}", result.size());
                } catch (Exception ignored) {
                }
                return result;
            }
        });
    }

    /**
     * complexsearch 搜索(带 android 签名,可搜到无版权歌曲元数据)。
     * 失败返回空列表,让外层自动回退 mobilecdn。
     */
    private static List<Song> searchViaComplexSearch(String keyword, int page, int pageSize) {
        try {
            int cltime = (int) (System.currentTimeMillis() / 1000);
            String mid = KuGouConfig.mid != null ? KuGouConfig.mid : "";
            String userid = KuGouConfig.userid != null ? KuGouConfig.userid : "0";
            String appid = "3116";
            int clientver = 11440;

            // 业务参数(对齐 EchoMusic search.js)
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("albumhide", 0);
            params.put("iscorrection", 1);
            params.put("keyword", keyword);
            params.put("nocollect", 0);
            params.put("page", page);
            params.put("pagesize", pageSize);
            params.put("platform", "AndroidFilter");

            // 默认认证参数(对齐 EchoMusic request.js defaultParams)
            params.put("dfid", KuGouConfig.dfid);
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

            // Android 签名(Lite salt)
            params.put("signature", KuGouSignature.signatureAndroidParams(params, ""));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
            headers.put("x-router", "complexsearch.kugou.com");
            headers.put("dfid", KuGouConfig.dfid);
            headers.put("mid", mid);
            headers.put("clienttime", String.valueOf(cltime));
            headers.put("kg-rc", "1");
            headers.put("kg-thash", "5d816a0");
            headers.put("kg-rec", "1");
            headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

            // Cookie(如有登录态)
            StringBuilder cookieSb = new StringBuilder();
            if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                cookieSb.append("token=").append(KuGouConfig.token).append("; ");
            }
            if (!"0".equals(userid) && !userid.isEmpty()) {
                cookieSb.append("userid=").append(userid).append("; ");
            }
            for (var entry : KuGouConfig.cookies.entrySet()) {
                cookieSb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
            }
            String cookieStr = cookieSb.toString().trim();
            if (!cookieStr.isEmpty()) {
                headers.put("Cookie", cookieStr);
            }

            KuGouLogger.info(
                    "[NetMusicKuGou] ComplexSearch requesting: https://gateway.kugou.com/v3/search/song, params keys={}",
                    params.keySet());
            HttpUtils.HttpResponse response = HttpUtils.get(
                    "https://gateway.kugou.com/v3/search/song", headers, params);
            KuGouLogger.info(
                    "[NetMusicKuGou] ComplexSearch HTTP {} bodyLen={}",
                    response.statusCode, response.body == null ? 0 : response.body.length());

            if (!response.isOk() || response.body == null || response.body.isEmpty()) {
                KuGouLogger.warn("[NetMusicKuGou] ComplexSearch HTTP {} or empty body", response.statusCode);
                return Collections.emptyList();
            }

            // 诊断:输出前 500 字符响应,方便定位错误码与格式
            String bodyPreview = response.body.length() > 500
                    ? response.body.substring(0, 500) + "..."
                    : response.body;
            KuGouLogger.info("[NetMusicKuGou] ComplexSearch body preview: {}", bodyPreview);

            List<Song> result = parseSearchResult(response.body);
            if (result.isEmpty()) {
                // 尝试解析 error_code/errmsg
                try {
                    JsonObject root = GSON.fromJson(response.body, JsonObject.class);
                    if (root != null) {
                        int errcode = root.has("error_code") ? root.get("error_code").getAsInt() :
                                (root.has("err_code") ? root.get("err_code").getAsInt() : -1);
                        String errmsg = getStr(root, "errmsg");
                        String status = root.has("status") ? String.valueOf(root.get("status").getAsInt()) : "N/A";
                        KuGouLogger.warn(
                                "[NetMusicKuGou] ComplexSearch empty parse, status={}, error_code={}, errmsg={}",
                                status, errcode, errmsg);
                    }
                } catch (Exception pe) {
                    KuGouLogger.warn("[NetMusicKuGou] ComplexSearch parse error-code failed: {}", pe.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            KuGouLogger.warn("[NetMusicKuGou] ComplexSearch exception: {}", e.toString(), e);
            return Collections.emptyList();
        }
    }

    /**
     * mobilecdn 公开 API 搜索(无需签名,仅免费歌曲)作为兜底
     */
    private static List<Song> searchViaMobileCdn(String keyword, int page, int pageSize) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("format", "json");
            params.put("keyword", keyword);
            params.put("page", page);
            params.put("pagesize", pageSize);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            HttpUtils.HttpResponse response = HttpUtils.get(
                    "http://mobilecdn.kugou.com/api/v3/search/song", headers, params);

            if (!response.isOk() || response.body == null || response.body.isEmpty()) {
                KuGouLogger.warn("[NetMusicKuGou] mobilecdn HTTP {} or empty", response.statusCode);
                return Collections.emptyList();
            }
            return parseSearchResult(response.body);
        } catch (Exception e) {
            KuGouLogger.warn("[NetMusicKuGou] mobilecdn exception: {}", e.toString(), e);
            return Collections.emptyList();
        }
    }

    private static List<Song> parseSearchResult(String jsonStr) {
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return Collections.emptyList();

            // complexsearch: error_code=0 成功; mobilecdn: status=1 成功
            boolean success = false;
            if (root.has("error_code")) {
                int err = root.get("error_code").getAsInt();
                if (err == 0) success = true;
            }
            if (!success && root.has("status")) {
                int status = root.get("status").getAsInt();
                if (status == 1) success = true;
            }
            if (!success) {
                KuGouLogger.warn("[NetMusicKuGou] Search result not success, root keys={}", root.keySet());
                return Collections.emptyList();
            }

            // data 字段可能是对象或直接有结果数组
            JsonArray results = null;
            JsonObject dataObj = null;
            if (root.has("data") && root.get("data").isJsonObject()) {
                dataObj = root.getAsJsonObject("data");
                if (dataObj.has("info") && dataObj.get("info").isJsonArray()) {
                    results = dataObj.getAsJsonArray("info");
                } else if (dataObj.has("lists") && dataObj.get("lists").isJsonArray()) {
                    results = dataObj.getAsJsonArray("lists");
                }
            }
            // complexsearch 某些分支结果在 data.all_songs 或 data.song
            if (results == null && dataObj != null) {
                if (dataObj.has("all_songs") && dataObj.get("all_songs").isJsonArray()) {
                    results = dataObj.getAsJsonArray("all_songs");
                } else if (dataObj.has("song") && dataObj.get("song").isJsonArray()) {
                    results = dataObj.getAsJsonArray("song");
                }
            }
            // 极端兜底:根级 lists/info
            if (results == null) {
                if (root.has("lists") && root.get("lists").isJsonArray()) {
                    results = root.getAsJsonArray("lists");
                } else if (root.has("info") && root.get("info").isJsonArray()) {
                    results = root.getAsJsonArray("info");
                }
            }
            if (results == null || results.isEmpty()) {
                KuGouLogger.warn("[NetMusicKuGou] Search result no songs array, dataObj keys={}",
                        dataObj != null ? dataObj.keySet() : "N/A");
                return Collections.emptyList();
            }

            List<Song> songs = new ArrayList<>();
            for (JsonElement elem : results) {
                if (!elem.isJsonObject()) continue;
                JsonObject item = elem.getAsJsonObject();
                // complexsearch 用 FileName / FileHash / SingerName / AlbumID / AlbumName 驼峰
                // mobilecdn 用 songname / hash / singername / album_id / album_name 小写下划线
                String hash = firstNonEmpty(
                        getStr(item, "FileHash"),
                        getStr(item, "hash"),
                        getStr(item, "Hash"),
                        getStr(item, "file_hash"));
                String id = firstNonEmpty(
                        getStr(item, "MixSongID"),
                        getStr(item, "id"),
                        getStr(item, "ID"),
                        getStr(item, "song_id"));
                if (id.isEmpty()) id = hash;
                String name = firstNonEmpty(
                        getStr(item, "OriSongName"),
                        getStr(item, "songname"),
                        getStr(item, "SongName"),
                        getStr(item, "song_name"),
                        getStr(item, "FileName"),
                        getStr(item, "filename"),
                        getStr(item, "audio_name"));
                // 若 name 还是空、FileName 是"歌手 - 歌名"格式,尝试提取
                if (name.isEmpty()) {
                    String fileName = getStr(item, "FileName");
                    int sep = fileName.indexOf(" - ");
                    if (sep > 0) name = fileName.substring(sep + 3).trim();
                    else if (!fileName.isEmpty()) name = fileName;
                }
                String singer = firstNonEmpty(
                        getStr(item, "singername"),
                        getStr(item, "SingerName"),
                        getStr(item, "singer_name"),
                        getStr(item, "author_name"),
                        getStr(item, "artist_name"));
                // 若 singer 空、FileName 是"歌手 - 歌名"格式,尝试提取
                if (singer.isEmpty()) {
                    String fileName = getStr(item, "FileName");
                    int sep = fileName.indexOf(" - ");
                    if (sep > 0) singer = fileName.substring(0, sep).trim();
                }
                String album = firstNonEmpty(
                        getStr(item, "album_name"),
                        getStr(item, "AlbumName"),
                        getStr(item, "album"));
                String albumId = firstNonEmpty(
                        getStr(item, "AlbumID"),
                        getStr(item, "album_id"),
                        getStr(item, "album_audio_id"));
                int duration = parseDuration(item);

                if (!name.isEmpty() || !hash.isEmpty()) {
                    songs.add(new Song(id, name, singer, album, hash, albumId, duration));
                }
            }
            KuGouLogger.info("[NetMusicKuGou] parseSearchResult parsed {} songs", songs.size());
            return songs;
        } catch (Exception e) {
            KuGouLogger.warn("[NetMusicKuGou] Parse search result failed: {}", e.toString(), e);
            return Collections.emptyList();
        }
    }

    /** 返回第一个非空字符串 */
    private static String firstNonEmpty(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isEmpty()) return s;
        }
        return "";
    }

    /** 解析时长字段(duration / timelength / timeLength / Duration),单位秒 */
    private static int parseDuration(JsonObject item) {
        String[] fields = {"duration", "timelength", "timeLength", "Duration", "time"};
        for (String f : fields) {
            if (item.has(f) && !item.get(f).isJsonNull()) {
                try {
                    int v = item.get(f).getAsInt();
                    // timelength 某些字段是毫秒, mobilecdn 的 duration 是秒,这里统一到秒
                    // 简单启发:大于 100000 视为毫秒
                    if (v > 100000) v = Math.round(v / 1000f);
                    return v;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    // ==================== 获取歌曲 URL ====================

    /**
     * 音质等级表（从低到高）。对齐 EchoMusic song.ts AUDIO_QUALITY_ORDER:
     * ['128','320','flac','high','super']
     */
    private static final AudioQuality[] QUALITY_ORDER_ASC = new AudioQuality[]{
            AudioQuality.STANDARD,    // 0 "128"
            AudioQuality.HQ,          // 1 "320"
            AudioQuality.SQ_FLAC,     // 2 "flac"
            AudioQuality.HIGH,        // 3 "high"
            AudioQuality.SUPER_DSD    // 4 "super"
    };

    /**
     * 给定用户请求的音质,返回 从该音质起按 高→低 降级的数组(相当于 EchoMusic slice(0,index+1).reverse)。
     * 例:quality=super → [super,high,flac,320,128]
     *    quality=high  → [high,flac,320,128]
     *    null         → [320,128]（默认从 HQ 开始,但兼容模式会把音质传成 null 用原 hash 直接兜底）
     */
    private static AudioQuality[] getQualityFallbackOrder(AudioQuality requested) {
        // 找 requested 在 ASC 表中的位置
        int idx = 1; // 默认 HQ (index=1),对应 EchoMusic 原代码 default=128,但对模组免费用户更合理的是 HQ
        if (requested != null) {
            for (int i = 0; i < QUALITY_ORDER_ASC.length; i++) {
                if (QUALITY_ORDER_ASC[i] == requested) { idx = i; break; }
            }
        }
        // 切 0..idx 并逆序 → 高→低
        AudioQuality[] asc = new AudioQuality[idx + 1];
        System.arraycopy(QUALITY_ORDER_ASC, 0, asc, 0, idx + 1);
        AudioQuality[] desc = new AudioQuality[asc.length];
        for (int i = 0; i < asc.length; i++) desc[i] = asc[asc.length - 1 - i];
        KuGouLogger.info("[NetMusicKuGou] quality ladder for {} → {}",
                requested == null ? "null" : requested.getValue(),
                java.util.Arrays.stream(desc).map(q -> q.getValue()).toList());
        return desc;
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
     * 完整流程(与 EchoMusic resolver.ts 对齐):
     *   0. POST /v2/get_res_privilege/lite 拉取 relateGoods[](含各音质独立 hash + level/quality 标记)
     *   1. 音质降级链: 对每个音质优先找 relateGoods 匹配项 → 用 matched.hash(非原始 hash)
     *        请求 v5/url, 并把 albumId/album_audio_id 一并传入。找不到匹配时用原始 hash 兜底。
     *   2. 兼容性模式 fallback: 不带 quality 直接用原始 hash 调 v5/url
     *   3. yiting → magic ppage_id → v6/priv_url 逐级兜底
     */
    public static CompletableFuture<String> getSongUrl(String hash, String albumId, AudioQuality quality) {
        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            final String lowerHash = hash.toLowerCase();
            final long albumIdL = parseAlbumId(albumId);

            // 步骤 0: 拉取 relateGoods (失败时返回空列表,不阻塞后续流程)
            KuGouLogger.info("[NetMusicKuGou] getSongUrl start: hash={}, albumId={}, quality={}",
                    lowerHash, albumId, quality == null ? "null" : quality.getValue());
            List<RelateGood> relateGoods = Collections.emptyList();
            try {
                relateGoods = fetchSongPrivilegeLite(lowerHash, albumId);
                KuGouLogger.info("[NetMusicKuGou] relateGoods count={}, items={}",
                        relateGoods.size(), relateGoods);
            } catch (Exception e) {
                KuGouLogger.warn("[NetMusicKuGou] privilege_lite failed, proceed without: {}", e.toString());
            }

            AudioQuality[] ladder = getQualityFallbackOrder(quality);

            // 步骤 1: 音质降级链 (EchoMusic resolver.ts L192-207)
            //   对 candidates 中每个音质:
            //     A. 如果 relateGoods 有匹配 → 先 [matched.hash + album_id=0](这是 EchoMusic 实际传参风格),
            //        失败再 [matched.hash + album_id=albumIdL],都失败再走 fallback。
            //     B. 没匹配 → 公开 API → [originalHash + albumIdL]
            for (int i = 0; i < ladder.length; i++) {
                AudioQuality q = ladder[i];
                boolean isLastInLadder = (i == ladder.length - 1);

                String url = "";
                RelateGood matched = matchRelateGoodForQuality(relateGoods, q);
                if (matched != null && matched.hash != null && !matched.hash.isEmpty()) {
                    KuGouLogger.info(
                            "[NetMusicKuGou] Quality {} matched relateGood: hash={}, q={}, level={} (original hash={})",
                            q.getValue(), matched.hash, matched.quality, matched.level, lowerHash);
                    // A1: EchoMusic 真实调用风格:传 relateGood hash + 对应 quality,不传 album_id(=0)
                    url = fetchAuthenticatedSongUrl(matched.hash, q, 0L);
                    // A2: 再试 albumIdL
                    if (url.isEmpty() && albumIdL != 0) {
                        url = fetchAuthenticatedSongUrl(matched.hash, q, albumIdL);
                    }
                }
                // 1a) relateGoods 匹配不到或两轮都失败, 退回到公开 API + 原 hash
                if (url.isEmpty()) {
                    url = fetchPublicSongUrl(lowerHash, q);
                }
                // 1b) 仍没有, 用原始 hash + album_id 再试一次 v5/url
                if (url.isEmpty()) {
                    url = fetchAuthenticatedSongUrl(lowerHash, q, albumIdL);
                }

                if (!url.isEmpty()) {
                    KuGouLogger.info("[NetMusicKuGou] URL resolved in {}ms via quality={}",
                            System.currentTimeMillis() - t0, q.getValue());
                    return url;
                }

                if (!isLastInLadder) {
                    KuGouLogger.info("[NetMusicKuGou] Quality {} unavailable for hash={}, falling back to {}",
                            q.getValue(), lowerHash, ladder[i + 1].getValue());
                }
            }

            // 步骤 2: 兼容模式(不带 quality,让服务端自己挑)对应 EchoMusic resolver.ts L209-224
            KuGouLogger.warn("[NetMusicKuGou] All qualities exhausted for hash={}, trying compat (no quality)", lowerHash);
            String compatUrl = fetchAuthenticatedSongUrl(lowerHash, null, albumIdL);
            if (!compatUrl.isEmpty()) return compatUrl;

            // 步骤 3: 最后兜底 v3/yiting
            KuGouLogger.warn("[NetMusicKuGou] Compat fallback empty for hash={}, trying gateway v3", lowerHash);
            String yitingUrl = fetchGatewaySongUrl(lowerHash);
            if (!yitingUrl.isEmpty()) return yitingUrl;

            // 步骤 4: 魔法 ppage_id 兜底(EchoMusic resolver.ts L226-239 getSongUrl(hash, '', 356753938))
            KuGouLogger.warn("[NetMusicKuGou] yiting empty for hash={}, trying magic ppage_id", lowerHash);
            String magicUrl = fetchMagicPpageUrl(lowerHash, albumIdL);
            if (!magicUrl.isEmpty()) {
                KuGouLogger.info("[NetMusicKuGou] URL final (magic ppage_id) in {}ms: len={}, prefix={}",
                        System.currentTimeMillis() - t0,
                        magicUrl.length(),
                        magicUrl.length() < 64 ? magicUrl : magicUrl.substring(0, 64) + "...");
                return magicUrl;
            }

            // 步骤 5: v6/priv_url POST 兜底(EchoMusic song_url_new.js)
            KuGouLogger.warn("[NetMusicKuGou] magic ppage_id empty for hash={}, trying v6/priv_url", lowerHash);
            String v6Url = fetchV6PrivUrl(lowerHash, albumIdL);
            if (!v6Url.isEmpty()) {
                KuGouLogger.info("[NetMusicKuGou] URL final (v6/priv_url) in {}ms: len={}, prefix={}",
                        System.currentTimeMillis() - t0,
                        v6Url.length(),
                        v6Url.length() < 64 ? v6Url : v6Url.substring(0, 64) + "...");
                return v6Url;
            }

            KuGouLogger.warn("[NetMusicKuGou] ALL fallbacks exhausted for hash={}, returning empty URL after {}ms",
                    lowerHash, System.currentTimeMillis() - t0);
            return "";
        });
    }

    /** 解析 albumId 为 long, 非数字时返回 0 */
    private static long parseAlbumId(String albumId) {
        if (albumId == null || albumId.isEmpty()) return 0;
        try { return Long.parseLong(albumId.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** privilege_lite 返回的 relate_goods 条目:每个音质一个独立 hash */
    public static final class RelateGood {
        public final String hash;
        public final String quality; // 如 "128"/"320"/"flac"/"sq"/"hires"/"high"/"dsd"/"super"
        public final int level;      // 1..7, 1=128, 2=320, 5=flac/sq, 6=high/hires, 7=super/dsd

        public RelateGood(String hash, String quality, int level) {
            this.hash = hash;
            this.quality = quality;
            this.level = level;
        }

        @Override
        public String toString() {
            return "{" + level + "/" + quality + "@" + (hash == null ? "?" : hash.substring(0, Math.min(8, hash.length()))) + "...}";
        }
    }

    /** 按音质匹配 relateGoods (对齐 EchoMusic doesRelateGoodMatchQuality song.ts L152) */
    private static RelateGood matchRelateGoodForQuality(List<RelateGood> goods, AudioQuality q) {
        if (goods == null || goods.isEmpty()) return null;
        String qv = q == null ? "128" : q.getValue();
        // 128 音质: 任何 relateGoods 都可用, 优先找显式标记的 128
        if ("128".equals(qv)) {
            for (RelateGood g : goods) {
                if (g == null || g.hash == null || g.hash.isEmpty()) continue;
                String normQ = (g.quality == null ? "" : g.quality).trim().toLowerCase();
                if ("128".equals(normQ) || "standard".equals(normQ) || g.level == 1 || g.level == 2 || g.level == 0) {
                    return g;
                }
            }
            // 退而求其次: 第一个有 hash 的
            for (RelateGood g : goods) {
                if (g != null && g.hash != null && !g.hash.isEmpty()) return g;
            }
            return null;
        }
        for (RelateGood g : goods) {
            if (g == null || g.hash == null || g.hash.isEmpty()) continue;
            String normQ = (g.quality == null ? "" : g.quality).trim().toLowerCase();
            boolean hit = false;
            switch (qv) {
                case "320":
                    hit = "320".equals(normQ) || "hq".equals(normQ) || g.level == 4; break;
                case "flac":
                    hit = "flac".equals(normQ) || "sq".equals(normQ) || g.level == 5; break;
                case "high":
                    hit = "high".equals(normQ) || "hires".equals(normQ) || "hi-res".equals(normQ) || "res".equals(normQ) || g.level == 6; break;
                case "super":
                    hit = "super".equals(normQ) || "dsd".equals(normQ) || g.level == 7; break;
                default: break;
            }
            if (hit) return g;
        }
        return null;
    }

    /**
     * 对应 EchoMusic privilege_lite.js: POST /v2/get_res_privilege/lite (x-router: media.store.kugou.com)
     * <p>
     * 请求体: { appid, area_code, behavior, clientver, need_hash_offset, relate, support_verify,
     *          resource:[{type:"audio", page_id:0, hash, album_id}], qualities:[...] }
     * <p>
     * 返回: data[0].relate_goods = [{hash, quality, level}, ...]  每音质独立 hash
     */
    private static List<RelateGood> fetchSongPrivilegeLite(String hash, String albumId) throws IOException {
        String dfid = KuGouConfig.dfid;
        if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) return Collections.emptyList();

        String mid = KuGouConfig.mid != null ? KuGouConfig.mid : "";
        String userid = KuGouConfig.userid != null ? KuGouConfig.userid : "0";
        int cltime = (int) (System.currentTimeMillis() / 1000);

        // 1. 组装 query params(因为走 gateway,需要在 URL 上带 appid/clientver/dfid 等默认参数, 再加 signature)
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("dfid", dfid);
        queryParams.put("mid", mid);
        queryParams.put("uuid", "-");
        queryParams.put("appid", "3116");
        queryParams.put("clientver", 11440);
        queryParams.put("clienttime", String.valueOf(cltime));
        if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) queryParams.put("token", KuGouConfig.token);
        if (!"0".equals(userid) && !userid.isEmpty()) queryParams.put("userid", userid);

        // URL 级 Android 签名
        queryParams.put("signature", KuGouSignature.signatureAndroidParams(queryParams, ""));

        // 2. body(JSON) 按 privilege_lite.js
        JsonObject body = new JsonObject();
        body.addProperty("appid", "3116");
        body.addProperty("area_code", 1);
        body.addProperty("behavior", "play");
        body.addProperty("clientver", 11440);
        body.addProperty("need_hash_offset", 1);
        body.addProperty("relate", 1);
        body.addProperty("support_verify", 1);
        JsonArray qualities = new JsonArray();
        for (String q : new String[]{"128", "320", "flac", "high", "viper_atmos", "viper_tape", "viper_clear", "super", "multitrack"}) {
            qualities.add(q);
        }
        body.add("qualities", qualities);

        JsonArray resource = new JsonArray();
        JsonObject resItem = new JsonObject();
        resItem.addProperty("type", "audio");
        resItem.addProperty("page_id", 0);
        resItem.addProperty("hash", hash.toLowerCase());
        long albumIdL = parseAlbumId(albumId);
        resItem.addProperty("album_id", albumIdL);
        resource.add(resItem);
        body.add("resource", resource);

        String jsonBody = GSON.toJson(body);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
        headers.put("x-router", "media.store.kugou.com");
        headers.put("Content-Type", "application/json");
        headers.put("dfid", dfid);
        headers.put("mid", mid);
        headers.put("clienttime", String.valueOf(cltime));
        headers.put("kg-rc", "1");
        headers.put("kg-thash", "5d816a0");
        headers.put("kg-rec", "1");
        headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

        StringBuilder cookieSb = new StringBuilder();
        if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
            cookieSb.append("token=").append(KuGouConfig.token).append("; ");
        }
        if (!"0".equals(userid) && !userid.isEmpty()) {
            cookieSb.append("userid=").append(userid).append("; ");
        }
        for (var entry : KuGouConfig.cookies.entrySet()) {
            cookieSb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
        }
        String cookieStr = cookieSb.toString().trim();
        if (!cookieStr.isEmpty()) headers.put("Cookie", cookieStr);

        HttpUtils.HttpResponse response = HttpUtils.postRaw(
                "https://gateway.kugou.com/v2/get_res_privilege/lite",
                headers, queryParams, jsonBody);
        KuGouLogger.info("[NetMusicKuGou] privilege_lite HTTP {} bodyLen={}",
                response.statusCode, response.body == null ? 0 : response.body.length());

        if (!response.isOk() || response.body == null || response.body.isEmpty()) {
            return Collections.emptyList();
        }
        // 预览
        String preview = response.body.length() < 600 ? response.body : response.body.substring(0, 600) + "...";
        KuGouLogger.info("[NetMusicKuGou] privilege_lite resp preview: {}", preview);
        return parseRelateGoods(response.body);
    }

    /** 从 privilege_lite 响应中解析 relate_goods 数组 */
    private static List<RelateGood> parseRelateGoods(String jsonStr) {
        List<RelateGood> out = new ArrayList<>();
        try {
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return out;
            int ec = root.has("error_code") ? root.get("error_code").getAsInt() :
                    (root.has("err_code") ? root.get("err_code").getAsInt() : 0);
            int st = root.has("status") ? root.get("status").getAsInt() : -1;
            if (ec != 0 && !(st == 1 || st == 200)) {
                KuGouLogger.warn("[NetMusicKuGou] privilege_lite not success: error_code={}, status={}", ec, st);
                return out;
            }

            // data 可能是数组 [{resource+relate_goods}] 或对象包含 data
            JsonArray dataArr = null;
            if (root.has("data")) {
                JsonElement d = root.get("data");
                if (d.isJsonArray()) dataArr = d.getAsJsonArray();
                else if (d.isJsonObject()) {
                    // 某些响应包在 data.data / data.list
                    if (d.getAsJsonObject().has("data") && d.getAsJsonObject().get("data").isJsonArray()) {
                        dataArr = d.getAsJsonObject().getAsJsonArray("data");
                    } else if (d.getAsJsonObject().has("list") && d.getAsJsonObject().get("list").isJsonArray()) {
                        dataArr = d.getAsJsonObject().getAsJsonArray("list");
                    }
                }
            }
            if (dataArr == null) return out;

            for (JsonElement e : dataArr) {
                if (!e.isJsonObject()) continue;
                JsonObject item = e.getAsJsonObject();
                JsonArray goodsArr = null;
                if (item.has("relate_goods") && item.get("relate_goods").isJsonArray()) {
                    goodsArr = item.getAsJsonArray("relate_goods");
                } else if (item.has("relateGoods") && item.get("relateGoods").isJsonArray()) {
                    goodsArr = item.getAsJsonArray("relateGoods");
                }
                if (goodsArr == null) continue;
                for (JsonElement ge : goodsArr) {
                    if (!ge.isJsonObject()) continue;
                    JsonObject g = ge.getAsJsonObject();
                    String h = firstNonEmpty(
                            getStr(g, "hash"),
                            getStr(g, "Hash"),
                            getStr(g, "file_hash"));
                    if (h == null || h.isEmpty()) continue;
                    String q = firstNonEmpty(
                            getStr(g, "quality"),
                            getStr(g, "Quality"),
                            getStr(g, "name"));
                    int lvl = g.has("level") ? g.get("level").getAsInt() : 0;
                    if (lvl == 0 && g.has("Level")) lvl = g.get("Level").getAsInt();
                    out.add(new RelateGood(h, q, lvl));
                }
            }
        } catch (Exception e) {
            KuGouLogger.warn("[NetMusicKuGou] parseRelateGoods exception: {}", e.toString());
        }
        return out;
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
     * <p>
     * ⚠️ 严格对齐 EchoMusic server/module/song_url.js (L15-L51):
     *   - encryptKey=true: 只加 key 字段（MD5(hash+57ae12eb+appid+mid+userid)）
     *   - notSign=true:  不要 signature 字段！（加了反而触发 31833 版权校验）
     *   - version/clientver = 11430（与 song_url.js L26/L39 硬编码一致，不是 11440）
     *   - GET 请求, x-router=trackercdn.kugou.com
     *   - Lite 配套: page_id=967177915, pid=411, ppage_id=356753938,823673182,967485191
     */
    private static String fetchAuthenticatedSongUrl(String hash, AudioQuality quality, long albumId) {
        try {
            String dfid = KuGouConfig.dfid;
            if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) {
                KuGouLogger.warn("[NetMusicKuGou] Auth API (v5/url) skipped: no dfid");
                return "";
            }

            int cltime = (int) (System.currentTimeMillis() / 1000);
            String mid = KuGouConfig.mid != null ? KuGouConfig.mid : "";
            String userid = KuGouConfig.userid != null ? KuGouConfig.userid : "0";
            String appid = "3116";
            // EchoMusic song_url.js L26/L39 硬编码 clientver=11430 / version=11430 (不是 11440)
            final int clientver = 11430;

            // 音质映射：与KuGou保持一致
            String qualityStr = (quality != null) ? quality.getValue() : "128";
            if (!qualityStr.equals("128") && !qualityStr.equals("320") &&
                !qualityStr.equals("flac") && !qualityStr.equals("high") && !qualityStr.equals("super")) {
                qualityStr = "magic_" + qualityStr;
            }

            Map<String, Object> params = new LinkedHashMap<>();
            // 注意: EchoMusic resolver.ts 调 getSongUrl(matched.hash, quality) 时没传 album_id,
            // 因此服务端 params.album_id 为 0。传入真实 albumId 时也可能触发版权匹配失败。
            // 两种策略都试一次: 先 0, 再 albumId。这里 albumId 直接用传入值,外层循环负责两种尝试。
            params.put("album_id", albumId);
            params.put("area_code", 1);
            params.put("hash", hash.toLowerCase());
            params.put("ssa_flag", "is_fromtrack");
            params.put("version", clientver);
            params.put("page_id", 967177915);              // Lite 值
            params.put("quality", qualityStr);
            params.put("album_audio_id", albumId);
            params.put("behavior", "play");
            params.put("pid", 411);                        // Lite pid
            params.put("cmd", 26);
            params.put("pidversion", 3001);
            params.put("IsFreePart", 0);
            params.put("ppage_id", "356753938,823673182,967485191"); // Lite ppage_id
            params.put("cdnBackup", 1);
            params.put("module", "");
            params.put("clientver", clientver);

            // 基础认证参数
            params.put("dfid", dfid);
            params.put("mid", mid);
            params.put("uuid", "-");
            params.put("appid", appid);
            params.put("clienttime", String.valueOf(cltime));
            if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) params.put("token", KuGouConfig.token);
            if (userid != null && !userid.isEmpty() && !"0".equals(userid)) params.put("userid", userid);

            // encryptKey:true — 只加 key (Lite 平台 salt=185672dd,不是 57ae12eb)
            // helper.js L70-73 signKey(hash,mid,userid,appid) = md5(hash + LITE_SALT + appid + mid + userid)
            String liteSignSalt = "185672dd44712f60bb1736df5a377e82";
            String key = CryptoUtils.md5(hash.toLowerCase() + liteSignSalt + appid + mid + userid);
            params.put("key", key);
            // encryptType=android 且 song_url.js notSign:true — URL query 层 signature 用
            // signatureAndroidParams(params, "") 即可;Lite salt = LnT6xpN3khm36zse0QzvmgTZ3waWdRSA
            params.put("signature", KuGouSignature.signatureAndroidParams(params, ""));

            KuGouLogger.info("[NetMusicKuGou] v5/url try(hash={}, q={}, album_id={})",
                    hash.substring(0, Math.min(8, hash.length())) + "...", qualityStr, albumId);

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
            if (!cookieStr.isEmpty()) headers.put("Cookie", cookieStr);

            HttpUtils.HttpResponse response = HttpUtils.get(
                    "https://gateway.kugou.com/v5/url", headers, params);

            if (!response.isOk() || response.body == null || response.body.isEmpty()) return "";
            String prev = response.body.length() < 500 ? response.body : response.body.substring(0, 500) + "...";
            KuGouLogger.info("[NetMusicKuGou] v5/url resp(hash={}): {}",
                    hash.substring(0, Math.min(8, hash.length())) + "...", prev);
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
     * 方式4: 魔法 ppage_id 兜底（对应 EchoMusic resolver.ts:227 getSongUrl(hash, '', 356753938)）
     * <p>
     * 当所有正规途径都失败时,使用单值魔法 ppage_id=356753938 绕过无版权限制。
     * 该 ppage_id 是 Lite 平台的兜底值,EchoMusic 在所有音质和 yiting 都失败后作为最后兜底使用。
     * 关键点:
     *   - quality = "128" (最低音质,最易获取)
     *   - ppage_id = "356753938" (单值,非逗号分隔列表)
     *   - page_id = 967177915, pid = 411 (Lite 平台配套值)
     */
    private static String fetchMagicPpageUrl(String hash, long albumId) {
        try {
            String dfid = KuGouConfig.dfid;
            if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) {
                KuGouLogger.warn("[NetMusicKuGou] Magic ppage_id skipped: no dfid");
                return "";
            }

            int cltime = (int) (System.currentTimeMillis() / 1000);
            String mid = KuGouConfig.mid != null ? KuGouConfig.mid : "";
            String userid = KuGouConfig.userid != null ? KuGouConfig.userid : "0";
            String appid = "3116";
            final int clientver = 11430; // EchoMusic song_url.js 硬编码版本号

            Map<String, Object> params = new LinkedHashMap<>();
            // EchoMusic resolver.ts 魔法兜底 getSongUrl(hash, '', 356753938) 同样没传 album_id,
            // 所以先试 album_id=0,失败再试 albumId
            params.put("album_id", 0L);
            params.put("area_code", 1);
            params.put("hash", hash.toLowerCase());
            params.put("ssa_flag", "is_fromtrack");
            params.put("version", clientver);
            params.put("page_id", 967177915);              // Lite 值
            params.put("quality", "128");                  // 最低音质,最易获取
            params.put("album_audio_id", 0L);
            params.put("behavior", "play");
            params.put("pid", 411);                        // Lite pid
            params.put("cmd", 26);
            params.put("pidversion", 3001);
            params.put("IsFreePart", 0);
            params.put("ppage_id", "356753938");           // 单值魔法(不是逗号列表)
            params.put("cdnBackup", 1);
            params.put("module", "");
            params.put("clientver", clientver);

            // 基础认证参数
            params.put("dfid", dfid);
            params.put("mid", mid);
            params.put("uuid", "-");
            params.put("appid", appid);
            params.put("clienttime", String.valueOf(cltime));
            if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) params.put("token", KuGouConfig.token);
            if (userid != null && !userid.isEmpty() && !"0".equals(userid)) params.put("userid", userid);

            // encryptKey:true — Lite 平台 salt=185672dd,不是 57ae12eb
            String liteSignSalt = "185672dd44712f60bb1736df5a377e82";
            params.put("key", CryptoUtils.md5(hash.toLowerCase() + liteSignSalt + appid + mid + userid));
            // encryptType=android,notSign:true — signature 也要补(Lite salt)
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
            String body = (response.isOk() && response.body != null) ? response.body : "";
            String url = body.isEmpty() ? "" : parseV5UrlResponse(body);

            // album_id=0 失败时再试真实 albumId(有的场景需要传真实 albumId 才放行)
            if (url.isEmpty() && albumId != 0) {
                KuGouLogger.info("[NetMusicKuGou] Magic ppage_id album_id=0 failed, retry album_id={}", albumId);
                Map<String, Object> params2 = new LinkedHashMap<>(params);
                params2.put("album_id", albumId);
                params2.put("album_audio_id", albumId);
                // key 签名只有 hash+signSalt+appid+mid+userid,不依赖 album_id,可直接复用
                HttpUtils.HttpResponse r2 = HttpUtils.get("https://gateway.kugou.com/v5/url", headers, params2);
                String b2 = (r2.isOk() && r2.body != null) ? r2.body : "";
                if (!b2.isEmpty()) url = parseV5UrlResponse(b2);
            }
            return url;

        } catch (Exception e) {
            KuGouLogger.error("[NetMusicKuGou] Magic ppage_id API exception: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * 方式5: v6/priv_url 兜底(对应 EchoMusic song_url_new.js)
     * <p>
     * - POST JSON body 结构: {area_code, behavior, qualities, resource{...}, token, tracker_param{key,priv_vip_type=6,...}, userid, vip}
     * - tracker_param.key 使用 v6 专属 salt="185672dd44712f60bb1736df5a377e82"(与 v5/url 的 57ae... 不同)
     * - URL query 层照常挂默认参数 + signatureAndroidParams(queryParams, data=bodyJSON) (encryptType=android)
     */
    private static String fetchV6PrivUrl(String hash, long albumId) {
        try {
            String dfid = KuGouConfig.dfid;
            if (dfid == null || dfid.isEmpty() || "-".equals(dfid)) return "";

            int cltime = (int) (System.currentTimeMillis() / 1000);
            long clienttimeMs = System.currentTimeMillis();
            String mid = KuGouConfig.mid != null ? KuGouConfig.mid : "";
            String useridStr = KuGouConfig.userid != null ? KuGouConfig.userid : "0";
            long userid;
            try { userid = Long.parseLong(useridStr); } catch (NumberFormatException e) { userid = 0; }
            String appid = "3116";
            int clientver = 11440;
            String lowerHash = hash.toLowerCase();
            String token = KuGouConfig.token != null ? KuGouConfig.token : "";
            String vipToken = KuGouConfig.vipToken != null ? KuGouConfig.vipToken : "";
            String vipType = KuGouConfig.vipType != null ? KuGouConfig.vipType : "0";

            // 1. 组装 POST body(dataMap),严格对齐 EchoMusic song_url_new.js L14-44 的真实写法
            //    注意字段类型:
            //     - area_code: "1" (字符串),不是 1 数字
            //     - resource.collect_list_id: "3" (字符串)
            //     - tracker_param.pid: "411", pidversion: "3001", priv_vip_type: "6" (全字符串)
            //     - tracker_param.auth: "", open_time: "" (空字符串保留,不能删)
            //     - vip: 直接用 vipType(来自 cookie,字符串或数字都接受,不要强转数字)
            JsonObject body = new JsonObject();
            body.addProperty("area_code", "1");
            body.addProperty("behavior", "play");

            JsonArray qualities = new JsonArray();
            for (String q : new String[]{"128", "320", "flac", "high", "multitrack", "viper_atmos", "viper_tape", "viper_clear", "super"})
                qualities.add(q);
            body.add("qualities", qualities);

            JsonObject resource = new JsonObject();
            resource.addProperty("album_audio_id", albumId);
            resource.addProperty("collect_list_id", "3");
            resource.addProperty("collect_time", clienttimeMs);
            resource.addProperty("hash", lowerHash);
            resource.addProperty("id", 0);
            resource.addProperty("page_id", 1);
            resource.addProperty("type", "audio");
            body.add("resource", resource);

            body.addProperty("token", token);  // 即使空也要传(EchoMusic song_url_new.js L27 无条件加)

            // tracker_param.key: cryptoMd5(hash + "185672dd44712f60bb1736df5a377e82" + appid + mid + userid)
            String v6KeySalt = "185672dd44712f60bb1736df5a377e82";
            String trackerKey = CryptoUtils.md5(lowerHash + v6KeySalt + appid + mid + userid);
            JsonObject trackerParam = new JsonObject();
            trackerParam.addProperty("all_m", 1);
            trackerParam.addProperty("auth", "");   // ⚠️ 必须保留空字符串(不能删!)song_url_new.js L30
            trackerParam.addProperty("is_free_part", 0);
            trackerParam.addProperty("key", trackerKey);
            trackerParam.addProperty("module_id", 0);
            trackerParam.addProperty("need_climax", 1);
            trackerParam.addProperty("need_xcdn", 1);
            trackerParam.addProperty("open_time", ""); // ⚠️ 必须保留空字符串 song_url_new.js L36
            trackerParam.addProperty("pid", "411");     // 字符串,不是数字
            trackerParam.addProperty("pidversion", "3001"); // 字符串
            trackerParam.addProperty("priv_vip_type", "6"); // 字符串
            trackerParam.addProperty("viptoken", vipToken);  // 空也要传 song_url_new.js L40
            body.add("tracker_param", trackerParam);

            body.addProperty("userid", String.valueOf(userid));
            // vip: 直接用 vipType(来自 cookie,字符串 或 "0"/数字),不要强转
            body.addProperty("vip", vipType == null || vipType.isEmpty() ? "0" : vipType);

            String bodyJson = GSON.toJson(body);
            KuGouLogger.info("[NetMusicKuGou] v6/priv_url POST body(len={}): {}", bodyJson.length(),
                    bodyJson.length() < 800 ? bodyJson : bodyJson.substring(0, 800) + "...");

            // 2. URL query 层默认参数(encryptType=android 风格),signature = signatureAndroidParams(queryParams, bodyJson)
            Map<String, Object> queryParams = new LinkedHashMap<>();
            queryParams.put("dfid", dfid);
            queryParams.put("mid", mid);
            queryParams.put("uuid", "-");
            queryParams.put("appid", appid);
            queryParams.put("clientver", clientver);
            queryParams.put("clienttime", String.valueOf(cltime));
            if (!token.isEmpty()) queryParams.put("token", token);
            if (userid != 0) queryParams.put("userid", String.valueOf(userid));
            // Lite 模式的 secret = LnT6xpN3khm36zse0QzvmgTZ3waWdRSA
            queryParams.put("signature", KuGouSignature.signatureAndroidParams(queryParams, bodyJson));

            // 3. headers + Cookie
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
            headers.put("Content-Type", "application/json");
            headers.put("dfid", dfid);
            headers.put("mid", mid);
            headers.put("clienttime", String.valueOf(cltime));
            headers.put("kg-rc", "1");
            headers.put("kg-thash", "5d816a0");
            headers.put("kg-rec", "1");
            headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");

            StringBuilder cookieSb = new StringBuilder();
            cookieSb.append("dfid=").append(dfid).append("; ");
            if (!token.isEmpty()) cookieSb.append("token=").append(token).append("; ");
            if (userid != 0) cookieSb.append("userid=").append(userid).append("; ");
            if (!"0".equals(vipType) && !vipType.isEmpty()) cookieSb.append("vip_type=").append(vipType).append("; ");
            if (!vipToken.isEmpty()) cookieSb.append("vip_token=").append(vipToken).append("; ");
            for (var entry : KuGouConfig.cookies.entrySet()) {
                cookieSb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
            }
            headers.put("Cookie", cookieSb.toString().trim());

            HttpUtils.HttpResponse response = HttpUtils.postRaw(
                    "http://tracker.kugou.com/v6/priv_url",
                    headers, queryParams, bodyJson);
            KuGouLogger.info("[NetMusicKuGou] v6/priv_url HTTP {} bodyLen={}",
                    response.statusCode, response.body == null ? 0 : response.body.length());
            if (response.body != null && !response.body.isEmpty()) {
                String prev = response.body.length() < 700 ? response.body : response.body.substring(0, 700) + "...";
                KuGouLogger.info("[NetMusicKuGou] v6/priv_url resp preview: {}", prev);
            }

            if (!response.isOk() || response.body == null || response.body.isEmpty()) return "";
            String url = parseV5UrlResponse(response.body);
            KuGouLogger.info("[NetMusicKuGou] v6/priv_url parsed URL: len={}, prefix={}",
                    url.length(),
                    url.length() < 80 ? url : url.substring(0, 80) + "...");
            return url;
        } catch (Exception e) {
            KuGouLogger.error("[NetMusicKuGou] v6/priv_url exception: {}", e.toString(), e);
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
            int error_code = root.has("error_code") ? root.get("error_code").getAsInt() :
                    (root.has("err_code") ? root.get("err_code").getAsInt() : -1);
            boolean hasErr = (status == 0 || status == -1) && error_code > 0;
            // 有的接口 status=200 但 error_code!=0,再检查
            if (root.has("error_code") && error_code > 0 && status != 1) {
                hasErr = true;
            }
            if (hasErr) {
                String msg = firstNonEmpty(
                        getStr(root, "error_msg"),
                        getStr(root, "errmsg"),
                        getStr(root, "message"),
                        getStr(root, "info"));
                String explain = KuGouErrorCode.explain(error_code);
                KuGouLogger.warn("[NetMusicKuGou] v5/url error_code={} ({}), msg={}",
                        error_code, explain, msg);
                return "";
            }

            // 递归解析 URL
            String url = resolveUrlRecursive(root);
            if (!url.isEmpty()) return url;

            return "";
        } catch (Exception e) {
            KuGouLogger.warn("[NetMusicKuGou] Parse v5/url response failed: {}", e.toString(), e);
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
                KuGouLogger.warn("[NetMusicKuGou] searchLyric skipped: empty hash/keyword (hash={}, kw={})", hash, keyword);
                return null;
            }
            KuGouLogger.info("[NetMusicKuGou] searchLyric start: hash={}, keyword='{}', duration={}",
                    hash, keyword, duration);
            try {
                // EchoMusic server/module/search_lyric.js: /v1/search + appid/clientver（非公开 /search）
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("album_audio_id", 0);
                params.put("appid", "3116");
                params.put("clientver", 11440);
                params.put("duration", duration > 0 ? duration : 0);
                params.put("hash", hash.toLowerCase());
                params.put("keywords", keyword);  // 注意是 keywords 复数（与 EchoMusic 一致）
                params.put("lrctxt", 1);
                params.put("man", "no");

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("Accept", "application/json, text/plain, */*");

                // 双轨：先试 /v1/search（EchoMusic 新版），失败回退到公开 /search
                HttpUtils.HttpResponse response = HttpUtils.get(
                        "https://lyrics.kugou.com/v1/search", headers, params);
                KuGouLogger.info("[NetMusicKuGou] searchLyric v1 HTTP {} bodyLen={}",
                        response.statusCode, response.body == null ? 0 : response.body.length());

                if (!response.isOk() || response.body == null || response.body.isEmpty()) {
                    KuGouLogger.warn("[NetMusicKuGou] searchLyric v1 HTTP {} or empty, fallback to /search",
                            response.statusCode);
                    // 回退公开 /search（不带 appid/clientver，向下兼容）
                    Map<String, Object> oldParams = new LinkedHashMap<>();
                    oldParams.put("hash", hash.toLowerCase());
                    oldParams.put("keyword", keyword);
                    oldParams.put("lrctxt", 1);
                    if (duration > 0) oldParams.put("duration", duration);
                    response = HttpUtils.get("http://lyrics.kugou.com/search", headers, oldParams);
                    KuGouLogger.info("[NetMusicKuGou] searchLyric fallback HTTP {} bodyLen={}",
                            response.statusCode, response.body == null ? 0 : response.body.length());
                }

                if (!response.isOk() || response.body == null || response.body.isEmpty()) {
                    KuGouLogger.warn("[NetMusicKuGou] searchLyric HTTP {} or empty body", response.statusCode);
                    return null;
                }
                return parseSearchLyricResult(response.body);
            } catch (Exception e) {
                KuGouLogger.error("[NetMusicKuGou] searchLyric exception: {}", e.toString(), e);
                return null;
            }
        });
    }

    private static LyricCandidate parseSearchLyricResult(String jsonStr) {
        try {
            if (jsonStr.length() < 800) {
                KuGouLogger.info("[NetMusicKuGou] searchLyric resp preview: {}", jsonStr);
            } else {
                KuGouLogger.info("[NetMusicKuGou] searchLyric resp preview: {}", jsonStr.substring(0, 800) + "...");
            }
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return null;

            // v1/search: error_code=0 成功,status=200 也视为成功
            // 旧 /search: status=200 成功;status=1 也兼容
            boolean success = false;
            if (root.has("error_code") && root.get("error_code").getAsInt() == 0) success = true;
            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            if (!success && (status == 200 || status == 1)) success = true;
            if (!success) {
                KuGouLogger.warn("[NetMusicKuGou] searchLyric not success: status={}, error_code={}",
                        status, root.has("error_code") ? root.get("error_code").getAsInt() : -1);
                return null;
            }

            JsonArray candidates = root.has("candidates") ? root.getAsJsonArray("candidates") : null;
            // 某些路径返回在 data.candidates
            if ((candidates == null || candidates.isEmpty()) && root.has("data") && root.get("data").isJsonObject()) {
                JsonObject d = root.getAsJsonObject("data");
                if (d.has("candidates")) candidates = d.getAsJsonArray("candidates");
            }
            if (candidates == null || candidates.isEmpty()) {
                KuGouLogger.warn("[NetMusicKuGou] searchLyric no candidates");
                return null;
            }

            // 取 score 最高的一项
            LyricCandidate best = null;
            for (JsonElement elem : candidates) {
                if (!elem.isJsonObject()) continue;
                JsonObject item = elem.getAsJsonObject();
                String id = getStr(item, "id");
                String accessKey = firstNonEmpty(getStr(item, "accesskey"), getStr(item, "accessKey"), getStr(item, "access_key"));
                if (id.isEmpty() || accessKey.isEmpty()) continue;
                LyricCandidate c = new LyricCandidate(
                        id,
                        accessKey,
                        firstNonEmpty(getStr(item, "singer"), getStr(item, "SingerName"), getStr(item, "artist")),
                        firstNonEmpty(getStr(item, "song"), getStr(item, "songname"), getStr(item, "SongName"), getStr(item, "title")),
                        item.has("score") ? item.get("score").getAsInt() :
                                (item.has("Score") ? item.get("Score").getAsInt() : 0));
                if (best == null || c.score > best.score) {
                    best = c;
                }
            }
            KuGouLogger.info("[NetMusicKuGou] searchLyric best candidate: id={}, song='{}', singer='{}', score={}",
                    best == null ? "null" : best.id,
                    best == null ? "" : best.songName,
                    best == null ? "" : best.singer,
                    best == null ? 0 : best.score);
            return best;
        } catch (Exception e) {
            KuGouLogger.warn("[NetMusicKuGou] parse search_lyric exception: {}", e.toString(), e);
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
                KuGouLogger.warn("[NetMusicKuGou] getLyric skipped: empty id/accessKey (id={}, ak={})", id, accessKey);
                return null;
            }
            KuGouLogger.info("[NetMusicKuGou] getLyric start: id={}, fmt={}", id, fmt);
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
                params.put("charset", "utf8");                 // EchoMusic lyric.js 必传
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
                params.put("signature", KuGouSignature.signatureAndroidParams(params, ""));

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
                KuGouLogger.info("[NetMusicKuGou] getLyric HTTP {} bodyLen={}",
                        response.statusCode, response.body == null ? 0 : response.body.length());

                if (!response.isOk() || response.body == null || response.body.isEmpty()) {
                    KuGouLogger.warn("[NetMusicKuGou] getLyric HTTP {} or empty", response.statusCode);
                    return null;
                }
                return parseLyricDownloadResponse(response.body, fmt != null ? fmt : "lrc");
            } catch (Exception e) {
                KuGouLogger.error("[NetMusicKuGou] getLyric exception: {}", e.toString(), e);
                return null;
            }
        });
    }

    private static LyricContent parseLyricDownloadResponse(String jsonStr, String requestedFmt) {
        try {
            // 响应预览（避免打印过长 base64 content 字段，先输出前 600 字符）
            String preview = jsonStr.length() < 600 ? jsonStr : jsonStr.substring(0, 600) + "...";
            KuGouLogger.info("[NetMusicKuGou] getLyric resp preview: {}", preview);
            JsonObject root = GSON.fromJson(jsonStr, JsonObject.class);
            if (root == null) return null;

            int status = root.has("status") ? root.get("status").getAsInt() : -1;
            int errcode = root.has("error_code") ? root.get("error_code").getAsInt() :
                    (root.has("err_code") ? root.get("err_code").getAsInt() : -1);
            String errmsg = firstNonEmpty(getStr(root, "error_msg"), getStr(root, "errmsg"), getStr(root, "message"));
            boolean success = false;
            if (status == 200 || status == 1) success = true;
            if (!success && errcode == 0) success = true;
            if (!success) {
                KuGouLogger.warn("[NetMusicKuGou] getLyric not success: status={}, error_code={}, errmsg={}",
                        status, errcode, errmsg);
                return null;
            }

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
