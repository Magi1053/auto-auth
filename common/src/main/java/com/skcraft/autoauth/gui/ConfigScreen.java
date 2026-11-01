package com.skcraft.autoauth.gui;

import com.skcraft.autoauth.auth.AuthStatusDisplay;
import com.skcraft.autoauth.auth.AuthStatusService;
import com.skcraft.autoauth.config.AccountRecord;
import com.skcraft.autoauth.config.ModConfig;
import com.skcraft.autoauth.session.SessionOrchestrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Scrollable account management with explicit manual selection. */
public class ConfigScreen extends Screen {
    private final Screen parent;
    private java.util.UUID displayedDefault;
    private AccountList list;
    private boolean choosingDefault;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("autoauth.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        displayedDefault = ModConfig.get().defaultUuid;
        double scroll = list == null ? 0 : list.getScrollAmount();
        list = addRenderableWidget(new AccountList(minecraft, width, Math.max(24, height - 126), 44));
        for (AccountRecord account : ModConfig.get().accountsSnapshot()) {
            list.addAccount(account);
        }
        list.setScrollAmount(scroll);
        int total = Math.min(320, width - 20);
        int left = (width - total) / 2;
        int gap = 4;
        int column = (total - 2 * gap) / 3;
        int middle = left + column + gap;
        int last = middle + column + gap;
        int lastWidth = left + total - last;
        addRenderableWidget(Button.builder(Component.translatable("autoauth.add"), b -> minecraft.setScreen(new WaitingForLoginScreen(this))).bounds(left, height - 52, last - left - gap, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("autoauth.settings"), b -> minecraft.setScreen(new SettingsScreen(this))).bounds(last, height - 52, lastWidth, 20).build());
        if (choosingDefault) {
            clearWidgets();
            addRenderableWidget(list);
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> toggleDefaultPicker())
                    .bounds(left, height - 28, total, 20).build());
            return;
        }
        Button makeDefault = addRenderableWidget(Button.builder(Component.translatable("autoauth.set_default"), b -> toggleDefaultPicker())
                .bounds(middle, height - 28, column, 20).build());
        makeDefault.active = !ModConfig.get().accountsSnapshot().isEmpty();
        addRenderableWidget(Button.builder(Component.translatable("autoauth.check"), b -> AuthStatusService.get().refreshStatus()).bounds(left, height - 28, column, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(last, height - 28, lastWidth, 20).build());
    }

    @Override
    public void tick() {
        if (!java.util.Objects.equals(displayedDefault, ModConfig.get().defaultUuid)) {
            rebuildWidgets();
        }
    }

    private void toggleDefaultPicker() {
        choosingDefault = !choosingDefault;
        rebuildWidgets();
    }

    private void useAccount(AccountRecord account) {
        if (choosingDefault) {
            guarded(this, () -> {
                ModConfig.get().setDefault(account.uuid());
                toggleDefaultPicker();
            });
            return;
        }
        if (!account.hasRefreshToken()) {
            minecraft.setScreen(new ErrorScreen(this, "autoauth.error.use", "autoauth.error.no_login"));
            return;
        }
        SessionOrchestrator.get().attemptReauth(account.uuid());
    }

    public static void guarded(Screen parent, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            Minecraft.getInstance().setScreen(new ErrorScreen(parent, "autoauth.error.storage", "autoauth.error.storage_detail"));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        var status = AuthStatusService.get().getStatus();
        graphics.drawCenteredString(font, choosingDefault ? Component.translatable("autoauth.pick_default") : title, width / 2, 10, 0xFFFFFF);
        String progress = SessionOrchestrator.get().progressMessage();
        boolean idle = "autoauth.progress.idle".equals(progress);
        String statusText = idle ? AuthStatusDisplay.label(status) : Component.translatable(progress).getString();
        graphics.drawCenteredString(font, Component.translatable("autoauth.status", statusText), width / 2, 26,
                idle ? AuthStatusDisplay.color(status) : 0xEFF4F4);
        if (ModConfig.get().accountsSnapshot().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("autoauth.no_accounts"), width / 2, 64, 0xEFF4F4);
        }
        graphics.drawCenteredString(font, Component.translatable("autoauth.plaintext"), width / 2, height - 72, 0xFFAA00);
    }

    @Override
    public void onClose() {
        if (choosingDefault) toggleDefaultPicker();
        else minecraft.setScreen(parent);
    }

    private final class AccountList extends ContainerObjectSelectionList<AccountRow> {
        AccountList(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, 36);
        }

        void addAccount(AccountRecord account) {
            addEntry(new AccountRow(account, getRowWidth()));
        }

        @Override
        public int getRowWidth() {
            return Math.min(320, width - 36);
        }
    }

    private final class AccountRow extends ContainerObjectSelectionList.Entry<AccountRow> {
        private final Button use;
        private final Button remove;

        AccountRow(AccountRecord account, int rowWidth) {
            use = new AccountButton(Component.literal(account.username()), b -> useAccount(account), rowWidth - (choosingDefault ? 0 : 24), account.uuid());
            use.setTooltip(Tooltip.create(Component.translatable(choosingDefault ? "autoauth.default_named" : "autoauth.switch_to", account.username())));
            remove = Button.builder(Component.literal("×"), b -> guarded(ConfigScreen.this, () -> {
                ModConfig.get().removeAccount(account.uuid());
                rebuildWidgets();
            })).bounds(0, 0, 20, 20).tooltip(Tooltip.create(Component.translatable("autoauth.remove_named", account.username()))).createNarration(label -> Component.translatable("autoauth.remove_named", account.username())).build();
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
            use.setPosition(left, top);
            remove.setPosition(left + rowWidth - 20, top + 6);
            use.active = !SessionOrchestrator.get().isBusy();
            use.render(graphics, mouseX, mouseY, partialTick);
            if (!choosingDefault) remove.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return choosingDefault ? List.of(use) : List.of(use, remove);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return choosingDefault ? List.of(use) : List.of(use, remove);
        }
    }
}
