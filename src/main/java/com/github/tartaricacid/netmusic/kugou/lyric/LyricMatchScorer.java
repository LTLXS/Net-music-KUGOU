package com.github.tartaricacid.netmusic.kugou.lyric;

import java.util.HashSet;
import java.util.Set;

/**
 * 歌词候选本地匹配评分。移植自 EchoMusic 桌面端 {@code songMatching.ts}。
 * <p>
 * 酷狗搜索接口返回的 {@code score} 只是服务端粗排，存在「同名不同歌」「伴奏/纯音乐占位」等情况。
 * 这里用标题相似度 + 歌手匹配 + 时长差异做本地二次评分，与父模组实际播放的歌曲对齐。
 * <p>
 * 加权总分：{@code 0.55 * title + 0.30 * artist + 0.15 * duration}，取值 0..1。
 */
public final class LyricMatchScorer {

    private LyricMatchScorer() {
    }

    /**
     * 对单个候选打分。
     *
     * @param candSong       候选歌名（来自酷狗 candidates）
     * @param candSinger     候选歌手
     * @param candDurationMs 候选时长（毫秒），未知传 0
     * @param querySong      实际播放歌名
     * @param querySinger    实际播放歌手
     * @param queryDurationMs 实际播放时长（毫秒），未知传 0
     * @return 0..1 的匹配分，越高越匹配
     */
    public static double scoreCandidate(String candSong, String candSinger, int candDurationMs,
                                        String querySong, String querySinger, int queryDurationMs) {
        double title = titleScore(querySong, candSong);
        double artist = artistScore(querySinger, candSinger);
        double duration = durationScoreMs(queryDurationMs, candDurationMs);
        return 0.55 * title + 0.30 * artist + 0.15 * duration;
    }

    /** 文本归一化：转小写 → 去括号内容 → 仅保留字母数字 → trim。 */
    static String normalizeForCompare(String s) {
        if (s == null) return "";
        String n = s.toLowerCase();
        n = n.replaceAll("\\([^)]*\\)", " ");
        n = n.replaceAll("\\[[^]]*\\]", " ");
        n = n.replaceAll("[^\\p{L}\\p{N}]", "");
        return n.trim();
    }

    /** 标题评分：完全相等 1.0 / 包含 0.85 / 否则字符集 Jaccard；任一为空返回 0.5 中性分。 */
    static double titleScore(String queryTitle, String candTitle) {
        String a = normalizeForCompare(queryTitle);
        String b = normalizeForCompare(candTitle);
        if (a.isEmpty() || b.isEmpty()) return 0.5;
        if (a.equals(b)) return 1.0;
        if (a.contains(b) || b.contains(a)) return 0.85;
        return jaccardCharSimilarity(a, b);
    }

    /** 歌手评分：完全相等 1.0 / 多歌手拆分后任一部分包含 0.9 / 无信息 0.5 中性 / 否则 0。 */
    static double artistScore(String queryArtist, String candArtist) {
        String q = normalizeForCompare(queryArtist);
        if (q.isEmpty() || candArtist == null || candArtist.isEmpty()) return 0.5;
        if (q.equals(normalizeForCompare(candArtist))) return 1.0;
        // 拆分需在归一化前进行：归一化会去掉分隔符
        String[] parts = candArtist.split("[/、&,]+");
        for (String p : parts) {
            String pp = normalizeForCompare(p);
            if (pp.isEmpty()) continue;
            if (q.contains(pp) || pp.contains(q)) return 0.9;
        }
        return 0.0;
    }

    /** 时长评分：差 ≤5s 1.0 / ≤15s 0.5 / 否则 0；任一未知返回 0.5 中性分。 */
    static double durationScoreMs(int queryMs, int candMs) {
        if (queryMs <= 0 || candMs <= 0) return 0.5;
        long diff = Math.abs((long) queryMs - candMs);
        if (diff <= 5000) return 1.0;
        if (diff <= 15000) return 0.5;
        return 0.0;
    }

    /** 字符级 Jaccard 相似度（code point 集合交/并）。 */
    private static double jaccardCharSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        Set<Integer> sa = toCodePointSet(a);
        Set<Integer> sb = toCodePointSet(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        int inter = 0;
        for (int cp : sa) if (sb.contains(cp)) inter++;
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    private static Set<Integer> toCodePointSet(String s) {
        Set<Integer> set = new HashSet<>();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            set.add(cp);
            i += Character.charCount(cp);
        }
        return set;
    }
}
