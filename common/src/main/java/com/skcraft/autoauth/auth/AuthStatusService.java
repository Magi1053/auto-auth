package com.skcraft.autoauth.auth;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.skcraft.autoauth.session.SessionOrchestrator;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AuthStatusService {
    private static final AuthStatusService INSTANCE = new AuthStatusService();
    private final StatusTracker tracker = new StatusTracker(task -> Minecraft.getInstance().execute(task), System::currentTimeMillis,
            () -> SessionOrchestrator.get().onBecameOnline());
    private static final long FRESHNESS_MS = 60_000;
    private AuthStatusService() { }
    public static AuthStatusService get() { return INSTANCE; }
    public AuthStatus getStatus() { return tracker.getStatus(); }
    public long generation() { return tracker.generation(); }

    public void sessionChanged() { tracker.invalidate(AuthStatus.UNKNOWN); }
    public void rejectCurrentSession() { tracker.invalidate(AuthStatus.INVALID); }
    public void refreshIfStale() { if (tracker.isStale(FRESHNESS_MS)) refreshStatus(); }

    public void refreshStatus() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) { minecraft.execute(this::refreshStatus); return; }
        User user = minecraft.getUser();
        MinecraftSessionService service = minecraft.getMinecraftSessionService();
        tracker.refresh(() -> CompletableFuture.supplyAsync(() -> check(user, service), Util.ioPool()));
    }

    private AuthStatus check(User user, MinecraftSessionService service) {
        SessionProbe.Snapshot snapshot = user == null ? null : new SessionProbe.Snapshot(user.getProfileId(), user.getName(),
                user.getAccessToken(), user.getType() == User.Type.MSA || user.getType() == User.Type.MOJANG);
        return SessionProbe.check(snapshot, identity -> {
            String serverId = UUID.randomUUID().toString().replace("-", "");
            service.joinServer(identity.uuid(), identity.token(), serverId);
            ProfileResult joined = service.hasJoinedServer(identity.username(), serverId, null);
            return joined != null && joined.profile() != null ? joined.profile().getId() : null;
        });
    }
}
