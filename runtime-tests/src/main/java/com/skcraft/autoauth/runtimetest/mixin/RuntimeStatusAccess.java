package com.skcraft.autoauth.runtimetest.mixin;

import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.auth.StatusTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AuthStatusService.class, remap = false)
public interface RuntimeStatusAccess {
    @Accessor("tracker") StatusTracker autoauthTest$tracker();
}
