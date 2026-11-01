package com.skcraft.autoauth.auth;

import com.skcraft.autoauth.Constants;
import com.skcraft.autoauth.mixin.MinecraftSessionAccess;
import com.skcraft.autoauth.mixin.RealmsAvailabilityAccess;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.realmsclient.client.RealmsClient;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;

import java.util.Objects;
import java.net.Proxy;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class SessionApplier {
    private SessionApplier() { }

    /** Fully prepared account-specific identity; construction performs no Minecraft mutation. */
    public record PreparedSession(User user, UserApiService userApiService, ProfileResult profile,
                                  UserApiService.UserProperties properties) {
        public PreparedSession {
            Objects.requireNonNull(user);
            Objects.requireNonNull(userApiService);
            Objects.requireNonNull(profile);
            Objects.requireNonNull(properties);
            if (!user.getProfileId().equals(profile.profile().getId())) {
                throw new IllegalArgumentException("Prepared profile does not match session");
            }
        }
    }

    public static CompletableFuture<Void> applyAsync(User user) {
        return applyAsync(user, () -> true);
    }

    public static CompletableFuture<Void> applyAsync(User user, BooleanSupplier current) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) throw new IllegalStateException("Start session replacement on the client thread");
        Objects.requireNonNull(user);
        var proxy = minecraft.getProxy();
        var sessionService = minecraft.getMinecraftSessionService();
        return CompletableFuture.supplyAsync(() -> prepare(user, sessionService, proxy), Util.ioPool()).thenCompose(prepared -> {
            CompletableFuture<Void> done = new CompletableFuture<>();
            minecraft.execute(() -> {
                try {
                    if (!current.getAsBoolean()) throw new CancellationException("Superseded session replacement");
                    installPreparedOnClientThread(prepared);
                    done.complete(null);
                } catch (Throwable error) {
                    done.completeExceptionally(error);
                }
            });
            return done;
        });
    }

    public static PreparedSession prepare(User user, MinecraftSessionService sessionService, Proxy proxy) {
        try {
            UserApiService service = new YggdrasilAuthenticationService(proxy).createUserApiService(user.getAccessToken());
            return prepareWithServices(user, service, sessionService);
        } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    /** Provider-boundary seam: the same validation runs in production and deterministic preparation tests. */
    static PreparedSession prepareWithServices(User user, UserApiService service, MinecraftSessionService sessionService)
            throws AuthenticationException {
        var properties = service.fetchProperties();
        ProfileResult profile = sessionService.fetchProfile(user.getProfileId(), true);
        if (profile == null) throw new IllegalStateException("Unable to fetch the account profile");
        return new PreparedSession(user, service, profile, properties);
    }

    /** Installs already prepared state in one client-thread turn, without provider calls. */
    public static void installPreparedOnClientThread(PreparedSession prepared) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) throw new IllegalStateException("Session replacement requires the client thread");
        MinecraftSessionAccess access = (MinecraftSessionAccess) (Object) minecraft;
        User user = prepared.user();
        UserApiService service = prepared.userApiService();
        PlayerSocialManager social = new PlayerSocialManager(minecraft, service);
        ProfileKeyPairManager keys = ProfileKeyPairManager.create(service, user, minecraft.gameDirectory.toPath());
        ReportingContext reporting = ReportingContext.create(ReportEnvironment.local(), service);
        RealmsDataFetcher realms = new RealmsDataFetcher(new RealmsClient(user.getSessionId(), user.getName(), minecraft));
        ClientTelemetryManager telemetry = new ClientTelemetryManager(minecraft, service, user);
        ClientTelemetryManager oldTelemetry = access.autoauth$getTelemetryManager();
        access.autoauth$setUser(user);
        access.autoauth$setUserApiService(service);
        access.autoauth$setProfileFuture(CompletableFuture.completedFuture(prepared.profile()));
        access.autoauth$setUserPropertiesFuture(CompletableFuture.completedFuture(prepared.properties()));
        access.autoauth$setPlayerSocialManager(social);
        access.autoauth$setProfileKeyPairManager(keys);
        access.autoauth$setReportingContext(reporting);
        access.autoauth$setRealmsDataFetcher(realms);
        access.autoauth$setTelemetryManager(telemetry);
        RealmsAvailabilityAccess.autoauth$setFuture(null);
        AuthStatusService.get().sessionChanged();
        // Retirement cannot turn an already committed replacement into an apparent sign-in failure.
        try {
            oldTelemetry.close();
        } catch (RuntimeException ignored) {
            Constants.LOG.warn("Unable to retire the previous telemetry manager");
        }
        Constants.LOG.info("Applied replacement Minecraft session");
    }
}
