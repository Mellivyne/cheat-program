package com.cheatprogram.gui;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.visual.Personal;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.PersonalAvatar;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.PersonalInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Local profile, observed server details and an explicit report entry point. */
public final class PersonalScreen extends GuiBase {
    private static final float W = 720, PREFERRED_H = 452;
    private final Screen parent;
    private final Personal module;
    private final TextField displayId = new TextField(32);
    private final Scroller scroll = new Scroller();
    private float panelHeight, offset, contentHeight = 640;
    private String statusKey = "";
    private boolean closed;

    public PersonalScreen(Screen parent, Personal module) {
        super(Lang.t("module.personal")); this.parent = parent; this.module = module;
        displayId.setText(module.configuredDisplayName());
        PersonalAvatar.warm(module.avatarPath()); PersonalInfo.forceRefresh();
    }
    @Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        beginFrame();
        if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; }
        R2D.begin(graphics);
        try {
            panelHeight = layoutPanel(W, PREFERRED_H); updateMouse(); drawPanelSurface(graphics, W, panelHeight, 24);
            c.textC(BOLD, Lang.t("module.personal"), 24, 29, 16, Theme.TEXT);
            iconButton("personal.close", Icon.CLOSE, W - 29, 29, 16, Theme.TEXT_2, this::onClose);
            c.textC(REG, Lang.t("ui.personal.subtitle"), 24, 54, 10.5f, Theme.TEXT_3);
            float top = 80, height = Math.max(60, panelHeight - 146);
            scroll.max = Math.max(0, contentHeight - height); scroll.update(dt); offset = scroll.get();
            c.clip(16, top, W - 32, height); pushHitClip(16, top, W - 32, height);
            hit(16, top, W - 32, height).scroll(scroll::scroll);
            try { contentHeight = contents(top) - top; }
            finally { popHitClip(); c.unclip(); }
            panelScrollBar("personal", scroll, W - 12, top, height);
            c.textC(REG, c.ellipsize(REG, Lang.t(statusKey.isBlank() ? PersonalAvatar.statusKey() : statusKey), 9.5f, W - 48),
                    24, panelHeight - 51, 9.5f, Theme.TEXT_3);
            button("personal.back", Lang.t("ui.back"), Icon.BACK, 24, panelHeight - 34, 100, 25, false, this::onClose);
            button("personal.reports", Lang.t("ui.personal.reports"), Icon.INFO, 136, panelHeight - 34, 412, 25, false, () -> {
                commit(); mc.setScreen(new PersonalReportsScreen(this));
            });
            button("personal.save", Lang.t("ui.personal.save"), Icon.SAVE, 560, panelHeight - 34, 136, 25, true, () -> {
                commit(); statusKey = "ui.personal.saved";
            });
        } finally { R2D.end(); endFrame(); }
    }
    private float y(float value) { return value - offset; }
    private float contents(float top) {
        float start = top + 4;
        c.lens(24, y(start), W - 48, 262, 18, Theme.lift(.045f), Theme.chipGlass(c.scale));
        PersonalAvatar.draw(c, 40, y(start + 20), 88, 88, 44, 1);
        c.textC(REG, Lang.t("ui.personal.displayId"), 152, y(start + 24), 10.5f, Theme.TEXT_2);
        textField(displayId, module.authenticatedName(), Icon.USER, 152, y(start + 39), W - 192, 30);
        c.textC(REG, Lang.t("ui.personal.authenticatedId", module.authenticatedName()), 152, y(start + 88), 10.5f, Theme.TEXT_3);
        float note = start + 112;
        for (String line : c.wrap(REG, Lang.t("ui.personal.localIdHint"), 10.5f, W - 192)) {
            c.textC(REG, line, 152, y(note), 10.5f, Theme.TEXT_3); note += 15;
        }
        button("personal.avatar.choose", Lang.t("ui.personal.avatarChoose"), Icon.FOLDER, 40, y(start + 160), 151, 28, false, this::chooseAvatar);
        button("personal.avatar.edit", Lang.t("ui.personal.cropEdit"), null, 203, y(start + 160), 151, 28, false, this::editAvatar);
        button("personal.avatar.clear", Lang.t("ui.personal.avatarClear"), Icon.TRASH, 366, y(start + 160), 151, 28, false, () -> {
            module.avatarPath(""); Config.saveAll(); statusKey = "ui.personal.avatarDefault";
        });
        button("personal.id.clear", Lang.t("ui.personal.idReset"), Icon.REFRESH, 529, y(start + 160), 151, 28, false, () -> {
            displayId.setText(""); module.displayName(""); Config.saveAll(); statusKey = "ui.personal.idRestored";
        });
        c.textC(REG, c.ellipsize(REG, Lang.t(PersonalAvatar.statusKey()), 9.5f, W - 80), 40, y(start + 206), 9.5f, Theme.TEXT_3);
        c.textC(REG, Lang.t("ui.personal.welcome"), 40, y(start + 238), 11, Theme.TEXT_2);
        toggle("personal.welcome", W - 76, y(start + 230), module.welcomeEnabled(), 1);
        hit(32, y(start + 220), W - 64, 31).left(() -> {
            module.welcomeEnabled(!module.welcomeEnabled()); Config.saveAll();
        });

        PersonalInfo.Snapshot data = PersonalInfo.snapshot();
        float serverTop = start + 282, content = serverTop + 28;
        String address = !data.inWorld() ? Lang.t("ui.personal.notConnected") : data.integrated()
                ? Lang.t("ui.personal.localWorld") : data.address().isBlank() ? Lang.t("ui.personal.addressUnavailable") : data.address();
        java.util.List<String> addressLines = c.wrap(REG, address, 11, W - 80);
        String operatorText = data.operatorStatusKey().isBlank() ? String.join("、", data.operators()) : Lang.t(data.operatorStatusKey());
        java.util.List<String> operatorLines = c.wrap(REG, operatorText, 10.5f, W - 80);
        float serverHeight = 226 + Math.max(0, addressLines.size() - 1) * 16 + Math.max(0, operatorLines.size() - 1) * 16;
        c.lens(24, y(serverTop), W - 48, serverHeight, 18, Theme.lift(.035f), Theme.chipGlass(c.scale));
        c.textC(BOLD, Lang.t("ui.personal.server"), 40, y(content), 13, Theme.TEXT); content += 35;
        c.textC(REG, Lang.t("ui.personal.address"), 40, y(content), 10.5f, Theme.TEXT_3); content += 22;
        for (String line : addressLines) { c.textC(REG, line, 40, y(content), 11, Theme.TEXT_2); content += 16; }
        content += 18;
        String online = !data.inWorld() ? Lang.t("ui.personal.notConnected")
                : data.integrated() ? Lang.t("ui.personal.onlineCount", data.players()) : Lang.t("ui.personal.clientOnlineCount", data.players());
        c.textC(REG, Lang.t("ui.personal.online") + "  " + online, 40, y(content), 11, Theme.TEXT_2); content += 31;
        c.textC(REG, Lang.t("ui.personal.operators"), 40, y(content), 10.5f, Theme.TEXT_3); content += 22;
        for (String line : operatorLines) { c.textC(REG, line, 40, y(content), 10.5f, Theme.TEXT_2); content += 16; }
        content += 16;
        c.textC(REG, Lang.t("ui.personal.serverPrivacy"), 40, y(content), 9.5f, Theme.TEXT_3);
        float refreshY = serverTop + serverHeight + 12;
        button("personal.refresh", Lang.t("ui.personal.refresh"), Icon.REFRESH, 24, y(refreshY), W - 48, 28, false, () -> {
            PersonalInfo.forceRefresh(); statusKey = "ui.personal.refreshed";
        });
        return refreshY + 44;
    }
    private void chooseAvatar() {
        PersonalAvatar.choose(module.avatarPath(), file -> {
            if (closed) return;
            commit(); statusKey = "";
            mc.setScreen(new PersonalAvatarEditorScreen(this, module, file));
        }, () -> { if (!closed) statusKey = "ui.personal.avatarPickerError"; });
    }
    private void editAvatar() {
        if (module.avatarPath().isBlank()) { chooseAvatar(); return; }
        try {
            commit(); statusKey = "";
            mc.setScreen(new PersonalAvatarEditorScreen(this, module, java.nio.file.Path.of(module.avatarPath())));
        } catch (RuntimeException unavailable) { statusKey = "ui.personal.avatarError"; }
    }
    private void commit() { module.displayName(displayId.text); Config.saveAll(); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (!dispatchClick(x, y, button)) focused = null; return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { if (focused != null) focused = null; else onClose(); return true; }
        if (key == GLFW.GLFW_KEY_TAB) { focused = focused == displayId ? null : displayId; return true; }
        if (focused != null) { focused.keyPressed(key, modifiers); return true; } return false;
    }
    @Override public boolean charTyped(char character, int modifiers) { return focused != null && focused.charTyped(character); }
    @Override public void onClose() { commit(); closed = true; mc.setScreen(parent); }
    @Override public void removed() { commit(); super.removed(); }
}
