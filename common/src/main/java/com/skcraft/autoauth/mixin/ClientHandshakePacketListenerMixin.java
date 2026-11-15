package com.skcraft.autoauth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.skcraft.autoauth.auth.AuthenticationErrors;
import com.skcraft.autoauth.session.SessionOrchestrator;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerMixin {
    @Shadow @Final private Connection connection;
    @Unique private volatile boolean autoauth$rejectedCredentials;

    @WrapOperation(method = "authenticateServer", at = @At(value = "INVOKE",
            target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;joinServer(Ljava/util/UUID;Ljava/lang/String;Ljava/lang/String;)V",
            remap = false))
    private void autoauth$classifyInvalidServiceResponse(MinecraftSessionService service, UUID profileId,
            String accessToken, String serverHash, Operation<Void> original) throws Exception {
        try {
            original.call(service, profileId, accessToken, serverHash);
        } catch (Exception failure) {
            if (failure instanceof InvalidCredentialsException) autoauth$rejectedCredentials = true;
            // Let vanilla choose its localized service-unavailable disconnect message.
            throw failure instanceof AuthenticationException authentication
                    ? AuthenticationErrors.normalize(authentication) : failure;
        }
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void autoauth$recoverRejectedSession(DisconnectionDetails details, CallbackInfo ci) {
        if (SessionOrchestrator.get().recoverRejectedServerJoin(connection, autoauth$rejectedCredentials)) ci.cancel();
    }

    @Inject(method = "handleGameProfile", at = @At("HEAD"))
    private void autoauth$clearCompletedAttempt(ClientboundGameProfilePacket packet, CallbackInfo ci) {
        Minecraft.getInstance().execute(() -> SessionOrchestrator.get().serverJoinAccepted(connection));
    }
}
