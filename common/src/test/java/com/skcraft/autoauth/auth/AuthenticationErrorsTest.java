package com.skcraft.autoauth.auth;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.MinecraftClientException;
import com.mojang.authlib.minecraft.client.ObjectMapper;
import com.mojang.authlib.yggdrasil.response.ErrorResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationErrorsTest {
    @Test void htmlErrorFromAuthlibBecomesAnOutageWithoutExposingTheResponse() {
        String html = "<!DOCTYPE html><html><head><style type='text/css'>body { font-family: Arial; }</style>"
                + "<title>Service unavailable</title></head><body>503</body></html>";
        AuthenticationException original = malformedResponse(html);
        assertTrue(original.getMessage().contains(html), "Fixture must reproduce Authlib's raw-response leak");

        AuthenticationException normalized = AuthenticationErrors.normalize(original);
        assertInstanceOf(AuthenticationUnavailableException.class, normalized);
        assertFalse(normalized.getMessage().contains(html));
        assertSame(original, normalized.getCause());
    }

    @Test void malformedJsonIsAlsoAnOutageRatherThanRejectedCredentials() {
        assertInstanceOf(AuthenticationUnavailableException.class,
                AuthenticationErrors.normalize(malformedResponse("{\"errorMessage\":")));
    }

    @Test void ordinaryAuthenticationFailuresKeepTheirTypeAndMessage() {
        for (AuthenticationException failure : new AuthenticationException[] {
                new InvalidCredentialsException("Invalid session"),
                new AuthenticationUnavailableException("Provider unavailable"),
                new AuthenticationException("Account access denied") }) {
            assertSame(failure, AuthenticationErrors.normalize(failure));
        }
    }

    static AuthenticationException malformedResponse(String response) {
        return assertThrows(MinecraftClientException.class,
                () -> ObjectMapper.create().readValue(response, ErrorResponse.class)).toAuthenticationException();
    }
}
