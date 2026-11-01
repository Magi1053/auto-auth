package com.skcraft.autoauth.gui;

import com.skcraft.autoauth.auth.AuthStatus;
import com.skcraft.autoauth.auth.AuthStatusDisplay;
import com.skcraft.autoauth.auth.AuthStatusService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shared top-left status HUD (render + hit-test) for multiplayer / Realms / disconnect screens.
 */
public final class StatusHud {
    public static final int X = 8;
    public static final int Y = 8;

    private StatusHud() {
    }

    public static void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        AuthStatus status = AuthStatusService.get().getStatus();
        String line = AuthStatusDisplay.hudLine(status);
        int color = hit(font, mouseX, mouseY) ? 0xFFFFFF : AuthStatusDisplay.color(status);
        graphics.drawString(font, line, X, Y, color, false);
        if (hit(font, mouseX, mouseY)) {
            graphics.renderTooltip(font, Component.translatable("autoauth.open"), mouseX, mouseY);
        }
    }

    public static boolean hit(Font font, double mouseX, double mouseY) {
        String line = AuthStatusDisplay.hudLine(AuthStatusService.get().getStatus());
        int w = font.width(line);
        return mouseX >= X && mouseX < X + w && mouseY >= Y && mouseY < Y + font.lineHeight;
    }

    public static boolean openConfigOnClick(Screen parent, Font font, double mouseX, double mouseY, int button) {
        if (button != 0 || !hit(font, mouseX, mouseY)) {
            return false;
        }
        Minecraft.getInstance().setScreen(new ConfigScreen(parent));
        return true;
    }
}
