package com.skcraft.autoauth.session;

import com.skcraft.autoauth.auth.AuthFailure;
import com.skcraft.autoauth.auth.AuthStatus;
import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.auth.MicrosoftAuth;
import com.skcraft.autoauth.auth.SessionApplier;
import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.gui.RealmsWaitingScreen;
import com.skcraft.autoauth.gui.ServerWaitingScreen;
import com.skcraft.autoauth.gui.WaitingScreen;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class SessionOrchestrator {
    private static final SessionOrchestrator INSTANCE = new SessionOrchestrator();
    private boolean realmsRecoveryAttempted;
    private UUID manuallySelectedAccount;
    private final RecoveryController recovery = new RecoveryController(
            task -> Minecraft.getInstance().execute(task), System::currentTimeMillis,
            account -> CompletableFuture.supplyAsync(() -> {
                try {
                    var result = MicrosoftAuth.authenticateWithRefreshToken(account.refreshToken());
                    return new RecoveryController.Result(result.uuid(), result.username(), result.accessToken(), result.refreshToken());
                } catch (Exception e) { throw new CompletionException(e); }
            }, Util.ioPool()),
            account -> ModConfig.get().findAccount(account.uuid()).map(saved -> Objects.equals(saved.refreshToken(), account.refreshToken())).orElse(false),
            result -> ModConfig.get().updateTokens(result.uuid(), result.accessToken(), result.refreshToken(), result.username(),
                    Objects.equals(manuallySelectedAccount, result.uuid())),
            (result, current) -> SessionApplier.applyAsync(new MicrosoftAuth.AuthResult(result.accessToken(), result.refreshToken(), result.uuid(), result.username()).toUser(),
                    () -> current.getAsBoolean() && ModConfig.get().findAccount(result.uuid()).map(saved -> Objects.equals(saved.refreshToken(), result.refreshToken())).orElse(false)),
            error -> error instanceof AuthFailure failure ? failure.retryable()
                    : error instanceof IOException || error instanceof AuthenticationUnavailableException,
            () -> AuthStatusService.get().refreshStatus()
    );

    private SessionOrchestrator() { }
    public static SessionOrchestrator get() { return INSTANCE; }
    public void onBecameOnline() { recovery.becameOnline(); }
    public boolean isBusy() { return recovery.isBusy(); }
    public String progressMessage() { return recovery.message(); }
    public void cancelReauth() { recovery.cancel(); }

    public void tickAuthStatus(Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) { minecraft.execute(() -> tickAuthStatus(screen)); return; }
        var statusService = AuthStatusService.get();
        AuthStatus status = statusService.getStatus();
        if (!ModConfig.get().auto && PendingJoins.isPending()) {
            PendingJoins.cancel();
            if (screen instanceof WaitingScreen waiting) minecraft.setScreen(waiting.getParent());
            return;
        }
        if (status == AuthStatus.ONLINE) {
            recovery.becameOnline();
            if (!recovery.isBusy() && screen instanceof WaitingScreen) resumePendingIfReady(screen);
            if (!PendingJoins.isPending() && !recovery.isBusy()) statusService.refreshIfStale();
        } else if (status.needsReauth() && ModConfig.get().auto) {
            recovery.verificationRejected();
            attemptReauth(false);
        } else if (!recovery.isBusy()) {
            statusService.refreshIfStale();
        }
    }

    public void attemptReauth(boolean force) { start(null, force); }
    public void attemptReauth(UUID selectedAccount) { start(Objects.requireNonNull(selectedAccount), true); }

    private void start(UUID selectedAccount, boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) { minecraft.execute(() -> start(selectedAccount, force)); return; }
        if (!force && !AuthStatusService.get().getStatus().needsReauth()) return;
        if (recovery.isBusy()) return;
        ModConfig config = ModConfig.get();
        var user = minecraft.getUser();
        var accounts = config.accountsSnapshot().stream().map(a -> new RecoveryController.Account(a.uuid(), a.username(), a.refreshToken())).toList();
        var account = RecoveryController.select(selectedAccount, user == null ? null : user.getProfileId(),
                user == null ? null : user.getName(), config.defaultUuid, accounts);
        manuallySelectedAccount = selectedAccount;
        if (force) realmsRecoveryAttempted = false;
        recovery.attempt(account, force);
    }

    /** Prevent repeated Realms-only rejection from creating an endless successful-reauth loop. */
    public boolean beginRealmsRecovery(Screen parent) {
        if (PendingJoins.isPendingRealms()) return true;
        if (realmsRecoveryAttempted) return false;
        realmsRecoveryAttempted = true;
        AuthStatusService.get().rejectCurrentSession();
        PendingJoins.beginRealms(parent);
        return true;
    }

    public void onRealmsAvailable() { realmsRecoveryAttempted = false; }

    private void resumePendingIfReady(Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (PendingJoins.isPendingServer() && screen instanceof ServerWaitingScreen) {
            Screen parent = PendingJoins.serverParent();
            var address = PendingJoins.serverAddress();
            var data = PendingJoins.serverData();
            boolean quickPlay = PendingJoins.quickPlay();
            var transfer = PendingJoins.transferState();
            PendingJoins.cancel();
            if (address != null) ConnectScreen.startConnecting(parent, minecraft, address, data, quickPlay, transfer);
        } else if (PendingJoins.isPendingRealms() && screen instanceof RealmsWaitingScreen waiting) {
            Screen parent = PendingJoins.realmsParent();
            PendingJoins.cancel();
            minecraft.setScreen(new RealmsMainScreen(parent != null ? parent : waiting.getParent()));
        } else if (!PendingJoins.isPending() && screen instanceof WaitingScreen waiting) {
            minecraft.setScreen(waiting.getParent());
        }
    }

    public static void showToast(String title, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(message)));
    }
}
