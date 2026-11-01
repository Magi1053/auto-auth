package com.skcraft.autoauth.auth;

import java.io.IOException;

/** Safe player-facing failure; no raw provider message, token or response body. */
public final class AuthFailure extends IOException {
    private final boolean retryable;

    public AuthFailure(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
