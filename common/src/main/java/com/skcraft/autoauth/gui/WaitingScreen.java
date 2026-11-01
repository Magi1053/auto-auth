package com.skcraft.autoauth.gui;

import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.auth.AuthStatus;
import com.skcraft.autoauth.auth.AuthStatusDisplay;
import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.session.PendingJoins;
import com.skcraft.autoauth.session.SessionOrchestrator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public abstract class WaitingScreen extends Screen {
    private final Screen parent;

    protected WaitingScreen(Component title, Screen parent) {
        super(title);
        this.parent = PendingJoins.unwrapWaitingParent(parent);
    }

    public Screen getParent() {
        return parent;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = firstButtonY();
        addRenderableWidget(Button.builder(Component.translatable("autoauth.accounts"), button -> minecraft.setScreen(new ConfigScreen(this)))
                .bounds(x, y, 200, 20).build());
        int cancelY = y + 24;
        if (!ModConfig.get().accountsSnapshot().isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("autoauth.retry"), button -> SessionOrchestrator.get().attemptReauth(true))
                    .bounds(x, cancelY, 200, 20).build());
            cancelY += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onCancel())
                .bounds(x, cancelY, 200, 20).build());
    }

    private int firstButtonY() {
        return Math.min(height - 72, height / 2 + 20);
    }

    protected void onCancel() {
        PendingJoins.cancel();
        minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        SessionOrchestrator.get().tickAuthStatus(this);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        AuthStatus status = AuthStatusService.get().getStatus();
        int y = firstButtonY();
        graphics.drawCenteredString(font, AuthStatusDisplay.label(status), width / 2, y - 56, AuthStatusDisplay.color(status));
        graphics.drawCenteredString(font, title, width / 2, y - 36, 0xFFFFFF);
        graphics.drawCenteredString(font, hintLine(status), width / 2, y - 18, 0xEFF4F4);
    }

    protected Component hintLine(AuthStatus status) {
        return switch (status) {
            case ONLINE -> Component.translatable("autoauth.progress.continuing");
            case CHECKING -> Component.translatable("autoauth.progress.checking");
            case INVALID, OFFLINE -> Component.translatable(SessionOrchestrator.get().progressMessage());
            case UNKNOWN -> Component.translatable("autoauth.progress.checking");
        };
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        onCancel();
    }
}
