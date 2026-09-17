package com.github.tartaricacid.netmusic.kugou.util;

import io.nayuki.qrcodegen.QrCode;
import net.minecraft.client.gui.GuiGraphics;

public final class QrCodeRenderer {

    private QrCodeRenderer() {}

    public static boolean[][] generate(String content) {
        QrCode qr = QrCode.encodeText(content, QrCode.Ecc.MEDIUM);
        int n = qr.size;
        boolean[][] matrix = new boolean[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                matrix[y][x] = qr.getModule(x, y);
            }
        }
        return matrix;
    }

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
