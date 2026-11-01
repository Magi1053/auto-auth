package com.skcraft.autoauth.runtimetest.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public interface RuntimeMinecraftAccess {
    @Accessor("profileFuture") CompletableFuture<ProfileResult> autoauthTest$profile();
    @Accessor("userPropertiesFuture") CompletableFuture<UserApiService.UserProperties> autoauthTest$properties();
    @Accessor("userApiService") UserApiService autoauthTest$api();
    @Accessor("realmsDataFetcher") RealmsDataFetcher autoauthTest$realms();
    @Accessor("playerSocialManager") PlayerSocialManager autoauthTest$social();
    @Accessor("profileKeyPairManager") ProfileKeyPairManager autoauthTest$keys();
    @Accessor("reportingContext") ReportingContext autoauthTest$reporting();
    @Accessor("telemetryManager") ClientTelemetryManager autoauthTest$telemetry();
}
