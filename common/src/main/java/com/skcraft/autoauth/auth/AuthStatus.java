package com.skcraft.autoauth.auth;

public enum AuthStatus {
    UNKNOWN,
    CHECKING,
    ONLINE,
    INVALID,
    OFFLINE;

    public boolean isOnline() {
        return this == ONLINE;
    }

    public boolean needsReauth() {
        return this == INVALID || this == OFFLINE;
    }

    public boolean blocksConnectWhenAuto() {
        return needsReauth();
    }
}
