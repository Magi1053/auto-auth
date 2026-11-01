package com.skcraft.autoauth.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public interface MinecraftSessionAccess {
    @Accessor("profileFuture")
    @Mutable
    void autoauth$setProfileFuture(CompletableFuture<ProfileResult> profile);

    @Accessor("userPropertiesFuture")
    @Mutable
    void autoauth$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> properties);

    @Accessor("telemetryManager")
    ClientTelemetryManager autoauth$getTelemetryManager();

    @Accessor("telemetryManager")
    @Mutable
    void autoauth$setTelemetryManager(ClientTelemetryManager manager);

    @Accessor("user")
    @Mutable
    void autoauth$setUser(User user);

    @Accessor("userApiService")
    @Mutable
    void autoauth$setUserApiService(UserApiService userApiService);

    @Accessor("playerSocialManager")
    @Mutable
    void autoauth$setPlayerSocialManager(PlayerSocialManager playerSocialManager);

    @Accessor("profileKeyPairManager")
    @Mutable
    void autoauth$setProfileKeyPairManager(ProfileKeyPairManager profileKeyPairManager);

    @Accessor("reportingContext")
    @Mutable
    void autoauth$setReportingContext(ReportingContext reportingContext);

    @Accessor("realmsDataFetcher")
    @Mutable
    void autoauth$setRealmsDataFetcher(RealmsDataFetcher realmsDataFetcher);
}
