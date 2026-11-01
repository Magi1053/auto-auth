package com.skcraft.autoauth.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ServerWaitingScreen extends WaitingScreen {
    public ServerWaitingScreen(Screen parent) {
        super(Component.translatable("autoauth.server_wait"), parent);
    }
}
