package com.skcraft.autoauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

public final class Constants {
    public static final String MOD_ID = "autoauth";
    public static final String MOD_NAME = "Auto-Auth";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    /**
     * Shipped Azure AD public client id (jar default for players).
     * Prefer build resource {@code autoauth-build.properties}; optional env/sysprop for local packaging tests.
     */
    public static String defaultAzureClientId() {
        try (InputStream in = Constants.class.getResourceAsStream("/autoauth-build.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String baked = properties.getProperty("azure_client_id", "");
                if (baked != null && !baked.isBlank() && !baked.contains("${")) {
                    return baked.trim();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        String fromProp = System.getProperty("autoauth.clientId", "");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        String fromEnv = System.getenv("AUTOAUTH_CLIENT_ID");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return "";
    }

    private Constants() {
    }
}
