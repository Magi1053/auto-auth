package com.skcraft.autoauth.gui;

import com.skcraft.autoauth.config.ModConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Secondary Auto-Auth settings (auto sign-in, debug commands).
 */
public class SettingsScreen extends Screen {
    private final Screen parent;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("autoauth.settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig config = ModConfig.get();

        addRenderableWidget(Checkbox.builder(Component.translatable("autoauth.auto"), font)
                .pos(width / 2 - 150, 50)
                .selected(config.auto)
                .onValueChange((box, selected) -> {
                    ConfigScreen.guarded(this, () -> ModConfig.get().setAuto(selected));
                })
                .build());

        addRenderableWidget(Checkbox.builder(Component.translatable("autoauth.debug"), font)
                .pos(width / 2 - 150, 74)
                .selected(config.debug)
                .onValueChange((box, selected) -> {
                    ConfigScreen.guarded(this, () -> ModConfig.get().setDebug(selected));
                })
                .build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(width / 2 - 100, height - 40, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("autoauth.builtin"), width / 2, 110, 0xD4DFDF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
