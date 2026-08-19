package com.github.tartaricacid.netmusic.kugou.lyric;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * KRC 酷狗私有二进制歌词格式解码器。
 * <p>
 * 流程：跳过 4 字节 "krc1" 魔数头 → 16 字节密钥循环 XOR → zlib 解压 → 剥除逐字标签得到 LRC。
 */
public final class KrcDecoder {

    /** KRC XOR 密钥（16 字节，不带 -128 减法） */
    private static final byte[] DECRYPT_KEY = {
            '@', 'G', 'a', 'w', '^', '2', 't', 'G',
            'Q', '6', '1', '-', (byte) 'Î', (byte) 'Ò', 'n', 'i'
    };

    private KrcDecoder() {}

    /**
     * 把 KRC base64 字符串解码为 LRC 文本。
     *
     * @param krcBase64 酷狗 /download 返回的 content 字段
     * @return 标准 LRC 文本，失败返回 {@code null}
     */
    public static String decodeToLrc(String krcBase64) {
        if (krcBase64 == null || krcBase64.isEmpty()) {
            return null;
        }
        try {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(krcBase64);
            } catch (IllegalArgumentException e) {
                raw = krcBase64.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            if (raw.length < 4) {
                return null;
            }
            byte[] body = new byte[raw.length - 4];
            System.arraycopy(raw, 4, body, 0, body.length);

            byte[] inflated = inflate(xorDecrypt(body));
            String text = stripKrcTags(new String(inflated, java.nio.charset.StandardCharsets.UTF_8));
            return (text == null || text.trim().isEmpty()) ? null : text;
        } catch (IllegalArgumentException | DataFormatException e) {
            KuGouLogger.warn("[NetMusicKuGou] KRC decode failed: {}", e.getMessage());
            return null;
        }
    }

    private static byte[] xorDecrypt(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ DECRYPT_KEY[i % DECRYPT_KEY.length]);
        }
        return out;
    }

    private static byte[] inflate(byte[] data) throws DataFormatException {
        Inflater inflater = new Inflater(false);
        try {
            inflater.setInput(data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(data.length * 2, 256));
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /**
     * 剥除 KRC 逐字标签 {@code <...>} 和 {@code (offset,len)} 字标签，保留 {@code [mm:ss.fff]} 时间标签。
     */
    private static String stripKrcTags(String krcText) {
        if (krcText.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(krcText.length());
        for (String line : krcText.split("\n")) {
            String trimmed = line.trim();
            int tagEnd = trimmed.indexOf(']');
            if (tagEnd < 0) {
                continue;
            }
            String body = trimmed.substring(tagEnd + 1)
                    .replaceAll("<[^>]*>", "")
                    .replaceAll("\\([^)]*\\)", "");
            sb.append(trimmed, 0, tagEnd + 1).append(body).append('\n');
        }
        return sb.toString();
    }
}
