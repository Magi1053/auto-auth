package com.skcraft.autoauth.mixin;

import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.gui.RealmsWaitingScreen;
import com.skcraft.autoauth.session.PendingJoins;
import com.skcraft.autoauth.session.SessionOrchestrator;
import com.mojang.realmsclient.RealmsAvailability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RealmsAvailability.Result.class)
public abstract class RealmsAvailabilityResultMixin {
    @Shadow
    public abstract RealmsAvailability.Type type();

    @Inject(method = "createErrorScreen", at = @At("HEAD"), cancellable = true)
    private void autoauth$onAuthError(Screen parent, CallbackInfoReturnable<Screen> cir) {
        if (type() == RealmsAvailability.Type.SUCCESS) {
            SessionOrchestrator.get().onRealmsAvailable();
            return;
        }
        if (type() != RealmsAvailability.Type.AUTHENTICATION_ERROR) {
            return;
        }
        if (!ModConfig.get().auto) {
            return;
        }
        Screen unwrapped = PendingJoins.unwrapWaitingParent(parent);
        if (!SessionOrchestrator.get().beginRealmsRecovery(unwrapped)) {
            return;
        }
        cir.setReturnValue(new RealmsWaitingScreen(unwrapped != null ? unwrapped : Minecraft.getInstance().screen));
    }
}
