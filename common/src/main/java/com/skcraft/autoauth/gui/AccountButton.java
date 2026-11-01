package com.skcraft.autoauth.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.skcraft.autoauth.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.TimeUnit;

public final class AccountButton extends Button {
    private final UUID accountId;
    private final Component identifier;
    private static final Cache<UUID, CompletableFuture<PlayerSkin>> SKINS = CacheBuilder.newBuilder()
            .maximumSize(64).expireAfterWrite(10, TimeUnit.MINUTES).build();
    private final CompletableFuture<PlayerSkin> skin;

    AccountButton(Component label, OnPress action, int width, UUID uuid) {
        super(0, 0, width, 32, label, action, DEFAULT_NARRATION);
        accountId = uuid;
        identifier = Component.literal(uuid.toString());
        skin = cachedSkin(uuid);
    }

    public static void preloadSavedHeads() {
        for (var account : ModConfig.get().accountsSnapshot()) {
            cachedSkin(account.uuid());
        }
    }

    private static CompletableFuture<PlayerSkin> cachedSkin(UUID uuid) {
        var skin = SKINS.asMap().computeIfAbsent(uuid, AccountButton::loadSkin);
        skin.whenComplete((result, error) -> {
            if (result == null) SKINS.asMap().remove(uuid, skin);
        });
        return skin;
    }

    private static CompletableFuture<PlayerSkin> loadSkin(UUID uuid) {
        var client = Minecraft.getInstance();
        var service = client.getMinecraftSessionService();
        var skins = client.getSkinManager();
        Path file = client.gameDirectory.toPath().resolve("cache/autoauth/heads").resolve(uuid + ".txt");
        var cached = CompletableFuture.supplyAsync(() -> readProfile(file, uuid), Util.ioPool())
                .thenCompose(profile -> profile == null ? CompletableFuture.<PlayerSkin>completedFuture(null)
                        : skins.getOrLoad(profile)).exceptionally(error -> null);
        var fresh = CompletableFuture.supplyAsync(() -> service.fetchProfile(uuid, true), Util.ioPool())
                .thenCompose(result -> {
                    if (result == null) return CompletableFuture.<PlayerSkin>completedFuture(null);
                    var profile = result.profile();
                    return skins.getOrLoad(profile).thenApplyAsync(skin -> {
                        if (skin != null) saveProfile(file, profile);
                        return skin;
                    }, Util.ioPool());
                }).exceptionally(error -> null);
        return cached.thenCompose(skin -> skin == null ? fresh : CompletableFuture.completedFuture(skin));
    }

    private static GameProfile readProfile(Path file, UUID uuid) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 65536) return null;
            var lines = Files.readAllLines(file);
            if (lines.size() != 3) return null;
            var profile = new GameProfile(uuid, lines.get(0));
            profile.getProperties().put("textures", new Property("textures", lines.get(1), lines.get(2)));
            return profile;
        } catch (java.io.IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void saveProfile(Path file, GameProfile profile) {
        var texture = profile.getProperties().get("textures").stream().filter(Property::hasSignature).findFirst();
        if (texture.isEmpty()) return;
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "head-", ".tmp");
            try {
                Files.write(temp, java.util.List.of(profile.getName(), texture.get().value(), texture.get().signature()));
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (java.io.IOException ignored) {
            // The live texture is still usable if the cache cannot be written.
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        if (isHoveredOrFocused()) {
            graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), 0xFFCCCCCC);
        }
        int x = getX() + 4;
        int y = getY();
        PlayerSkin resolved = skin.getNow(null);
        if (resolved != null) {
            PlayerFaceRenderer.draw(graphics, resolved.texture(), x, y + 4, 24);
        } else {
            graphics.fill(x, y + 4, x + 24, y + 28, 0x55222222);
            graphics.pose().pushPose();
            graphics.pose().translate(x + 12, y + 16, 0);
            graphics.pose().scale(2, 2, 1);
            graphics.drawCenteredString(font, skin.isDone() ? "?" : "...", 0, -4, 0xFFBBBBBB);
            graphics.pose().popPose();
        }

        int textX = x + 30;
        int right = getX() + getWidth() - 3;
        if (accountId.equals(ModConfig.get().defaultUuid)) {
            Component label = Component.translatable("autoauth.is_default");
            right -= font.width(label);
            graphics.drawString(font, label, right, y + 5, 0xFFFFD77D);
            right -= 5;
        }
        if (accountId.equals(Minecraft.getInstance().getUser().getProfileId())) {
            Component activeLabel = Component.translatable("autoauth.active");
            right -= font.width(activeLabel);
            graphics.drawString(font, activeLabel, right, y + 5, 0xFF80DD80);
            right -= 5;
        }
        float nameScale = 1.125F;
        graphics.pose().pushPose();
        graphics.pose().translate(textX, y + 4, 0);
        graphics.pose().scale(nameScale, nameScale, 1);
        String label = font.plainSubstrByWidth(getMessage().getString(), (int) ((right - textX) / nameScale));
        graphics.drawString(font, label, 0, 0, active ? 0xFFFFFFFF : 0xFFAAAAAA);
        graphics.pose().popPose();
        // Keep the full UUID on one line at normal GUI scales.
        graphics.pose().pushPose();
        graphics.pose().translate(textX, y + 19, 0);
        float scale = Math.min(0.85F, (float) (getX() + getWidth() - 3 - textX) / font.width(identifier));
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, identifier, 0, 0, 0xFFBBBBBB);
        graphics.pose().popPose();
    }
}
