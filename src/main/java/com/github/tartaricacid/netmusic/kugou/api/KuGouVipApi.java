package com.github.tartaricacid.netmusic.kugou.api;

import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.util.HttpUtils;
import com.github.tartaricacid.netmusic.kugou.util.KuGouSignature;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 酷狗概念版 VIP 领取 API
 * 参照 KuGou server/module/youth_day_vip.js 和 youth_day_vip_upgrade.js
 */
public final class KuGouVipApi {

    private static final Gson GSON = new Gson();

    /** 最后一次 VIP 领取操作的结果描述，供 UI 显示 */
    public static volatile String lastVipResultMessage = "";

    /**
     * 每次领取操作的结果状态。用于驱动周期重试逻辑：
     * <ul>
     *   <li>NEVER_TRIED — 启动后还没尝试过</li>
     *   <li>IN_PROGRESS — 当前正在请求中（避免并发）</li>
     *   <li>SUCCESS — 领取成功</li>
     *   <li>ALREADY_CLAIMED — 服务端返回 20002（今日已领）</li>
     *   <li>FAILED — 其他失败（网络错误、签名错误等）</li>
     * </ul>
     */
    public enum ClaimStatus {
        NEVER_TRIED,
        IN_PROGRESS,
        SUCCESS,
        ALREADY_CLAIMED,
        FAILED
    }

    /** 当前领取任务状态（线程间可见） */
    public static volatile ClaimStatus lastClaimStatus = ClaimStatus.NEVER_TRIED;

    /** 上次领取尝试的日期（yyyy-MM-dd），用于跨日重置 */
    public static volatile String lastClaimDate = "";

    private static final String RECEIVE_VIP_URL =
            "https://gateway.kugou.com/youth/v1/recharge/receive_vip_listen_song";
    private static final String UPGRADE_VIP_URL =
            "https://gateway.kugou.com/youth/v1/listen_song/upgrade_vip_reward";

    private KuGouVipApi() {}

    /**
     * 领取每日 VIP（概念版专属）
     * 参照 KuGou server/module/youth_day_vip.js
     *
     * @param kugouId 酷狗用户 ID
     * @return 是否领取成功
     */
    /**
     * 领取酷狗"概念版"每日 VIP。
     * <p>
     * 调用 kugouvip.kugou.com /v1/youth_day_vip/recv_vip_listen_song。
     *
     * @param kugouId     酷狗 userid
     * @param receiveDay  领取日期，格式 yyyy-MM-dd；传 null 时回退到 LocalDate.now()
     * @return true=本次真正领到了新 VIP；false=失败、已领过、参数错误等
     */
    public static CompletableFuture<Boolean> receiveDailyVip(String kugouId, String receiveDay) {
        // 用 String[0] 包装：参数 receiveDay 既要传入 lambda、又要被 lambda 内重新赋值。
        // Java 要求 lambda 引用的本地变量是 effectively final，所以用单元素数组绕过。
        final String[] dayRef = { receiveDay };
        return CompletableFuture.supplyAsync(() -> {
            lastClaimStatus = ClaimStatus.IN_PROGRESS;
            try {
                // 确保设备已注册
                Boolean ready = KuGouApiClient.ensureDeviceRegistered().get();
                if (!ready) {
                    KuGouLogger.warn("[NetMusicKuGou] Cannot receive daily VIP: device not registered");
                    lastClaimStatus = ClaimStatus.FAILED;
                    markAttemptedToday();
                    return false;
                }

                // 解析业务参数
                long uid = parseLongSafe(kugouId);
                if (uid <= 0) {
                    KuGouLogger.warn("[NetMusicKuGou] receiveDailyVip invalid kugouId: {}", kugouId);
                    lastClaimStatus = ClaimStatus.FAILED;
                    markAttemptedToday();
                    return false;
                }

                // 优先使用 server 时区的"今天"；如果调用方未传则取本地
                if (dayRef[0] == null || dayRef[0].isEmpty()) {
                    dayRef[0] = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                }
                // 业务参数：id=90139（概念版 VIP 每日领取 source_id）
                String sourceId = "90139";

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("source_id", 90139);
                params.put("receive_day", dayRef[0]);
                params.put("appid", KuGouSignature.APPID);
                params.put("clientver", KuGouSignature.CLIENTVER);
                params.put("clienttime", System.currentTimeMillis() / 1000);
                params.put("dfid", KuGouConfig.dfid);
                params.put("mid", KuGouConfig.mid);
                params.put("uuid", "-");
                // token/userid 必须放 URL params 参与签名(参照 KuGou useAxios 的 defaultParams)
                if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                    params.put("token", KuGouConfig.token);
                }
                if (KuGouConfig.userid != null && !KuGouConfig.userid.isEmpty()) {
                    params.put("userid", KuGouConfig.userid);
                }
                // 签名必须在 token/userid 加入后计算
                params.put("signature", KuGouSignature.signatureAndroidParams(params, null));

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("dfid", KuGouConfig.dfid);
                headers.put("mid", KuGouConfig.mid);
                headers.put("clienttime", String.valueOf(params.get("clienttime")));
                headers.put("kg-rc", "1");
                headers.put("kg-thash", "5d816a0");
                headers.put("kg-rec", "1");
                headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
                // 不发送 Cookie header (KuGou 不发送)

                // params 全部在 URL query, body 为空 (跟 KuGou useAxios 一致)
                HttpUtils.HttpResponse response = HttpUtils.postRaw(RECEIVE_VIP_URL, headers, params, null);

                String respBody = response.body != null ? response.body : "";

                if (!response.isOk()) {
                    KuGouLogger.warn("[NetMusicKuGou] Receive daily VIP HTTP error: {}", response.statusCode);
                    lastClaimStatus = ClaimStatus.FAILED;
                    markAttemptedToday();
                    return false;
                }

                JsonObject root = GSON.fromJson(response.body, JsonObject.class);
                int status = root.has("status") ? root.get("status").getAsInt() : -1;
                int errorCode = root.has("error_code") ? root.get("error_code").getAsInt() : 0;

                if (status == 1) {
                    // ✅ 真正成功领取
                    lastVipResultMessage = "VIP领取成功！";
                    KuGouLogger.info("[NetMusicKuGou] Receive daily VIP: success");
                    lastClaimStatus = ClaimStatus.SUCCESS;
                    markAttemptedToday();
                    return true;
                }

                // ⚠️ error_code=131001 = 今日已领取(正常,不重试,等明天 0 点)
                // error_code=20002 = 错误请求格式
                if (errorCode == 131001 || errorCode == 20002) {
                    java.time.LocalDate tomorrow = LocalDate.now().plusDays(1);
                    lastVipResultMessage = "今日VIP领取已达上限，将于 " + tomorrow + " 00:00 后重置";
                    KuGouLogger.info("[NetMusicKuGou] Receive daily VIP: daily limit reached ({})", errorCode);
                    lastClaimStatus = ClaimStatus.ALREADY_CLAIMED;
                    markAttemptedToday();
                    return false;  // 不是成功，VIP没有续期
                }

                // ❌ 其他失败 - 使用错误码解释器
                // KuGouErrorCode.getFullMessage 已自带 "错误码 XXXX: <描述>"，不要再加前缀
                lastVipResultMessage = KuGouErrorCode.getFullMessage(errorCode);
                KuGouLogger.warn("[NetMusicKuGou] Receive daily VIP: {} (status={}, errcode={})",
                        lastVipResultMessage, status, errorCode);
                lastClaimStatus = ClaimStatus.FAILED;
                markAttemptedToday();
                return false;
            } catch (Exception e) {
                KuGouLogger.error("[NetMusicKuGou] Receive daily VIP error: {}", e.getMessage(), e);
                lastClaimStatus = ClaimStatus.FAILED;
                markAttemptedToday();
                return false;
            }
        });
    }

    /**
     * 升级畅听 VIP 奖励（概念版专属）
     * 参照 KuGou server/module/youth_day_vip_upgrade.js
     *
     * @param kugouId 酷狗用户 ID
     * @return 是否升级成功
     */
    public static CompletableFuture<Boolean> upgradeVipReward(String kugouId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Boolean ready = KuGouApiClient.ensureDeviceRegistered().get();
                if (!ready) {
                    KuGouLogger.warn("[NetMusicKuGou] Cannot upgrade VIP reward: device not registered");
                    return false;
                }
                long uid;
                try {
                    uid = Long.parseLong(kugouId);
                } catch (NumberFormatException e) {
                    uid = 0;
                }

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("kugouid", uid);
                params.put("ad_type", 1);
                params.put("appid", KuGouSignature.APPID);
                params.put("clientver", KuGouSignature.CLIENTVER);
                params.put("clienttime", System.currentTimeMillis() / 1000);
                params.put("dfid", KuGouConfig.dfid);
                params.put("mid", KuGouConfig.mid);
                params.put("uuid", "-");
                // token/userid 必须放 URL params 参与签名(参照 KuGou useAxios 的 defaultParams)
                if (KuGouConfig.token != null && !KuGouConfig.token.isEmpty()) {
                    params.put("token", KuGouConfig.token);
                }
                if (KuGouConfig.userid != null && !KuGouConfig.userid.isEmpty()) {
                    params.put("userid", KuGouConfig.userid);
                }
                params.put("signature", KuGouSignature.signatureAndroidParams(params, null));

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
                headers.put("dfid", KuGouConfig.dfid);
                headers.put("mid", KuGouConfig.mid);
                headers.put("clienttime", String.valueOf(params.get("clienttime")));
                headers.put("kg-rc", "1");
                headers.put("kg-thash", "5d816a0");
                headers.put("kg-rec", "1");
                headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
                // 不发送 Cookie header (KuGou 不发送)

                // params 全部在 URL query, body 为空 (跟 KuGou useAxios 一致)
                HttpUtils.HttpResponse response = HttpUtils.postRaw(UPGRADE_VIP_URL, headers, params, null);

                String respBody = response.body != null ? response.body : "";

                if (!response.isOk()) {
                    KuGouLogger.warn("[NetMusicKuGou] Upgrade VIP reward HTTP error: {}", response.statusCode);
                    return false;
                }

                JsonObject root = GSON.fromJson(response.body, JsonObject.class);
                int status = root.has("status") ? root.get("status").getAsInt() : -1;
                int errorCode = root.has("error_code") ? root.get("error_code").getAsInt() : 0;

                // ✅ status=1 真正成功
                // ✅ error_code=297002 = 今日已升级(等价成功,与 KuGou 一致)
                // error_code=20002 = 错误请求格式
                if (status == 1 || errorCode == 297002) {
                    lastVipResultMessage = (status == 1)
                            ? "VIP升级成功！"
                            : "VIP升级今日已生效";
                    KuGouLogger.info("[NetMusicKuGou] Upgrade VIP: success (status={}, errcode={})", status, errorCode);
                    lastClaimStatus = ClaimStatus.SUCCESS;
                    markAttemptedToday();
                    return true;
                }

                // ❌ 其他失败 - 使用错误码解释器
                // KuGouErrorCode.getFullMessage 已自带 "错误码 XXXX: <描述>"，不要再加前缀
                lastVipResultMessage = KuGouErrorCode.getFullMessage(errorCode);
                KuGouLogger.warn("[NetMusicKuGou] Upgrade VIP reward: {} (status={}, errcode={})",
                        lastVipResultMessage, status, errorCode);
                return false;
            } catch (Exception e) {
                KuGouLogger.error("[NetMusicKuGou] Upgrade VIP reward error: {}", e.getMessage(), e);
                return false;
            }
        });
    }

    /**
     * 判断今天是否还需要再尝试一次。
     * <p>
     * 策略（对齐 KuGou 的"每次都问 server"思路 —— 不靠本地 20002 拦截）：
     * <ul>
     *   <li>如果今天还没成功领取（SUCCESS）→ 继续重试</li>
     *   <li>如果今天已经 SUCCESS → 不再发请求（避免对 server 造成无意义压力）</li>
     *   <li>如果是其他状态（NEVER_TRIED / IN_PROGRESS / FAILED / ALREADY_CLAIMED）→ 都允许再试，
     *       这样 24h 周期过了之后才能及时再领</li>
     * </ul>
     */
    public static boolean shouldRetryToday() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        if (!today.equals(lastClaimDate)) {
            return true;  // 跨日或无记录时允许重试
        }
        // 请求进行中时不重复触发
        if (lastClaimStatus == ClaimStatus.IN_PROGRESS) {
            return false;
        }
        // 当天 SUCCESS 后停止重试，其他状态允许重试
        return lastClaimStatus != ClaimStatus.SUCCESS;
    }

    /**
     * 把指定日期写入到 lastClaimDate（用于跨日判定）。
     */
    private static void markAttemptedToday() {
        lastClaimDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    /**
     * 解析酷狗 server 返回的时间戳为北京时间日期字符串 yyyy-MM-dd。
     * <p>
     * KuGou 实现（user.ts:289-325）：把 server 时间戳 + 8 小时（UTC+8 北京时区）偏移再格式化。
     * 如果 server 调用失败，本地 fallback + 8 小时偏移。
     *
     * @param serverTimeMs server_now 接口返回的毫秒时间戳，<=0 表示获取失败
     * @return "yyyy-MM-dd" 格式的日期
     */
    public static String toBeijingDateString(long serverTimeMs) {
        long baseMs = serverTimeMs > 0 ? serverTimeMs : System.currentTimeMillis();
        long beijingMs = baseMs + 8L * 3600 * 1000;
        return java.time.Instant.ofEpochMilli(beijingMs)
                .atZone(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    /**
     * 安全解析 long，无法解析返回 0。
     */
    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
