package com.skcraft.autoauth.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RealmsWaitingScreen extends WaitingScreen {
    public RealmsWaitingScreen(Screen parent) {
        super(Component.translatable("autoauth.realms_wait"), parent);
    }
}
