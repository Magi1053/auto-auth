package com.skcraft.autoauth.platform;

import com.skcraft.autoauth.Constants;
import com.skcraft.autoauth.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService.getClass().getSimpleName(), clazz.getSimpleName());
        return loadedService;
    }
}
