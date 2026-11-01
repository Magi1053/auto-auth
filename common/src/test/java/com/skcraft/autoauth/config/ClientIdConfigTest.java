package com.skcraft.autoauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClientIdConfigTest {
    private static final String DEFAULT = "d18bb4d8-a27f-4451-a87f-fe6de4436813";
    private static final String PACK = "68d506ce-63f9-4991-aed0-9b4316defcf2";
    @TempDir Path directory;

    @Test
    void missingPublicSettingsRetainAccountOrJarDefaultWithoutCreatingFiles() {
        assertEquals(DEFAULT, ClientIdConfig.resolve(directory.resolve("client.json"), DEFAULT));
        assertFalse(Files.exists(directory.resolve("client.json")));
    }

    @Test
    void sparsePackOverrideWinsWithoutReplacingSavedAccounts() throws Exception {
        Path accounts = directory.resolve("config.json");
        String saved = "{\"accounts\":[{\"refreshToken\":\"synthetic-private-login\"}]}";
        Files.writeString(accounts, saved);
        Path settings = directory.resolve("client.json");
        Files.writeString(settings, "{\"clientId\":\"  " + PACK + "  \"}");
        assertEquals(PACK, ClientIdConfig.resolve(settings, DEFAULT));
        assertEquals(saved, Files.readString(accounts));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void blankOrOmittedOverrideUsesTheExistingConfiguredDefault() throws Exception {
        Path settings = directory.resolve("client.json");
        for (String contents : new String[]{"{}", "{\"clientId\":null}", "{\"clientId\":\"  \"}"}) {
            Files.writeString(settings, contents);
            assertEquals(DEFAULT, ClientIdConfig.resolve(settings, DEFAULT));
        }
    }

    @Test
    void invalidSettingsDoNotSilentlyUseADifferentApplication() throws Exception {
        Path settings = directory.resolve("client.json");
        for (String contents : new String[]{"broken", "[]", "{\"clientId\":42}"}) {
            Files.writeString(settings, contents);
            assertThrows(ConfigException.class, () -> ClientIdConfig.resolve(settings, DEFAULT));
            assertEquals(contents, Files.readString(settings));
        }
    }

    @Test
    void unreadableSettingsReportFailureInsteadOfFallingBack() throws Exception {
        Path settings = Files.createDirectory(directory.resolve("client.json"));
        assertThrows(ConfigException.class, () -> ClientIdConfig.resolve(settings, DEFAULT));
    }
}
