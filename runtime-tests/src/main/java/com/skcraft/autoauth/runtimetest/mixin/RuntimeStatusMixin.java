package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.auth.AuthStatus;
import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.runtimetest.RuntimeFixtures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AuthStatusService.class, remap = false)
public abstract class RuntimeStatusMixin {
    @Inject(method = "check", at = @At("HEAD"), cancellable = true)
    private void autoauthTest$probe(User user, MinecraftSessionService ignored, CallbackInfoReturnable<AuthStatus> cir) {
        cir.setReturnValue(RuntimeFixtures.probe);
    }
}
