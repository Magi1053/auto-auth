package com.skcraft.autoauth.auth;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Generation-aware publication; an outage leaves status UNKNOWN, never INVALID. */
public final class StatusTracker {
    private final Executor client;
    private final LongSupplier clock;
    private final Runnable onOnline;
    private volatile AuthStatus status = AuthStatus.UNKNOWN;
    private long generation;
    private long checkedAt = Long.MIN_VALUE;
    private CompletableFuture<AuthStatus> inFlight;

    public StatusTracker(Executor client, LongSupplier clock) {
        this(client, clock, () -> { });
    }

    public StatusTracker(Executor client, LongSupplier clock, Runnable onOnline) {
        this.client = client;
        this.clock = clock;
        this.onOnline = onOnline;
    }

    public void invalidate(AuthStatus initial) {
        generation++;
        status = initial;
        inFlight = null;
        checkedAt = Long.MIN_VALUE;
    }

    public void refresh(Supplier<CompletableFuture<AuthStatus>> check) {
        if (inFlight != null) return;
        long operation = generation;
        status = AuthStatus.CHECKING;
        try {
            inFlight = check.get();
            inFlight.whenComplete((result, error) -> client.execute(() -> {
                if (generation != operation) return;
                status = error == null && result != null ? result : AuthStatus.UNKNOWN;
                checkedAt = clock.getAsLong();
                inFlight = null;
                if (status == AuthStatus.ONLINE) onOnline.run();
            }));
        } catch (RuntimeException e) {
            status = AuthStatus.UNKNOWN;
            checkedAt = clock.getAsLong();
            inFlight = null;
        }
    }

    public boolean isStale(long ageMs) {
        return inFlight == null && (checkedAt == Long.MIN_VALUE || clock.getAsLong() - checkedAt >= ageMs);
    }
    public AuthStatus getStatus() { return status; }
    public long generation() { return generation; }
}
