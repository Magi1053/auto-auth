package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.auth.SessionApplier;
import com.skcraft.autoauth.runtimetest.RuntimeFixtures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.net.Proxy;

@Mixin(value = SessionApplier.class, remap = false)
public abstract class RuntimeSessionMixin {
    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true)
    private static void autoauthTest$prepare(User user, MinecraftSessionService ignored, Proxy proxy,
                                            CallbackInfoReturnable<SessionApplier.PreparedSession> cir) {
        cir.setReturnValue(RuntimeFixtures.prepared(user));
    }
}
