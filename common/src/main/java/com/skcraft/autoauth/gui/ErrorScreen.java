package com.skcraft.autoauth.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ErrorScreen extends Screen {
    private final Screen parent;
    private final Component details;
    private MultiLineLabel detailsLabel = MultiLineLabel.EMPTY;

    public ErrorScreen(Screen parent, String title, String details) {
        super(title != null && title.startsWith("autoauth.") ? Component.translatable(title) : Component.literal(title == null ? "Error" : title));
        this.parent = parent;
        this.details = details != null && details.startsWith("autoauth.") ? Component.translatable(details) : Component.literal(details == null ? "" : details);
    }

    @Override
    protected void init() {
        detailsLabel = MultiLineLabel.create(font, details, Math.min(width - 50, 320));
        int buttonY = Math.min(height / 2 + 20 + detailsLabel.getLineCount() * 9, height - 28);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose()).bounds(width / 2 - 100, buttonY, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 50, 0xFFAB97);
        detailsLabel.renderCentered(graphics, width / 2, height / 2 - 30);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
