package com.skcraft.autoauth.auth;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SessionProbeTest {
    private final UUID uuid = UUID.randomUUID();
    private SessionProbe.Snapshot valid() { return new SessionProbe.Snapshot(uuid, "Synthetic", "synthetic-token", true); }

    @Test void sentinelTokensAndOfflineUsersNeverContactProvider() {
        for (String token : new String[] { null, "", " ", "0", "null", "NULL" }) {
            assertEquals(AuthStatus.OFFLINE, SessionProbe.check(new SessionProbe.Snapshot(uuid, "Synthetic", token, true),
                    identity -> { fail("Provider must not be contacted"); return null; }));
        }
        assertEquals(AuthStatus.OFFLINE, SessionProbe.check(new SessionProbe.Snapshot(uuid, "Synthetic", "token", false), identity -> uuid));
        assertEquals(AuthStatus.OFFLINE, SessionProbe.check(null, identity -> uuid));
        assertEquals(AuthStatus.OFFLINE, SessionProbe.check(new SessionProbe.Snapshot(null, "Synthetic", "token", true), identity -> uuid));
    }

    @Test void authenticatedProfileMustMatchExpectedUuid() {
        assertEquals(AuthStatus.ONLINE, SessionProbe.check(valid(), identity -> uuid));
        assertEquals(AuthStatus.INVALID, SessionProbe.check(valid(), identity -> UUID.randomUUID()));
        assertEquals(AuthStatus.INVALID, SessionProbe.check(valid(), identity -> null));
    }

    @Test void rejectedCredentialsAreDistinctFromOutages() {
        assertEquals(AuthStatus.INVALID, SessionProbe.check(valid(), identity -> { throw new AuthenticationException("Synthetic rejection"); }));
        assertEquals(AuthStatus.UNKNOWN, SessionProbe.check(valid(), identity -> { throw new AuthenticationUnavailableException("Synthetic outage"); }));
        assertEquals(AuthStatus.UNKNOWN, SessionProbe.check(valid(), identity -> { throw new IOException("Synthetic transport failure"); }));
    }

    @Test void snapshotToStringDoesNotExposeCredential() {
        assertFalse(valid().toString().contains("synthetic-token"));
    }
}
