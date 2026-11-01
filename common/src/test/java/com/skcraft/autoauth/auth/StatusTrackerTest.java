package com.skcraft.autoauth.auth;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class StatusTrackerTest {
    @Test void delayedPriorSessionResultCannotOverwriteReplacement() {
        StatusTracker tracker = new StatusTracker(Runnable::run, () -> 0);
        var old = new CompletableFuture<AuthStatus>();
        tracker.refresh(() -> old);
        tracker.invalidate(AuthStatus.UNKNOWN);
        tracker.refresh(() -> CompletableFuture.completedFuture(AuthStatus.ONLINE));
        old.complete(AuthStatus.INVALID);
        assertEquals(AuthStatus.ONLINE, tracker.getStatus());
    }

    @Test void checkingAndUnknownDoNotBlockConnectAndOnlyRejectedStatesDo() {
        assertFalse(AuthStatus.CHECKING.blocksConnectWhenAuto());
        assertFalse(AuthStatus.UNKNOWN.blocksConnectWhenAuto());
        assertFalse(AuthStatus.ONLINE.blocksConnectWhenAuto());
        assertTrue(AuthStatus.INVALID.blocksConnectWhenAuto());
        assertTrue(AuthStatus.OFFLINE.blocksConnectWhenAuto());
    }

    @Test void unavailableServiceDoesNotBecomeInvalidAndIsRateLimited() {
        AtomicLong clock = new AtomicLong();
        StatusTracker tracker = new StatusTracker(Runnable::run, clock::get);
        tracker.refresh(() -> CompletableFuture.failedFuture(new IllegalStateException("Synthetic outage")));
        assertEquals(AuthStatus.UNKNOWN, tracker.getStatus());
        assertFalse(tracker.isStale(60_000));
        clock.set(60_000);
        assertTrue(tracker.isStale(60_000));
    }

    @Test void repeatedTicksShareSingleProbe() {
        AtomicInteger count = new AtomicInteger();
        StatusTracker tracker = new StatusTracker(Runnable::run, () -> 0);
        var response = new CompletableFuture<AuthStatus>();
        for (int i = 0; i < 50; i++) tracker.refresh(() -> { count.incrementAndGet(); return response; });
        assertEquals(1, count.get());
        assertEquals(AuthStatus.CHECKING, tracker.getStatus());
        response.complete(AuthStatus.ONLINE);
        assertEquals(AuthStatus.ONLINE, tracker.getStatus());
    }

    @Test void publicationRunsOnClientExecutorAndInvalidationDiscardsQueuedWork() {
        ArrayDeque<Runnable> client = new ArrayDeque<>();
        StatusTracker tracker = new StatusTracker(client::add, () -> 0);
        tracker.refresh(() -> CompletableFuture.completedFuture(AuthStatus.ONLINE));
        assertEquals(AuthStatus.CHECKING, tracker.getStatus());
        tracker.invalidate(AuthStatus.INVALID);
        client.removeFirst().run();
        assertEquals(AuthStatus.INVALID, tracker.getStatus());
    }

    @Test void synchronousProbeFailureCannotLeaveCheckingStuck() {
        StatusTracker tracker = new StatusTracker(Runnable::run, () -> 0);
        tracker.refresh(() -> { throw new IllegalStateException("Synthetic unavailable probe"); });
        assertEquals(AuthStatus.UNKNOWN, tracker.getStatus());
        tracker.refresh(() -> CompletableFuture.completedFuture(AuthStatus.OFFLINE));
        assertEquals(AuthStatus.OFFLINE, tracker.getStatus());
    }

    @Test void onlineNotificationRunsOnPublicationExactlyOnceWithoutMenuTicks() {
        ArrayDeque<Runnable> client = new ArrayDeque<>();
        AtomicInteger online = new AtomicInteger();
        StatusTracker tracker = new StatusTracker(client::add, () -> 0, online::incrementAndGet);
        CompletableFuture<AuthStatus> response = new CompletableFuture<>();
        tracker.refresh(() -> response);
        response.complete(AuthStatus.ONLINE);
        assertEquals(0, online.get(), "The worker must not notify recovery directly");
        client.removeFirst().run();
        assertEquals(1, online.get());
        assertFalse(response.complete(AuthStatus.ONLINE));
        assertTrue(client.isEmpty());
    }

    @Test void obsoleteOnlinePublicationCannotResetTheNewSessionsRecovery() {
        ArrayDeque<Runnable> client = new ArrayDeque<>();
        AtomicInteger online = new AtomicInteger();
        StatusTracker tracker = new StatusTracker(client::add, () -> 0, online::incrementAndGet);
        tracker.refresh(() -> CompletableFuture.completedFuture(AuthStatus.ONLINE));
        tracker.invalidate(AuthStatus.INVALID);
        client.removeFirst().run();
        assertEquals(0, online.get());
        assertEquals(AuthStatus.INVALID, tracker.getStatus());
    }
}
