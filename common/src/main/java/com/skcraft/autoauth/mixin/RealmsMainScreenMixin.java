package com.skcraft.autoauth.mixin;

import com.skcraft.autoauth.gui.StatusHud;
import com.skcraft.autoauth.session.SessionOrchestrator;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RealmsMainScreen.class)
public abstract class RealmsMainScreenMixin extends Screen {
    protected RealmsMainScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void autoauth$renderStatus(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        StatusHud.render(graphics, font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (StatusHud.openConfigOnClick((RealmsMainScreen) (Object) this, font, mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void autoauth$tick(CallbackInfo ci) {
        SessionOrchestrator.get().tickAuthStatus((RealmsMainScreen) (Object) this);
    }
}
