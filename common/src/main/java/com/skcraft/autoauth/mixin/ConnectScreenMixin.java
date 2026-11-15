package com.skcraft.autoauth.mixin;

import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.gui.ServerWaitingScreen;
import com.skcraft.autoauth.session.PendingJoins;
import com.skcraft.autoauth.session.SessionOrchestrator;
import com.llamalad7.mixinextras.sugar.Local;
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

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void autoauth$onStartConnecting(
            Screen parent,
            Minecraft minecraft,
            ServerAddress address,
            ServerData data,
            boolean isQuickPlay,
            TransferState transferState,
            CallbackInfo ci
    ) {
        if (minecraft.screen instanceof ConnectScreen) return;
        SessionOrchestrator.get().beginServerAttempt();
        if (!ModConfig.get().auto) {
            return;
        }
        if (!AuthStatusService.get().getStatus().blocksConnectWhenAuto()) {
            return;
        }

        Screen unwrapped = PendingJoins.unwrapWaitingParent(parent);
        PendingJoins.beginServer(unwrapped, address, data, isQuickPlay, transferState);
        minecraft.setScreen(new ServerWaitingScreen(unwrapped));
        ci.cancel();
    }

    @Inject(method = "startConnecting", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/ConnectScreen;connect(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;Lnet/minecraft/client/multiplayer/TransferState;)V"))
    private static void autoauth$rememberServerAttempt(Screen parent, Minecraft minecraft, ServerAddress address,
            ServerData data, boolean quickPlay, TransferState transfer, CallbackInfo ci, @Local ConnectScreen screen) {
        SessionOrchestrator.get().rememberServerAttempt(screen, parent, address, data, quickPlay, transfer);
    }
}
