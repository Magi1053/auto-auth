package com.skcraft.autoauth.auth;

/**
 * Player-facing labels and colors for {@link AuthStatus}.
 */
public final class AuthStatusDisplay {
    private AuthStatusDisplay() {
    }

    public static String label(AuthStatus status) {
        return net.minecraft.network.chat.Component.translatable("autoauth.status." + status.name().toLowerCase(java.util.Locale.ROOT)).getString();
    }

    /** ARGB color for status text (no alpha channel required by drawString — use 0xRRGGBB). */
    public static int color(AuthStatus status) {
        return switch (status) {
            case ONLINE -> 0x55FF55;
            case CHECKING, UNKNOWN -> 0xAAAAAA;
            case OFFLINE -> 0xFFAA00;
            case INVALID -> 0xFF5555;
        };
    }

    public static String hudLine(AuthStatus status) {
        return "Auto-Auth: " + label(status);
    }
}
