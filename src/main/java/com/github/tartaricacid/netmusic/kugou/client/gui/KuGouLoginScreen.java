package com.github.tartaricacid.netmusic.kugou.client.gui;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.support.LoginStateManager;
import com.github.tartaricacid.netmusic.kugou.api.KuGouLoginApi;
import com.github.tartaricacid.netmusic.kugou.config.ClientConfig;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.util.QrCodeRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class KuGouLoginScreen extends Screen {
    private final Screen parent;
    private String qrKey = "";
    private String qrUrl = "";
    private boolean[][] qrMatrix = null;
    private boolean qrChecking = false;
    private long qrStartTime = 0;
    private Component status = Component.empty();
    private Component copyFeedback = Component.empty();
    private volatile Thread qrCheckThread = null;

    public KuGouLoginScreen(Screen parent) {
        super(Component.translatable("netmusic_kugou.login.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelWidth = 220;
        int panelX = (this.width - panelWidth) / 2;
        // 窗口不够高时自动压缩面板高度，确保标题区和底部按钮都不被裁
        int panelHeight = Math.min(210, this.height - 50);
        int panelY = Math.max(32, (this.height - panelHeight) / 2);

        if (qrKey.isEmpty() || !qrChecking) {
            generateQrCode();
        }

        int copyBtnY = panelY + panelHeight - 48;
        int closeBtnY = panelY + panelHeight - 28;

        Button copyButton = Button.builder(Component.translatable("netmusic_kugou.login.copy_link"), button -> copyQrUrl())
                .pos(panelX + 35, copyBtnY)
                .size(70, 20)
                .build();
        this.addRenderableWidget(copyButton);

        Button refreshButton = Button.builder(Component.translatable("netmusic_kugou.login.refresh"), button -> generateQrCode())
                .pos(panelX + 115, copyBtnY)
                .size(70, 20)
                .build();
        this.addRenderableWidget(refreshButton);

        Button closeButton = Button.builder(Component.translatable("netmusic_kugou.login.close"), button -> this.onClose())
                .pos((this.width - 60) / 2, closeBtnY)
                .size(60, 20)
                .build();
        this.addRenderableWidget(closeButton);
    }

    private void copyQrUrl() {
        if (qrUrl.isEmpty()) {
            copyFeedback = Component.translatable("netmusic_kugou.login.link_empty");
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(qrUrl);
        copyFeedback = Component.translatable("netmusic_kugou.login.copied");
    }

    private void generateQrCode() {
        status = Component.translatable("netmusic_kugou.login.getting_qr");
        qrChecking = false;
        qrKey = "";
        qrUrl = "";
        qrMatrix = null;

        KuGouLoginApi.fetchQrKey()
                .thenAccept(result -> {
                    this.minecraft.execute(() -> {
                        if (result.isSuccess()) {
                            qrKey = result.qrcode;
                            qrUrl = result.qrUrl;
                            KuGouLogger.info("QR URL received, length={}, url={}",
                                    qrUrl.length(), qrUrl.substring(0, Math.min(80, qrUrl.length())));
                            qrMatrix = QrCodeRenderer.generate(qrUrl);
                            KuGouLogger.info("QR matrix result: {}",
                                    qrMatrix != null ? "OK(" + qrMatrix.length + ")" : "null");
                            qrStartTime = System.currentTimeMillis();
                            status = Component.translatable("netmusic_kugou.login.scan_qr_prompt");
                            startQrCheck();
                        } else {
                            status = Component.translatable("netmusic_kugou.login.qr_failed", result.error);
                        }
                    });
                });
    }

    private void startQrCheck() {
        qrChecking = true;
        Thread t = new Thread(() -> {
            while (qrChecking && !qrKey.isEmpty()) {
                try {
                    Thread.sleep(3000);

                    if (!qrChecking || qrKey.isEmpty()) break;

                    final String keyToCheck = qrKey;
                    KuGouLoginApi.checkQrCode(keyToCheck)
                        .thenAccept(result -> {
                            this.minecraft.execute(() -> {
                                switch (result.status) {
                                    case 0:
                                        status = Component.translatable("netmusic_kugou.login.qr_expired");
                                        qrChecking = false;
                                        break;
                                    case 1:
                                        status = Component.translatable("netmusic_kugou.login.waiting_scan");
                                        break;
                                    case 2:
                                        status = Component.translatable("netmusic_kugou.login.scanned_confirm"); // 已扫码，请在手机上确认
                                        break;
                                    case 4:
                                        status = Component.translatable("netmusic_kugou.login.success");
                                        LoginStateManager.saveState();
                                        // 同步cookie到配置界面的VIP Cookie字段
                                        String cookieStr = KuGouConfig.getCookieString();
                                        if (!cookieStr.isEmpty()) {
                                            ClientConfig.VIP_COOKIE.set(cookieStr);
                                        }
                                        qrChecking = false;
                                        this.minecraft.setScreen(this.parent);
                                        break;
                                    default:
                                        status = Component.translatable("netmusic_kugou.login.status_abnormal", result.status, result.message);
                                }
                            });
                        });
                } catch (InterruptedException e) {
                    // 屏幕关闭 → onClose 会 interrupt 线程
                    qrChecking = false;
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "KuGouLoginScreen-QrCheck");
        qrCheckThread = t;
        t.start();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 修改 GuiGraphics 的矩阵且不复原，导致面板上方（标题/状态）文字飞出屏幕。
        drawBackground(graphics);

        int panelWidth = 220;
        int panelX = (this.width - panelWidth) / 2;
        // 窗口不够高时自动压缩面板高度，确保标题区和底部按钮都不被裁
        int panelHeight = Math.min(210, this.height - 50);
        int panelY = Math.max(32, (this.height - panelHeight) / 2);
        graphics.fill(panelX - 5, panelY - 5, panelX + panelWidth + 5, panelY + panelHeight - 5, 0xAA000000);

        graphics.drawCenteredString(this.font, Component.translatable("netmusic_kugou.login.title").getString(), this.width / 2, panelY - 30, 0xFFFFFF);

        renderQrCode(graphics, panelX, panelY, panelHeight);

        // 状态栏（面板上方）—— 必须在 super.render 之前绘制，
        // 否则按钮渲染会改动 GuiGraphics 坐标矩阵，使面板上方文字飞到屏幕外
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.status, this.width / 2, panelY - 18, 0xCCCCCC);
        }

        String loginStatus = KuGouConfig.userid.isEmpty() ? Component.translatable("netmusic_kugou.status.not_logged_in").getString() : Component.translatable("netmusic_kugou.status.logged_in", KuGouConfig.userid).getString();
        graphics.drawString(this.font, Component.translatable("netmusic_kugou.status.label").getString() + loginStatus, 10, this.height - 35, 0xAAAAAA, false);

        super.render(graphics, mouseX, mouseY, partialTick);

    }

    /**
     * 直接绘制背景，不触发 ScreenEvent.BackgroundRendered 事件（部分渲染mod会在其中
     * 修改 GuiGraphics 矩阵且不复原，导致面板上方文字飞出屏幕）。
     */
    private void drawBackground(GuiGraphics graphics) {
        if (this.minecraft != null && this.minecraft.level != null) {
            // 游戏内：直接绘制深色渐变背景，不调用 super.renderBackground，避免 post 事件
            graphics.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
        } else {
            this.renderBackground(graphics);
        }
    }

    private void renderQrCode(GuiGraphics graphics, int panelX, int panelY, int panelHeight) {
        if (qrMatrix == null || qrMatrix.length == 0) {
            if (qrUrl.isEmpty()) {
                graphics.drawString(this.font, Component.translatable("netmusic_kugou.login.loading").getString(), panelX, panelY + 50, 0xAAAAAA, false);
            } else {
                graphics.drawString(this.font, Component.translatable("netmusic_kugou.login.generate_failed").getString(), panelX, panelY + 55, 0xFF5555, false);
            }
            return;
        }

        final int qrSize = 90;
        int n = qrMatrix.length;
        int ps = Math.max(1, Math.round((float) qrSize / n));
        int actualQrSize = n * ps;
        int qrX = panelX + (220 - actualQrSize) / 2;
        int qrY = panelY + 15;

        graphics.fill(qrX - 3, qrY - 3, qrX + actualQrSize + 3, qrY + actualQrSize + 3, 0xFFFFFFFF);

        QrCodeRenderer.render(graphics, qrX, qrY, qrMatrix, qrSize);

        int textY = qrY + actualQrSize + 5;

        if (copyFeedback.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("netmusic_kugou.login.scan_prompt2").getString(), this.width / 2, textY, 0xAAAAAA);
        } else {
            graphics.drawCenteredString(this.font, copyFeedback, this.width / 2, textY, 0x55FF55);
        }

        long elapsed = (System.currentTimeMillis() - qrStartTime) / 1000;
        String timeStr = Component.translatable("netmusic_kugou.login.waited", elapsed).getString();
        int timeColor = elapsed > 120 ? 0xFF5555 : 0x888888;
        graphics.drawCenteredString(this.font, timeStr, this.width / 2, textY + 12, timeColor);
    }

    @Override
    public void onClose() {
        qrChecking = false;
        Thread t = qrCheckThread;
        if (t != null) {
            t.interrupt();
        }
        this.minecraft.setScreen(parent);
    }

    /**
     * 扫码登录期间不要让游戏暂停，否则父模组 NetMusic 会把方块音乐判定为"游戏已暂停"而停止播放。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 默认 {@code Screen.renderBlurredBackground} 会无条件调
     * {@code gameRenderer.processBlurEffect()} 把整个游戏世界模糊化（不只 PauseScreen）。
     * 扫码登录屏的二维码和文字会被模糊到几乎看不清。覆盖为 no-op 关闭模糊。
     * <p>注：Forge 1.20.1 的 Screen 类没有此方法，保留仅为文档/后续版本兼容。</p>
     */
    protected void renderBlurredBackground(GuiGraphics graphics, float partialTick) {
    }
}
