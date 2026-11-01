package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.runtimetest.RuntimeSuite;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class RuntimeTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void autoauthTest$tick(CallbackInfo ci) {
        RuntimeSuite.tick();
    }
}
