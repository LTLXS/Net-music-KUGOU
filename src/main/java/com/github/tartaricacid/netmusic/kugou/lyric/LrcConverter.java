package com.github.tartaricacid.netmusic.kugou.lyric;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import org.apache.commons.lang3.StringUtils;
import com.github.tartaricacid.netmusic.kugou.KuGouLogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 LRC 文本 / KRC 文本解析成父模组 {@link LyricRecord}。
 * <p>
 * 父模组的 {@code LyricParser.parseLyric} 只接受网易云 JSON 格式，所以这里另起一个
 * 解析 LRC 纯文本的版本。其内部使用父模组相同的 tick 换算公式：
 * {@code totalTick = ((minutes * 60 + seconds) * 1000 + milliseconds) / 50}。
 * <p>
 * <b>本版支持</b>：把酷狗 KRC language 字段的 {@code type=1}（中文翻译）和
 * {@code type=0}（罗马音/拼音/原语言）<b>分别解析</b>，由 renderer 根据配置选择是否显示。
 */
public final class LrcConverter {

    private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d+):(\\d+)[.:](\\d+)](.*)");
    private static final Pattern KRC_PATTERN = Pattern.compile("\\[(\\d+),(\\d+)\\](.*)");
    /**
     * LRC/KRC 元信息行识别（借鉴 KuGou 的 {@code getStaticLyricLines}）。
     * 覆盖 {@code [id]} {@code [ar]} {@code [ti]} {@code [al]} {@code [by]} {@code [offset]}
     * {@code [hash]} {@code [language]} {@code [kana]} 等字段。
     */
    private static final Pattern METADATA_PATTERN = Pattern.compile(
            "^\\s*\\[(id|ar|ti|al|by|offset|hash|language|kana):",
            Pattern.CASE_INSENSITIVE);

    private LrcConverter() {}

    /**
     * 把 LRC 文本转换为父模组可用的 {@link LyricRecord}（不含翻译）。
     * <p>
     * 注意：tick 换算公式（{@code /50}）是父模组约定的「1 tick = 50ms」，不可更改。
     *
     * @param lrcText   标准 LRC 文本（{@code [mm:ss.fff]text}）
     * @param songName  当 0 tick 没有歌词行时，用此作为第一行
     * @return 解析结果；若 LRC 为空则返回 null
     */
    public static LyricRecord toLyricRecord(String lrcText, String songName) {
        Int2ObjectSortedMap<String> map = parseLrc(lrcText);
        if (map.isEmpty()) {
            // 诊断：解析失败时打印第 8-16 行（跳过元数据，看真正歌词格式）
            String[] lines = lrcText == null ? new String[0] : lrcText.split("\n");
            int from = Math.min(8, lines.length);
            int to = Math.min(16, lines.length);
            KuGouLogger.warn(
                    "[NetMusicKuGou] parseLrc returned empty. totalLines={}, firstLyricLines(8-16): {}",
                    lines.length,
                    from < to
                            ? String.join(" || ", java.util.Arrays.copyOfRange(lines, from, to))
                            : "(not enough lines)");
            return null;
        }
        if (!map.containsKey(0) && StringUtils.isNotBlank(songName)) {
            map.put(0, songName);
        }
        return new LyricRecord(map);
    }

    /**
     * 把 LRC 原文 + 翻译 JSON 一起打包成 {@link LyricRecord}（<b>仅翻译</b>，不返回罗马音）。
     * 行为兼容旧调用方；新代码请用 {@link #toLyricData}。
     *
     * @param lrcText   原歌词 LRC 文本
     * @param transJson 酷狗 KRC language 字段解码后的 JSON 字符串。null/空 = 无翻译
     * @param songName  当 0 tick 没有歌词行时，用此作为第一行
     * @return 包含原文 + 翻译的 LyricRecord；若 LRC 为空则返回 null
     */
    public static LyricRecord toLyricRecordWithTranslation(String lrcText, String transJson, String songName) {
        KuGouLyricData data = toLyricData(lrcText, transJson, songName);
        return data == null ? null : data.record;
    }

    /**
     * 把 LRC + 翻译 JSON + 罗马音一起解析为 {@link KuGouLyricData}。
     * <p>
     * <b>区别于 {@link #toLyricRecordWithTranslation}</b>：本方法同时返回 {@code romaji} map
     * （酷狗 type=0），由 renderer 根据用户配置决定是否渲染。
     *
     * @return 包含原文 LyricRecord + 翻译 map + 罗马音 map；若 LRC 为空返回 null
     */
    public static KuGouLyricData toLyricData(String lrcText, String transJson, String songName) {
        Int2ObjectSortedMap<String> map = parseLrc(lrcText);
        if (map.isEmpty()) {
            return null;
        }
        if (!map.containsKey(0) && StringUtils.isNotBlank(songName)) {
            map.put(0, songName);
        }
        KrcLyrics krc = parseKrcLyrics(transJson, map);
        if (krc.translation != null && !krc.translation.isEmpty()) {
            int[] mainTicks = ticksOf(map);
            for (int tick : mainTicks) {
                krc.translation.putIfAbsent(tick, "");
            }
        }
        // 显示出"上句罗马音"。补空字符串后，floor key 就会指向当前 tick，值为 "" → 隐藏。
        if (krc.romaji != null && !krc.romaji.isEmpty()) {
            int[] mainTicks = ticksOf(map);
            for (int tick : mainTicks) {
                krc.romaji.putIfAbsent(tick, "");
            }
        }
        LyricRecord record;
        if (krc.translation != null && !krc.translation.isEmpty()) {
            record = new LyricRecord(map, krc.translation);
        } else {
            record = new LyricRecord(map);
        }
        return new KuGouLyricData(record, krc.romaji);
    }

    /**
     * 判断一行主歌词是否为元数据（songName / 词:xxx / 曲:xxx / 编曲:xxx / Artist - SongName 等）。
     * <p>
     * 先用 {@link #METADATA_PATTERN} 识别标准 LRC/KRC 元信息标签（[id:xxx] [ar:xxx] [ti:xxx] 等），
     * 再用前缀检测识别中文制作信息（词/曲/编/作/制作 等）。
     */
    private static boolean isMetadataLine(String text) {
        if (text == null || text.isEmpty()) return true;
        if (METADATA_PATTERN.matcher(text).find()) return true;
        return text.startsWith("词") || text.startsWith("曲")
                || text.startsWith("歌") || text.startsWith("Song")
                || text.startsWith("编") || text.startsWith("作")
                || text.startsWith("原") || text.startsWith("翻")
                || text.startsWith("Mix") || text.startsWith("Mixing")
                || text.startsWith("Master") || text.startsWith("Produce")
                || text.startsWith("Lyric") || text.startsWith("Music")
                || text.startsWith("Compose") || text.startsWith("Arrange")
                || text.startsWith("Vocal") || text.startsWith("Guitar")
                || text.startsWith("Bass") || text.startsWith("Drum")
                || text.startsWith("Piano") || text.startsWith("String")
                || text.startsWith("Chorus") || text.startsWith("Director")
                || text.startsWith("Studio") || text.startsWith("Record")
                || text.startsWith("制作") || text.startsWith("演唱")
                || text.startsWith("混音") || text.startsWith("录音")
                || text.startsWith("出品") || text.startsWith("发行")
                || text.startsWith("监制") || text.startsWith("统筹")
                || text.contains(" - ");
    }

    public static Int2ObjectSortedMap<String> parseLrc(String lrcText) {
        Int2ObjectSortedMap<String> result = new Int2ObjectRBTreeMap<>();
        if (StringUtils.isBlank(lrcText)) {
            return result;
        }
        for (String line : lrcText.split("\n")) {
            String trimmed = line.trim();
            Matcher krcMatch = KRC_PATTERN.matcher(trimmed);
            if (krcMatch.find()) {
                int timeMs = Integer.parseInt(krcMatch.group(1));
                String text = krcMatch.group(3).trim();
                int totalTick = timeMs / 50;
                result.put(totalTick, text);
                continue;
            }
            Matcher lrcMatch = LRC_PATTERN.matcher(trimmed);
            if (lrcMatch.find()) {
                int minutes = Integer.parseInt(lrcMatch.group(1));
                int seconds = Integer.parseInt(lrcMatch.group(2));
                int millis = Integer.parseInt(lrcMatch.group(3));
                String text = lrcMatch.group(4).trim();
                int totalTick = ((minutes * 60 + seconds) * 1000 + millis) / 50;
                result.put(totalTick, text);
            }
        }
        return result;
    }

    /**
     * 解析酷狗 KRC language 字段（已 base64 解码的 JSON）为 <b>翻译 + 罗马音</b>两组映射。
     * <p>
     * JSON 结构（数组）：
     * <pre>
     * { "content": [
     *     { "lyricContent": [...], "type": 0, "language": 0 },  // 罗马音/拼音/原语言
     *     { "lyricContent": [...], "type": 1, "language": 0 }   // 中文翻译（仅部分歌有）
     * ] }
     * </pre>
     * <p>
     * 路由规则：
     * <ul>
     *   <li>type=1 → {@code translation}（中文翻译）</li>
     *   <li>type=0 → {@code romaji}（罗马音/拼音/原语言）</li>
     *   <li>type 字段缺失 → translation（兼容旧 KRC）</li>
     * </ul>
     * <p>
     * 对齐策略：优先用 KRC 自带 {@code timePoint} 直接算 tick（与主歌词同坐标系，最准），
     * fallback 到按行 1:1 对齐或 word-level 贪心均分（兼容没 timePoint 的老 KRC）。
     *
     * @param json 酷狗 language 字段解码后的 JSON
     * @param mainLyrics 主歌词 tick 序列（按出现顺序），用于 fallback 对齐
     */
    public static KrcLyrics parseKrcLyrics(
            String json,
            Int2ObjectSortedMap<String> mainLyrics) {
        Int2ObjectSortedMap<String> translation = new Int2ObjectRBTreeMap<>();
        Int2ObjectSortedMap<String> romaji = new Int2ObjectRBTreeMap<>();

        if (StringUtils.isBlank(json)) {
            return new KrcLyrics(translation, romaji);
        }
        try {
            JsonElement rootElem = JsonParser.parseString(json);
            JsonArray content;
            if (rootElem.isJsonObject()) {
                var root = rootElem.getAsJsonObject();
                if (!root.has("content")) return new KrcLyrics(translation, romaji);
                content = root.getAsJsonArray("content");
            } else if (rootElem.isJsonArray()) {
                content = rootElem.getAsJsonArray();
            } else {
                return new KrcLyrics(translation, romaji);
            }

            for (var elem : content) {
                if (!elem.isJsonObject()) continue;
                var obj = elem.getAsJsonObject();
                if (!obj.has("lyricContent")) continue;

                int type = obj.has("type") ? obj.get("type").getAsInt() : -1;
                Int2ObjectSortedMap<String> target = (type == 0) ? romaji : translation;
                // 区分 KRC type：type=0 是罗马音/拼音（同行不同 script，应该嗅探验证），
                // type=1 是翻译（跨语言/跨 script，不该用 content overlap 验证）。
                boolean isRomaji = (type == 0);

                var arr = obj.getAsJsonArray("lyricContent");
                List<KrcTimedEntry> entries = collectEntries(arr);
                if (entries.isEmpty()) continue;

                alignEntries(entries, mainLyrics, target, isRomaji);
            }
        } catch (Exception e) {
            KuGouLogger.warn(
                    "[NetMusicKuGou] parseKrcLyrics failed: {}", e.getMessage());
        }
        return new KrcLyrics(translation, romaji);
    }

    private static List<KrcTimedEntry> collectEntries(JsonArray arr) {
        List<KrcTimedEntry> entries = new ArrayList<>();
        for (var item : arr) {
            String text = null;
            long timePoint = -1L;
            if (item.isJsonObject()) {
                var io = item.getAsJsonObject();
                if (io.has("content") && !io.get("content").isJsonNull()) {
                    var ce = io.get("content");
                    if (ce.isJsonPrimitive()) {
                        text = ce.getAsString();
                    } else if (ce.isJsonArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (var cj : ce.getAsJsonArray()) {
                            if (cj != null && cj.isJsonPrimitive()) {
                                String s = cj.getAsString();
                                if (s != null && !s.isEmpty()) {
                                    if (sb.length() > 0) sb.append(' ');
                                    sb.append(s);
                                }
                            }
                        }
                        text = sb.toString();
                    }
                }
                if (text == null) {
                    text = io.toString();
                }
                if (io.has("timePoint") && io.get("timePoint").isJsonPrimitive()) {
                    timePoint = io.get("timePoint").getAsLong();
                }
            } else if (item.isJsonArray()) {
                var inner = item.getAsJsonArray();
                StringBuilder sb = new StringBuilder();
                for (var je : inner) {
                    if (je != null && je.isJsonPrimitive()) {
                        String s = je.getAsString();
                        if (s != null && !s.isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(s);
                        }
                    }
                }
                text = sb.toString();
            } else if (item.isJsonPrimitive()) {
                text = item.getAsString();
            } else {
                continue;
            }
            // 保留所有条目以维持与主歌词的索引对应关系
            // 空条目和含":"的条目不能丢弃，否则会破坏 1:1 对齐
            if (text == null) text = "";
            entries.add(new KrcTimedEntry(timePoint, text));
        }
        return entries;
    }

    /**
     * 把 entries 写入 target。优先用 KRC 自带 timePoint 直接对齐，否则 fallback 到 1:1 / 贪心均分。
     * <p>
     * 结束后会跑一次"内容嗅探"重对齐 + 验证（对 type=0 罗马音和 type=1 翻译<b>都做</b>）：
     * 1) 对每个 target entry 找最佳匹配 LRC 主歌词行，取中位数偏移，
     *    如果得分提升 ≥20%（或旧得分 0 且新得分 > 0 对翻译）就重写 target。
     * 2) 如果 KRC 内容跟 LRC 主歌词字符重叠太少（说明对齐错位严重），就 <b>清空 target</b>，
     *    让 renderer 走"不显示翻译/音译"的分支。
     *
     * @param isRomaji  true=type=0 罗马音（要嗅探验证）/ false=type=1 翻译（也要嗅探重对齐）
     */
    private static void alignEntries(List<KrcTimedEntry> entries,
                                      Int2ObjectSortedMap<String> mainLyrics,
                                      Int2ObjectSortedMap<String> target,
                                      boolean isRomaji) {
        int validTimePoints = 0;
        for (var en : entries) {
            if (en.timePoint >= 0) validTimePoints++;
        }
        if (validTimePoints > 0) {
            int written = 0;
            int[] mainTicks = ticksOf(mainLyrics);
            for (var en : entries) {
                if (en.timePoint < 0) continue;
                // 跳过空条目和 header 条目
                if (en.text == null || en.text.trim().isEmpty()) continue;
                if (isHeaderEntry(en.text)) continue;
                int tick = (int) (en.timePoint / 50);
                // 把 timePoint 对齐到**最近的（且不大于）**主歌词 tick，
                // 避免 timePoint 与主歌词 tick 有微小偏移导致 desync。
                int snapped = snapToMainTick(tick, mainTicks);
                target.put(snapped, en.text);
                written++;
            }
            // timePoint 路径也要做内容嗅探重对齐 + 验证：
            // 很多 KRC 的 timePoint 跟 LRC tick 坐标系不一致（或 type=0/type=1 内容互相错位），
            // 对 type=0 罗马音和 type=1 翻译都做嗅探（type=1 翻译也经常错位）。
            refineByContentSniffing(entries, mainLyrics, target, isRomaji);
        } else {
            alignByLine(entries, mainLyrics, target);
            // ===== Strategy 4: 内容嗅探（content-sniffing）=====
            // KRC 的内容跟 LRC 主歌词的索引经常不对齐（比如 KRC 缺前 4 行，
            // Strategy 1/2/3 只能做"等量对齐"，无法识别这种偏移。
            // 找出"哪条 KRC entry 跟哪条 LRC 主歌词最匹配"，取中位数偏移，重做对齐。
            // 对 type=0/1 都做内容嗅探
            refineByContentSniffing(entries, mainLyrics, target, isRomaji);
        }
    }

    private static int snapToMainTick(int tick, int[] mainTicks) {
        if (mainTicks == null || mainTicks.length == 0) return tick;
        int lo = 0, hi = mainTicks.length - 1, best = mainTicks[0];
        if (tick < mainTicks[0]) return mainTicks[0];
        if (tick >= mainTicks[hi]) return mainTicks[hi];
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (mainTicks[mid] <= tick) {
                best = mainTicks[mid];
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /**
     * 与主歌词按行对齐（重写版 v2）。
     * <p>
     * 核心策略（按优先级）：
     * <ol>
     *   <li>直接 1:1：entries 数量 == 主歌词总行数（含元数据行），逐行映射</li>
     *   <li>非元数据 1:1：entries 数量 == 非元数据行数，仅映射到非元数据行</li>
     *   <li>启发式：最佳努力对齐（跳过 header、处理数量差）</li>
     * </ol>
     * 前提：{@link #collectEntries} 已保留所有条目（含空条目），维持索引对应关系。
     */
    private static void alignByLine(List<KrcTimedEntry> entries,
                                    Int2ObjectSortedMap<String> mainLyrics,
                                    Int2ObjectSortedMap<String> target) {
        int[] mainTicks = mainLyrics.keySet().toIntArray();
        if (mainTicks.length == 0 || entries.isEmpty()) return;

        // ===== 跳过开头的 header entries =====
        int offset = 0;
        while (offset < entries.size() && isHeaderEntry(entries.get(offset).text)) {
            offset++;
        }
        int remaining = entries.size() - offset;

        // ===== 策略 1：直接 1:1（entries 数量 == 主歌词总行数） =====
        if (remaining == mainTicks.length) {
            for (int i = 0; i < mainTicks.length; i++) {
                String text = entries.get(offset + i).text;
                if (text != null && !text.trim().isEmpty() && !isHeaderEntry(text)) {
                    target.put(mainTicks[i], text);
                }
            }
            return;
        }

        List<Integer> nonMetaTicks = new ArrayList<>();
        boolean[] isMeta = new boolean[mainTicks.length];
        for (int i = 0; i < mainTicks.length; i++) {
            String text = mainLyrics.get(mainTicks[i]);
            isMeta[i] = isMetadataLine(text);
            if (!isMeta[i]) nonMetaTicks.add(mainTicks[i]);
        }
        if (!nonMetaTicks.isEmpty()) {
            int firstNonMetaIdx = -1;
            for (int i = 0; i < mainTicks.length; i++) {
                if (!isMeta[i]) { firstNonMetaIdx = i; break; }
            }
            if (firstNonMetaIdx >= 0) {
                boolean followedByMeta = false;
                for (int j = firstNonMetaIdx + 1; j < Math.min(firstNonMetaIdx + 4, mainTicks.length); j++) {
                    if (isMeta[j]) { followedByMeta = true; break; }
                }
                if (followedByMeta) {
                    isMeta[firstNonMetaIdx] = true;
                    nonMetaTicks.remove(0);
                }
            }
        }
        int nonMetaCount = nonMetaTicks.size();
        if (nonMetaCount <= 0) return;

        // ===== 策略 2：非元数据 1:1（entries 数量 == 非元数据行数） =====
        if (remaining == nonMetaCount) {
            for (int i = 0; i < nonMetaCount; i++) {
                String text = entries.get(offset + i).text;
                if (text != null && !text.trim().isEmpty() && !isHeaderEntry(text)) {
                    target.put(nonMetaTicks.get(i), text);
                }
            }
            return;
        }

        // ===== 策略 3：启发式对齐 =====
        // 跳过开头空 entries（对应元数据行的空翻译）
        while (offset < entries.size() && entries.get(offset).text.trim().isEmpty()) {
            offset++;
        }
        remaining = entries.size() - offset;

        int entryIdx = offset;
        int tickStart = 0;
        if (remaining > nonMetaCount) {
            entryIdx += remaining - nonMetaCount;
        } else if (remaining < nonMetaCount) {
            tickStart = nonMetaCount - remaining;
        }

        for (int i = tickStart; i < nonMetaCount && entryIdx < entries.size(); i++) {
            String text = entries.get(entryIdx).text;
            if (text != null && !text.trim().isEmpty() && !isHeaderEntry(text)) {
                target.put(nonMetaTicks.get(i), text);
            }
            entryIdx++;
        }
    }

    /**
     * Strategy 4：内容嗅探 + 重对齐 + 验证。
     * <p>
     * 做三件事：
     * <ol>
     *   <li><b>重对齐（重要）</b>：对每个 target entry（按 nonMetaTicks 顺序），
     *       找与之最匹配的 LRC 主歌词行，收集 (bestLrcIdx - currentIdx) 偏移，
     *       取中位数。如果中位数偏移能改善得分 ≥20%，<b>重写 target</b>。
     *       这能修复 KRC type=0/type=1 互相错位、或 KRC 缺前 N 行的情况
     *       （如《怪盗ハッチさん》的 type=1 数组偏移了 3 行）。</li>
     *   <li><b>验证当前 target</b>：遍历 target 里每条 (tick→text)，跟主歌词 tick 对应行的
     *       kanji/kana 重叠分求和。如果总分低于阈值，说明 KRC 内容跟 LRC 主歌词几乎对不上，
     *       <b>直接清空 target</b>（renderer 走"不显示翻译/音译"分支）。</li>
     *   <li>（仅 type=0 罗马音严格嗅探）</li>
     * </ol>
     * <p>
     * 这三件事对 timePoint 路径 <b>都要做</b>：很多 KRC 的 timePoint 跟 LRC tick 坐标系不一致
     * （或 type=0/type=1 内容互相错位），光靠 timePoint 拼出来的不一定对得上 LRC 主歌词。
     *
     * @param entries    KRC 原始条目（含 header/空条目）
     * @param mainLyrics 主歌词（key=tick, value=日文原文）
     * @param target     当前已对齐的 map（会被清空或重写）
     * @param isRomaji   true=type=0 罗马音，false=type=1 中文翻译
     */
    private static void refineByContentSniffing(List<KrcTimedEntry> entries,
                                                 Int2ObjectSortedMap<String> mainLyrics,
                                                 Int2ObjectSortedMap<String> target,
                                                 boolean isRomaji) {
        int[] mainTicks = mainLyrics.keySet().toIntArray();
        if (mainTicks.length == 0 || entries.isEmpty() || target.isEmpty()) return;

        List<Integer> nonMetaTicks = new ArrayList<>();
        List<String> nonMetaLines = new ArrayList<>();
        for (int i = 0; i < mainTicks.length; i++) {
            String text = mainLyrics.get(mainTicks[i]);
            if (!isMetadataLine(text) && text != null && !text.isEmpty()) {
                nonMetaTicks.add(mainTicks[i]);
                nonMetaLines.add(text);
            }
        }
        if (nonMetaTicks.isEmpty()) return;

        int headerSkip = 0;
        while (headerSkip < entries.size() && isHeaderEntry(entries.get(headerSkip).text)) {
            headerSkip++;
        }
        List<String> krcLines = new ArrayList<>();
        for (int i = headerSkip; i < entries.size(); i++) {
            String t = entries.get(i).text;
            if (t == null || t.trim().isEmpty()) continue;
            if (isHeaderEntry(t)) continue;
            krcLines.add(t);
        }
        if (krcLines.isEmpty()) return;

        // content-sniffing 的 kanji/kana 重叠打分永远是 0，会误判正确的翻译为"错位"清空。
        // 这种情况必须信任行数对齐（Strategy 1/2/3 已经处理），跳过嗅探。
        if (!isMainLyricsCjk(nonMetaLines)) {
            return;
        }

        // ===== 步骤 1：单调贪心重对齐（针对 KRC type=1 翻译与 LRC 主歌词错位）=====
        // median offset 假设错位是均匀的，但 KRC type=1 翻译常常跟 LRC 主歌词
        // 不是固定偏移（副歌、重复段会拉偏 median）。用单调贪心：按 KRC entry 顺序
        try {
            if (krcLines.size() < 3) {
                // 条目太少，跳过重对齐
            } else {
                // 1a) 单调贪心：按 krcLines 顺序，对每个 entry 在剩余 LRC 中找最佳匹配
                Int2ObjectSortedMap<String> newTarget = new Int2ObjectRBTreeMap<>();
                java.util.Set<Integer> usedLrcIdxs = new java.util.HashSet<>();
                int lrcSearchStart = 0;
                int matched = 0;
                for (int k = 0; k < krcLines.size(); k++) {
                    String text = krcLines.get(k);
                    int bestLrcIdx = -1;
                    int bestOverlap = 0;
                    for (int j = lrcSearchStart; j < nonMetaLines.size(); j++) {
                        if (usedLrcIdxs.contains(j)) continue;
                        int s = contentOverlap(text, nonMetaLines.get(j));
                        if (s > bestOverlap) {
                            bestOverlap = s;
                            bestLrcIdx = j;
                        }
                    }
                    if (bestLrcIdx >= 0 && bestOverlap > 0) {
                        newTarget.put(nonMetaTicks.get(bestLrcIdx), text);
                        usedLrcIdxs.add(bestLrcIdx);
                        // 保持单调：下一个 entry 只能从 bestLrcIdx+1 开始找
                        // 但允许小范围回退（最多 3），以应对 KRC 偶有跳行
                        lrcSearchStart = Math.max(lrcSearchStart, bestLrcIdx - 3);
                        matched++;
                    } else {
                        // 找不到匹配时，lrcSearchStart 不变（允许回退找下一个 entry）
                    }
                }

                if (matched < 3) {
                    // ⇒ 重对齐结果不可信，保留 Strategy 1/2/3 已经在 target 里的旧值，不动。
                } else {
                    int oldScore = computeTotalOverlap(target, mainLyrics);
                    int newScore = computeTotalOverlap(newTarget, mainLyrics);

                    if (newScore > oldScore) {
                        target.clear();
                        target.putAll(newTarget);
                    }
                }
            }
        } catch (Exception ex) {
            KuGouLogger.warn("[NetMusicKuGou] content-sniffing re-align failed: {}", ex.getMessage());
        }

        // 分数 ≤ 1 时清空 target。
        int targetScore = 0;
        int targetChecked = 0;
        for (var e : target.int2ObjectEntrySet()) {
            String mainText = mainLyrics.get(e.getIntKey());
            if (mainText == null || isMetadataLine(mainText) || mainText.isEmpty()) continue;
            targetScore += contentOverlap(e.getValue(), mainText);
            targetChecked++;
        }
        int krcCount = krcLines.size();
        int nonMetaCount = nonMetaLines.size();
        int quality = scoreKrcQuality(krcCount, nonMetaCount, target.size(),
                mainTicks.length, targetChecked, targetScore, krcLines, nonMetaLines);

        if (quality <= 1) {
            KuGouLogger.warn(
                    "[NetMusicKuGou]   content-sniffing: KRC quality too low ({}), clearing target to hide misaligned translation/romaji",
                    quality);
            target.clear();
        }
    }

    /**
     * 计算 target 中所有 (tick→text) 与对应主歌词行的 kanji/kana 总重叠分。
     * 用于重对齐前后的得分对比。
     */
    private static int computeTotalOverlap(Int2ObjectSortedMap<String> target,
                                           Int2ObjectSortedMap<String> mainLyrics) {
        int score = 0;
        for (var e : target.int2ObjectEntrySet()) {
            String mainText = mainLyrics.get(e.getIntKey());
            if (mainText == null || isMetadataLine(mainText) || mainText.isEmpty()) continue;
            score += contentOverlap(e.getValue(), mainText);
        }
        return score;
    }

    private static int computeOverlapScore(List<String> krcLines, List<String> lrcLines, int offset) {
        int score = 0;
        for (int i = 0; i < krcLines.size(); i++) {
            int lrcIdx = i + offset;
            if (lrcIdx < 0 || lrcIdx >= lrcLines.size()) continue;
            score += contentOverlap(krcLines.get(i), lrcLines.get(lrcIdx));
        }
        return score;
    }

    /**
     * 给当前 KRC 内容打分（1~5），高分表示 KRC 内容看起来跟 LRC 主歌词对得上。
     * <p>
     * 借鉴 KuGou 的 {@code recommendationLevel}（1~5 星推荐）思路，
     * 把"行数差异 / target 填充率 / 内容重叠"等指标合并成一个直观的分数。
     * <ul>
     *   <li><b>0 分</b>：KRC 行数 &gt; 2×LRC 行数（KRC 远多于 LRC = 拆字/重复 = 不可信，
     *       借鉴 {@code hasLargeDurationDiff} 思路）<b>或</b> target 里没有任何 entry 对应到主歌词 tick
     *       （timePoint 路径下极端错位）</li>
     *   <li><b>1 分</b>：底分</li>
     *   <li><b>+1</b>：行数差异 ≤ 33%（兼容 KRC 部分翻译：KRC &lt; LRC 是合法的）</li>
     *   <li><b>+1</b>：target 填充 ≥ mainTicks/2（Strategy 1/2/3 成功填了大部分）</li>
     *   <li><b>+1</b>：KRC 行跟 LRC 主歌词的 kanji/kana 重叠分 &gt; 0
     *       （至少有几行字符能对得上）</li>
     * </ul>
     * 分数 ≤ 1 时认为 KRC 不可信，调用方应清空 target。
     */
    private static int scoreKrcQuality(int krcCount, int nonMetaCount, int targetSize,
                                       int mainTicksLength, int targetChecked, int targetScore,
                                       List<String> krcLines, List<String> nonMetaLines) {
        // KRC 行数远多于 LRC（≥ 2×）= 拆字/重复 = 完全不可信（直接 0 分，调用方会清空 target）
        // 注意：只防 KRC > LRC 的情况；KRC < LRC 是合法的（部分翻译：中文歌只有部分外文翻译）
        if (krcCount > nonMetaCount * 2) {
            return 0;
        }
        // target 里没有 entry 对应到主歌词 tick → 也清空
        if (targetChecked == 0) {
            return 0;
        }
        int score = 1;
        // +1: 行数匹配（≤ 33% 差异，兼容 KRC 少于 LRC 的部分翻译）
        int diff = Math.abs(krcCount - nonMetaCount);
        int matchDiff = Math.max(2, nonMetaCount / 3);
        if (diff <= matchDiff) score++;
        if (targetSize >= mainTicksLength / 2) score++;
        // +1: 内容嗅探重叠分 > 0
        if (targetScore > 0) score++;
        return Math.min(5, Math.max(1, score));
    }

    private static int contentOverlap(String s1, String s2) {
        if (s1 == null || s2 == null) return 0;

        boolean isRomaji = false;
        for (int i = 0; i < s1.length(); i++) {
            char c = s1.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                isRomaji = true;
                break;
            }
        }
        String processed1 = isRomaji ? romajiToKana(s1) : s1;

        Set<Integer> set1 = new HashSet<>();
        Set<Integer> set2 = new HashSet<>();
        for (int i = 0; i < processed1.length(); ) {
            int cp = processed1.codePointAt(i);
            if (isKana(cp) || isKanji(cp)) set1.add(cp);
            i += Character.charCount(cp);
        }
        for (int i = 0; i < s2.length(); ) {
            int cp = s2.codePointAt(i);
            if (isKana(cp) || isKanji(cp)) set2.add(cp);
            i += Character.charCount(cp);
        }
        set1.retainAll(set2);
        return set1.size();
    }

    /**
     * 简易 romaji → kana 转换。覆盖基本元音、清音、浊音、半浊音、拗音、拨音「ん」/「ン」等。
     * 长音、促音、外来语特殊写法不能完美处理，但对"内容嗅探"足够。
     */
    private static String romajiToKana(String romaji) {
        if (romaji == null || romaji.isEmpty()) return "";
        String lower = romaji.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < lower.length()) {
            boolean matched = false;
            if (i + 3 <= lower.length()) {
                String three = lower.substring(i, i + 3);
                String kana = ROMAJI_KANA_MAP.get(three);
                if (kana != null) {
                    sb.append(kana);
                    i += 3;
                    matched = true;
                }
            }
            if (!matched && i + 2 <= lower.length()) {
                String two = lower.substring(i, i + 2);
                String kana = ROMAJI_KANA_MAP.get(two);
                if (kana != null) {
                    sb.append(kana);
                    i += 2;
                    matched = true;
                }
            }
            if (!matched) {
                String one = lower.substring(i, i + 1);
                String kana = ROMAJI_KANA_MAP.get(one);
                if (kana != null) {
                    sb.append(kana);
                    i += 1;
                } else {
                    // 跳过空格/标点/未识别字符
                    i++;
                }
            }
        }
        return sb.toString();
    }

    private static final java.util.Map<String, String> ROMAJI_KANA_MAP = buildRomajiKanaMap();

    private static java.util.Map<String, String> buildRomajiKanaMap() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("a", "あ"); m.put("i", "い"); m.put("u", "う"); m.put("e", "え"); m.put("o", "お");
        m.put("ka", "か"); m.put("ki", "き"); m.put("ku", "く"); m.put("ke", "け"); m.put("ko", "こ");
        m.put("sa", "さ"); m.put("shi", "し"); m.put("su", "す"); m.put("se", "せ"); m.put("so", "そ");
        m.put("ta", "た"); m.put("chi", "ち"); m.put("tsu", "つ"); m.put("te", "て"); m.put("to", "と");
        m.put("na", "な"); m.put("ni", "に"); m.put("nu", "ぬ"); m.put("ne", "ね"); m.put("no", "の");
        m.put("ha", "は"); m.put("hi", "ひ"); m.put("fu", "ふ"); m.put("he", "へ"); m.put("ho", "ほ");
        m.put("ma", "ま"); m.put("mi", "み"); m.put("mu", "む"); m.put("me", "め"); m.put("mo", "も");
        m.put("ya", "や"); m.put("yu", "ゆ"); m.put("yo", "よ");
        m.put("ra", "ら"); m.put("ri", "り"); m.put("ru", "る"); m.put("re", "れ"); m.put("ro", "ろ");
        m.put("wa", "わ"); m.put("wo", "を"); m.put("n", "ん");
        m.put("ga", "が"); m.put("gi", "ぎ"); m.put("gu", "ぐ"); m.put("ge", "げ"); m.put("go", "ご");
        m.put("za", "ざ"); m.put("ji", "じ"); m.put("zu", "ず"); m.put("ze", "ぜ"); m.put("zo", "ぞ");
        m.put("da", "だ"); m.put("di", "ぢ"); m.put("du", "づ"); m.put("de", "で"); m.put("do", "ど");
        m.put("ba", "ば"); m.put("bi", "び"); m.put("bu", "ぶ"); m.put("be", "べ"); m.put("bo", "ぼ");
        m.put("pa", "ぱ"); m.put("pi", "ぴ"); m.put("pu", "ぷ"); m.put("pe", "ぺ"); m.put("po", "ぽ");
        m.put("sha", "しゃ"); m.put("shu", "しゅ"); m.put("sho", "しょ");
        m.put("cha", "ちゃ"); m.put("chu", "ちゅ"); m.put("cho", "ちょ");
        m.put("ja", "じゃ"); m.put("ju", "じゅ"); m.put("jo", "じょ");
        m.put("kyo", "きょ"); m.put("kyu", "きゅ"); m.put("kya", "きゃ");
        m.put("nyo", "にょ"); m.put("nyu", "にゅ"); m.put("nya", "にゃ");
        m.put("hyo", "ひょ"); m.put("hyu", "ひゅ"); m.put("hya", "ひゃ");
        m.put("myo", "みょ"); m.put("myu", "みゅ"); m.put("mya", "みゃ");
        m.put("ryo", "りょ"); m.put("ryu", "りゅ"); m.put("rya", "りゃ");
        m.put("gyo", "ぎょ"); m.put("gyu", "ぎゅ"); m.put("gya", "ぎゃ");
        m.put("byo", "びょ"); m.put("byu", "びゅ"); m.put("bya", "びゃ");
        m.put("pyo", "ぴょ"); m.put("pyu", "ぴゅ"); m.put("pya", "ぴゃ");
        return m;
    }

    private static boolean isKanji(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF);
    }

    private static boolean isKana(int cp) {
        return (cp >= 0x3040 && cp <= 0x309F)
                || (cp >= 0x30A0 && cp <= 0x30FF);
    }

    /**
     * 判断主歌词是否"主要是 CJK"。content-sniffing 用 kanji/kana 重叠打分；
     * 如果主歌词全是非 CJK 文本（意大利语/英语/拉丁转写等），重叠永远是 0，会把
     * 正确的翻译误判为"错位"清空。这里要求非空字符里至少 30% 是 CJK 才信任嗅探。
     */
    private static boolean isMainLyricsCjk(List<String> lines) {
        if (lines == null || lines.isEmpty()) return false;
        int cjk = 0;
        int total = 0;
        for (String s : lines) {
            if (s == null) continue;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                if (cp <= 0x20) { i += Character.charCount(cp); continue; } // 跳过空白/控制字符
                total++;
                if (isKanji(cp) || isKana(cp)) cjk++;
                i += Character.charCount(cp);
            }
        }
        if (total == 0) return false;
        return (cjk * 10) >= (total * 3);
    }

    private static boolean isHeaderEntry(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("翻译") || text.contains("以下歌词")
                || text.contains("文曲") || text.contains("提供")
                || text.contains("AI") || text.startsWith("Song:")
                || (text.contains(" - ") && text.length() > 15)
                || text.contains("(feat.") || text.contains(" feat. ")
                || text.contains("(Feat.") || text.contains(" Feat. ")
                || text.contains("【") || text.startsWith("Title:")
                || text.contains("著作权") || text.contains("版权");
    }

    /**
     * 带时间戳的 KRC 翻译条目。timePoint < 0 表示没有时间戳（需要 fallback 对齐）。
     */
    private static final class KrcTimedEntry {
        final long timePoint;
        final String text;

        KrcTimedEntry(long timePoint, String text) {
            this.timePoint = timePoint;
            this.text = text;
        }
    }

    public static final class KrcLyrics {
        public final Int2ObjectSortedMap<String> translation;
        public final Int2ObjectSortedMap<String> romaji;

        public KrcLyrics(Int2ObjectSortedMap<String> translation, Int2ObjectSortedMap<String> romaji) {
            this.translation = translation == null ? new Int2ObjectRBTreeMap<>() : translation;
            this.romaji = romaji == null ? new Int2ObjectRBTreeMap<>() : romaji;
        }
    }

    public static final class KuGouLyricData {
        public final LyricRecord record;
        public final Int2ObjectSortedMap<String> romaji;

        public KuGouLyricData(LyricRecord record, Int2ObjectSortedMap<String> romaji) {
            this.record = record;
            this.romaji = romaji == null ? new Int2ObjectRBTreeMap<>() : romaji;
        }
    }

    /** 取排序歌词 map 的 tick 键数组（多处共用，避免重复写 keySet().toIntArray()）。 */
    private static int[] ticksOf(Int2ObjectSortedMap<String> map) {
        return map.keySet().toIntArray();
    }
}
