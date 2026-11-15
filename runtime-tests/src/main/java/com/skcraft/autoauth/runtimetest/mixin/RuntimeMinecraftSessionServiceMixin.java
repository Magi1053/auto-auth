package com.skcraft.autoauth.runtimetest.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.skcraft.autoauth.runtimetest.RuntimeFixtures;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Intercept at the game boundary because NeoForge loads Authlib in a separate bootstrap module. */
@Mixin(Minecraft.class)
public abstract class RuntimeMinecraftSessionServiceMixin {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/authlib/yggdrasil/YggdrasilAuthenticationService;createMinecraftSessionService()Lcom/mojang/authlib/minecraft/MinecraftSessionService;",
            remap = false))
    private MinecraftSessionService autoauthTest$sessionService(MinecraftSessionService original) {
        return RuntimeFixtures.sessionService();
    }
}
