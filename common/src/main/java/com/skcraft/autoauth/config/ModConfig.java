package com.skcraft.autoauth.config;

import com.skcraft.autoauth.Constants;
import com.skcraft.autoauth.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Client-owned settings facade. Account-returning methods always provide detached snapshots. */
public final class ModConfig {
    private static volatile ModConfig INSTANCE = new ModConfig();

    public boolean debug;
    public boolean auto = true;
    public String clientId = Constants.defaultAzureClientId();
    public List<AccountRecord> accounts = new ArrayList<>();
    public UUID defaultUuid;

    private transient ConfigStore store;
    private transient ConfigStore.Snapshot committed;

    private ModConfig() {
    }

    public static ModConfig get() {
        return INSTANCE;
    }

    public static String clientId() {
        String configured = get().clientId;
        String fallback = configured == null || configured.isBlank() ? Constants.defaultAzureClientId() : configured.trim();
        return ClientIdConfig.resolve(configPath().resolveSibling("client.json"), fallback);
    }

    public static Path configPath() {
        return Services.PLATFORM.getConfigDirectory().resolve("config.json");
    }

    public static synchronized void load() {
        INSTANCE = open(new ConfigStore(configPath(), Constants.defaultAzureClientId()));
    }

    // Explicit store seam for deterministic persistence tests, never selected by a runtime flag.
    static ModConfig open(ConfigStore store) {
        ModConfig config = new ModConfig();
        config.store = store;
        config.publish(store.load());
        return config;
    }

    public static void save() {
        get().saveCurrent();
    }

    private synchronized void saveCurrent() {
        ConfigStore.Snapshot candidate = snapshot();
        try {
            store().save(candidate);
            committed = candidate;
        } catch (ConfigException failure) {
            if (committed != null) {
                publish(committed);
            }
            throw failure;
        }
    }

    private ConfigStore store() {
        if (store == null) {
            // A failed startup read must never turn an unknown existing account
            // file into a writable empty configuration on the next UI action.
            throw new ConfigException("Saved accounts are unavailable. Fix access to the config folder and restart Minecraft.");
        }
        return store;
    }

    public synchronized ConfigStore.Snapshot snapshot() {
        return new ConfigStore.Snapshot(debug, auto, clientId, accountsSnapshot(), defaultUuid);
    }

    public synchronized List<AccountRecord> accountsSnapshot() {
        return accounts.stream().map(AccountRecord::copy).toList();
    }

    public synchronized Optional<AccountRecord> findAccount(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return accounts.stream().filter(account -> uuid.equals(account.uuid())).findFirst().map(AccountRecord::copy);
    }

    public Optional<AccountRecord> accountForCurrentUser() {
        User user = Minecraft.getInstance().getUser();
        return selectAccount(user == null ? null : user.getProfileId());
    }

    /** Automatic fallback policy; manual switching must pass the selected UUID explicitly. */
    public synchronized Optional<AccountRecord> selectAccount(UUID currentUuid) {
        Optional<AccountRecord> current = findAccount(currentUuid);
        if (current.isPresent()) {
            return current;
        }
        Optional<AccountRecord> fallback = findAccount(defaultUuid);
        return fallback.isPresent() ? fallback : accounts.size() == 1 ? Optional.of(accounts.getFirst().copy()) : Optional.empty();
    }

    public synchronized void setAuto(boolean enabled) {
        persist(new ConfigStore.Snapshot(debug, enabled, clientId, accountsSnapshot(), defaultUuid));
    }

    public synchronized void setDebug(boolean enabled) {
        persist(new ConfigStore.Snapshot(enabled, auto, clientId, accountsSnapshot(), defaultUuid));
    }

    public synchronized void setDefault(UUID uuid) {
        persist(new ConfigStore.Snapshot(debug, auto, clientId, accountsSnapshot(), uuid));
    }

    public synchronized void upsertAccount(AccountRecord record) {
        Objects.requireNonNull(record, "record");
        List<AccountRecord> next = new ArrayList<>(accountsSnapshot());
        next.removeIf(existing -> Objects.equals(existing.uuid(), record.uuid()));
        next.add(record.copy());
        persist(new ConfigStore.Snapshot(debug, auto, clientId, next, defaultUuid == null ? record.uuid() : defaultUuid));
    }

    public synchronized void removeAccount(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        List<AccountRecord> next = new ArrayList<>(accountsSnapshot());
        next.removeIf(account -> uuid.equals(account.uuid()));
        UUID nextDefault = uuid.equals(defaultUuid) ? next.isEmpty() ? null : next.getFirst().uuid() : defaultUuid;
        persist(new ConfigStore.Snapshot(debug, auto, clientId, next, nextDefault));
    }

    public synchronized void updateTokens(UUID uuid, String accessToken, String refreshToken, String username) {
        updateTokens(uuid, accessToken, refreshToken, username, false);
    }

    public synchronized void updateTokens(UUID uuid, String accessToken, String refreshToken, String username, boolean makeDefault) {
        List<AccountRecord> next = new ArrayList<>(accountsSnapshot());
        AccountRecord account = next.stream().filter(value -> Objects.equals(uuid, value.uuid())).findFirst()
                .orElseThrow(() -> new ConfigException("This account was removed while sign-in was running. Add it again to continue."));
        account.setAccessToken(accessToken);
        if (refreshToken != null && !refreshToken.isBlank()) {
            account.setRefreshToken(refreshToken);
        }
        if (username != null && !username.isBlank()) {
            account.setUsername(username);
        }
        persist(new ConfigStore.Snapshot(debug, auto, clientId, next, makeDefault ? uuid : defaultUuid));
    }

    private void persist(ConfigStore.Snapshot candidate) {
        store().save(candidate);
        publish(candidate);
    }

    private void publish(ConfigStore.Snapshot snapshot) {
        debug = snapshot.debug();
        auto = snapshot.auto();
        clientId = snapshot.clientId();
        accounts = new ArrayList<>(snapshot.accounts());
        defaultUuid = snapshot.defaultUuid();
        committed = snapshot;
    }
}
