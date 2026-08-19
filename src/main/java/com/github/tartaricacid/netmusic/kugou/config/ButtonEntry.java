package com.github.tartaricacid.netmusic.kugou.config;

import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cloth Config v11 自定义按钮条目。
 * 用于替代 v11 中不存在的 startButton() API。
 * 继承 TooltipListEntry 以获得自动 tooltip 支持。
 */
@OnlyIn(Dist.CLIENT)
public class ButtonEntry extends TooltipListEntry<Void> {

    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_BG = 0xFF555555;
    private static final int BUTTON_BG_HOVER = 0xFF888888;
    private static final int BUTTON_OUTLINE = 0xFFAAAAAA;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private final Component buttonText;
    private final Runnable onClick;

    // 缓存渲染位置，用于 mouseClicked 判定
    private int lastX, lastY, lastEntryWidth, lastEntryHeight;

    public ButtonEntry(Component fieldName, Component buttonText, Runnable onClick,
                       @Nullable Supplier<Optional<Component[]>> tooltipSupplier) {
        super(fieldName, tooltipSupplier, false);
        this.buttonText = buttonText;
        this.onClick = onClick;
    }

    @Override
    public void render(GuiGraphics graphics, int index, int y, int x,
                       int entryWidth, int entryHeight,
                       int mouseX, int mouseY, boolean isHovered, float delta) {
        // TooltipListEntry.render() 会自动处理 tooltip 显示
        super.render(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);

        this.lastX = x;
        this.lastY = y;
        this.lastEntryWidth = entryWidth;
        this.lastEntryHeight = entryHeight;

        // 按钮位置：右对齐
        int bx = x + entryWidth - BUTTON_WIDTH - 2;
        int by = y + (entryHeight - BUTTON_HEIGHT) / 2;

        boolean hovered = mouseX >= bx && mouseX < bx + BUTTON_WIDTH
                && mouseY >= by && mouseY < by + BUTTON_HEIGHT;

        // 背景
        graphics.fill(bx, by, bx + BUTTON_WIDTH, by + BUTTON_HEIGHT, hovered ? BUTTON_BG_HOVER : BUTTON_BG);

        // 边框（用 fill 画四条线）
        graphics.fill(bx, by, bx + BUTTON_WIDTH, by + 1, BUTTON_OUTLINE);
        graphics.fill(bx, by + BUTTON_HEIGHT - 1, bx + BUTTON_WIDTH, by + BUTTON_HEIGHT, BUTTON_OUTLINE);
        graphics.fill(bx, by, bx + 1, by + BUTTON_HEIGHT, BUTTON_OUTLINE);
        graphics.fill(bx + BUTTON_WIDTH - 1, by, bx + BUTTON_WIDTH, by + BUTTON_HEIGHT, BUTTON_OUTLINE);

        // 按钮文字居中
        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(buttonText);
        graphics.drawString(font, buttonText,
                bx + (BUTTON_WIDTH - textWidth) / 2,
                by + (BUTTON_HEIGHT - 8) / 2,
                TEXT_COLOR);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isMouseInside((int) mouseX, (int) mouseY, lastX, lastY, lastEntryWidth, lastEntryHeight)) {
            int bx = lastX + lastEntryWidth - BUTTON_WIDTH - 2;
            int by = lastY + (lastEntryHeight - BUTTON_HEIGHT) / 2;
            if (mouseX >= bx && mouseX < bx + BUTTON_WIDTH
                    && mouseY >= by && mouseY < by + BUTTON_HEIGHT) {
                onClick.run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public int getItemHeight() {
        return 24;
    }

    @Override
    public Void getValue() {
        return null;
    }

    @Override
    public Optional<Void> getDefaultValue() {
        return Optional.empty();
    }

    @Override
    public void save() {
    }

    @Override
    public Optional<Component> getError() {
        return Optional.empty();
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return Collections.emptyList();
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return Collections.emptyList();
    }

    /**
     * 快捷工厂方法：创建无额外标签的按钮
     */
    public static ButtonEntry of(Component fieldName, Component buttonText, Runnable onClick) {
        return new ButtonEntry(fieldName, buttonText, onClick, null);
    }
}
