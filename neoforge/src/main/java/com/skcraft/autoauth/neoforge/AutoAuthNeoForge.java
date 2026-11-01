package com.skcraft.autoauth.neoforge;

import com.skcraft.autoauth.AutoAuth;
import com.skcraft.autoauth.Constants;
import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.gui.ConfigScreen;
import com.skcraft.autoauth.session.SessionOrchestrator;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class AutoAuthNeoForge {
    public AutoAuthNeoForge(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(AutoAuth::init);
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // Debug-oriented client commands; available when config.debug is enabled at use time.
        event.getDispatcher().register(Commands.literal("autoauth")
                .requires(source -> ModConfig.get().debug)
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal("Auth status: " + AuthStatusService.get().getStatus()));
                            return 1;
                        }))
                .then(Commands.literal("refresh")
                        .executes(ctx -> {
                            SessionOrchestrator.get().attemptReauth(true);
                            ctx.getSource().sendSystemMessage(Component.literal("Reauth requested"));
                            return 1;
                        }))
                .then(Commands.literal("config")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new ConfigScreen(mc.screen)));
                            return 1;
                        }))
                .then(Commands.literal("auto")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    ModConfig.get().setAuto(BoolArgumentType.getBool(ctx, "enabled"));
                                    ctx.getSource().sendSystemMessage(Component.literal("auto=" + ModConfig.get().auto));
                                    return 1;
                                }))));
    }
}
