package com.skcraft.autoauth.runtimetest;

import com.skcraft.autoauth.auth.AuthStatus;
import com.skcraft.autoauth.auth.MicrosoftAuth;
import com.skcraft.autoauth.auth.SessionApplier;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic provider implementations linked exclusively into the separate runtime-test mod. */
public final class RuntimeFixtures {
    public static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static volatile AuthStatus probe = AuthStatus.ONLINE;
    public static volatile String lastRefresh;
    public static volatile CompletableFuture<MicrosoftAuth.AuthResult> refreshBarrier;
    public static volatile String realmsName;
    public static volatile String realmsSession;
    public static final AtomicInteger realmConstructors = new AtomicInteger();
    public static final AtomicInteger availabilityChecks = new AtomicInteger();
    public static final AtomicInteger connects = new AtomicInteger();
    public static volatile ConnectCall connect;
    public static volatile boolean allowLocalSocket;

    public record ConnectCall(Screen parent, ServerAddress address, ServerData data, boolean quickPlay, TransferState transfer) { }

    private RuntimeFixtures() { }

    public static User user(UUID id, String name) {
        return new User(name, id, "synthetic-access-" + name, Optional.empty(), Optional.empty(), User.Type.MSA);
    }

    public static SessionApplier.PreparedSession prepared(User user) {
        return prepared(user, true);
    }

    public static SessionApplier.PreparedSession prepared(User user, boolean allowed) {
        var flags = allowed ? Set.of(UserApiService.UserFlag.SERVERS_ALLOWED, UserApiService.UserFlag.REALMS_ALLOWED,
                UserApiService.UserFlag.CHAT_ALLOWED) : Set.<UserApiService.UserFlag>of();
        var properties = new UserApiService.UserProperties(flags, Map.of());
        UserApiService api = (UserApiService) Proxy.newProxyInstance(UserApiService.class.getClassLoader(),
                new Class<?>[]{UserApiService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("fetchProperties")) return properties;
                    if (method.getName().equals("toString")) return "SyntheticUserApiService";
                    try {
                        return method.invoke(UserApiService.OFFLINE, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        return new SessionApplier.PreparedSession(user, api, new ProfileResult(new GameProfile(user.getProfileId(), user.getName())), properties);
    }

    public static MicrosoftAuth.AuthResult refresh(String token) {
        lastRefresh = token;
        CompletableFuture<MicrosoftAuth.AuthResult> barrier = refreshBarrier;
        if (barrier != null) return barrier.join();
        boolean bob = token.contains("Bob");
        return new MicrosoftAuth.AuthResult("synthetic-access-" + (bob ? "Bob" : "Alice"),
                "synthetic-refresh-" + (bob ? "Bob" : "Alice") + "-rotated", bob ? B : A, bob ? "Bob" : "Alice");
    }
}
