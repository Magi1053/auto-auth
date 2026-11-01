package com.skcraft.autoauth.gui;

import com.skcraft.autoauth.auth.AuthFailure;
import com.skcraft.autoauth.auth.MicrosoftAuth;
import com.skcraft.autoauth.config.AccountRecord;
import com.skcraft.autoauth.config.ModConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The screen owns its listener and cancels it when closed or replaced. */
public class WaitingForLoginScreen extends Screen {
    private final Screen parent;
    private MicrosoftAuth.LoginOperation operation;
    private boolean finished;

    public WaitingForLoginScreen(Screen parent) {
        super(Component.translatable("autoauth.login"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 - 100, height / 2 + 36, 200, 20).build());
        if (operation != null || finished) {
            return;
        }
        try {
            operation = MicrosoftAuth.beginLogin();
            operation.result().whenComplete((result, error) -> minecraft.execute(() -> {
                if (finished || minecraft.screen != this) {
                    return;
                }
                finished = true;
                if (error != null) {
                    minecraft.setScreen(loginError(error));
                    return;
                }
                ConfigScreen.guarded(parent, () -> {
                    ModConfig.get().upsertAccount(new AccountRecord(result.accessToken(), result.refreshToken(), result.uuid(), result.username()));
                    minecraft.setScreen(parent);
                });
            }));
        } catch (Exception error) {
            finished = true;
            minecraft.execute(() -> minecraft.setScreen(loginError(error)));
        }
    }

    private ErrorScreen loginError(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        String detail = error instanceof AuthFailure ? error.getMessage() : "autoauth.error.login_detail";
        return new ErrorScreen(parent, "autoauth.error.login", detail);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 36, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("autoauth.browser"), width / 2, height / 2 - 8, 0xEFF4F4);
        graphics.drawCenteredString(font, Component.translatable("autoauth.timeout"), width / 2, height / 2 + 10, 0xD4DFDF);
    }

    @Override
    public void removed() {
        finished = true;
        if (operation != null) {
            operation.close();
        }
    }

    @Override
    public void onClose() {
        removed();
        minecraft.setScreen(parent);
    }
}
