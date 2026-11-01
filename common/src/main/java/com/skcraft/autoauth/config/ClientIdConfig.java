package com.skcraft.autoauth.config;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Public pack-owned settings are separate from the player-owned credential store. */
public final class ClientIdConfig {
    private ClientIdConfig() { }

    public static String resolve(Path path, String fallback) {
        if (Files.notExists(path)) {
            return fallback;
        }
        try {
            var json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            var id = json.get("clientId");
            if (id == null || id.isJsonNull()) {
                return fallback;
            }
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException();
            }
            String value = id.getAsString().trim();
            return value.isEmpty() ? fallback : value;
        } catch (IOException | RuntimeException invalid) {
            // Do not silently refresh a token against a different app on a read failure.
            throw new ConfigException("Could not read the public login settings. Check config/autoauth/client.json and try again.");
        }
    }
}
