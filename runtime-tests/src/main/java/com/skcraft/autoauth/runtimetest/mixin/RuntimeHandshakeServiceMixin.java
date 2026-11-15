package com.skcraft.autoauth.runtimetest.mixin;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.skcraft.autoauth.runtimetest.RuntimeFixtures;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces only the provider boundary; the real vanilla join-authentication method still runs. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class RuntimeHandshakeServiceMixin {
    @Inject(method = "getMinecraftSessionService", at = @At("HEAD"), cancellable = true)
    private void autoauthTest$joinService(CallbackInfoReturnable<MinecraftSessionService> cir) {
        if (RuntimeFixtures.joinSessionService != null) {
            cir.setReturnValue(RuntimeFixtures.joinSessionService);
        }
    }
}
