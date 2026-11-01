package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.runtimetest.RuntimeFixtures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records arrival in the actual vanilla body, after every production HEAD cancellation. */
@Mixin(value = ConnectScreen.class, priority = 500)
public abstract class RuntimeConnectMixin {
    @Inject(method = "startConnecting", at = @At(value = "NEW", target = "net/minecraft/client/gui/screens/ConnectScreen"), cancellable = true)
    private static void autoauthTest$capture(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data,
                                             boolean quickPlay, TransferState transfer, CallbackInfo ci) {
        RuntimeFixtures.connect = new RuntimeFixtures.ConnectCall(parent, address, data, quickPlay, transfer);
        RuntimeFixtures.connects.incrementAndGet();
        if (RuntimeFixtures.allowLocalSocket && address.getHost().equals("127.0.0.1")) {
            return;
        }
        ci.cancel();
    }
}
