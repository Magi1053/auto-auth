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
import com.skcraft.autoauth.mixin.ConnectScreenAccess;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class SessionOrchestrator {
    private static final SessionOrchestrator INSTANCE = new SessionOrchestrator();
    private boolean realmsRecoveryAttempted;
    private boolean serverRecoveryAttempted;
    private boolean resumingServerJoin;
    private ServerAttempt serverAttempt;
    private UUID manuallySelectedAccount;

    private record ServerAttempt(ConnectScreen screen, Screen parent, ServerAddress address,
            ServerData data, boolean quickPlay, TransferState transfer) { }
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
            // A rejected verification blocks further refreshes, but the current token may
            // become valid later. Keep probing instead of requiring the Accounts button.
            if (!recovery.isBusy()) statusService.refreshIfStale();
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
        if (force) {
            realmsRecoveryAttempted = false;
            serverRecoveryAttempted = false;
        }
        if (recovery.attempt(account, force) && PendingJoins.isPendingServer()) serverRecoveryAttempted = true;
    }

    /** A fresh connection request may recover once; automatic resumption retains that limit. */
    public void beginServerAttempt() {
        serverAttempt = null;
        if (!resumingServerJoin) serverRecoveryAttempted = false;
    }

    public void rememberServerAttempt(ConnectScreen screen, Screen parent, ServerAddress address,
            ServerData data, boolean quickPlay, TransferState transfer) {
        serverAttempt = new ServerAttempt(screen, PendingJoins.unwrapWaitingParent(parent), address, data, quickPlay, transfer);
    }

    /** Called on the client thread when the login connection has actually disconnected. */
    public boolean recoverRejectedServerJoin(Connection connection, boolean rejectedCredentials) {
        ServerAttempt attempt = serverAttempt;
        Minecraft minecraft = Minecraft.getInstance();
        if (attempt == null || minecraft.screen != attempt.screen()) return false;
        ConnectScreenAccess owner = (ConnectScreenAccess) (Object) attempt.screen();
        if (owner.autoauth$getConnection() != connection || owner.autoauth$isAborted()) return false;
        serverAttempt = null;
        if (!rejectedCredentials || !ModConfig.get().auto || serverRecoveryAttempted
                || attempt.data().isLan() || attempt.data().isRealm()) return false;
        serverRecoveryAttempted = true;
        AuthStatusService.get().rejectCurrentSession();
        PendingJoins.beginServer(attempt.parent(), attempt.address(), attempt.data(), attempt.quickPlay(), attempt.transfer());
        minecraft.setScreen(new ServerWaitingScreen(attempt.parent()));
        return true;
    }

    public void serverJoinAccepted(Connection connection) {
        ServerAttempt attempt = serverAttempt;
        if (attempt != null && ((ConnectScreenAccess) (Object) attempt.screen()).autoauth$getConnection() == connection) {
            serverAttempt = null;
        }
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
            if (address != null) {
                resumingServerJoin = true;
                try {
                    ConnectScreen.startConnecting(parent, minecraft, address, data, quickPlay, transfer);
                } finally {
                    resumingServerJoin = false;
                }
            }
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
