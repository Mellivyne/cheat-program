package com.cheatprogram.gui;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Global font selection; the preview uses the exact renderer used throughout the client. */
public final class FontSettingsScreen extends GuiBase {
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(job -> {
        Thread worker = new Thread(job, "CheatProgram-font-files"); worker.setDaemon(true); return worker;
    });
    private final Screen parent;
    private final TextField family = new TextField(256), path = new TextField(4096), search = new TextField(128);
    private final Scroller scroll = new Scroller();
    private List<String> families = List.of();
    private String statusKey = "";
    private boolean choosing, closed;

    public FontSettingsScreen(Screen parent) {
        super(Lang.t("ui.font.title")); this.parent = parent;
        family.setText(ClientSettings.INSTANCE.fontFamily.get()); path.setText(ClientSettings.INSTANCE.fontPath.get());
        family.onEnter = this::applyFamily; path.onEnter = this::applyPath;
        search.onChange = ignored -> { scroll.target = 0; scroll.value.snap(0); };
        FILES.execute(() -> { List<String> found = Fonts.systemFamilies(); mc.execute(() -> { if (!closed) families = found; }); });
    }

    @Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        beginFrame(); if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; } R2D.begin(graphics);
        try {
            float height = layoutPanel(720, 670); drawPanelSurface(graphics, 720, height, 24);
            c.textC(BOLD, Lang.t("ui.font.title"), 24, 29, 16, Theme.TEXT);
            c.textC(REG, c.ellipsize(REG, Lang.t("ui.font.globalHint"), 9.5f, 672), 24, 54, 9.5f, Theme.TEXT_3);
            String[] modes = {"smooth", "vanilla", "custom"};
            for (int i = 0; i < modes.length; i++) {
                String mode = modes[i]; float x = 24 + i * 228;
                button("font.mode." + mode, c.ellipsize(MED, Lang.mode(mode), 10.5f, 196), null, x, 76, 216, 30,
                        ClientSettings.INSTANCE.fontMode.is(mode), () -> { ClientSettings.INSTANCE.fontMode.set(mode); statusKey = ""; });
            }
            float top = 120, bottom = height - 62, contentHeight = 470 + Math.min(families.size(), 1000) * 30;
            scroll.max = Math.max(0, contentHeight - (bottom - top)); scroll.update(dt);
            hit(20, top, 680, Math.max(0, bottom - top)).scroll(scroll::scroll);
            c.clip(20, top, 680, Math.max(0, bottom - top)); pushHitClip(20, top, 680, Math.max(0, bottom - top));
            try {
                float y = top - scroll.get();
                c.textC(MED, Lang.t("ui.font.preview"), 26, y + 14, 12, Theme.TEXT);
                c.textC(REG, c.ellipsize(REG, Lang.t("ui.font.sample"), 15, 656), 28, y + 47, 15, Theme.TEXT);
                c.textC(BOLD, c.ellipsize(BOLD, Lang.t("ui.font.sample"), 15, 656), 28, y + 76, 15, Theme.TEXT);
                c.textC(REG, "中文 繁體 日本語 한국어 · Español Français Deutsch", 28, y + 105, 12, Theme.TEXT_2);
                c.textC(REG, Lang.t("ui.font.family"), 26, y + 143, 10.5f, Theme.TEXT_2);
                textField(family, Lang.t("ui.font.familyHint"), null, 24, y + 160, 484, 30);
                button("font.family.apply", Lang.t("ui.font.apply"), Icon.CHECK, 520, y + 160, 176, 30, false, this::applyFamily);
                c.textC(REG, Lang.t("ui.font.file"), 26, y + 218, 10.5f, Theme.TEXT_2);
                textField(path, Lang.t("ui.font.fileHint"), Icon.FOLDER, 24, y + 234, 416, 30);
                button("font.file.apply", Lang.t("ui.font.apply"), Icon.CHECK, 452, y + 234, 112, 30, false, this::applyPath);
                button("font.file.browse", Lang.t(choosing ? "ui.font.loading" : "ui.font.import"), Icon.FOLDER, 576, y + 234, 120, 30, false, this::chooseFont);
                String error = statusKey.isEmpty() ? Fonts.errorKey() : statusKey;
                if (!error.isEmpty()) for (String line : c.wrap(REG, Lang.t(error), 9, 656)) { c.textC(REG, line, 28, y + 291, 9, Theme.TEXT_2); y += 14; }
                c.textC(REG, c.ellipsize(REG, Lang.t("ui.font.fallbackHint"), 9, 656), 28, y + 326, 9, Theme.TEXT_3);
                textField(search, Lang.t("ui.font.search"), Icon.SEARCH, 24, y + 351, 672, 29);
                String filter = search.text.toLowerCase(Locale.ROOT); float row = y + 394;
                int count = 0;
                for (String name : families) {
                    if (!name.toLowerCase(Locale.ROOT).contains(filter)) continue;
                    if (row + 28 >= top && row < bottom) button("font.family." + name, c.ellipsize(MED, name, 10.5f, 636), null,
                            24, row, 672, 28, ClientSettings.INSTANCE.fontMode.is("custom") && path.text.isBlank() && name.equalsIgnoreCase(family.text),
                            () -> { family.setText(name); applyFamily(); });
                    row += 30; count++;
                }
                scroll.max = Math.max(0, row + 12 - (top - scroll.get()) - (bottom - top));
                if (count == 0) c.textC(REG, Lang.t("ui.font.noFamilies"), 28, y + 413, 10, Theme.TEXT_3);
            } finally { popHitClip(); c.unclip(); }
            panelScrollBar("font.scroll", scroll, 705, top, Math.max(0, bottom - top));
            button("font.back", Lang.t("ui.back"), Icon.BACK, 24, height - 44, 112, 28, false, this::onClose);
            button("font.reset", Lang.t("ui.font.reset"), Icon.REFRESH, 484, height - 44, 212, 28, false, () -> {
                ClientSettings.INSTANCE.fontMode.reset(); ClientSettings.INSTANCE.fontFamily.reset(); ClientSettings.INSTANCE.fontPath.reset();
                family.setText(""); path.setText(""); statusKey = "";
            });
        } finally { R2D.end(); endFrame(); }
    }

    private void applyFamily() {
        ClientSettings.INSTANCE.fontFamily.set(family.text.strip()); ClientSettings.INSTANCE.fontPath.set(""); path.setText("");
        ClientSettings.INSTANCE.fontMode.set("custom"); statusKey = "";
    }
    private void applyPath() {
        ClientSettings.INSTANCE.fontPath.set(path.text.strip()); ClientSettings.INSTANCE.fontMode.set("custom"); statusKey = "";
    }
    private void chooseFont() {
        if (choosing || closed) return; choosing = true; statusKey = "";
        String title = Lang.t("ui.font.import");
        FILES.execute(() -> {
            String result = null, failure = "";
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var filters = stack.mallocPointer(2); filters.put(stack.UTF8("*.ttf")).put(stack.UTF8("*.otf")).flip();
                String selected = TinyFileDialogs.tinyfd_openFileDialog(title, null, filters, "TrueType / OpenType", false);
                if (selected != null) {
                    Path source = Path.of(selected); Font.createFont(Font.TRUETYPE_FONT, source.toFile());
                    byte[] bytes = Files.readAllBytes(source);
                    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                    String extension = source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".otf") ? ".otf" : ".ttf";
                    synchronized(com.cheatprogram.core.Config.persistenceLock()) {
                    if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IllegalStateException("Restart required after initialization");
                    Path directory = FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/fonts"); Files.createDirectories(directory);
                    Path target = directory.resolve(hash + extension); if (!Files.exists(target)) Files.write(target, bytes);
                    result = target.toAbsolutePath().toString();
                    }
                }
            } catch (Throwable invalid) { failure = "ui.font.invalidFile"; }
            String picked = result, error = failure;
            mc.execute(() -> { if (!closed) { choosing = false; statusKey = error; if (picked != null) { path.setText(picked); applyPath(); } } });
        });
    }
    @Override public boolean mouseClicked(double x, double y, int button) { if (!dispatchClick(x, y, button)) focused = null; return true; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
    @Override public boolean keyPressed(int key, int scan, int mods) { if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; } return focused != null && focused.keyPressed(key, mods); }
    @Override public boolean charTyped(char character, int mods) { return focused != null && focused.charTyped(character); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { closed = true; mc.gui.setScreen(parent); }
    @Override public void removed() { closed = true; super.removed(); }
}
