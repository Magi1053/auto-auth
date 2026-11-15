package com.skcraft.autoauth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.skcraft.autoauth.auth.AuthenticationErrors;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerMixin {
    @WrapOperation(method = "authenticateServer", at = @At(value = "INVOKE",
            target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;joinServer(Ljava/util/UUID;Ljava/lang/String;Ljava/lang/String;)V",
            remap = false))
    private void autoauth$classifyInvalidServiceResponse(MinecraftSessionService service, UUID profileId,
            String accessToken, String serverHash, Operation<Void> original) throws Exception {
        try {
            original.call(service, profileId, accessToken, serverHash);
        } catch (Exception failure) {
            // Let vanilla choose its localized service-unavailable disconnect message.
            throw failure instanceof AuthenticationException authentication
                    ? AuthenticationErrors.normalize(authentication) : failure;
        }
    }
}
