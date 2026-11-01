package com.skcraft.autoauth.fabric;

import com.skcraft.autoauth.AutoAuth;
import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.gui.ConfigScreen;
import com.skcraft.autoauth.session.SessionOrchestrator;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class AutoAuthFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AutoAuth.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("autoauth")
                        .requires(source -> ModConfig.get().debug)
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(Component.literal("Auth status: " + AuthStatusService.get().getStatus()));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("refresh")
                                .executes(ctx -> {
                                    SessionOrchestrator.get().attemptReauth(true);
                                    ctx.getSource().sendFeedback(Component.literal("Reauth requested"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("config")
                                .executes(ctx -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    mc.execute(() -> mc.setScreen(new ConfigScreen(mc.screen)));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("auto")
                                .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            ModConfig.get().setAuto(BoolArgumentType.getBool(ctx, "enabled"));
                                            ctx.getSource().sendFeedback(Component.literal("auto=" + ModConfig.get().auto));
                                            return 1;
                                        })))));

    }
}
