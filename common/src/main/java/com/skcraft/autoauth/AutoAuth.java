package com.skcraft.autoauth;

import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.config.ModConfig;

public final class AutoAuth {
    private AutoAuth() {
    }

    public static void init() {
        try {
            ModConfig.load();
        } catch (com.skcraft.autoauth.config.ConfigException failure) {
            Constants.LOG.warn("Auto-Auth could not load its saved settings; account changes will report persistence failures.");
        }
        AuthStatusService.get().refreshStatus();
        Constants.LOG.info("{} initialized ({})", Constants.MOD_NAME, Constants.MOD_ID);
    }
}
