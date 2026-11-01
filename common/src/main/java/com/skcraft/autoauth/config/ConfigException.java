package com.skcraft.autoauth.config;

/** A safe, player-readable persistence failure. Never includes serialized account contents. */
public final class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
