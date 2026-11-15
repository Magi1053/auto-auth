package com.skcraft.autoauth.runtimetest.mixin;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientHandshakePacketListenerImpl.class)
public interface RuntimeHandshakeAccess {
    @Invoker("authenticateServer") Component autoauthTest$authenticateServer(String serverHash);
}
