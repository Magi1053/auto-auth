package com.skcraft.autoauth.config;

import java.util.Objects;
import java.util.UUID;

public final class AccountRecord {
    private String accessToken;
    private String refreshToken;
    private UUID uuid;
    private String username;

    public AccountRecord() {
    }

    public AccountRecord(String accessToken, String refreshToken, UUID uuid, String username) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.uuid = uuid;
        this.username = username;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    public AccountRecord copy() {
        return new AccountRecord(accessToken, refreshToken, uuid, username);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountRecord that)) {
            return false;
        }
        return Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uuid);
    }
}
