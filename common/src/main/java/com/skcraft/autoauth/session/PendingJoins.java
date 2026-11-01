package com.skcraft.autoauth.session;

import com.skcraft.autoauth.gui.WaitingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class PendingJoins {
    private static volatile boolean pendingServerJoin;
    private static volatile boolean pendingRealmsJoin;
    private static volatile Screen serverParent;
    private static volatile Screen realmsParent;
    private static volatile ServerAddress serverAddress;
    private static volatile ServerData serverData;
    private static volatile boolean quickPlay;
    private static volatile TransferState transferState;

    private PendingJoins() {
    }

    public static synchronized void beginServer(
            Screen parent,
            ServerAddress address,
            ServerData data,
            boolean isQuickPlay,
            TransferState transfer
    ) {
        pendingServerJoin = true;
        pendingRealmsJoin = false;
        realmsParent = null;
        serverParent = unwrapWaitingParent(parent);
        serverAddress = address;
        serverData = data;
        quickPlay = isQuickPlay;
        transferState = transfer;
    }

    public static synchronized void beginRealms() {
        beginRealms(null);
    }

    public static synchronized void beginRealms(Screen parent) {
        pendingRealmsJoin = true;
        pendingServerJoin = false;
        clearServerParams();
        realmsParent = unwrapWaitingParent(parent);
    }

    public static synchronized void cancel() {
        pendingServerJoin = false;
        pendingRealmsJoin = false;
        realmsParent = null;
        clearServerParams();
    }

    public static boolean isPending() {
        return pendingServerJoin || pendingRealmsJoin;
    }

    public static boolean isPendingServer() {
        return pendingServerJoin;
    }

    public static boolean isPendingRealms() {
        return pendingRealmsJoin;
    }

    public static Screen serverParent() {
        return serverParent;
    }

    public static Screen realmsParent() { return realmsParent; }

    public static ServerAddress serverAddress() {
        return serverAddress;
    }

    public static ServerData serverData() {
        return serverData;
    }

    public static boolean quickPlay() {
        return quickPlay;
    }

    public static TransferState transferState() {
        return transferState;
    }

    public static Screen unwrapWaitingParent(Screen screen) {
        Screen current = screen;
        while (current instanceof WaitingScreen waiting) {
            current = waiting.getParent();
        }
        return current;
    }

    private static void clearServerParams() {
        serverParent = null;
        serverAddress = null;
        serverData = null;
        quickPlay = false;
        transferState = null;
    }
}
