package com.github.tartaricacid.netmusic.kugou.config;

import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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

@OnlyIn(Dist.CLIENT)
public class ButtonEntry extends TooltipListEntry<Void> {

    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;

    private final Component buttonText;
    private final Runnable onClick;
    private final Button buttonWidget;

    private int lastX, lastY, lastEntryWidth, lastEntryHeight;

    public ButtonEntry(Component fieldName, Component buttonText, Runnable onClick,
                       @Nullable Supplier<Optional<Component[]>> tooltipSupplier) {
        super(fieldName, tooltipSupplier, false);
        this.buttonText = buttonText;
        this.onClick = onClick;
        this.buttonWidget = Button.builder(buttonText, b -> onClick.run())
                .bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
    }

    @Override
    public void render(GuiGraphics graphics, int index, int y, int x,
                       int entryWidth, int entryHeight,
                       int mouseX, int mouseY, boolean isHovered, float delta) {
        super.render(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);

        this.lastX = x;
        this.lastY = y;
        this.lastEntryWidth = entryWidth;
        this.lastEntryHeight = entryHeight;

        int bx = x + entryWidth - BUTTON_WIDTH - 2;
        int by = y + (entryHeight - BUTTON_HEIGHT) / 2;

        buttonWidget.setX(bx);
        buttonWidget.setY(by);
        buttonWidget.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isMouseInside((int) mouseX, (int) mouseY, lastX, lastY, lastEntryWidth, lastEntryHeight)) {
            int bx = lastX + lastEntryWidth - BUTTON_WIDTH - 2;
            int by = lastY + (lastEntryHeight - BUTTON_HEIGHT) / 2;
            buttonWidget.setX(bx);
            buttonWidget.setY(by);
            return buttonWidget.mouseClicked(mouseX, mouseY, button);
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
        return Collections.singletonList(buttonWidget);
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return Collections.singletonList(buttonWidget);
    }

    public static ButtonEntry of(Component fieldName, Component buttonText, Runnable onClick) {
        return new ButtonEntry(fieldName, buttonText, onClick, null);
    }
}
