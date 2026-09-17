package com.github.tartaricacid.netmusic.kugou.client.gui;

import com.github.tartaricacid.netmusic.kugou.KuGouLogger;
import com.github.tartaricacid.netmusic.kugou.NetMusicKuGou;
import com.github.tartaricacid.netmusic.kugou.api.KuGouApiClient;
import com.github.tartaricacid.netmusic.kugou.config.KuGouConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class KuGouSearchScreen extends Screen {
    private static final int SEARCH_HEIGHT = 20;
    private static final int SEARCH_BUTTON_WIDTH = 60;
    private static final int LIST_ITEM_HEIGHT = 24;
    private static final int LIST_TOP_PADDING = 8;

    private final Screen parent;
    private final Consumer<SearchResult> onSelect;
    private final List<SearchResult> searchResults = new ArrayList<>();
    private EditBox searchBox;
    private Button searchButton;
    private Button prevButton;
    private Button nextButton;
    private Button selectButton;
    private Button vipCheckButton;
    private Component status = Component.empty();
    private String vipStatusText = "";
    private boolean vipChecking = false;
    private boolean searching = false;
    private boolean isSearching = false;
    private int currentPage = 1;
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    public KuGouSearchScreen(Screen parent, String initialQuery, Consumer<SearchResult> onSelect) {
        super(Component.translatable("netmusic_kugou.search.title"));
        this.parent = parent;
        this.onSelect = onSelect;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(250, this.width - SEARCH_BUTTON_WIDTH - 24);
        int boxX = (this.width - (boxWidth + SEARCH_BUTTON_WIDTH + 4)) / 2;
        int boxY = 24;

        this.searchBox = new EditBox(this.font, boxX, boxY, boxWidth, SEARCH_HEIGHT, Component.translatable("netmusic_kugou.search.box_hint"));
        this.searchBox.setBordered(true);
        this.searchBox.setTextColor(0xFFFFFF);
        this.addWidget(this.searchBox);
        this.setInitialFocus(this.searchBox);

        this.searchButton = Button.builder(Component.translatable("netmusic_kugou.gui.search"), button -> runSearch())
                .pos(boxX + boxWidth + 4, boxY)
                .size(SEARCH_BUTTON_WIDTH, SEARCH_HEIGHT)
                .build();
        this.addRenderableWidget(this.searchButton);

        int buttonY = this.height - 45;
        this.prevButton = Button.builder(Component.literal("<"), button -> previousPage())
                .pos(10, buttonY)
                .size(30, 20)
                .build();
        this.addRenderableWidget(this.prevButton);

        this.nextButton = Button.builder(Component.literal(">"), button -> nextPage())
                .pos(this.width - 40, buttonY)
                .size(30, 20)
                .build();
        this.addRenderableWidget(this.nextButton);

        this.selectButton = Button.builder(Component.translatable("netmusic_kugou.search.select"), button -> applySelection())
                .pos((this.width - 80) / 2, buttonY)
                .size(80, 20)
                .build();
        this.addRenderableWidget(this.selectButton);

        int vipBtnX = 10;
        int vipBtnY = buttonY + 24;
        this.vipCheckButton = Button.builder(Component.translatable("netmusic_kugou.search.check_vip"), button -> checkVipStatus())
                .pos(vipBtnX, vipBtnY)
                .size(90, 20)
                .build();
        this.addRenderableWidget(this.vipCheckButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // ⚠️ 列表顶部必须在搜索框下方，避免与搜索栏重叠
        int listTop = Math.max(52, this.height / 2 - 120);
        int listBottom = this.height - 60;
        int listWidth = this.width - 20;
        int listX = 10;

        graphics.fill(listX, listTop, listX + listWidth, listBottom, 0x80000000);

        int visibleCount = (listBottom - listTop - LIST_TOP_PADDING) / LIST_ITEM_HEIGHT;
        int startIndex = scrollOffset;
        int endIndex = Math.min(startIndex + visibleCount, searchResults.size());

        for (int i = startIndex; i < endIndex; i++) {
            SearchResult result = searchResults.get(i);
            int y = listTop + LIST_TOP_PADDING + (i - startIndex) * LIST_ITEM_HEIGHT;
            boolean isSelected = selectedIndex == i;
            boolean isHovered = mouseX >= listX && mouseX <= listX + listWidth &&
                    mouseY >= y && mouseY < y + LIST_ITEM_HEIGHT;

            if (isSelected) {
                graphics.fill(listX, y, listX + listWidth, y + LIST_ITEM_HEIGHT, 0xFF4A90D9);
            } else if (isHovered) {
                graphics.fill(listX, y, listX + listWidth, y + LIST_ITEM_HEIGHT, 0x804A90D9);
            }

            String songName = result.songName;
            if (songName.length() > 35) {
                songName = songName.substring(0, 32) + "...";
            }
            graphics.drawString(this.font, songName, listX + 8, y + 4, 0xFFFFFF, false);

            String artist = result.singerName;
            if (artist.length() > 20) {
                artist = artist.substring(0, 17) + "...";
            }
            graphics.drawString(this.font, artist, listX + 8, y + 16, 0xAAAAAA, false);

            String timeStr = result.duration > 0 ?
                    String.format("%d:%02d", result.duration / 60, result.duration % 60) : "";
            graphics.drawString(this.font, timeStr, listX + listWidth - 40, y + 4, 0xAAAAAA, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        this.searchBox.render(graphics, mouseX, mouseY, partialTick);

        if (!this.status.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.status, this.width / 2, 56, 0xCCCCCC);
        }

        String pageStr = Component.translatable("netmusic_kugou.search.page", currentPage).getString();
        graphics.drawCenteredString(this.font, pageStr, this.width / 2, this.height - 20, 0xFFFFFF);

        String loginStatus = KuGouConfig.userid.isEmpty() ? Component.translatable("netmusic_kugou.status.not_logged_in").getString() : Component.translatable("netmusic_kugou.status.logged_in", KuGouConfig.userid).getString();
        graphics.drawString(this.font, Component.translatable("netmusic_kugou.status.label").getString() + loginStatus, 10, this.height - 35, 0xAAAAAA, false);

        if (vipStatusText != null && !vipStatusText.isEmpty()) {
            String[] lines = vipStatusText.split("\n");
            int vipY = this.height - 55;
            for (int i = 0; i < lines.length && i < 8; i++) {
                int color = lines[i].contains("✓") ? 0x55FF55 :
                            lines[i].contains("✗") || lines[i].contains("⚠") ? 0xFFAA00 :
                            lines[i].contains("=") || lines[i].contains("-") || lines[i].contains("账号") ? 0xCCCCCC :
                            0xFFFFFF;
                graphics.drawString(this.font, lines[i], this.width / 2 - 100, vipY + i * 9, color, false);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // ⚠️ 与 render() 保持一致的 listTop 计算
        int listTop = Math.max(52, this.height / 2 - 120);
        int listBottom = this.height - 60;
        int listWidth = this.width - 20;
        int listX = 10;

        if (mouseX >= listX && mouseX <= listX + listWidth && mouseY >= listTop && mouseY <= listBottom) {
            int relativeY = (int) (mouseY - listTop - LIST_TOP_PADDING);
            int clickedIndex = relativeY / LIST_ITEM_HEIGHT + scrollOffset;

            if (clickedIndex >= 0 && clickedIndex < searchResults.size()) {
                selectedIndex = clickedIndex;
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            if (selectedIndex >= 0) {
                applySelection();
            } else {
                runSearch();
            }
            return true;
        }
        if (keyCode == 256) {
            onClose();
            return true;
        }
        if (keyCode == 265) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            return true;
        }
        if (keyCode == 264) {
            int maxScroll = Math.max(0, searchResults.size() - 5);
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
            return true;
        }
        if (keyCode == 263 && selectedIndex > 0) {
            selectedIndex--;
            return true;
        }
        if (keyCode == 262 && selectedIndex < searchResults.size() - 1) {
            selectedIndex++;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void runSearch() {
        if (this.isSearching) {
            return;
        }
        String keyword = this.searchBox.getValue();
        if (!keyword.isEmpty()) {
            this.isSearching = true;
            this.searching = true;
            this.status = Component.translatable("netmusic_kugou.search.searching");
            this.searchResults.clear();
            this.selectedIndex = -1;

            try {
                KuGouApiClient.search(keyword, currentPage, 30)
                        .thenAccept(results -> {
                            this.searchResults.clear();
                            for (KuGouApiClient.Song song : results) {
                                this.searchResults.add(new SearchResult(
                                        song.name,
                                        song.singer,
                                        song.duration,
                                        song.hash,
                                        song.albumId
                                ));
                            }
                            this.searching = false;
                            this.isSearching = false;
                            this.status = searchResults.isEmpty() ? Component.translatable("netmusic_kugou.search.no_result") : Component.empty();
                            this.scrollOffset = 0;
                        }).exceptionally(e -> {
                            KuGouLogger.error("===== KuGou Search Failed =====");
                            KuGouLogger.error("Error message: {}", e.getMessage());
                            e.printStackTrace();
                            this.status = Component.translatable("netmusic_kugou.search.failed", e.getMessage());
                            this.searching = false;
                            this.isSearching = false;
                            return null;
                        });
            } catch (Exception e) {
                KuGouLogger.error("===== KuGou Search Preparation Failed =====");
                KuGouLogger.error("Error message: {}", e.getMessage());
                this.status = Component.translatable("netmusic_kugou.search.failed", e.getMessage());
                this.searching = false;
                this.isSearching = false;
            }
        }
    }

    private void previousPage() {
        if (currentPage > 1) {
            currentPage--;
            runSearch();
        }
    }

    private void nextPage() {
        currentPage++;
        runSearch();
    }

    private void checkVipStatus() {
        if (vipChecking) return;
        if (!KuGouConfig.isLoggedIn()) {
            this.status = Component.translatable("netmusic_kugou.search.please_login");
            vipStatusText = Component.translatable("netmusic_kugou.search.vip_not_logged").getString();;
            return;
        }
        vipChecking = true;
        this.status = Component.translatable("netmusic_kugou.search.querying_vip");
        vipStatusText = "";

        KuGouApiClient.getVipInfo()
            .thenAccept(json -> {
                vipStatusText = KuGouApiClient.parseVipStatus(json);
                if (vipStatusText.contains("已开通") || vipStatusText.contains("✓")) {
                    this.status = Component.translatable("netmusic_kugou.search.vip_ok");
                } else if (vipStatusText.contains("未开通") || vipStatusText.contains("无VIP")) {
                    this.status = Component.translatable("netmusic_kugou.search.vip_none");
                } else {
                    this.status = Component.translatable("netmusic_kugou.search.query_done");
                }
                vipChecking = false;
            })
            .exceptionally(e -> {
                String errMsg = e.getMessage() != null ? e.getMessage() : Component.translatable("netmusic_kugou.error.default").getString();
                vipStatusText = Component.translatable("netmusic_kugou.vip.query_failed", errMsg).getString();;
                this.status = Component.translatable("netmusic_kugou.search.failed", errMsg);
                vipChecking = false;
                return null;
            });
    }

    private void applySelection() {
        if (selectedIndex >= 0 && selectedIndex < searchResults.size()) {
            SearchResult result = searchResults.get(selectedIndex);
            onSelect.accept(result);
            onClose();
        }
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /**
     * 父模组 CD 烧录界面的搜索弹窗期间不要让游戏暂停，否则 NetMusic 会把方块音乐判定为"游戏已暂停"而停止播放。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 默认 {@code Screen.renderBlurredBackground} 会无条件调
     * {@code gameRenderer.processBlurEffect()} 把世界模糊化，导致弹窗内容被糊。
     * 覆盖为 no-op 关闭模糊。
     */
    protected void renderBlurredBackground(GuiGraphics graphics, float partialTick) {
    }

    public void setSearchResults(java.util.List<SearchResult> results) {
        this.searchResults.clear();
        this.searchResults.addAll(results);
        this.status = searchResults.isEmpty() ? Component.translatable("netmusic_kugou.search.no_result") : Component.empty();
        this.scrollOffset = 0;
    }

    public static class SearchResult {
        public String songName;
        public String singerName;
        public int duration;
        public String fileHash;
        public String albumId;

        public SearchResult(String songName, String singerName, int duration, String fileHash, String albumId) {
            this.songName = songName;
            this.singerName = singerName;
            this.duration = duration;
            this.fileHash = fileHash;
            this.albumId = albumId;
        }

        public static SearchResult fromBuf(net.minecraft.network.FriendlyByteBuf buf) {
            return new SearchResult(
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readVarInt(),
                    buf.readUtf(),
                    buf.readUtf()
            );
        }

        public static void toBuf(SearchResult result, net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeUtf(result.songName != null ? result.songName : "");
            buf.writeUtf(result.singerName != null ? result.singerName : "");
            buf.writeVarInt(result.duration);
            buf.writeUtf(result.fileHash != null ? result.fileHash : "");
            buf.writeUtf(result.albumId != null ? result.albumId : "");
        }
    }
}
