package com.github.tartaricacid.netmusic.kugou.api;

import com.github.tartaricacid.netmusic.kugou.config.AudioQuality;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class KuGouApiHelper {

    private KuGouApiHelper() {}

    private static final AudioQuality[] QUALITY_ORDER_ASC = new AudioQuality[]{
            AudioQuality.STANDARD,
            AudioQuality.HQ,
            AudioQuality.SQ_FLAC,
            AudioQuality.HIGH,
            AudioQuality.SUPER_DSD
    };

    public static String getStr(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonPrimitive()) return elem.getAsString();
        }
        return "";
    }

    public static String firstNonEmpty(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isEmpty()) return s;
        }
        return "";
    }

    public static int parseDuration(JsonObject item) {
        String[] fields = {"duration", "timelength", "timeLength", "Duration", "time"};
        for (String f : fields) {
            if (item.has(f) && !item.get(f).isJsonNull()) {
                try {
                    int v = item.get(f).getAsInt();
                    if (v > 100000) v = Math.round(v / 1000f);
                    return v;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    public static AudioQuality[] getQualityFallbackOrder(AudioQuality requested) {
        int idx = 1;
        if (requested != null) {
            for (int i = 0; i < QUALITY_ORDER_ASC.length; i++) {
                if (QUALITY_ORDER_ASC[i] == requested) { idx = i; break; }
            }
        }
        AudioQuality[] asc = new AudioQuality[idx + 1];
        System.arraycopy(QUALITY_ORDER_ASC, 0, asc, 0, idx + 1);
        AudioQuality[] desc = new AudioQuality[asc.length];
        for (int i = 0; i < asc.length; i++) desc[i] = asc[asc.length - 1 - i];
        return desc;
    }

    public static long parseAlbumId(String albumId) {
        if (albumId == null || albumId.isEmpty()) return 0;
        try { return Long.parseLong(albumId.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
