package com.github.tartaricacid.netmusic.kugou.util;

import io.nayuki.qrcodegen.QrCode;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QR 码渲染器 — 使用内嵌的 Nayuki qrcodegen 库（class 文件直接打包进 JAR）
 */
public final class QrCodeRenderer {

    private QrCodeRenderer() {}

    private static final Logger LOG = LoggerFactory.getLogger("NetMusic-KuGou");

    public static boolean[][] generate(String content, int targetSize) {
        try {
            LOG.info("QR generating: len={}", content.length());
            QrCode qr = QrCode.encodeText(content, QrCode.Ecc.MEDIUM);
            int size = qr.size;
            boolean[][] matrix = new boolean[size][size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    matrix[y][x] = qr.getModule(x, y);
            LOG.info("QR OK: {}x{}", size, size);
            return matrix;
        } catch (Exception e) {
            LOG.error("QR FAILED", e);
            return null;
        }
    }

    public static boolean[][] generate(String content) { return generate(content, 200); }

    public static void render(GuiGraphics graphics, int x, int y, boolean[][] matrix, int targetSize) {
        if (matrix == null || matrix.length == 0) return;
        int n = matrix.length;
        int ps = Math.max(1, Math.round((float) targetSize / n));
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                int color = matrix[row][col] ? 0xFF000000 : 0xFFFFFFFF;
                graphics.fill(x + col * ps, y + row * ps,
                        x + col * ps + ps, y + row * ps + ps, color);
            }
        }
    }
}
