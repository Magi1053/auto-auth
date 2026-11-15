package com.skcraft.autoauth.auth;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.MinecraftClientException;

/** Distinguishes malformed provider responses from rejected credentials. */
public final class AuthenticationErrors {
    private AuthenticationErrors() { }

    public static AuthenticationException normalize(AuthenticationException failure) {
        // Authlib includes the entire response in JSON_ERROR, then wraps it in a generic
        // AuthenticationException. Vanilla displays that message verbatim during join.
        if (failure.getCause() instanceof MinecraftClientException clientFailure
                && clientFailure.getType() == MinecraftClientException.ErrorType.JSON_ERROR) {
            return new AuthenticationUnavailableException(
                    "The authentication service returned an invalid response. Please try again later.", failure);
        }
        return failure;
    }
}
