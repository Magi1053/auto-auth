package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.runtimetest.RuntimeFixtures;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla startup may ask for an MSA API service even with a synthetic launcher token. */
@Mixin(value = YggdrasilAuthenticationService.class, remap = false)
public abstract class RuntimeAuthenticationServiceMixin {
    @Inject(method = "createUserApiService", at = @At("HEAD"), cancellable = true)
    private void autoauthTest$api(String token, CallbackInfoReturnable<UserApiService> cir) {
        cir.setReturnValue(RuntimeFixtures.prepared(RuntimeFixtures.user(RuntimeFixtures.A, "StartupFixture")).userApiService());
    }
}
