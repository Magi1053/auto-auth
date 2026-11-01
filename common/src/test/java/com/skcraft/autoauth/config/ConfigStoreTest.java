package com.skcraft.autoauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    @TempDir Path directory;

    private Path path() {
        return directory.resolve("config.json");
    }

    private ConfigStore store() {
        return new ConfigStore(path(), "built-in-client");
    }

    private static AccountRecord account(UUID uuid, String name) {
        return new AccountRecord("synthetic-access-" + name, "synthetic-refresh-" + name, uuid, name);
    }

    @Test
    void uninitializedFacadeCannotOverwriteUnreadableAccountsAfterStartupFailure() throws Exception {
        var constructor = ModConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ModConfig unavailable = constructor.newInstance();
        ConfigException failure = assertThrows(ConfigException.class, () -> unavailable.upsertAccount(account(A, "Alice")));
        assertTrue(failure.getMessage().contains("restart"));
        assertTrue(unavailable.accountsSnapshot().isEmpty());
    }

    @Test
    void createsAndRoundTripsSettingsAndAccountsWithoutBomOrCrLf() throws Exception {
        ConfigStore store = store();
        var initial = store.load();
        assertTrue(initial.auto());
        assertFalse(initial.debug());
        assertEquals("built-in-client", initial.clientId());
        assertTrue(initial.accounts().isEmpty());
        store.save(new ConfigStore.Snapshot(true, false, "override-client", List.of(account(A, "Alice")), A));
        var loaded = store.load();
        assertTrue(loaded.debug());
        assertFalse(loaded.auto());
        assertEquals("override-client", loaded.clientId());
        assertEquals("synthetic-refresh-Alice", loaded.accounts().getFirst().refreshToken());
        assertEquals(A, loaded.defaultUuid());
        String contents = Files.readString(path());
        assertFalse(contents.startsWith("\uFEFF"));
        assertFalse(contents.contains("\r"));
        assertTrue(contents.endsWith("\n"));
        try (var files = Files.list(directory)) {
            assertEquals(List.of("config.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void migratesMissingAndBlankClientIdAndPreservesExplicitOverride() throws Exception {
        for (String source : List.of("{}", "{\"clientId\":null}", "{\"clientId\":\"   \"}")) {
            Files.writeString(path(), source);
            assertEquals("built-in-client", store().load().clientId());
        }
        Files.writeString(path(), "{\"clientId\":\"  pack-client  \"}");
        assertEquals("pack-client", store().load().clientId());
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "ordinary migration is not classified as corruption");
        }
    }

    @Test
    void corruptJsonIsPreservedBeforeDefaultsReplaceIt() throws Exception {
        String invalid = "{\"refreshToken\":\"synthetic-private-token\", broken";
        Files.writeString(path(), invalid);
        assertTrue(store().load().accounts().isEmpty());
        List<Path> backup;
        try (var files = Files.list(directory)) {
            backup = files.filter(p -> p.getFileName().toString().contains(".corrupt-")).toList();
        }
        assertEquals(1, backup.size());
        assertEquals(invalid, Files.readString(backup.getFirst()));
        assertFalse(Files.readString(path()).contains("synthetic-private-token"));
    }

    @Test
    void removesInvalidRecordsKeepsLatestDuplicateAndRepairsDanglingDefault() throws Exception {
        Files.writeString(path(), """
                {"accounts":[null,{"username":"MissingId"},
                  {"uuid":"11111111-1111-1111-1111-111111111111","username":"Old","refreshToken":"old"},
                  {"uuid":"11111111-1111-1111-1111-111111111111","username":"Alice","refreshToken":"new"}],
                 "defaultUuid":"22222222-2222-2222-2222-222222222222"}
                """);
        var loaded = store().load();
        assertEquals(1, loaded.accounts().size());
        assertEquals("Alice", loaded.accounts().getFirst().username());
        assertEquals("new", loaded.accounts().getFirst().refreshToken());
        assertEquals("", loaded.accounts().getFirst().accessToken());
        assertEquals(A, loaded.defaultUuid());
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void rejectsCoercedBooleanAndNonObjectRoots() throws Exception {
        for (String invalid : List.of("null", "[]", "{\"auto\":\"false\"}")) {
            Files.writeString(path(), invalid);
            assertTrue(store().load().auto());
        }
    }

    @Test
    void snapshotsAreDefensiveAtConstructionAndRead() {
        AccountRecord input = account(A, "Alice");
        ArrayList<AccountRecord> list = new ArrayList<>(List.of(input));
        var snapshot = new ConfigStore.Snapshot(false, true, "client", list, A);
        input.setUsername("Changed");
        list.clear();
        snapshot.accounts().getFirst().setRefreshToken("Changed");
        assertEquals("Alice", snapshot.accounts().getFirst().username());
        assertEquals("synthetic-refresh-Alice", snapshot.accounts().getFirst().refreshToken());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.accounts().clear());
    }

    @Test
    void failedAtomicReplacementPreservesOriginalAndDeletesTemporary() throws Exception {
        store().load();
        byte[] original = Files.readAllBytes(path());
        ConfigStore failing = new ConfigStore(path(), "client", (destination, contents) ->
                ConfigStore.writeAtomic(destination, contents, (temporary, target) -> {
                    assertTrue(Files.size(temporary) > 0, "the candidate was written before replacement");
                    throw new IOException("synthetic-private-token");
                }));
        ConfigException error = assertThrows(ConfigException.class, () ->
                failing.save(new ConfigStore.Snapshot(true, true, "client", List.of(account(A, "Alice")), A)));
        assertArrayEquals(original, Files.readAllBytes(path()));
        assertNull(error.getCause());
        assertFalse(error.toString().contains("synthetic-private-token"));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void unsupportedAtomicReplacementDoesNotFallBackToDestructiveWrite() throws Exception {
        store().load();
        String original = Files.readString(path());
        assertThrows(IOException.class, () -> ConfigStore.writeAtomic(path(), "new-data", (temporary, target) -> {
            throw new AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "unsupported");
        }));
        assertEquals(original, Files.readString(path()));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void invalidSaveCannotOverwriteValidFile() throws Exception {
        store().load();
        String original = Files.readString(path());
        assertThrows(ConfigException.class, () -> store().save(new ConfigStore.Snapshot(false, true, "client",
                List.of(account(A, "Alice"), account(A, "Duplicate")), A)));
        assertThrows(ConfigException.class, () -> store().save(new ConfigStore.Snapshot(false, true, "client", List.of(), B)));
        assertEquals(original, Files.readString(path()));
    }

    @Test
    void unreadableInputDoesNotResetExistingState() throws Exception {
        Files.createDirectory(path());
        ConfigException error = assertThrows(ConfigException.class, () -> store().load());
        assertTrue(Files.isDirectory(path()));
        assertNull(error.getCause());
    }

    @Test
    void autoSelectionPrefersCurrentThenDefaultThenOnlySavedAccount() {
        ModConfig config = ModConfig.open(store());
        config.upsertAccount(account(A, "Alice"));
        config.upsertAccount(account(B, "Bob"));
        config.setDefault(B);
        assertEquals(A, config.selectAccount(A).orElseThrow().uuid());
        assertEquals(B, config.selectAccount(null).orElseThrow().uuid());
        config.setDefault(null);
        assertTrue(config.selectAccount(null).isEmpty());
        config.removeAccount(B);
        assertEquals(A, config.selectAccount(null).orElseThrow().uuid());
        config.removeAccount(A);
        assertTrue(config.selectAccount(null).isEmpty());
    }

    @Test
    void finalTokensPersistAndRemovedAccountIsNeverRecreated() {
        ModConfig config = ModConfig.open(store());
        config.upsertAccount(account(A, "Alice"));
        config.updateTokens(A, "rotated-access", "rotated-refresh", "Renamed");
        ModConfig reopened = ModConfig.open(store());
        AccountRecord persisted = reopened.findAccount(A).orElseThrow();
        assertEquals("rotated-access", persisted.accessToken());
        assertEquals("rotated-refresh", persisted.refreshToken());
        assertEquals("Renamed", persisted.username());
        reopened.updateTokens(A, "next-access", null, null);
        assertEquals("rotated-refresh", reopened.findAccount(A).orElseThrow().refreshToken());
        reopened.removeAccount(A);
        assertThrows(ConfigException.class, () -> reopened.updateTokens(A, "late-access", "late-refresh", "Alice"));
        assertTrue(ModConfig.open(store()).accountsSnapshot().isEmpty());
        assertNull(reopened.defaultUuid);
    }

    @Test
    void inputAndReturnedAccountsCannotChangeCommittedTokens() {
        ModConfig config = ModConfig.open(store());
        AccountRecord input = account(A, "Alice");
        config.upsertAccount(input);
        input.setRefreshToken("mutated-input");
        config.findAccount(A).orElseThrow().setRefreshToken("mutated-output");
        config.accountsSnapshot().getFirst().setRefreshToken("mutated-snapshot");
        assertEquals("synthetic-refresh-Alice", config.findAccount(A).orElseThrow().refreshToken());
    }

    @Test
    void manualSwitchCommitsDefaultAndRotatedTokensTogether() {
        ModConfig config = ModConfig.open(store());
        config.upsertAccount(account(A, "Alice"));
        config.upsertAccount(account(B, "Bob"));
        assertEquals(A, config.defaultUuid);
        config.updateTokens(B, "new-access", "new-refresh", "Bob", true);
        ModConfig reopened = ModConfig.open(store());
        assertEquals(B, reopened.defaultUuid);
        assertEquals("new-refresh", reopened.findAccount(B).orElseThrow().refreshToken());
    }

    @Test
    void failedMutationDoesNotPublishCandidate() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        ConfigStore store = new ConfigStore(path(), "client", (destination, contents) -> {
            if (fail.get()) throw new IOException("denied");
            ConfigStore.writeAtomic(destination, contents, (temporary, target) ->
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING));
        });
        ModConfig config = ModConfig.open(store);
        config.upsertAccount(account(A, "Alice"));
        byte[] previous = Files.readAllBytes(path());
        fail.set(true);
        assertThrows(ConfigException.class, () -> config.updateTokens(A, "bad-access", "bad-refresh", "Bad"));
        assertThrows(ConfigException.class, () -> config.removeAccount(A));
        assertThrows(ConfigException.class, () -> config.setAuto(false));
        assertEquals("Alice", config.findAccount(A).orElseThrow().username());
        assertTrue(config.auto);
        assertEquals(A, config.defaultUuid);
        assertArrayEquals(previous, Files.readAllBytes(path()));
    }
}
