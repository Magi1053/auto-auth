package com.skcraft.autoauth.mixin;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ConnectScreen.class)
public interface ConnectScreenAccess {
    @Accessor("connection") Connection autoauth$getConnection();
    @Accessor("aborted") boolean autoauth$isAborted();
}
