package com.skcraft.autoauth.mixin;

import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.gui.StatusHud;
import com.skcraft.autoauth.session.PendingJoins;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
    protected DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @ModifyVariable(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/DisconnectionDetails;Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static Screen autoauth$unwrapParent(Screen parent) {
        PendingJoins.cancel();
        return PendingJoins.unwrapWaitingParent(parent);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void autoauth$onInit(CallbackInfo ci) {
        AuthStatusService.get().refreshStatus();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        StatusHud.render(graphics, font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (StatusHud.openConfigOnClick((DisconnectedScreen) (Object) this, font, mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
