package com.skcraft.autoauth.runtimetest;

import com.skcraft.autoauth.auth.AuthStatus;
import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.auth.MicrosoftAuth;
import com.skcraft.autoauth.runtimetest.RuntimeAuthFixture;
import com.skcraft.autoauth.auth.SessionApplier;
import com.skcraft.autoauth.config.AccountRecord;
import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.gui.ConfigScreen;
import com.skcraft.autoauth.gui.RealmsWaitingScreen;
import com.skcraft.autoauth.gui.ServerWaitingScreen;
import com.skcraft.autoauth.gui.SettingsScreen;
import com.skcraft.autoauth.gui.WaitingForLoginScreen;
import com.skcraft.autoauth.mixin.RealmsAvailabilityAccess;
import com.skcraft.autoauth.runtimetest.mixin.RuntimeDisconnectAccess;
import com.skcraft.autoauth.runtimetest.mixin.RuntimeMinecraftAccess;
import com.skcraft.autoauth.runtimetest.mixin.RuntimeStatusAccess;
import com.skcraft.autoauth.session.PendingJoins;
import com.skcraft.autoauth.session.SessionOrchestrator;
import com.mojang.realmsclient.RealmsAvailability;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Actual-client scenarios, driven on the real client thread and never linked into production jars. */
public final class RuntimeSuite {
    private record Scenario(String id, Supplier<CompletableFuture<Void>> run) { }
    private record Wait(BooleanSupplier ready, Runnable action, CompletableFuture<Void> done, int deadline) { }
    private static final List<Wait> waits = new ArrayList<>();
    private static final List<String> passed = new ArrayList<>();
    private static boolean started;
    private static boolean finished;
    private static int ticks;
    private static int index;
    private static CompletableFuture<Void> running;
    private static List<Scenario> scenarios;
    private static Screen original;
    private static String startupState;

    private RuntimeSuite() { }

    public static void tick() {
        if (finished) return;
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            String currentState = (mc.screen == null ? "none" : mc.screen.getClass().getName()) + "/"
                    + (mc.getOverlay() == null ? "none" : mc.getOverlay().getClass().getName());
            if (!currentState.equals(startupState)) {
                startupState = currentState;
                System.out.println("AUTOAUTH CLIENT BOOT " + currentState);
            }
            if (mc.getOverlay() == null && mc.screen instanceof AccessibilityOnboardingScreen onboarding) {
                onboarding.onClose();
                return;
            }
            if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
            started = true;
            original = mc.screen;
            scenarios = List.of(new Scenario("session-consistency", RuntimeSuite::sessionConsistency),
                    new Scenario("explicit-account-switch", RuntimeSuite::explicitSwitch),
                    new Scenario("connect-status-gates", RuntimeSuite::connectGates),
                    new Scenario("pending-server-resume-and-cancel", RuntimeSuite::serverRecovery),
                    new Scenario("disconnect-overloads", RuntimeSuite::disconnects),
                    new Scenario("local-connection-failure", RuntimeSuite::localConnectionFailure),
                    new Scenario("realms-cached-auth-recovery", RuntimeSuite::realms),
                    new Scenario("account-list-settings-navigation", RuntimeSuite::accountUi),
                    new Scenario("browser-add-and-cancel", RuntimeSuite::browserUi));
        }
        ticks++;
        try {
            require(mc.isSameThread(), "test driver is off the client thread");
            for (Wait wait : List.copyOf(waits)) {
                if (wait.ready().getAsBoolean()) {
                    waits.remove(wait);
                    try { wait.action().run(); wait.done().complete(null); }
                    catch (Throwable error) { wait.done().completeExceptionally(error); }
                } else if (ticks > wait.deadline()) {
                    waits.remove(wait);
                    wait.done().completeExceptionally(new AssertionError("Scenario condition timed out"));
                }
            }
            if (running != null && !running.isDone()) return;
            if (running != null) {
                running.join();
                passed.add(scenarios.get(index - 1).id());
                System.out.println("AUTOAUTH CLIENT PASS " + passed.getLast());
                running = null;
            }
            if (index == scenarios.size()) {
                finish(null);
            } else {
                System.out.println("AUTOAUTH CLIENT START " + scenarios.get(index).id());
                running = scenarios.get(index++).run().get();
            }
        } catch (Throwable error) {
            finish(error);
        }
    }

    private static CompletableFuture<Void> sessionConsistency() {
        var mc = Minecraft.getInstance();
        // Exercise the real loader-specific config path without altering saved credentials.
        var publicSettings = ModConfig.configPath().resolveSibling("client.json");
        try {
            require(!Files.exists(publicSettings), "fixture public override was not cleaned up");
            String fallback = ModConfig.clientId();
            String packId = "68d506ce-63f9-4991-aed0-9b4316defcf2";
            try {
                Files.writeString(publicSettings, "{\"clientId\":\"" + packId + "\"}\n", StandardCharsets.UTF_8);
                require(ModConfig.clientId().equals(packId), "pack public client ID did not override account defaults");
            } finally {
                Files.deleteIfExists(publicSettings);
            }
            require(ModConfig.clientId().equals(fallback), "missing override did not restore configured default");
        } catch (java.io.IOException error) {
            throw new java.util.concurrent.CompletionException(error);
        }
        var a = RuntimeFixtures.prepared(RuntimeFixtures.user(RuntimeFixtures.A, "Alice"), false);
        RealmsAvailabilityAccess.autoauth$setFuture(CompletableFuture.completedFuture(new RealmsAvailability.Result(RealmsAvailability.Type.AUTHENTICATION_ERROR)));
        SessionApplier.installPreparedOnClientThread(a);
        require(mc.getUser().getProfileId().equals(RuntimeFixtures.A), "A was not installed");
        require(!mc.allowsMultiplayer() && !mc.allowsRealms(), "A permissions not installed");
        var previousRealms = ((RuntimeMinecraftAccess) mc).autoauthTest$realms();
        var previousSocial = ((RuntimeMinecraftAccess) mc).autoauthTest$social();
        var previousKeys = ((RuntimeMinecraftAccess) mc).autoauthTest$keys();
        var previousReporting = ((RuntimeMinecraftAccess) mc).autoauthTest$reporting();
        var previousTelemetry = ((RuntimeMinecraftAccess) mc).autoauthTest$telemetry();
        var b = RuntimeFixtures.prepared(RuntimeFixtures.user(RuntimeFixtures.B, "Bob"));
        SessionApplier.installPreparedOnClientThread(b);
        require(mc.getGameProfile().getId().equals(RuntimeFixtures.B), "profile retained previous identity");
        require(((RuntimeMinecraftAccess) mc).autoauthTest$api() == b.userApiService(), "API service not replaced");
        require(((RuntimeMinecraftAccess) mc).autoauthTest$profile().join() == b.profile(), "profile future not replaced");
        require(((RuntimeMinecraftAccess) mc).autoauthTest$properties().join() == b.properties(), "properties future not replaced");
        require(mc.allowsMultiplayer() && mc.allowsRealms(), "B permissions not installed");
        require(((RuntimeMinecraftAccess) mc).autoauthTest$realms() != previousRealms, "Realms fetcher not replaced");
        require(((RuntimeMinecraftAccess) mc).autoauthTest$social() != previousSocial, "social manager not replaced");
        require(((RuntimeMinecraftAccess) mc).autoauthTest$keys() != previousKeys, "profile keypair manager not replaced");
        require(((RuntimeMinecraftAccess) mc).autoauthTest$reporting() != previousReporting, "reporting context not replaced");
        require(((RuntimeMinecraftAccess) mc).autoauthTest$telemetry() != previousTelemetry, "telemetry manager not replaced");
        require("Bob".equals(RuntimeFixtures.realmsName) && b.user().getSessionId().equals(RuntimeFixtures.realmsSession), "Realms captured previous credentials");
        require(RealmsAvailabilityAccess.autoauth$getFuture() == null, "Realms cache not invalidated");
        return CompletableFuture.runAsync(() -> {
            try {
                SessionApplier.installPreparedOnClientThread(b);
                throw new AssertionError("off-thread session install accepted");
            } catch (IllegalStateException expected) { }
        });
    }

    private static CompletableFuture<Void> explicitSwitch() {
        resetAccounts();
        SessionApplier.installPreparedOnClientThread(RuntimeFixtures.prepared(RuntimeFixtures.user(RuntimeFixtures.A, "Alice")));
        setStatus(AuthStatus.ONLINE);
        ModConfig.get().setAuto(false);
        RuntimeFixtures.lastRefresh = null;
        ConfigScreen screen = new ConfigScreen(original);
        Minecraft.getInstance().setScreen(screen);
        return afterTicks(2, () -> click(screen, buttonNamed(screen, "Bob"))).thenCompose(ignored -> waitFor(() ->
                !SessionOrchestrator.get().isBusy() && Minecraft.getInstance().getUser().getProfileId().equals(RuntimeFixtures.B)
                        && AuthStatusService.get().getStatus() == AuthStatus.ONLINE, () -> {
            require(RuntimeFixtures.lastRefresh != null && RuntimeFixtures.lastRefresh.contains("Bob"), "manual B selected A");
            require(ModConfig.get().defaultUuid.equals(RuntimeFixtures.B), "manual selection did not persist default");
            require(ModConfig.get().findAccount(RuntimeFixtures.B).orElseThrow().refreshToken().endsWith("rotated"), "final token not saved");
            require(Minecraft.getInstance().getGameProfile().getId().equals(RuntimeFixtures.B), "explicit switch left old profile");
            SessionOrchestrator.get().tickAuthStatus(screen);
            Minecraft.getInstance().setScreen(original);
        }));
    }

    private static CompletableFuture<Void> connectGates() {
        var mc = Minecraft.getInstance();
        var address = ServerAddress.parseString("127.0.0.1:25561");
        var data = new ServerData("Fixture", "127.0.0.1:25561", ServerData.Type.OTHER);
        var transfer = new TransferState(Map.of());
        for (AuthStatus status : List.of(AuthStatus.UNKNOWN, AuthStatus.CHECKING, AuthStatus.ONLINE)) {
            ModConfig.get().setAuto(true);
            setStatus(status);
            int before = RuntimeFixtures.connects.get();
            ConnectScreen.startConnecting(original, mc, address, data, true, transfer);
            require(RuntimeFixtures.connects.get() == before + 1, "nonblocking status was intercepted: " + status);
            require(!PendingJoins.isPending(), "pass-through created pending join");
        }
        ModConfig.get().setAuto(false);
        setStatus(AuthStatus.INVALID);
        int before = RuntimeFixtures.connects.get();
        ConnectScreen.startConnecting(original, mc, address, data, false, transfer);
        require(RuntimeFixtures.connects.get() == before + 1, "auto=false intercepted connect");
        ModConfig.get().setAuto(true);
        setStatus(AuthStatus.INVALID);
        ConnectScreen.startConnecting(original, mc, address, data, true, transfer);
        require(RuntimeFixtures.connects.get() == before + 1, "invalid auto join reached connect body");
        require(mc.screen instanceof ServerWaitingScreen, "missing server waiting screen");
        require(PendingJoins.serverData() == data && PendingJoins.transferState() == transfer && PendingJoins.quickPlay(), "pending parameters lost");
        ((ServerWaitingScreen) mc.screen).onClose();
        require(!PendingJoins.isPending() && mc.screen == original, "waiting cancel retained pending join");
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<Void> serverRecovery() {
        resetAccounts();
        ModConfig.get().setAuto(true);
        RuntimeFixtures.probe = AuthStatus.ONLINE;
        SessionApplier.installPreparedOnClientThread(RuntimeFixtures.prepared(RuntimeFixtures.user(RuntimeFixtures.A, "Alice")));
        setStatus(AuthStatus.INVALID);
        int before = RuntimeFixtures.connects.get();
        var address = ServerAddress.parseString("127.0.0.1:25562");
        var data = new ServerData("Fixture", "127.0.0.1:25562", ServerData.Type.OTHER);
        var transfer = new TransferState(Map.of());
        ConnectScreen.startConnecting(new ServerWaitingScreen(original), Minecraft.getInstance(), address, data, true, transfer);
        return waitFor(() -> RuntimeFixtures.connects.get() == before + 1, () -> {
            var call = RuntimeFixtures.connect;
            require(call.parent() == original && call.data() == data && call.transfer() == transfer && call.quickPlay(), "resume arguments/parent changed");
            require(!PendingJoins.isPending(), "resumed request still pending");
            Minecraft.getInstance().setScreen(original);
        }).thenCompose(ignored -> {
            RuntimeFixtures.refreshBarrier = new CompletableFuture<>();
            setStatus(AuthStatus.INVALID);
            ConnectScreen.startConnecting(original, Minecraft.getInstance(), address, data, false, transfer);
            return waitFor(() -> SessionOrchestrator.get().isBusy(), () -> {
                ((ServerWaitingScreen) Minecraft.getInstance().screen).onClose();
                RuntimeFixtures.refreshBarrier.complete(new MicrosoftAuth.AuthResult("synthetic-cancelled-access", "synthetic-cancelled-refresh", RuntimeFixtures.A, "Alice"));
                RuntimeFixtures.refreshBarrier = null;
            }).thenCompose(nothing -> afterTicks(5, () -> {
                require(RuntimeFixtures.connects.get() == before + 1, "cancelled join resumed after late refresh");
                require(!PendingJoins.isPending() && Minecraft.getInstance().screen == original, "cancel failed navigation");
            }));
        });
    }

    private static CompletableFuture<Void> disconnects() {
        ModConfig.get().setAuto(false);
        var nested = new ServerWaitingScreen(new ServerWaitingScreen(original));
        Component title = Component.literal("Fixture failure");
        Component reason = Component.literal("Synthetic disconnect");
        List<Supplier<DisconnectedScreen>> constructors = List.of(
                () -> new DisconnectedScreen(nested, title, reason),
                () -> new DisconnectedScreen(nested, title, reason, Component.literal("Back")),
                () -> new DisconnectedScreen(nested, title, new DisconnectionDetails(reason)),
                () -> new DisconnectedScreen(nested, title, new DisconnectionDetails(reason), Component.literal("Back")));
        for (Supplier<DisconnectedScreen> constructor : constructors) {
            PendingJoins.beginRealms(nested);
            DisconnectedScreen screen = constructor.get();
            require(!PendingJoins.isPending(), "disconnect overload retained pending request");
            require(((RuntimeDisconnectAccess) screen).autoauthTest$parent() == original, "disconnect overload retained waiting parent");
        }
        DisconnectedScreen screen = constructors.getFirst().get();
        Minecraft.getInstance().setScreen(screen);
        return afterTicks(2, () -> {
            Button back = buttons(screen).stream().findFirst().orElseThrow();
            back.onPress();
            require(Minecraft.getInstance().screen == original, "disconnect Back did not reach original parent");
        });
    }

    private static CompletableFuture<Void> realms() {
        resetAccounts();
        ModConfig.get().setAuto(true);
        SessionApplier.installPreparedOnClientThread(RuntimeFixtures.prepared(RuntimeFixtures.user(RuntimeFixtures.A, "Alice")));
        setStatus(AuthStatus.ONLINE);
        RealmsAvailabilityAccess.autoauth$setFuture(CompletableFuture.completedFuture(new RealmsAvailability.Result(RealmsAvailability.Type.AUTHENTICATION_ERROR)));
        Screen wait = new RealmsAvailability.Result(RealmsAvailability.Type.AUTHENTICATION_ERROR).createErrorScreen(original);
        require(wait instanceof RealmsWaitingScreen, "Realms auth error not intercepted");
        require(AuthStatusService.get().getStatus() == AuthStatus.INVALID, "stale ONLINE was retained on Realms auth error");
        require(new RealmsAvailability.Result(RealmsAvailability.Type.AUTHENTICATION_ERROR).createErrorScreen(original) instanceof RealmsWaitingScreen,
                "duplicate in-flight auth error did not reuse pending recovery");
        int checks = RuntimeFixtures.availabilityChecks.get();
        Minecraft.getInstance().setScreen(wait);
        return waitFor(() -> Minecraft.getInstance().screen instanceof RealmsMainScreen && !PendingJoins.isPending(), () -> {
            require("Alice".equals(RuntimeFixtures.realmsName), "Realms recovery captured incorrect account");
        }).thenCompose(ignored -> waitFor(() -> RuntimeFixtures.availabilityChecks.get() > checks, () -> {
            var fresh = RealmsAvailabilityAccess.autoauth$getFuture();
            require(fresh != null && fresh.join().type() == RealmsAvailability.Type.SUCCESS, "Realms retained cached auth error");
            Screen nonAuth = new RealmsAvailability.Result(RealmsAvailability.Type.INCOMPATIBLE_CLIENT).createErrorScreen(original);
            require(!(nonAuth instanceof RealmsWaitingScreen), "non-auth Realms error intercepted");
            Screen consent = new RealmsAvailability.Result(RealmsAvailability.Type.NEEDS_PARENTAL_CONSENT).createErrorScreen(original);
            require(!(consent instanceof RealmsWaitingScreen), "parental-consent Realms error intercepted");
            Minecraft.getInstance().setScreen(original);
        }));
    }

    private static CompletableFuture<Void> localConnectionFailure() {
        ModConfig.get().setAuto(false);
        setStatus(AuthStatus.ONLINE);
        try {
            ServerSocket fixture = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            fixture.setSoTimeout(15_000);
            CompletableFuture<Void> accepted = CompletableFuture.runAsync(() -> {
                try (var socket = fixture.accept()) {
                    // Force a reset on both epoll and NIO instead of relying on half-close timing.
                    socket.setSoLinger(true, 0);
                } catch (java.io.IOException error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            });
            RuntimeFixtures.allowLocalSocket = true;
            String host = "127.0.0.1:" + fixture.getLocalPort();
            Screen nested = new ServerWaitingScreen(original);
            PendingJoins.beginRealms(nested);
            ConnectScreen.startConnecting(nested, Minecraft.getInstance(), ServerAddress.parseString(host),
                    new ServerData("Loopback fixture", host, ServerData.Type.OTHER), false, null);
            return waitFor(() -> Minecraft.getInstance().screen instanceof DisconnectedScreen && accepted.isDone(), () -> {
                accepted.join();
                DisconnectedScreen screen = (DisconnectedScreen) Minecraft.getInstance().screen;
                require(!PendingJoins.isPending(), "real disconnect retained pending request");
                require(((RuntimeDisconnectAccess) screen).autoauthTest$parent() == original, "real disconnect retained waiting parent");
                buttons(screen).getFirst().onPress();
                require(Minecraft.getInstance().screen == original, "real disconnect Back retained waiting parent");
            }).whenComplete((ignored, failure) -> {
                RuntimeFixtures.allowLocalSocket = false;
                try { fixture.close(); } catch (java.io.IOException ignoredClose) { }
            });
        } catch (java.io.IOException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static CompletableFuture<Void> accountUi() {
        ModConfig.get().setAuto(false);
        for (int i = 0; i < 18; i++) {
            UUID id = new UUID(0, i + 100);
            ModConfig.get().upsertAccount(new AccountRecord("synthetic-access", "synthetic-refresh", id, "Extra" + i));
        }
        ConfigScreen screen = new ConfigScreen(original);
        Minecraft.getInstance().setScreen(screen);
        return afterTicks(2, () -> {
            var list = (AbstractSelectionList<?>) screen.children().stream().filter(AbstractSelectionList.class::isInstance).findFirst().orElseThrow();
            require(list.children().size() >= 18, "account rows were truncated");
            list.mouseScrolled(list.getX() + 10, list.getY() + 10, 0, -100);
            require(list.getScrollAmount() > 0, "account list cannot scroll");
            var last = (ContainerEventHandler) list.children().getLast();
            Button remove = last.children().stream().filter(Button.class::isInstance).map(Button.class::cast).skip(1).findFirst().orElseThrow();
            int count = ModConfig.get().accountsSnapshot().size();
            remove.onPress();
            require(ModConfig.get().accountsSnapshot().size() == count - 1, "last account cannot be removed");
            buttonWithKey(screen, "autoauth.settings").onPress();
            require(Minecraft.getInstance().screen instanceof SettingsScreen, "settings button did not open settings");
            Screen settings = Minecraft.getInstance().screen;
            Checkbox auto = settings.children().stream().filter(Checkbox.class::isInstance).map(Checkbox.class::cast).findFirst().orElseThrow();
            auto.onPress();
            require(ModConfig.get().auto, "checkbox did not save setting");
            auto.onPress();
            require(!ModConfig.get().auto, "checkbox did not restore setting");
            buttonWithKey(settings, "gui.done").onPress();
            require(Minecraft.getInstance().screen == screen, "settings Done lost account parent");
            screen.resize(Minecraft.getInstance(), 320, 240);
            screen.onClose();
            require(Minecraft.getInstance().screen == original, "account Done lost original parent");
        });
    }

    private static CompletableFuture<Void> browserUi() {
        ConfigScreen accounts = new ConfigScreen(original);
        Minecraft.getInstance().setScreen(accounts);
        buttonWithKey(accounts, "autoauth.add").onPress();
        require(Minecraft.getInstance().screen instanceof WaitingForLoginScreen, "Add did not open login");
        var cancelled = RuntimeAuthFixture.operation;
        Minecraft.getInstance().screen.onClose();
        require(Minecraft.getInstance().screen == accounts, "Cancel nested account screen");
        require(cancelled.result().isCompletedExceptionally(), "Cancel left browser operation running");
        buttonWithKey(accounts, "autoauth.add").onPress();
        return RuntimeAuthFixture.completeLogin().thenCompose(ignored -> waitFor(() -> Minecraft.getInstance().screen == accounts, () -> {
            UUID added = UUID.fromString("33333333-3333-3333-3333-333333333333");
            require(ModConfig.get().findAccount(added).isPresent(), "successful browser login did not save account");
            require("synthetic-added-refresh".equals(ModConfig.get().findAccount(added).orElseThrow().refreshToken()), "wrong final add-account token");
            accounts.onClose();
            require(Minecraft.getInstance().screen == original, "successful Add accumulated account parents");
        }));
    }

    private static void resetAccounts() {
        SessionOrchestrator.get().cancelReauth();
        PendingJoins.cancel();
        // Independent scenarios begin from a known verified period, then induce their own expiration.
        setStatus(AuthStatus.ONLINE);
        SessionOrchestrator.get().onBecameOnline();
        for (AccountRecord record : ModConfig.get().accountsSnapshot()) ModConfig.get().removeAccount(record.uuid());
        ModConfig.get().upsertAccount(new AccountRecord("synthetic-access-Alice", "synthetic-refresh-Alice", RuntimeFixtures.A, "Alice"));
        ModConfig.get().upsertAccount(new AccountRecord("synthetic-access-Bob", "synthetic-refresh-Bob", RuntimeFixtures.B, "Bob"));
    }

    private static void setStatus(AuthStatus status) {
        ((RuntimeStatusAccess) (Object) AuthStatusService.get()).autoauthTest$tracker().invalidate(status);
    }

    private static List<Button> buttons(ContainerEventHandler parent) {
        List<Button> buttons = new ArrayList<>();
        for (GuiEventListener child : parent.children()) {
            if (child instanceof Button button) buttons.add(button);
            if (child instanceof ContainerEventHandler container) buttons.addAll(buttons(container));
        }
        return buttons;
    }

    private static Button buttonNamed(Screen screen, String name) {
        return buttons(screen).stream().filter(button -> button.getMessage().getString().contains(name)).findFirst().orElseThrow();
    }

    private static Button buttonWithKey(Screen screen, String key) {
        String label = Component.translatable(key).getString();
        return buttons(screen).stream().filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }

    private static void click(Screen screen, Button button) {
        require(button.active, "fixture attempted a disabled button");
        double x = button.getX() + button.getWidth() / 2.0;
        double y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "screen did not dispatch the rendered account click");
        screen.mouseReleased(x, y, 0);
    }

    private static CompletableFuture<Void> afterTicks(int delay, Runnable action) {
        int due = ticks + delay;
        return waitFor(() -> ticks >= due, action);
    }

    private static CompletableFuture<Void> waitFor(BooleanSupplier ready, Runnable action) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> waits.add(new Wait(ready, action, done, ticks + 400)));
        return done;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void finish(Throwable error) {
        finished = true;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (error != null) {
                try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    screenshot.writeToFile(mc.gameDirectory.toPath().resolve("failure.png"));
                } catch (Throwable screenshotFailure) {
                    System.err.println("Could not capture client failure screenshot");
                }
            }
            RuntimeAuthFixture.close();
            StringBuilder text = new StringBuilder();
            int skipped = error == null ? 0 : Math.max(0, scenarios.size() - passed.size() - 1);
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"AutoAuthClient\" tests=\"" + scenarios.size()
                    + "\" failures=\"" + (error == null ? 0 : 1) + "\" errors=\"0\" skipped=\"" + skipped + "\">\n");
            for (String id : passed) {
                text.append("PASS ").append(id).append('\n');
                xml.append("  <testcase classname=\"AutoAuthClient\" name=\"").append(id).append("\"/>\n");
            }
            if (error == null) {
                text.append("ALL CLIENT CHECKS PASSED\n");
            } else {
                while (error.getCause() != null) error = error.getCause();
                String id = index > 0 ? scenarios.get(index - 1).id() : "bootstrap";
                text.append("FAIL ").append(id).append(": ").append(error.getClass().getSimpleName()).append('\n');
                xml.append("  <testcase classname=\"AutoAuthClient\" name=\"").append(id).append("\"><failure message=\"Scenario failed; see sanitized client log\"/></testcase>\n");
                for (int unrun = index; unrun < scenarios.size(); unrun++) {
                    xml.append("  <testcase classname=\"AutoAuthClient\" name=\"").append(scenarios.get(unrun).id()).append("\"><skipped/></testcase>\n");
                }
                error.printStackTrace();
            }
            xml.append("</testsuite>\n");
            Files.writeString(mc.gameDirectory.toPath().resolve("results.txt"), text, StandardCharsets.UTF_8);
            Files.writeString(mc.gameDirectory.toPath().resolve("runtime-tests.xml"), xml, StandardCharsets.UTF_8);
            System.out.println(text);
        } catch (Throwable reportFailure) {
            reportFailure.printStackTrace();
            System.exit(2);
        }
        if (error == null) mc.stop();
        else System.exit(1);
    }
}
