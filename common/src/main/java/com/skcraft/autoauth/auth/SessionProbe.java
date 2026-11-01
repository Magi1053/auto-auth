package com.skcraft.autoauth.auth;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import java.util.UUID;

/** Input validation and failure classification shared by runtime auth checks and deterministic tests. */
public final class SessionProbe {
    public record Snapshot(UUID uuid, String username, String token, boolean onlineAccount) {
        @Override public String toString() { return "Snapshot[" + uuid + "]"; }
    }
    @FunctionalInterface public interface OnlineCheck { UUID check(Snapshot snapshot) throws Exception; }
    private SessionProbe() { }

    public static AuthStatus check(Snapshot snapshot, OnlineCheck online) {
        if (snapshot == null || snapshot.uuid() == null || snapshot.username() == null || snapshot.username().isBlank()
                || !snapshot.onlineAccount() || snapshot.token() == null || snapshot.token().isBlank()
                || "0".equals(snapshot.token()) || "null".equalsIgnoreCase(snapshot.token())) return AuthStatus.OFFLINE;
        try {
            return snapshot.uuid().equals(online.check(snapshot)) ? AuthStatus.ONLINE : AuthStatus.INVALID;
        } catch (AuthenticationUnavailableException e) {
            return AuthStatus.UNKNOWN;
        } catch (AuthenticationException e) {
            return AuthStatus.INVALID;
        } catch (Exception e) {
            return AuthStatus.UNKNOWN;
        }
    }
}
