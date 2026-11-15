package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.auth.StatusTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = StatusTracker.class, remap = false)
public interface RuntimeStatusTrackerAccess {
    @Accessor("checkedAt") void autoauthTest$checkedAt(long checkedAt);
}
