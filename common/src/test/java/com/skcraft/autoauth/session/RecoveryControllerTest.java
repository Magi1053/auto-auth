package com.skcraft.autoauth.session;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryControllerTest {
    private static final RecoveryController.Account A = new RecoveryController.Account(UUID.randomUUID(), "Alice", "synthetic-a");
    private static final RecoveryController.Account B = new RecoveryController.Account(UUID.randomUUID(), "Bob", "synthetic-b");
    private static RecoveryController.Result result(RecoveryController.Account account) {
        return new RecoveryController.Result(account.uuid(), "Synthetic", "synthetic-access", "synthetic-rotated");
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong();
        final ArrayDeque<CompletableFuture<RecoveryController.Result>> requests = new ArrayDeque<>();
        final List<RecoveryController.Account> requested = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final AtomicInteger successes = new AtomicInteger();
        boolean present = true;
        boolean failSave;
        CompletableFuture<Void> installation = CompletableFuture.completedFuture(null);
        final AtomicReference<BooleanSupplier> commitAllowed = new AtomicReference<>();
        final RecoveryController controller = new RecoveryController(Runnable::run, clock::get,
                account -> { requested.add(account); return requests.removeFirst(); }, account -> present,
                value -> { if (failSave) throw new IllegalStateException("Disk unavailable"); events.add("persist"); },
                (value, current) -> { events.add("install"); commitAllowed.set(current); return installation; },
                error -> error instanceof IOException, successes::incrementAndGet);
        CompletableFuture<RecoveryController.Result> next() {
            var future = new CompletableFuture<RecoveryController.Result>();
            requests.add(future);
            return future;
        }
    }

    @Test void explicitSelectionOverridesCurrentAndDefaultAccount() {
        assertEquals(B, RecoveryController.select(B.uuid(), A.uuid(), null, A.uuid(), List.of(A, B)));
        assertNull(RecoveryController.select(UUID.randomUUID(), A.uuid(), null, A.uuid(), List.of(A, B)));
    }

    @Test void automaticSelectionUsesCurrentThenDefaultThenOnlyAccount() {
        assertEquals(A, RecoveryController.select(null, A.uuid(), null, B.uuid(), List.of(A, B)));
        assertEquals(B, RecoveryController.select(null, UUID.randomUUID(), null, B.uuid(), List.of(A, B)));
        assertEquals(A, RecoveryController.select(null, null, null, null, List.of(A)));
        assertNull(RecoveryController.select(null, null, null, null, List.of(A, B)));
    }

    @Test void offlineSessionRecoversItsSavedAccountBeforeTheDefault() {
        String name = "alice";
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(A, RecoveryController.select(null, offline, name, B.uuid(), List.of(B, A)));
        assertEquals(A, RecoveryController.select(null, null, name, B.uuid(), List.of(B, A)));
        assertEquals(B, RecoveryController.select(null, B.uuid(), name, A.uuid(), List.of(A, B)));
        var duplicate = new RecoveryController.Account(UUID.randomUUID(), "Alice", "other-token");
        assertEquals(B, RecoveryController.select(null, offline, name, B.uuid(), List.of(A, B, duplicate)));
    }

    @Test void persistsFinalResultBeforeInstallAndAllowsFutureRecovery() {
        Fixture f = new Fixture();
        var first = f.next();
        assertTrue(f.controller.attempt(B, false));
        first.complete(result(B));
        assertEquals(List.of(B), f.requested);
        assertEquals(List.of("persist", "install"), f.events);
        assertEquals(1, f.successes.get());
        assertFalse(f.controller.isBusy());
        var second = f.next();
        assertTrue(f.controller.attempt(B, false));
        second.complete(result(B));
        assertEquals(2, f.successes.get());
    }

    @Test void concurrentAndForcedRequestsCannotDuplicateFlight() {
        Fixture f = new Fixture();
        var response = f.next();
        assertTrue(f.controller.attempt(A, false));
        for (int i = 0; i < 100; i++) assertFalse(f.controller.attempt(B, i % 2 == 0));
        assertEquals(List.of(A), f.requested);
        response.complete(result(A));
    }

    @Test void transientFailureWaitsExactlyUntilDeadlineAndStopsAfterThreeFailures() {
        Fixture f = new Fixture();
        for (int failure = 0; failure < 3; failure++) {
            var response = f.next();
            assertTrue(f.controller.attempt(A, false));
            response.completeExceptionally(new IOException("Synthetic outage"));
            assertFalse(f.controller.attempt(A, false));
            f.clock.addAndGet(29_999);
            assertFalse(f.controller.attempt(A, false));
            f.clock.incrementAndGet();
        }
        assertFalse(f.controller.attempt(A, false));
        assertEquals("autoauth.progress.failed", f.controller.message());
        var retry = f.next();
        assertTrue(f.controller.attempt(A, true));
        retry.complete(result(A));
        assertEquals(1, f.successes.get());
    }

    @Test void permanentFailureNeedsAnExplicitRetry() {
        Fixture f = new Fixture();
        var response = f.next();
        f.controller.attempt(A, false);
        response.completeExceptionally(new IllegalArgumentException("Login required"));
        f.clock.set(1_000_000);
        assertFalse(f.controller.attempt(A, false));
        assertEquals("autoauth.progress.failed", f.controller.message());
    }

    @Test void removedOrChangedAccountCannotBeRecreatedByLateRefresh() {
        Fixture f = new Fixture();
        var response = f.next();
        f.controller.attempt(A, true);
        f.present = false;
        response.complete(result(A));
        assertTrue(f.events.isEmpty());
        assertEquals(0, f.successes.get());
        assertFalse(f.controller.isBusy());
    }

    @Test void mismatchedReturnedIdentityCannotOverwriteTheRequestedAccount() {
        Fixture f = new Fixture();
        var response = f.next();
        f.controller.attempt(A, true);
        response.complete(result(B));
        assertTrue(f.events.isEmpty());
        assertEquals("autoauth.progress.failed", f.controller.message());
    }

    @Test void saveFailureNeverAppliesOrReportsSuccess() {
        Fixture f = new Fixture();
        f.failSave = true;
        var response = f.next();
        f.controller.attempt(A, true);
        response.complete(result(A));
        assertTrue(f.events.isEmpty());
        assertEquals(0, f.successes.get());
        assertFalse(f.controller.isBusy());
    }

    @Test void cancellationDiscardsOldResultWithoutBlockingANewExplicitAttempt() {
        Fixture f = new Fixture();
        var old = f.next();
        f.controller.attempt(A, false);
        f.controller.cancel();
        var replacement = f.next();
        f.controller.attempt(B, true);
        old.complete(result(A));
        assertTrue(f.events.isEmpty());
        replacement.complete(result(B));
        assertEquals(1, f.successes.get());
    }

    @Test void cancelledPreparationCannotCommitOrPublishSuccess() {
        Fixture f = new Fixture();
        f.installation = new CompletableFuture<>();
        var response = f.next();
        f.controller.attempt(A, false);
        response.complete(result(A));
        assertTrue(f.commitAllowed.get().getAsBoolean());
        f.controller.cancel();
        assertFalse(f.commitAllowed.get().getAsBoolean());
        f.installation.complete(null);
        assertEquals(0, f.successes.get());
    }

    @Test void missingAccountShowsActionWithoutStartingNetworkWork() {
        Fixture f = new Fixture();
        assertFalse(f.controller.attempt(null, false));
        assertEquals("autoauth.progress.no_account", f.controller.message());
        assertTrue(f.requested.isEmpty());
    }

    @Test void freshlyAppliedButRejectedCredentialCannotCauseAnAutomaticRefreshLoop() {
        Fixture f = new Fixture();
        var response = f.next();
        f.controller.attempt(A, false);
        response.complete(result(A));
        f.controller.verificationRejected();
        f.clock.set(1_000_000);
        assertFalse(f.controller.attempt(A, false));
        assertEquals("autoauth.progress.failed", f.controller.message());
        f.controller.becameOnline();
        f.next();
        assertTrue(f.controller.attempt(A, false));
    }

    @Test void workerCompletionsAreMarshalledThroughClientExecutor() {
        ArrayDeque<Runnable> client = new ArrayDeque<>();
        var response = new CompletableFuture<RecoveryController.Result>();
        AtomicInteger saves = new AtomicInteger();
        RecoveryController controller = new RecoveryController(client::add, () -> 0,
                account -> response, account -> true, value -> saves.incrementAndGet(),
                (value, current) -> CompletableFuture.completedFuture(null), error -> false, () -> { });
        controller.attempt(A, true);
        response.complete(result(A));
        assertEquals(0, saves.get());
        client.removeFirst().run();
        assertEquals(1, saves.get());
        assertTrue(controller.isBusy());
        client.removeFirst().run();
        assertFalse(controller.isBusy());
    }
}
