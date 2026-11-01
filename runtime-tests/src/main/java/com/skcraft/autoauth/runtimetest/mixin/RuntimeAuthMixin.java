package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.auth.MicrosoftAuth;
import com.skcraft.autoauth.runtimetest.RuntimeAuthFixture;
import com.skcraft.autoauth.runtimetest.RuntimeFixtures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.io.IOException;

@Mixin(value = MicrosoftAuth.class, remap = false)
public abstract class RuntimeAuthMixin {
    @Inject(method = "authenticateWithRefreshToken", at = @At("HEAD"), cancellable = true)
    private static void autoauthTest$refresh(String token, CallbackInfoReturnable<MicrosoftAuth.AuthResult> cir) {
        cir.setReturnValue(RuntimeFixtures.refresh(token));
    }

    @Inject(method = "beginLogin", at = @At("HEAD"), cancellable = true)
    private static void autoauthTest$login(CallbackInfoReturnable<MicrosoftAuth.LoginOperation> cir) throws IOException {
        cir.setReturnValue(RuntimeAuthFixture.begin());
    }
}
