package com.skcraft.autoauth.auth;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.User;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual preparation pipeline without contacting a provider or constructing Minecraft. */
class SessionPreparationTest {
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static User user(UUID uuid, String name) {
        return new User(name, uuid, "synthetic-access", Optional.empty(), Optional.empty(), User.Type.MSA);
    }

    private static UserApiService api(UserApiService.UserProperties properties, Throwable failure) {
        return (UserApiService) Proxy.newProxyInstance(UserApiService.class.getClassLoader(), new Class<?>[] { UserApiService.class },
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("fetchProperties")) throw new AssertionError("Unexpected service method: " + method.getName());
                    if (failure != null) throw failure;
                    return properties;
                });
    }

    private static MinecraftSessionService profiles(UUID expected, ProfileResult profile, AtomicInteger requests) {
        return (MinecraftSessionService) Proxy.newProxyInstance(MinecraftSessionService.class.getClassLoader(),
                new Class<?>[] { MinecraftSessionService.class }, (proxy, method, arguments) -> {
                    if (!method.getName().equals("fetchProfile")) throw new AssertionError("Unexpected profile method: " + method.getName());
                    assertEquals(expected, arguments[0]);
                    assertEquals(true, arguments[1], "Profile lookup must require secure properties");
                    requests.incrementAndGet();
                    return profile;
                });
    }

    @Test void replacementContainsExactlyThePreparedAccountAndPermissions() throws Exception {
        User b = user(B, "Bob");
        var properties = new UserApiService.UserProperties(Set.of(UserApiService.UserFlag.SERVERS_ALLOWED), Map.of());
        var profile = new ProfileResult(new GameProfile(B, "Bob"));
        UserApiService api = api(properties, null);
        AtomicInteger requests = new AtomicInteger();
        var prepared = SessionApplier.prepareWithServices(b, api, profiles(B, profile, requests));
        assertSame(b, prepared.user());
        assertSame(api, prepared.userApiService());
        assertSame(profile, prepared.profile());
        assertSame(properties, prepared.properties());
        assertTrue(prepared.properties().flag(UserApiService.UserFlag.SERVERS_ALLOWED));
        assertFalse(prepared.properties().flag(UserApiService.UserFlag.REALMS_ALLOWED));
        assertEquals(1, requests.get());
    }

    @Test void providerFailureCannotBecomeAnOfflineSuccess() {
        AuthenticationUnavailableException failure = new AuthenticationUnavailableException("Synthetic provider outage");
        AtomicInteger profileRequests = new AtomicInteger();
        assertSame(failure, assertThrows(AuthenticationUnavailableException.class, () -> SessionApplier.prepareWithServices(
                user(A, "Alice"), api(null, failure), profiles(A, new ProfileResult(new GameProfile(A, "Alice")), profileRequests))));
        assertEquals(0, profileRequests.get(), "Failed property preparation must stop before profile lookup");
    }

    @Test void absentProfileCannotProduceAnInstallableSession() {
        assertThrows(IllegalStateException.class, () -> SessionApplier.prepareWithServices(user(A, "Alice"),
                api(new UserApiService.UserProperties(Set.of(), Map.of()), null), profiles(A, null, new AtomicInteger())));
    }

    @Test void wrongProfileUuidCannotProduceMixedAccountState() {
        var profile = new ProfileResult(new GameProfile(B, "Bob"));
        assertThrows(IllegalArgumentException.class, () -> SessionApplier.prepareWithServices(user(A, "Alice"),
                api(new UserApiService.UserProperties(Set.of(), Map.of()), null), profiles(A, profile, new AtomicInteger())));
    }

    @Test void restrictionsAreRetainedInsteadOfReplacingThemWithPermissiveDefaults() throws Exception {
        var properties = new UserApiService.UserProperties(Set.of(), Map.of());
        var prepared = SessionApplier.prepareWithServices(user(A, "Alice"), api(properties, null),
                profiles(A, new ProfileResult(new GameProfile(A, "Alice")), new AtomicInteger()));
        assertSame(properties, prepared.properties());
        assertFalse(prepared.properties().flag(UserApiService.UserFlag.SERVERS_ALLOWED));
        assertFalse(prepared.properties().flag(UserApiService.UserFlag.REALMS_ALLOWED));
    }
}
