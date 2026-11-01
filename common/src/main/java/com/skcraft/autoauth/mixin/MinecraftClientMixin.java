package com.skcraft.autoauth.mixin;

import com.skcraft.autoauth.session.SessionOrchestrator;
import com.skcraft.autoauth.session.PendingJoins;
import com.skcraft.autoauth.gui.WaitingForLoginScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Shadow
    public Screen screen;

    @Inject(method = { "stop", "destroy" }, at = @At("HEAD"))
    private void autoauth$shutdown(CallbackInfo ci) {
        if (screen instanceof WaitingForLoginScreen login) login.removed();
        SessionOrchestrator.get().cancelReauth();
        PendingJoins.cancel();
    }

    @Inject(method = "setScreen", at = @At("TAIL"))
    private void autoauth$preloadHeads(Screen next, CallbackInfo ci) {
        if (next instanceof net.minecraft.client.gui.screens.TitleScreen) {
            com.skcraft.autoauth.gui.AccountButton.preloadSavedHeads();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void autoauth$tickAuth(CallbackInfo ci) {
        if (screen instanceof DisconnectedScreen) {
            SessionOrchestrator.get().tickAuthStatus(screen);
        }
    }
}
