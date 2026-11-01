package com.skcraft.autoauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Disk boundary independent of the Minecraft client and loader. */
public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path path;
    private final String defaultClientId;
    private final AtomicWriter writer;

    @FunctionalInterface
    interface AtomicWriter {
        void write(Path destination, String contents) throws IOException;
    }

    @FunctionalInterface
    interface Replacer {
        void replace(Path temporary, Path destination) throws IOException;
    }

    public ConfigStore(Path path, String defaultClientId) {
        this(path, defaultClientId, ConfigStore::writeAtomic);
    }

    ConfigStore(Path path, String defaultClientId, AtomicWriter writer) {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        this.defaultClientId = defaultClientId == null ? "" : defaultClientId.trim();
        this.writer = Objects.requireNonNull(writer);
    }

    /** Deeply copied values: callers can never mutate the store through a returned account. */
    public record Snapshot(boolean debug, boolean auto, String clientId, List<AccountRecord> accounts, UUID defaultUuid) {
        public Snapshot {
            clientId = clientId == null ? "" : clientId.trim();
            accounts = accounts.stream().map(AccountRecord::copy).toList();
        }

        @Override
        public List<AccountRecord> accounts() {
            return accounts.stream().map(AccountRecord::copy).toList();
        }
    }

    public Snapshot defaults() {
        return new Snapshot(false, true, defaultClientId, List.of(), null);
    }

    public Path path() {
        return path;
    }

    /** Invalid input is preserved before replacement; IO failures never silently reset accounts. */
    public synchronized Snapshot load() {
        if (!Files.exists(path)) {
            Snapshot initial = defaults();
            save(initial);
            return initial;
        }
        final String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Could not read saved accounts. Check access to the Auto-Auth config folder.");
        }

        Snapshot snapshot;
        boolean invalid = false;
        try {
            var parsed = JsonParser.parseString(source);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException();
            }
            JsonObject json = parsed.getAsJsonObject();
            // Gson coerces strings to booleans; reject these instead of silently changing settings.
            for (String key : List.of("debug", "auto")) {
                if (json.has(key) && (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isBoolean())) {
                    throw new IllegalArgumentException();
                }
            }
            Data data = GSON.fromJson(json, Data.class);
            LinkedHashMap<UUID, AccountRecord> accounts = new LinkedHashMap<>();
            if (data.accounts == null) {
                invalid = true;
            } else {
                for (AccountRecord account : data.accounts) {
                    if (!isValid(account)) {
                        invalid = true;
                        continue;
                    }
                    AccountRecord normalized = normalized(account);
                    if (accounts.remove(normalized.uuid()) != null) {
                        invalid = true;
                    }
                    accounts.put(normalized.uuid(), normalized);
                }
            }
            UUID defaultUuid = data.defaultUuid;
            if (defaultUuid != null && !accounts.containsKey(defaultUuid)) {
                invalid = true;
                defaultUuid = accounts.isEmpty() ? null : accounts.keySet().iterator().next();
            }
            String clientId = data.clientId == null || data.clientId.isBlank() ? defaultClientId : data.clientId.trim();
            snapshot = new Snapshot(data.debug, data.auto, clientId, new ArrayList<>(accounts.values()), defaultUuid);
        } catch (RuntimeException e) {
            // JSON parser messages can contain tokens. Never retain/log the exception or its contents.
            invalid = true;
            snapshot = defaults();
        }

        if (invalid) {
            preserveCorruptInput();
        }
        save(snapshot);
        return snapshot;
    }

    public synchronized void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        validate(snapshot);
        Data data = new Data();
        data.debug = snapshot.debug();
        data.auto = snapshot.auto();
        data.clientId = snapshot.clientId().isBlank() ? defaultClientId : snapshot.clientId();
        data.accounts = snapshot.accounts();
        data.defaultUuid = snapshot.defaultUuid();
        try {
            writer.write(path, GSON.toJson(data) + "\n");
        } catch (IOException e) {
            throw new ConfigException("Could not save accounts. Check disk space and access to the Auto-Auth config folder.");
        }
    }

    private void preserveCorruptInput() {
        Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + UUID.randomUUID() + ".json");
        try {
            Files.copy(path, backup);
            restrictPermissions(backup);
        } catch (IOException e) {
            throw new ConfigException("Saved accounts need repair, but a recovery copy could not be created. The original file has been kept.");
        }
    }

    private static boolean isValid(AccountRecord account) {
        return account != null && account.uuid() != null && account.username() != null && !account.username().isBlank();
    }

    private static AccountRecord normalized(AccountRecord account) {
        return new AccountRecord(account.accessToken() == null ? "" : account.accessToken(),
                account.refreshToken() == null ? "" : account.refreshToken(), account.uuid(), account.username().trim());
    }

    private static void validate(Snapshot snapshot) {
        var seen = new java.util.HashSet<UUID>();
        for (AccountRecord record : snapshot.accounts()) {
            if (!isValid(record) || !seen.add(record.uuid())) {
                throw new ConfigException("Saved account details are invalid. Add the account again before saving.");
            }
        }
        if (snapshot.defaultUuid() != null && !seen.contains(snapshot.defaultUuid())) {
            throw new ConfigException("The default account is no longer saved. Choose another account.");
        }
    }

    private static void writeAtomic(Path destination, String contents) throws IOException {
        writeAtomic(destination, contents, (temporary, target) ->
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    static void writeAtomic(Path destination, String contents, Replacer replacer) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".autoauth-", ".tmp");
        try {
            restrictPermissions(temporary);
            byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                replacer.replace(temporary, destination);
            } catch (AtomicMoveNotSupportedException e) {
                // Refuse a destructive truncate/copy fallback; the previous file remains usable.
                throw new IOException("Atomic replacement is unavailable");
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictPermissions(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static final class Data {
        boolean debug;
        boolean auto = true;
        String clientId;
        List<AccountRecord> accounts = new ArrayList<>();
        UUID defaultUuid;
    }
}
