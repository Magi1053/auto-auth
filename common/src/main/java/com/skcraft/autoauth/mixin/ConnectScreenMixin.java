package com.skcraft.autoauth.mixin;

import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.gui.ServerWaitingScreen;
import com.skcraft.autoauth.session.PendingJoins;
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
}
