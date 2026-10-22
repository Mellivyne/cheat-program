package com.cheatprogram.gui;

import com.cheatprogram.audio.SpatialAudioEnvironment;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.module.visual.SpatialAudio;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Explicit, user-driven environment actions. Opening this page only checks the environment. */
public final class SpatialAudioEnvironmentScreen extends GuiBase {
    private static final float W = 880;
    private final Screen parent;
    private final SpatialAudio module;
    private final Scroller scroll = new Scroller();
    private boolean initialCheck;
    private float panelHeight;
    private Confirmation confirmation;
    private String actionNotice = "";

    private record Confirmation(String action, int pid, String source) {}

    public SpatialAudioEnvironmentScreen(Screen parent, SpatialAudio module) {
        super(Lang.t("ui.spatial.env.title"));
        this.parent = parent;
        this.module = module;
    }

    @Override protected void init() {
        if (!initialCheck) { initialCheck = true; SpatialAudioEnvironment.refresh(); }
    }

    @Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        beginFrame();
        if (!preparePanelBackground(graphics, partial)) { endFrame(); return; }
        R2D.begin(graphics);
        try {
            panelHeight = layoutPanel(W, 680);
            drawPanelSurface(graphics, W, panelHeight, 24);
            c.textC(BOLD, Lang.t("ui.spatial.env.title"), 24, 29, 16, Theme.TEXT);
            iconButton("spatial.env.close", Icon.CLOSE, W - 28, 29, 14, Theme.TEXT_2, this::onClose);
            c.textC(REG, c.ellipsize(REG, Lang.t("ui.spatial.env.subtitle"), 9.5f, W - 48), 25, 53, 9.5f, Theme.TEXT_3);
            SpatialAudioEnvironment.State state = SpatialAudioEnvironment.state();
            action("detect", "ui.spatial.env.detect", Icon.REFRESH, 24, 72, 172, 30, false, SpatialAudioEnvironment::refresh);
            c.textC(REG, c.ellipsize(REG, Lang.t(state.statusKey()), 10.5f, W - 232), 212, 87, 10.5f, Theme.TEXT_2);
            float top = 118, available = Math.max(40, panelHeight - 174);
            scroll.update(dt);
            hit(18, top, W - 36, available).scroll(scroll::scroll);
            c.clip(18, top, W - 36, available); pushHitClip(18, top, W - 36, available);
            try {
                float y = top - scroll.get(), start = y;
                y = sourceSection(state, y);
                y = actionSection(y + 16);
                y = folderSection(state, y + 16);
                scroll.max = Math.max(0, y + 12 - start - available);
            } finally { popHitClip(); c.unclip(); }
            panelScrollBar("spatial.env.scroll", scroll, W - 13, top, available);
            button("spatial.env.back", Lang.t("ui.back"), Icon.BACK, 24, panelHeight - 40, 112, 28, false, this::onClose);
            c.textRight(REG, Lang.t(state.busy() ? "ui.spatial.env.busy" : "ui.spatial.env.explicitOnly"), W - 25, panelHeight - 26, 8.5f, Theme.TEXT_3);
            if (confirmation != null) confirmationOverlay();
        } finally { R2D.end(); endFrame(); }
    }

    private float sourceSection(SpatialAudioEnvironment.State state, float y) {
        c.textC(MED, Lang.t("ui.spatial.env.source"), 28, y + 14, 12, Theme.TEXT); y += 29;
        AppCast.AudioSource source = module.selectedSource();
        String label = source == null ? Lang.t("ui.spatial.noCast") : source.label();
        button("spatial.env.source", c.ellipsize(MED, label, 10.5f, W - 96), Icon.LAYOUT,
                24, y, W - 48, 34, false, this::nextScreen); y += 44;
        y = notes(Lang.t("ui.spatial.env.sourceHint"), y, 9, Theme.TEXT_3);
        y = row("ui.spatial.env.driver", Lang.t(state.driverInstalled() ? "ui.spatial.env.detected" : "ui.spatial.env.notDetected"), y);
        y = row("ui.spatial.env.prerequisites", Lang.t(state.ready() ? "ui.spatial.env.prerequisitesReady" : "ui.spatial.env.prerequisitesMissing"), y);
        y = row("ui.spatial.env.appRoute", Lang.t(source != null && SpatialAudioEnvironment.routedFor(source.pid())
                ? "ui.spatial.env.routeConfirmed" : "ui.spatial.env.routeUnconfirmed"), y);
        String playback = state.playbackDevice();
        y = row("ui.spatial.env.gameOutput", playback == null || playback.isBlank() ? Lang.t("ui.spatial.env.unknownDevice") : playback, y);
        if (source != null) y = notes(module.captureStatus(), y + 5, 9, Theme.TEXT_2);
        String details = state.details();
        if (details != null && !details.isBlank()) y = notes(details.length() > 1800 ? details.substring(0, 1800) : details, y + 3, 9, Theme.TEXT_3);
        return y;
    }

    private float actionSection(float y) {
        c.textC(MED, Lang.t("ui.spatial.env.actions"), 28, y + 14, 12, Theme.TEXT); y += 31;
        float half = (W - 60) / 2;
        action("configure", "ui.spatial.env.configure", Icon.CHECK, 24, y, half, 33, true, () -> {
            AppCast.AudioSource source = module.selectedSource();
            if (source != null && source.active() && source.pid() > 4) {
                actionNotice = "";
                confirmation = new Confirmation("configure", source.pid(), source.label());
            } else actionNotice = "ui.spatial.env.selectActiveCast";
        });
        action("restore", "ui.spatial.env.restore", Icon.REFRESH, 36 + half, y, half, 33, false, SpatialAudioEnvironment::restore); y += 45;
        y = notes(Lang.t("ui.spatial.env.configureHint"), y, 9, Theme.TEXT_3);
        if (!actionNotice.isBlank()) y = notes(Lang.t(actionNotice), y, 9, Theme.TEXT_2);
        action("install", "ui.spatial.env.install", Icon.DOWNLOAD, 24, y, half, 33, false,
                () -> confirmation = new Confirmation("install", 0, ""));
        action("uninstall", "ui.spatial.env.uninstall", Icon.TRASH, 36 + half, y, half, 33, false,
                () -> confirmation = new Confirmation("uninstall", 0, "")); y += 45;
        y = notes(Lang.t("ui.spatial.env.driverHint"), y, 9, Theme.TEXT_3);
        return notes(Lang.t("ui.spatial.env.playbackHint"), y, 9, Theme.TEXT_3);
    }

    private float folderSection(SpatialAudioEnvironment.State state, float y) {
        c.textC(MED, Lang.t("ui.spatial.env.files"), 28, y + 14, 12, Theme.TEXT); y += 31;
        float half = (W - 60) / 2;
        action("folder", "ui.spatial.env.openFolder", Icon.FOLDER, 24, y, half, 31, false, SpatialAudioEnvironment::openFolder);
        action("downloads", "ui.spatial.env.openDownloads", Icon.FOLDER, 36 + half, y, half, 31, false, SpatialAudioEnvironment::openDownloadFolder); y += 44;
        y = notes(Lang.t("ui.spatial.env.folderPath", SpatialAudioEnvironment.directory()), y, 9, Theme.TEXT_3);
        y = notes(Lang.t("ui.spatial.env.downloadPath", SpatialAudioEnvironment.downloadDirectory()), y, 9, Theme.TEXT_3);
        return notes(Lang.t("ui.spatial.env.resultHint"), y + 3, 9, Theme.TEXT_3);
    }

    private float row(String key, String value, float y) {
        c.textC(REG, Lang.t(key), 28, y + 13, 10.5f, Theme.TEXT_2);
        c.textRight(REG, c.ellipsize(REG, value, 10.5f, W * .57f), W - 28, y + 13, 10.5f, Theme.TEXT);
        return y + 29;
    }

    private float notes(String text, float y, float size, int color) {
        for (String line : c.wrap(REG, text, size, W - 56)) {
            c.textC(REG, line, 28, y + 10, size, color); y += 15;
        }
        return y + 10;
    }

    private void action(String id, String key, Icon icon, float x, float y, float width, float height,
                        boolean primary, Runnable action) {
        button("spatial.env." + id, c.ellipsize(MED, Lang.t(key), 10.5f, width - 44), icon,
                x, y, width, height, primary, () -> { if (!SpatialAudioEnvironment.busy()) action.run(); });
    }

    private void nextScreen() {
        if (SpatialAudioEnvironment.busy()) return;
        if (module.backgroundAudio()) { mc.gui.setScreen(new SpatialAudioSourceScreen(this, module)); return; }
        List<Integer> choices = new ArrayList<>(); choices.add(-1);
        for (AppCast.AudioSource source : module.availableSources()) choices.add(source.index());
        module.screenIndex(choices.get((choices.indexOf(module.screenIndex()) + 1) % choices.size()));
    }

    private void confirmationOverlay() {
        Confirmation pending = confirmation;
        String key = "ui.spatial.env.confirm." + pending.action();
        String message = Lang.t(key, pending.source());
        float width = 616;
        List<String> lines = c.wrap(REG, message, 10.5f, width - 44);
        float height = 113 + lines.size() * 18;
        float x = (W - width) / 2, y = (panelHeight - height) / 2;
        c.rrect(0, 0, W, panelHeight, 0, Anim.black(.30f));
        hit(0, 0, W, panelHeight).click(button -> {}).scroll(amount -> {});
        c.lens(x, y, width, height, 20, Theme.lift(.17f), Theme.sheetGlass(c.scale));
        c.textC(BOLD, Lang.t("ui.spatial.env.confirm.title"), x + 22, y + 28, 14, Theme.TEXT);
        float py = y + 59;
        for (String line : lines) { c.textC(REG, line, x + 22, py, 10.5f, Theme.TEXT_2); py += 18; }
        button("spatial.env.confirm.cancel", Lang.t("ui.spatial.env.cancel"), null,
                x + 22, y + height - 46, (width - 56) / 2, 29, false, () -> confirmation = null);
        button("spatial.env.confirm.proceed", Lang.t("ui.spatial.env.confirm.action"), pending.action().equals("uninstall") ? Icon.TRASH : Icon.CHECK,
                x + width / 2 + 6, y + height - 46, (width - 56) / 2, 29, true, () -> {
                    if (SpatialAudioEnvironment.busy()) return;
                    confirmation = null;
                    switch (pending.action()) {
                        case "configure" -> {
                            AppCast.AudioSource selected = module.selectedSource();
                            if (selected == null || !selected.active() || selected.pid() != pending.pid()) {
                                actionNotice = "ui.spatial.env.selectActiveCast";
                                return;
                            }
                            module.ensureIsolatedCapture();
                            SpatialAudioEnvironment.configure(pending.pid());
                        }
                        case "install" -> SpatialAudioEnvironment.install();
                        case "uninstall" -> SpatialAudioEnvironment.uninstall();
                    }
                });
    }

    @Override public boolean mouseClicked(double x, double y, int button) { dispatchClick(x, y, button); return true; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { if (confirmation != null) confirmation = null; else onClose(); return true; }
        return false;
    }
    @Override public void onClose() { if (confirmation != null) confirmation = null; else mc.gui.setScreen(parent); }
}
