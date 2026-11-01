package com.skcraft.autoauth.session;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Client-executor-owned recovery policy, independent of Minecraft and wall-clock sleeps. */
public final class RecoveryController {
    public record Account(UUID uuid, String username, String refreshToken) {
        @Override public String toString() { return "Account[" + uuid + "]"; }
    }
    public record Result(UUID uuid, String username, String accessToken, String refreshToken) {
        @Override public String toString() { return "Result[" + uuid + "]"; }
    }

    private static final long COOLDOWN_MS = 30_000;
    private static final int MAX_FAILURES = 3;
    private final Executor client;
    private final LongSupplier clock;
    private final Function<Account, CompletableFuture<Result>> gateway;
    private final Predicate<Account> present;
    private final Consumer<Result> persist;
    private final BiFunction<Result, BooleanSupplier, CompletableFuture<Void>> install;
    private final Predicate<Throwable> retryable;
    private final Runnable success;
    private boolean busy;
    private boolean blocked;
    private boolean awaitingVerification;
    private int failures;
    private long retryAt;
    private volatile long generation;
    private String message = "autoauth.progress.idle";

    public RecoveryController(Executor client, LongSupplier clock,
            Function<Account, CompletableFuture<Result>> gateway, Predicate<Account> present,
            Consumer<Result> persist, BiFunction<Result, BooleanSupplier, CompletableFuture<Void>> install,
            Predicate<Throwable> retryable, Runnable success) {
        this.client = client;
        this.clock = clock;
        this.gateway = gateway;
        this.present = present;
        this.persist = persist;
        this.install = install;
        this.retryable = retryable;
        this.success = success;
    }

    /** Explicit selection never falls back to a different account. */
    public static Account select(UUID explicit, UUID current, String sessionName, UUID defaults, List<Account> accounts) {
        if (explicit != null) return find(explicit, accounts);
        Account match = find(current, accounts);
        if (match != null) return match;
        if (sessionName != null && !sessionName.isBlank() && (current == null || current.equals(
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + sessionName).getBytes(java.nio.charset.StandardCharsets.UTF_8))))) {
            var named = accounts.stream().filter(a -> sessionName.equalsIgnoreCase(a.username())).limit(2).toList();
            if (named.size() == 1) return named.getFirst();
        }
        match = find(defaults, accounts);
        return match != null ? match : accounts.size() == 1 ? accounts.getFirst() : null;
    }

    private static Account find(UUID id, List<Account> accounts) {
        return id == null ? null : accounts.stream().filter(a -> id.equals(a.uuid())).findFirst().orElse(null);
    }

    public boolean attempt(Account account, boolean force) {
        if (busy || (!force && (blocked || clock.getAsLong() < retryAt))) return false;
        if (force) { failures = 0; blocked = false; retryAt = 0; awaitingVerification = false; }
        if (account == null || account.refreshToken() == null || account.refreshToken().isBlank()) {
            blocked = true;
            message = "autoauth.progress.no_account";
            return false;
        }
        busy = true;
        message = "autoauth.progress.signing_in";
        long operation = ++generation;
        try {
            gateway.apply(account).whenComplete((result, error) -> client.execute(() -> {
                if (operation != generation) return;
                if (error != null) { fail(error); return; }
                try {
                    if (result == null || !Objects.equals(account.uuid(), result.uuid())) {
                        throw new IllegalStateException("Returned identity does not match the selected account");
                    }
                    if (!present.test(account)) throw new IllegalStateException("Saved account changed during sign-in");
                    persist.accept(result);
                    install.apply(result, () -> operation == generation).whenComplete((ignored, applyError) -> client.execute(() -> {
                        if (operation != generation) return;
                        if (applyError != null) { fail(applyError); return; }
                        busy = false;
                        blocked = false;
                        retryAt = 0;
                        awaitingVerification = true;
                        message = "autoauth.progress.checking";
                        success.run();
                    }));
                } catch (Throwable failure) {
                    fail(failure);
                }
            }));
        } catch (Throwable failure) {
            fail(failure);
        }
        return true;
    }

    private void fail(Throwable error) {
        busy = false;
        failures++;
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        blocked = failures >= MAX_FAILURES || !retryable.test(root);
        retryAt = clock.getAsLong() + COOLDOWN_MS;
        message = blocked ? "autoauth.progress.failed" : "autoauth.progress.retry_later";
    }

    public void becameOnline() {
        if (!busy) {
            failures = 0;
            blocked = false;
            retryAt = 0;
            message = "autoauth.progress.idle";
            awaitingVerification = false;
        }
    }

    /** A freshly installed token rejected by the verification probe requires user action. */
    public void verificationRejected() {
        if (awaitingVerification) {
            awaitingVerification = false;
            blocked = true;
            message = "autoauth.progress.failed";
        }
    }

    public void cancel() {
        generation++;
        busy = false;
        blocked = true;
        awaitingVerification = false;
        message = "autoauth.progress.failed";
    }

    public boolean isBusy() { return busy; }
    public String message() { return message; }
}
