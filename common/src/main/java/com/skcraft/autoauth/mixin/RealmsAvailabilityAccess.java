package com.skcraft.autoauth.mixin;

import com.mojang.realmsclient.RealmsAvailability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.concurrent.CompletableFuture;

@Mixin(RealmsAvailability.class)
public interface RealmsAvailabilityAccess {
    @Accessor("future")
    static void autoauth$setFuture(CompletableFuture<RealmsAvailability.Result> future) {
        throw new AssertionError("Mixin accessor was not transformed");
    }

    @Accessor("future")
    static CompletableFuture<RealmsAvailability.Result> autoauth$getFuture() {
        throw new AssertionError("Mixin accessor was not transformed");
    }
}
