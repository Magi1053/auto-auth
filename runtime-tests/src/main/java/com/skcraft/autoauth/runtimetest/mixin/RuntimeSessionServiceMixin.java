package com.skcraft.autoauth.runtimetest.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;

/** Prevent vanilla startup's asynchronous profile lookup from reaching a real provider. */
@Mixin(value = YggdrasilMinecraftSessionService.class, remap = false)
public abstract class RuntimeSessionServiceMixin {
    @Inject(method = "fetchProfile", at = @At("HEAD"), cancellable = true)
    private void autoauthTest$profile(UUID uuid, boolean secure, CallbackInfoReturnable<ProfileResult> cir) {
        cir.setReturnValue(new ProfileResult(new GameProfile(uuid, "FixturePlayer")));
    }
}
