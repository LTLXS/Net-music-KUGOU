package com.github.tartaricacid.netmusic.kugou.client.gui;

import com.github.tartaricacid.netmusic.kugou.api.KuGouLoginApi;
import com.github.tartaricacid.netmusic.kugou.api.KuGouOpenLoginApi;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import com.github.tartaricacid.netmusic.kugou.support.LoginStateManager;
import com.github.tartaricacid.netmusic.kugou.util.QrCodeRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 酷狗登录界面：支持 4 种登录方式（酷狗扫码 / 手机验证码 / 账号密码 / 微信扫码）。
 酷狗扫码沿用原有 /v2/qrcode 流程；微信扫码沿用 login_wx_create / login_openplat 实现。
*/
public class KuGouLoginScreen extends Screen {

    public enum Method {
        KUGOU("netmusic_kugou.login.method.kugou"),
        SMS("netmusic_kugou.login.method.sms"),
        ACCOUNT("netmusic_kugou.login.method.account"),
        WECHAT("netmusic_kugou.login.method.wechat");

        public final String langKey;

        Method(String langKey) {
            this.langKey = langKey;
        }

        public boolean isQr() {
            return this == KUGOU || this == WECHAT;
        }
    }

    private static final Method[] METHODS = Method.values();
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 200;
    // 二维码显示尺寸：酷狗内部按模块重绘，第三方按贴图缩放，统一用较小的固定尺寸避免过大
    private static final int QR_SIZE = 90;

    private final Screen parent;
    private Method method = Method.KUGOU;

    private int panelX;
    private int panelY;
    private int panelH;

    private String qrKey = "";
    private String qrUrl = "";
    private boolean[][] qrMatrix = null;

    private QrImageTexture qrTexture = null;
    private KuGouOpenLoginApi.QrTicket thirdTicket = null;

    private volatile boolean polling = false;
    private volatile Thread pollThread = null;
    private long qrStartTime = 0;

    private Component status = Component.empty();
    private Component copyFeedback = Component.empty();

    private EditBox phoneBox = null;
    private EditBox codeBox = null;
    private EditBox userBox = null;
    private EditBox passBox = null;

    public KuGouLoginScreen(Screen parent) {
        super(Component.translatable("netmusic_kugou.login.title"));
        this.parent = parent;
    }

    public KuGouLoginScreen(Screen parent, Method method) {
        this(parent);
        if (method != null) {
            this.method = method;
        }
    }

    @Override
    protected void init() {
        this.panelH = Math.min(PANEL_H, Math.max(120, this.height - 74));
        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = Math.max(64, (this.height - this.panelH) / 2);

        addMethodTabs();

        if (method.isQr()) {
            buildQrWidgets();
        } else {
            buildFormWidgets();
        }

        this.addRenderableWidget(Button.builder(Component.translatable("netmusic_kugou.login.close"), b -> this.onClose())
                .pos((this.width - 60) / 2, panelY + panelH - 24)
                .size(60, 20)
                .build());
    }

    private void addMethodTabs() {
        int tabW = 46;
        int tabY = panelY - 40;
        for (int i = 0; i < METHODS.length; i++) {
            Method m = METHODS[i];
            Button tab = Button.builder(Component.translatable(m.langKey), b -> switchMethod(m))
                    .pos(panelX + i * (tabW + 2), tabY)
                    .size(tabW, 20)
                    .build();
            tab.active = m != this.method;
            this.addRenderableWidget(tab);
        }
    }

    private void buildQrWidgets() {
        int btnY = panelY + panelH - 48;
        if (method == Method.KUGOU) {
            this.addRenderableWidget(Button.builder(Component.translatable("netmusic_kugou.login.copy_link"), b -> copyQrUrl())
                    .pos(panelX + 25, btnY)
                    .size(80, 20)
                    .build());
        }
        this.addRenderableWidget(Button.builder(Component.translatable("netmusic_kugou.login.refresh"), b -> generateQrCode())
                .pos(method == Method.KUGOU ? panelX + 135 : (panelX + (PANEL_W - 80) / 2), btnY)
                .size(80, 20)
                .build());

        if ((method == Method.KUGOU && (qrKey.isEmpty() || !polling))
                || (method != Method.KUGOU && (thirdTicket == null || !thirdTicket.isOk() || !polling))) {
            generateQrCode();
        }
    }

    private void buildFormWidgets() {
        int boxX = panelX + 20;
        int boxW = PANEL_W - 40;
        String phone = phoneBox == null ? "" : phoneBox.getValue();
        String code = codeBox == null ? "" : codeBox.getValue();
        String user = userBox == null ? "" : userBox.getValue();
        String pass = passBox == null ? "" : passBox.getValue();

        if (method == Method.SMS) {
            phoneBox = new EditBox(this.font, boxX, panelY + 18, boxW, 20, Component.translatable("netmusic_kugou.login.phone_hint"));
            phoneBox.setHint(Component.translatable("netmusic_kugou.login.phone_hint"));
            phoneBox.setMaxLength(16);
            phoneBox.setValue(phone);

            codeBox = new EditBox(this.font, boxX, panelY + 48, boxW, 20, Component.translatable("netmusic_kugou.login.code_hint"));
            codeBox.setHint(Component.translatable("netmusic_kugou.login.code_hint"));
            codeBox.setMaxLength(8);
            codeBox.setValue(code);

            this.addRenderableWidget(phoneBox);
            this.addRenderableWidget(codeBox);

            this.addRenderableWidget(Button.builder(Component.translatable("netmusic_kugou.login.send_code"), b -> sendSmsCode())
                    .pos(panelX + 22, panelY + 80)
                    .size(90, 20)
                    .build());
            this.addRenderableWidget(Button.builder(Component.translatable("netmusic_kugou.login.login"), b -> loginBySms())
                    .pos(panelX + 128, panelY + 80)
                    .size(90, 20)
                    .build());
        } else {
            userBox = new EditBox(this.font, boxX, panelY + 18, boxW, 20, Component.translatable("netmusic_kugou.login.username_hint"));
            userBox.setHint(Component.translatable("netmusic_kugou.login.username_hint"));
            userBox.setMaxLength(64);
            userBox.setValue(user);

            passBox = new EditBox(this.font, boxX, panelY + 48, boxW, 20, Component.translatable("netmusic_kugou.login.password_hint"));
            passBox.setHint(Component.translatable("netmusic_kugou.login.password_hint"));
            passBox.setMaxLength(64);
            passBox.setValue(pass);

            this.addRenderableWidget(userBox);
            this.addRenderableWidget(passBox);

            this.addRenderableWidget(Button.builder(Component.translatable("netmusic_kugou.login.login"), b -> loginByPassword())
                    .pos(panelX + (PANEL_W - 100) / 2, panelY + 80)
                    .size(100, 20)
                    .build());
        }
    }


    private void switchMethod(Method target) {
        if (target == this.method) {
            return;
        }
        this.method = target;
        stopPolling();
        resetQrState();
        this.status = Component.empty();
        this.copyFeedback = Component.empty();
        this.clearWidgets();
        this.init();
    }

    private void resetQrState() {
        qrKey = "";
        qrUrl = "";
        qrMatrix = null;
        thirdTicket = null;
        if (qrTexture != null) {
            qrTexture.close();
            qrTexture = null;
        }
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
        stopPolling();
        resetQrState();
        qrStartTime = System.currentTimeMillis();
        status = Component.translatable("netmusic_kugou.login.getting_qr");

        if (method == Method.KUGOU) {
            KuGouLoginApi.fetchQrKey().thenAccept(result -> this.minecraft.execute(() -> {
                if (result.isSuccess()) {
                    qrKey = result.qrcode;
                    qrUrl = result.qrUrl;
                    qrMatrix = QrCodeRenderer.generate(qrUrl);
                    qrStartTime = System.currentTimeMillis();
                    status = Component.translatable("netmusic_kugou.login.scan_qr_prompt");
                    startPolling();
                } else {
                    status = Component.translatable("netmusic_kugou.login.qr_failed", result.error);
                }
            }));
            return;
        }

        java.util.concurrent.CompletableFuture<KuGouOpenLoginApi.QrTicket> future =
                KuGouOpenLoginApi.createWechatQr();
        future.thenAccept(ticket -> this.minecraft.execute(() -> {
                    if (ticket.isOk()) {
                        if (qrTexture == null) {
                            qrTexture = QrImageTexture.create();
                        }
                        if (qrTexture.update(ticket.image)) {
                            thirdTicket = ticket;
                            qrStartTime = System.currentTimeMillis();
                            status = Component.translatable("netmusic_kugou.login.scan_wx_prompt");
                            startPolling();
                        } else {
                            status = Component.translatable("netmusic_kugou.login.generate_failed");
                        }
                    } else {
                        status = Component.translatable("netmusic_kugou.login.qr_failed",
                                ticket.error == null ? "unknown" : ticket.error);
                    }
                }));
    }

    private void sendSmsCode() {
        String mobile = phoneBox == null ? "" : phoneBox.getValue().trim();
        if (mobile.isEmpty()) {
            status = Component.translatable("netmusic_kugou.login.need_phone");
            return;
        }
        status = Component.translatable("netmusic_kugou.login.sending_code");
        KuGouLoginApi.sendSmsCode(mobile).thenAccept(r -> this.minecraft.execute(() -> status = Component.literal(r.message)));
    }

    private void loginBySms() {
        String mobile = phoneBox == null ? "" : phoneBox.getValue().trim();
        String code = codeBox == null ? "" : codeBox.getValue().trim();
        if (mobile.isEmpty() || code.isEmpty()) {
            status = Component.translatable("netmusic_kugou.login.need_phone_code");
            return;
        }
        status = Component.translatable("netmusic_kugou.login.logging_in");
        KuGouLoginApi.loginByPhone(mobile, code).thenAccept(r -> this.minecraft.execute(() -> handleLoginResult(r)));
    }

    private void loginByPassword() {
        String username = userBox == null ? "" : userBox.getValue().trim();
        String password = passBox == null ? "" : passBox.getValue();
        if (username.isEmpty() || password.isEmpty()) {
            status = Component.translatable("netmusic_kugou.login.need_account_pwd");
            return;
        }
        status = Component.translatable("netmusic_kugou.login.logging_in");
        KuGouLoginApi.loginByPassword(username, password).thenAccept(r -> this.minecraft.execute(() -> handleLoginResult(r)));
    }

    private void handleLoginResult(KuGouLoginApi.LoginResult result) {
        if (result.success) {
            finishLogin();
        } else {
            status = Component.literal(result.message);
        }
    }

    private void finishLogin() {
        stopPolling();
        // 登录态由 KuGouConfig + LoginStateManager 持久化（本地文件），无需写入 ServerConfig。
        // ServerConfig.vipCookie 仅用于专用/内置服务器通过配置文件注入登录态，不应在客户端登录流程写入，
        // 否则在客户端主菜单（SERVER 配置尚未加载）调用 set() 会抛 NPE 崩溃（已通过将类型改为 COMMON 修复，
        // 这里仍不写入，避免覆盖专用服务器管理员的手动配置）。
        LoginStateManager.saveState();
        status = Component.translatable("netmusic_kugou.login.success");
        this.minecraft.setScreen(this.parent);
    }


    private void startPolling() {
        stopPolling();
        polling = true;
        Thread t = new Thread(() -> {
            while (polling) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    polling = false;
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!polling) {
                    break;
                }
                try {
                    pollOnce();
                } catch (Throwable ignored) {
                }
            }
        }, "KuGouLogin-Poll");
        pollThread = t;
        t.start();
    }

    private void stopPolling() {
        polling = false;
        Thread t = pollThread;
        pollThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void pollOnce() {
        if (method == Method.KUGOU) {
            if (qrKey.isEmpty()) {
                return;
            }
            KuGouLoginApi.checkQrCode(qrKey)
                    .thenAccept(result -> this.minecraft.execute(() -> applyKugouResult(result)));
            return;
        }
        KuGouOpenLoginApi.QrTicket ticket = this.thirdTicket;
        if (ticket == null || !ticket.isOk()) {
            return;
        }
        KuGouOpenLoginApi.checkWechatQr(ticket)
                .thenAccept(result -> this.minecraft.execute(() -> applyThirdResult(result)));
    }

    private void applyKugouResult(KuGouLoginApi.QrCheckResult result) {
        switch (result.status) {
            case 0 -> {
                status = Component.translatable("netmusic_kugou.login.qr_expired");
                stopPolling();
            }
            case 1 -> status = Component.translatable("netmusic_kugou.login.waiting_scan");
            case 2 -> status = Component.translatable("netmusic_kugou.login.scanned_confirm");
            case 4 -> finishLogin();
            default -> status = Component.translatable("netmusic_kugou.login.status_abnormal", result.status, result.message);
        }
    }

    private void applyThirdResult(KuGouOpenLoginApi.PollResult result) {
        switch (result.state) {
            case WAITING -> status = Component.translatable("netmusic_kugou.login.waiting_scan");
            case SCANNED -> status = Component.translatable("netmusic_kugou.login.scanned_confirm");
            case EXPIRED -> {
                status = Component.translatable("netmusic_kugou.login.qr_expired");
                stopPolling();
            }
            case SUCCESS -> {
                status = Component.translatable("netmusic_kugou.login.success");
                finishLogin();
            }
            default -> {
                status = Component.translatable("netmusic_kugou.login.third_failed", result.message);
                stopPolling();
            }
        }
    }


    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 修改 GuiGraphics 的矩阵且不复原，导致面板上方（标题/状态）文字飞出屏幕。
        drawBackground(graphics);

        graphics.fill(panelX - 5, panelY - 5, panelX + PANEL_W + 5, panelY + panelH + 5, 0xAA000000);

        graphics.drawCenteredString(this.font, Component.translatable("netmusic_kugou.login.title").getString(),
                this.width / 2, panelY - 56, 0xFFFFFF);

        // 状态栏（面板上方）—— 必须在 super.render 之前绘制，
        // 否则按钮渲染会改动 GuiGraphics 坐标矩阵，使面板上方文字飞到屏幕外
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.status, this.width / 2, panelY - 14, 0xCCCCCC);
        }

        String loginStatus = KuGouConfig.userid == null || KuGouConfig.userid.isEmpty()
                ? Component.translatable("netmusic_kugou.status.not_logged_in").getString()
                : String.format(Component.translatable("netmusic_kugou.status.logged_in").getString(), KuGouConfig.userid);
        graphics.drawString(this.font, Component.translatable("netmusic_kugou.status.label").getString() + loginStatus,
                10, this.height - 20, 0xAAAAAA, false);

        if (method.isQr()) {
            renderQrArea(graphics);
        } else {
            renderFormHint(graphics);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
 直接绘制背景，不触发 ScreenEvent.BackgroundRendered 事件（部分渲染mod会在其中
 修改 GuiGraphics 矩阵且不复原，导致面板上方文字飞出屏幕）。
*/
    private void drawBackground(GuiGraphics graphics) {
        if (this.minecraft != null && this.minecraft.level != null) {
            // 游戏内：直接绘制深色渐变背景，不调用 super.renderBackground，避免 post 事件
            graphics.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
        } else {
            this.renderBackground(graphics);
        }
    }

    private void renderQrArea(GuiGraphics graphics) {
        boolean drawn = false;
        if (method == Method.KUGOU) {
            drawn = renderKugouQr(graphics);
        } else if (qrTexture != null && qrTexture.ready()) {
            int w = qrTexture.width();
            int h = qrTexture.height();
            int x = panelX + (PANEL_W - QR_SIZE) / 2;
            int y = panelY + 8;
            graphics.fill(x - 3, y - 3, x + QR_SIZE + 3, y + QR_SIZE + 3, 0xFFFFFFFF);
            // 整张贴图（w×h）缩放绘制到 QR_SIZE×QR_SIZE，而不是只采样左上角局部
            graphics.blit(qrTexture.id(), x, y, QR_SIZE, QR_SIZE, 0.0F, 0.0F, w, h, w, h);
            drawn = true;
        }

        int textY = panelY + 8 + QR_SIZE + 8;
        if (!drawn) {
            graphics.drawCenteredString(this.font, qrUrl.isEmpty()
                            ? Component.translatable("netmusic_kugou.login.loading").getString()
                            : Component.translatable("netmusic_kugou.login.generate_failed").getString(),
                    this.width / 2, panelY + 50, 0xAAAAAA);
            return;
        }

        if (copyFeedback.getString().isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable(method == Method.KUGOU ? "netmusic_kugou.login.scan_prompt2"
                            : "netmusic_kugou.login.scan_wx_prompt").getString(),
                    this.width / 2, textY, 0xAAAAAA);
        } else {
            graphics.drawCenteredString(this.font, copyFeedback, this.width / 2, textY, 0x55FF55);
        }

        long elapsed = (System.currentTimeMillis() - qrStartTime) / 1000;
        String timeStr = Component.translatable("netmusic_kugou.login.waited", elapsed).getString();
        graphics.drawCenteredString(this.font, timeStr, this.width / 2, textY + 12, elapsed > 120 ? 0xFF5555 : 0x888888);
    }

    private boolean renderKugouQr(GuiGraphics graphics) {
        if (qrMatrix == null || qrMatrix.length == 0) {
            return false;
        }
        int n = qrMatrix.length;
        int ps = Math.max(1, Math.round((float) QR_SIZE / n));
        int actual = n * ps;
        int x = panelX + (PANEL_W - actual) / 2;
        int y = panelY + 8;
        graphics.fill(x - 3, y - 3, x + actual + 3, y + actual + 3, 0xFFFFFFFF);
        QrCodeRenderer.render(graphics, x, y, qrMatrix, QR_SIZE);
        return true;
    }

    private void renderFormHint(GuiGraphics graphics) {
        String key = method == Method.SMS ? "netmusic_kugou.login.sms_tip" : "netmusic_kugou.login.account_tip";
        graphics.drawCenteredString(this.font, Component.translatable(key).getString(),
                this.width / 2, panelY + 110, 0x888888);
    }


    @Override
    public void onClose() {
        stopPolling();
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        stopPolling();
        if (qrTexture != null) {
            qrTexture.close();
            qrTexture = null;
        }
        super.removed();
    }

    /**
 登录期间不要让游戏暂停，否则父模组 NetMusic 会把方块音乐判定为"游戏已暂停"而停止播放。
*/
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
 默认 Screen.renderBlurredBackground 会无条件调
 gameRenderer.processBlurEffect() 把整个游戏世界模糊化（不只 PauseScreen）。
 扫码登录屏的二维码和文字会被模糊到几乎看不清。覆盖为 no-op 关闭模糊。
 注：Forge 1.20.1 的 Screen 类没有此方法，保留仅为文档/后续版本兼容。
*/
    protected void renderBlurredBackground(GuiGraphics graphics, float partialTick) {
    }
}
