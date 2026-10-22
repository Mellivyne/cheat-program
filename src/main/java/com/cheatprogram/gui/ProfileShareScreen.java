package com.cheatprogram.gui;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.ProfileShareCodec;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Offline profile code exchange. Text remains selectable/copyable using the global font. */
public final class ProfileShareScreen extends GuiBase {
    private final Screen parent;
    private final String profile;
    private final boolean deleting;
    private final TextField code = new TextField(ProfileShareCodec.MAX_CODE + 1), name = new TextField(32);
    private final Scroller scroll = new Scroller();
    private String status = "", statusArgument = "";
    private ProfileShareCodec.Decoded validated;

    public ProfileShareScreen(Screen parent, String profile) { this(parent, profile, false); }
    public ProfileShareScreen(Screen parent, String profile, boolean deleting) {
        super(Lang.t(deleting ? "ui.profileCode.deleteTitle" : "ui.profileCode.title"));
        this.parent = parent; this.profile = profile; this.deleting = deleting;
        code.onChange = ignored -> { validated = null; status = ""; };
        code.onEnter = this::apply;
        if (profile != null && !deleting) refreshCode();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        beginFrame(); if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; } R2D.begin(graphics);
        try {
            float height = layoutPanel(720, 492); drawPanelSurface(graphics, 720, height, 24);
            c.textC(BOLD, Lang.t(deleting ? "ui.profileCode.deleteTitle" : "ui.profileCode.title"), 24, 29, 16, Theme.TEXT);
            iconButton("profileCode.close", Icon.CLOSE, 692, 29, 16, Theme.TEXT_2, this::onClose);
            c.textC(REG, c.ellipsize(REG, profile == null ? Lang.t("ui.profileCode.importDesc") : profile, 11, 660), 24, 57, 11, Theme.TEXT_2);
            float top = 82, bodyHeight = Math.max(80, height - 144);
            scroll.max = Math.max(0, 380 - bodyHeight); scroll.update(dt);
            hit(20, top, 680, bodyHeight).scroll(scroll::scroll); c.clip(20, top, 680, bodyHeight); pushHitClip(20, top, 680, bodyHeight);
            try {
                float y = top - scroll.get();
                if (deleting) {
                    c.textC(MED, Lang.t("ui.profileCode.deleteName", profile), 26, y + 18, 13, Theme.TEXT);
                    y = paragraph(Lang.t("ui.profileCode.deleteHint"), y + 47, 11, Theme.TEXT_2) + 22;
                    button("profileCode.delete", Lang.t("ui.profileCode.deleteConfirm"), Icon.TRASH, 24, y, 672, 34, true, this::delete);
                    y += 59;
                } else {
                    boolean exporting = profile != null;
                    c.textC(MED, Lang.t(exporting ? "ui.profileCode.exportLabel" : "ui.profileCode.inputLabel"), 26, y + 13, 12, Theme.TEXT);
                    textField(code, "CP1.…", Icon.LINK, 24, y + 31, 672, 34);
                    if (exporting) {
                        button("profileCode.refresh", Lang.t("ui.profileCode.refresh"), Icon.REFRESH, 24, y + 79, 252, 32, false, this::refreshCode);
                        button("profileCode.copy", Lang.t("ui.profileCode.copy"), Icon.LINK, 288, y + 79, 408, 32, true, () -> {
                            if (!code.text.isBlank()) { mc.keyboardHandler.setClipboard(code.text); status = "ui.profileCode.copied"; statusArgument = ""; }
                        });
                        y += 139;
                        y = paragraph(Lang.t("ui.profileCode.identityHint"), y, 10.5f, Theme.TEXT_2) + 18;
                    } else {
                        c.textC(REG, Lang.t("ui.profileCode.nameLabel"), 26, y + 94, 11, Theme.TEXT_2);
                        textField(name, Lang.t("ui.profileCode.nameHint"), Icon.FOLDER, 24, y + 110, 672, 32);
                        button("profileCode.validate", Lang.t("ui.profileCode.validate"), Icon.CHECK, 24, y + 158, 252, 32, false, this::validate);
                        button("profileCode.apply", Lang.t("ui.profileCode.apply"), Icon.DOWNLOAD, 288, y + 158, 408, 32, true, this::apply);
                        y += 216;
                        y = paragraph(Lang.t("ui.profileCode.independentHint"), y, 10.5f, Theme.TEXT_2) + 18;
                    }
                    y = paragraph(Lang.t("ui.profileCode.privacyHint"), y, 9, Theme.TEXT_3) + 15;
                    y = paragraph(Lang.t("ui.profileCode.localResourceHint"), y, 9, Theme.TEXT_3) + 15;
                    if (validated != null) y = paragraph(Lang.t("ui.profileCode.validated", validated.modules().size(), validated.enabledCount()), y, 11, Theme.TEXT_2) + 14;
                }
                if (!status.isEmpty()) y = paragraph(Lang.t(status, statusArgument), y, 10.5f, Theme.TEXT_2) + 18;
                scroll.max = Math.max(0, y + scroll.get() + 10 - top - bodyHeight);
            } finally { popHitClip(); c.unclip(); }
            panelScrollBar("profileCode.scroll", scroll, 705, top, bodyHeight);
            button("profileCode.back", Lang.t("ui.back"), Icon.BACK, 24, height - 44, 130, 28, false, this::onClose);
        } finally { R2D.end(); endFrame(); }
    }
    private float paragraph(String text, float y, float size, int color) {
        for (String line : c.wrap(REG, text, size, 660)) { c.textC(REG, line, 26, y + 7, size, color); y += size + 6; }
        return y;
    }
    private void refreshCode() {
        if (profile == null || deleting) return;
        try { code.setText(Config.shareProfile(profile)); status = "ui.profileCode.ready"; statusArgument = Integer.toString(code.text.length()); }
        catch (ProfileShareCodec.InvalidCode invalid) { status = invalid.key; statusArgument = ""; code.setText(""); }
        catch (Exception failure) { status = "ui.profileCode.saveFailed"; statusArgument = ""; code.setText(""); }
    }
    private boolean validate() {
        try { validated = ProfileShareCodec.decode(code.text); status = "ui.profileCode.valid"; statusArgument = ""; return true; }
        catch (ProfileShareCodec.InvalidCode invalid) { validated = null; status = invalid.key; statusArgument = ""; return false; }
    }
    private void apply() {
        if (profile != null || deleting || !validate()) return;
        try {
            String imported = Config.importProfile(validated, name.text);
            Notifications.push(Lang.t("ui.profileCode.imported"), imported, true); onClose();
        } catch (Exception failure) { status = "ui.profileCode.saveFailed"; statusArgument = ""; }
    }
    private void delete() {
        if (!deleting || profile == null) return;
        if (Config.deleteProfile(profile)) { Notifications.push(Lang.t("ui.profileCode.deleted"), profile, false); onClose(); }
        else { status = "ui.profileCode.deleteFailed"; statusArgument = ""; }
    }
    @Override public boolean mouseClicked(double x, double y, int button) { if (!dispatchClick(x, y, button)) focused = null; return true; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (profile != null && focused == code && !((mods & GLFW.GLFW_MOD_CONTROL) != 0 && (key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_C))
                && key != GLFW.GLFW_KEY_LEFT && key != GLFW.GLFW_KEY_RIGHT && key != GLFW.GLFW_KEY_HOME && key != GLFW.GLFW_KEY_END) return true;
        return focused != null && focused.keyPressed(key, mods);
    }
    @Override public boolean charTyped(char character, int mods) { return !(profile != null && focused == code) && focused != null && focused.charTyped(character); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { mc.setScreen(parent); }
}
