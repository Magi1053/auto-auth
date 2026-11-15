package com.skcraft.autoauth.runtimetest;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.skcraft.autoauth.auth.AuthStatus;
import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.auth.MicrosoftAuth;
import com.skcraft.autoauth.auth.SessionApplier;
import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.gui.ServerWaitingScreen;
import com.skcraft.autoauth.runtimetest.mixin.RuntimeStatusAccess;
import com.skcraft.autoauth.session.PendingJoins;
import com.skcraft.autoauth.session.SessionOrchestrator;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.resources.ResourceLocation;

import static com.skcraft.autoauth.runtimetest.RuntimeSuite.*;

/** Real loopback login attempts; only Microsoft's provider boundary is synthetic. */
final class RuntimeServerJoinTests {
    private RuntimeServerJoinTests() { }

    static CompletableFuture<Void> recover(Screen parent, boolean checking) {
        Attempt attempt;
        try {
            attempt = new Attempt(parent, true, count -> count == 1 ? new InvalidCredentialsException("Synthetic rejection") : null);
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        CompletableFuture<AuthStatus> staleProbe = new CompletableFuture<>();
        if (checking) {
            RuntimeFixtures.refreshBarrier = new CompletableFuture<>();
            ((RuntimeStatusAccess) (Object) AuthStatusService.get()).autoauthTest$tracker().refresh(() -> staleProbe);
        }
        attempt.connect();
        CompletableFuture<Void> ready = checking ? waitFor(() -> Minecraft.getInstance().screen instanceof ServerWaitingScreen
                && SessionOrchestrator.get().isBusy(), () -> staleProbe.complete(AuthStatus.ONLINE)).thenCompose(ignored -> afterTicks(3, () -> {
                    require(attempt.logins.get() == 1, "obsolete ONLINE probe resumed before token refresh finished");
                    require(AuthStatusService.get().getStatus() != AuthStatus.ONLINE, "obsolete in-flight probe overwrote rejected session status");
                    RuntimeFixtures.refreshBarrier.complete(refreshedAlice());
                    RuntimeFixtures.refreshBarrier = null;
                })) : CompletableFuture.completedFuture(null);
        return ready.thenCompose(ignored -> waitFor(() -> attempt.server.authenticated.isDone()
                && Minecraft.getInstance().screen instanceof DisconnectedScreen, () -> {
                    attempt.server.authenticated.join();
                    require(attempt.logins.get() == 2 && attempt.server.connections.get() == 2, "server join was not retried exactly once");
                    require(RuntimeFixtures.refreshes.get() == attempt.refreshes + 1, "join rejection did not cause exactly one token refresh");
                    attempt.checkResumedParameters();
                    require(!PendingJoins.isPending(), "successful retried authentication retained pending join");
                    Minecraft.getInstance().setScreen(parent);
                })).whenComplete((ignored, error) -> attempt.close());
    }

    static CompletableFuture<Void> exclusions(Screen parent) {
        return excluded(parent, false, new InvalidCredentialsException("Synthetic rejection"))
                .thenCompose(ignored -> excluded(parent, true, new AuthenticationUnavailableException("Synthetic outage")))
                .thenCompose(ignored -> excluded(parent, true, new InsufficientPrivilegesException("Synthetic permission rejection")));
    }

    private static CompletableFuture<Void> excluded(Screen parent, boolean automatic, AuthenticationException failure) {
        Attempt attempt;
        try { attempt = new Attempt(parent, automatic, ignored -> failure); }
        catch (Exception error) { return CompletableFuture.failedFuture(error); }
        attempt.connect();
        return waitFor(() -> Minecraft.getInstance().screen instanceof DisconnectedScreen, () -> {
            require(!PendingJoins.isPending(), "excluded failure started automatic recovery");
        }).thenCompose(ignored -> afterTicks(5, () -> {
            require(attempt.logins.get() == 1 && RuntimeFixtures.refreshes.get() == attempt.refreshes,
                    "disabled recovery, outage, or permission failure refreshed credentials");
            Minecraft.getInstance().setScreen(parent);
        })).whenComplete((ignored, error) -> attempt.close());
    }

    static CompletableFuture<Void> cancel(Screen parent) {
        Attempt attempt;
        try { attempt = new Attempt(parent, true, ignored -> new InvalidCredentialsException("Synthetic rejection")); }
        catch (Exception error) { return CompletableFuture.failedFuture(error); }
        RuntimeFixtures.refreshBarrier = new CompletableFuture<>();
        attempt.connect();
        return waitFor(() -> Minecraft.getInstance().screen instanceof ServerWaitingScreen && SessionOrchestrator.get().isBusy(), () -> {
            ((ServerWaitingScreen) Minecraft.getInstance().screen).onClose();
            require(Minecraft.getInstance().screen == parent && !PendingJoins.isPending(), "cancel retained join ownership");
            RuntimeFixtures.refreshBarrier.complete(refreshedAlice());
            RuntimeFixtures.refreshBarrier = null;
        }).thenCompose(ignored -> waitFor(() -> !SessionOrchestrator.get().isBusy(), () -> { }))
                .thenCompose(ignored -> afterTicks(6, () -> {
                    require(attempt.logins.get() == 1 && RuntimeFixtures.connects.get() == attempt.connects + 1,
                            "cancelled handshake recovery reconnected after late token refresh");
                    require(Minecraft.getInstance().screen == parent && !PendingJoins.isPending(), "late refresh replaced the cancel destination");
                })).whenComplete((ignored, error) -> attempt.close());
    }

    static CompletableFuture<Void> bounded(Screen parent) {
        Attempt attempt;
        try { attempt = new Attempt(parent, true, ignored -> new InvalidCredentialsException("Synthetic repeated rejection")); }
        catch (Exception error) { return CompletableFuture.failedFuture(error); }
        attempt.connect();
        return waitFor(() -> attempt.logins.get() >= 2 && Minecraft.getInstance().screen instanceof DisconnectedScreen,
                () -> require(!PendingJoins.isPending(), "second rejection retained automatic retry"))
                .thenCompose(ignored -> afterTicks(10, () -> {
                    require(attempt.logins.get() == 2 && RuntimeFixtures.connects.get() == attempt.connects + 2,
                            "repeated join rejection created a reconnect loop");
                    require(RuntimeFixtures.refreshes.get() == attempt.refreshes + 1, "second join rejection repeated token refresh");
                    Minecraft.getInstance().setScreen(parent);
                })).whenComplete((ignored, error) -> attempt.close());
    }

    private static MicrosoftAuth.AuthResult refreshedAlice() {
        return new MicrosoftAuth.AuthResult("synthetic-refreshed-access-Alice", "synthetic-refresh-Alice-rotated", RuntimeFixtures.A, "Alice");
    }

    private static final class Attempt implements AutoCloseable {
        final RuntimeLoginFixture server = new RuntimeLoginFixture();
        final AtomicInteger logins = new AtomicInteger();
        final Screen parent;
        final ServerAddress address = ServerAddress.parseString(server.address());
        final ServerData data = new ServerData("Login fixture", server.address(), ServerData.Type.OTHER);
        final TransferState transfer = new TransferState(Map.of(ResourceLocation.fromNamespaceAndPath("autoauth", "fixture"), new byte[] { 4, 2 }));
        final int connects;
        final int refreshes;

        Attempt(Screen parent, boolean automatic, IntFunction<AuthenticationException> failures) throws Exception {
            resetAccounts();
            this.parent = parent;
            ModConfig.get().setAuto(automatic);
            RuntimeFixtures.probe = AuthStatus.ONLINE;
            SessionApplier.installPreparedOnClientThread(RuntimeFixtures.prepared(RuntimeFixtures.user(RuntimeFixtures.A, "Alice")));
            setStatus(AuthStatus.ONLINE);
            connects = RuntimeFixtures.connects.get();
            refreshes = RuntimeFixtures.refreshes.get();
            RuntimeFixtures.allowLocalSocket = true;
            RuntimeFixtures.joinSessionService = (MinecraftSessionService) Proxy.newProxyInstance(MinecraftSessionService.class.getClassLoader(),
                    new Class<?>[] { MinecraftSessionService.class }, (proxy, method, args) -> {
                        require(method.getName().equals("joinServer"), "unexpected login provider method");
                        require(RuntimeFixtures.A.equals(args[0]), "join recovery changed account identity");
                        AuthenticationException failure = failures.apply(logins.incrementAndGet());
                        if (failure != null) throw failure;
                        return null;
                    });
        }

        void connect() {
            ConnectScreen.startConnecting(parent, Minecraft.getInstance(), address, data, true, transfer);
        }

        void checkResumedParameters() {
            var call = RuntimeFixtures.connect;
            require(call.parent() == parent && call.address() == address && call.data() == data && call.quickPlay() && call.transfer() == transfer,
                    "retried server join changed parent, destination, quick-play, or transfer cookies");
        }

        @Override public void close() {
            server.close();
            RuntimeFixtures.allowLocalSocket = false;
            RuntimeFixtures.joinSessionService = null;
            CompletableFuture<MicrosoftAuth.AuthResult> barrier = RuntimeFixtures.refreshBarrier;
            if (barrier != null) barrier.complete(refreshedAlice());
            RuntimeFixtures.refreshBarrier = null;
            ModConfig.get().setAuto(false);
        }
    }
}
