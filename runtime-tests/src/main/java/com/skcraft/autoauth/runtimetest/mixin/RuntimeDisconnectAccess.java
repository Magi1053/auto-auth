package com.skcraft.autoauth.runtimetest.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DisconnectedScreen.class)
public interface RuntimeDisconnectAccess {
    @Accessor("parent") Screen autoauthTest$parent();
}
