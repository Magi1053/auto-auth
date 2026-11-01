package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.runtimetest.RuntimeFixtures;
import com.mojang.realmsclient.client.RealmsClient;
import com.mojang.realmsclient.client.Request;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RealmsClient.class)
public abstract class RuntimeRealmsMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void autoauthTest$capture(String sessionId, String username, Minecraft minecraft, CallbackInfo ci) {
        RuntimeFixtures.realmsName = username;
        RuntimeFixtures.realmsSession = sessionId;
        RuntimeFixtures.realmConstructors.incrementAndGet();
    }

    @Inject(method = "clientCompatible", at = @At("HEAD"), cancellable = true)
    private void autoauthTest$compatible(CallbackInfoReturnable<RealmsClient.CompatibleVersionResponse> cir) {
        RuntimeFixtures.availabilityChecks.incrementAndGet();
        cir.setReturnValue(RealmsClient.CompatibleVersionResponse.COMPATIBLE);
    }

    @Inject(method = "hasParentalConsent", at = @At("HEAD"), cancellable = true)
    private void autoauthTest$consent(CallbackInfoReturnable<Boolean> cir) { cir.setReturnValue(true); }

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void autoauthTest$noHttp(Request<?> request, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue("{\"servers\":[],\"invites\":[],\"notifications\":[],\"lists\":[]}");
    }
}
