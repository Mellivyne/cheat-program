package com.cheatprogram.gui;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.visual.Personal;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.PersonalAvatar;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent source preview: cancellation never changes the live avatar or rewrites its file. */
public final class PersonalAvatarEditorScreen extends GuiBase {
    private static final float W = 720, H = 452, VIEW_X = 24, VIEW_W = 420, VIEW_H = 232;
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Personal avatar editor"); thread.setDaemon(true); return thread;
    });
    private final Screen parent;
    private final Personal personal;
    private final Path initialFile;
    private final Identifier texture = Identifier.fromNamespaceAndPath("cheatprogram", "personal_editor/" + Long.toUnsignedString(System.nanoTime()));
    private final Scroller scroll = new Scroller();
    private final TextField pixelWidth = new TextField(7), pixelHeight = new TextField(7);
    private Personal.AvatarCrop crop = Personal.AvatarCrop.FULL;
    private Path sourceFile;
    private int sourceWidth = 1, sourceHeight = 1;
    private float panelHeight, viewY, offset;
    private double viewZoom = 1, panX, panY;
    private long request;
    private boolean started, closed, busy, registered;
    private String statusKey = "ui.personal.avatarLoading";
    private record View(double x, double y, double width, double height) {
        double u(double x) { return clamp((x - this.x) / width, 0, 1); }
        double v(double y) { return clamp((y - this.y) / height, 0, 1); }
    }

    public PersonalAvatarEditorScreen(Screen parent, Personal personal, Path file) {
        super(Lang.t("ui.personal.cropTitle")); this.parent = parent; this.personal = personal; this.initialFile = file;
        pixelWidth.onEnter = pixelHeight.onEnter = this::applyPixelDimensions;
    }
    @Override protected void init() {
        super.init();
        if (!started) { started = true; load(initialFile); }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        beginFrame();
        if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; }
        R2D.begin(graphics);
        try {
            panelHeight = layoutPanel(W, H); updateMouse(); drawPanelSurface(graphics, W, panelHeight, 24);
            c.textC(BOLD, Lang.t("ui.personal.cropTitle"), 24, 29, 16, Theme.TEXT);
            iconButton("avatar.editor.close", Icon.CLOSE, W - 29, 29, 16, Theme.TEXT_2, this::onClose);
            c.textC(REG, Lang.t("ui.personal.cropHint"), 24, 54, 10.5f, Theme.TEXT_3);
            float bodyTop = 77, bodyHeight = Math.max(60, panelHeight - 151);
            scroll.max = Math.max(0, 393 - bodyHeight); scroll.update(dt); offset = scroll.get();
            c.clip(16, bodyTop, W - 32, bodyHeight); pushHitClip(16, bodyTop, W - 32, bodyHeight);
            hit(16, bodyTop, W - 32, bodyHeight).scroll(scroll::scroll);
            try { contents(bodyTop - offset); } finally { popHitClip(); c.unclip(); }
            panelScrollBar("avatar.editor", scroll, W - 12, bodyTop, bodyHeight);
            c.textC(REG, c.ellipsize(REG, Lang.t(statusKey), 9.5f, W - 48), 24, panelHeight - 50, 9.5f, Theme.TEXT_3);
            button("avatar.editor.cancel", Lang.t("ui.personal.cropCancel"), Icon.BACK, 24, panelHeight - 34, 160, 26, false, this::onClose);
            button("avatar.editor.choose", Lang.t("ui.personal.avatarChoose"), Icon.FOLDER, 196, panelHeight - 34, 324, 26, false, this::choose);
            button("avatar.editor.apply", Lang.t("ui.personal.cropApply"), Icon.SAVE, 532, panelHeight - 34, 164, 26, true, this::apply);
        } finally { R2D.end(); endFrame(); }
    }
    private void contents(float top) {
        c.textC(MED, Lang.t("ui.personal.cropOriginal"), VIEW_X, top + 12, 11, Theme.TEXT_2);
        c.textC(MED, Lang.t("ui.personal.cropPreview"), 464, top + 12, 11, Theme.TEXT_2);
        viewY = top + 29;
        cropView(); preview(top + 29);
        button("avatar.crop.full", Lang.t("ui.personal.cropFull"), Icon.REFRESH, 24, top + 274, 204, 28, false, () -> {
            crop = new Personal.AvatarCrop(0, 0, 1, 1, crop.stretch()); resetView(); syncDimensions();
        });
        button("avatar.crop.square", Lang.t("ui.personal.cropSquare"), null, 240, top + 274, 204, 28, false, this::squareCrop);
        button("avatar.crop.fit", Lang.t(crop.stretch() ? "ui.personal.cropStretch" : "ui.personal.cropKeepAspect"), null,
                464, top + 274, 232, 28, crop.stretch(), () -> {
                    crop = new Personal.AvatarCrop(crop.x(), crop.y(), crop.width(), crop.height(), !crop.stretch());
                });
        c.textC(REG, Lang.t("ui.personal.cropWidth"), 24, top + 323, 11, Theme.TEXT_2);
        c.textC(REG, Lang.t("ui.personal.cropHeight"), 186, top + 323, 11, Theme.TEXT_2);
        textField(pixelWidth, "1", null, 24, top + 337, 150, 29);
        textField(pixelHeight, "1", null, 186, top + 337, 150, 29);
        button("avatar.crop.dimensions", Lang.t("ui.personal.cropSetDimensions"), null, 348, top + 337, 96, 29, false, this::applyPixelDimensions);
        c.textC(REG, Lang.t("ui.personal.cropSourceDimensions", sourceWidth, sourceHeight, Math.round(viewZoom * 100)),
                464, top + 323, 10.5f, Theme.TEXT_2);
        for (String line : c.wrap(REG, Lang.t("ui.personal.cropFitHint"), 10, 232)) {
            c.textC(REG, line, 464, top + 345, 10, Theme.TEXT_3); top += 14;
        }
    }
    private View view() {
        double fit = Math.min((VIEW_W - 24) / sourceWidth, (VIEW_H - 24) / sourceHeight) * viewZoom;
        double width = sourceWidth * fit, height = sourceHeight * fit;
        return new View(VIEW_X + VIEW_W / 2d - width / 2 + panX, viewY + VIEW_H / 2d - height / 2 + panY, width, height);
    }
    private void cropView() {
        c.rrect(VIEW_X, viewY, VIEW_W, VIEW_H, 14, Anim.black(.14f));
        if (!registered) { c.textMid(REG, Lang.t(statusKey), VIEW_X + VIEW_W / 2, viewY + VIEW_H / 2, 11, Theme.TEXT_3); return; }
        View view = view();
        float left = (float) (view.x + crop.x() * view.width), top = (float) (view.y + crop.y() * view.height);
        float width = (float) (crop.width() * view.width), height = (float) (crop.height() * view.height);
        c.clip(VIEW_X, viewY, VIEW_W, VIEW_H);
        try {
            R2D.texture(texture, c.px((float) view.x), c.py((float) view.y), (float) view.width * c.scale,
                    (float) view.height * c.scale, 0, 0, 1, 1, 0xFFFFFFFF);
            float x0 = Math.max(VIEW_X, left), x1 = Math.min(VIEW_X + VIEW_W, left + width);
            float y0 = Math.max(viewY, top), y1 = Math.min(viewY + VIEW_H, top + height);
            if (x1 > x0 && y1 > y0) {
                c.rect(VIEW_X, viewY, x0 - VIEW_X, VIEW_H, Anim.black(.48f));
                c.rect(x1, viewY, VIEW_X + VIEW_W - x1, VIEW_H, Anim.black(.48f));
                c.rect(x0, viewY, x1 - x0, y0 - viewY, Anim.black(.48f));
                c.rect(x0, y1, x1 - x0, viewY + VIEW_H - y1, Anim.black(.48f));
            } else c.rect(VIEW_X, viewY, VIEW_W, VIEW_H, Anim.black(.48f));
            c.rect(left, top, width, 1, Theme.TEXT); c.rect(left, top + height - 1, width, 1, Theme.TEXT);
            c.rect(left, top, 1, height, Theme.TEXT); c.rect(left + width - 1, top, 1, height, Theme.TEXT);
            for (int index = 1; index < 3; index++) {
                c.rect(left + width * index / 3, top, .5f, height, Anim.white(.28f));
                c.rect(left, top + height * index / 3, width, .5f, Anim.white(.28f));
            }
            for (int gy = 0; gy < 3; gy++) for (int gx = 0; gx < 3; gx++) {
                if (gx == 1 && gy == 1) continue;
                c.rrect(left + width * gx / 2 - 3, top + height * gy / 2 - 3, 6, 6, 1.5f, Theme.TEXT);
            }
        } finally { c.unclip(); }
        hit(VIEW_X, viewY, VIEW_W, VIEW_H).click(button -> {
            if (busy) return; focused = null;
            if (button == 1) {
                double oldX = panX, oldY = panY; float initialX = mx, initialY = my;
                drag = (x, y) -> { panX = oldX + x - initialX; panY = oldY + y - initialY; clampPan(); };
            } else if (button == 0) beginCropDrag();
        }).scroll(amount -> {
            if (busy || drag != null) return;
            View before = view(); double u = (mx - before.x) / before.width, v = (my - before.y) / before.height;
            viewZoom = clamp(viewZoom * Math.pow(1.12, amount), .25, 16);
            View after = view(); panX += mx - (after.x + u * after.width); panY += my - (after.y + v * after.height); clampPan();
        });
    }
    private void beginCropDrag() {
        View view = view(); Personal.AvatarCrop original = crop;
        double left = view.x + crop.x() * view.width, right = left + crop.width() * view.width;
        double top = view.y + crop.y() * view.height, bottom = top + crop.height() * view.height;
        boolean nearX = mx >= left - 7 && mx <= right + 7, nearY = my >= top - 7 && my <= bottom + 7;
        boolean leftEdge = nearY && Math.abs(mx - left) <= 7, rightEdge = nearY && Math.abs(mx - right) <= 7;
        boolean topEdge = nearX && Math.abs(my - top) <= 7, bottomEdge = nearX && Math.abs(my - bottom) <= 7;
        if (!com.cheatprogram.compat.CompatScreen.hasShiftDown() && (leftEdge || rightEdge || topEdge || bottomEdge)) {
            drag = (x, y) -> {
                View current = view(); double minW = 1d / sourceWidth, minH = 1d / sourceHeight;
                double x0 = original.x(), y0 = original.y(), x1 = x0 + original.width(), y1 = y0 + original.height();
                if (leftEdge) x0 = Math.min(x1 - minW, current.u(x));
                if (rightEdge) x1 = Math.max(x0 + minW, current.u(x));
                if (topEdge) y0 = Math.min(y1 - minH, current.v(y));
                if (bottomEdge) y1 = Math.max(y0 + minH, current.v(y));
                crop = new Personal.AvatarCrop(x0, y0, x1 - x0, y1 - y0, original.stretch()); syncDimensions();
            }; return;
        }
        if (!com.cheatprogram.compat.CompatScreen.hasShiftDown() && mx >= left && mx <= right && my >= top && my <= bottom
                && (crop.width() < .99999 || crop.height() < .99999)) {
            float initialX = mx, initialY = my;
            drag = (x, y) -> {
                crop = new Personal.AvatarCrop(original.x() + (x - initialX) / view.width,
                        original.y() + (y - initialY) / view.height, original.width(), original.height(), original.stretch());
                syncDimensions();
            }; return;
        }
        double startX = view.u(mx), startY = view.v(my);
        drag = (x, y) -> {
            double endX = view.u(x), endY = view.v(y);
            crop = new Personal.AvatarCrop(Math.min(startX, endX), Math.min(startY, endY),
                    Math.max(1d / sourceWidth, Math.abs(endX - startX)), Math.max(1d / sourceHeight, Math.abs(endY - startY)), original.stretch());
            syncDimensions();
        };
    }
    private void preview(float top) {
        c.lens(464, top, 232, VIEW_H, 14, Theme.lift(.035f), Theme.chipGlass(c.scale));
        c.textC(REG, Lang.t("ui.personal.cropAvatarPreview"), 480, top + 14, 9.5f, Theme.TEXT_3);
        c.textC(REG, Lang.t("ui.personal.cropWelcomePreview"), 574, top + 14, 9.5f, Theme.TEXT_3);
        PersonalAvatar.drawImage(c, registered ? texture : null, sourceWidth, sourceHeight, crop, 476, top + 28, 88, 88, 44, 1, 0);
        PersonalAvatar.drawImage(c, registered ? texture : null, sourceWidth, sourceHeight, crop, 568, top + 31, 112, 82, 8, 1, 0);
        c.textMid(MED, c.ellipsize(MED, personal.displayName(), 12, 204), 580, top + 145, 12, Theme.TEXT);
        c.lens(478, top + 187, 204, 30, 15, Theme.lift(.065f), Theme.lensGlass(c.scale));
        PersonalAvatar.drawImage(c, registered ? texture : null, sourceWidth, sourceHeight, crop, 489, top + 193, 22, 18, 4, 1, 0);
        c.textRight(MED, Lang.t("ui.welcome.success"), 649, top + 202, 10.5f, Theme.TEXT);
        c.icon(Icon.CHECK, 665, top + 202, 13, Theme.TEXT);
    }
    private void squareCrop() {
        if (!registered || busy) return;
        double width = crop.width(), height = crop.height();
        if (width * sourceWidth > height * sourceHeight) width = height * sourceHeight / sourceWidth;
        else height = width * sourceWidth / sourceHeight;
        crop = new Personal.AvatarCrop(crop.x() + (crop.width() - width) / 2, crop.y() + (crop.height() - height) / 2,
                width, height, crop.stretch()); syncDimensions();
    }
    private void applyPixelDimensions() {
        if (!registered || busy) return;
        try {
            double width = clamp(Double.parseDouble(pixelWidth.text.strip()), 1, sourceWidth) / sourceWidth;
            double height = clamp(Double.parseDouble(pixelHeight.text.strip()), 1, sourceHeight) / sourceHeight;
            if (!Double.isFinite(width) || !Double.isFinite(height)) throw new NumberFormatException();
            crop = new Personal.AvatarCrop(crop.x() + (crop.width() - width) / 2, crop.y() + (crop.height() - height) / 2,
                    width, height, crop.stretch()); focused = null; syncDimensions(); statusKey = "ui.personal.cropReady";
        } catch (NumberFormatException invalid) { statusKey = "ui.personal.cropInvalidDimensions"; }
    }
    private void syncDimensions() {
        if (focused != pixelWidth) pixelWidth.setText(Long.toString(Math.max(1, Math.round(crop.width() * sourceWidth))));
        if (focused != pixelHeight) pixelHeight.setText(Long.toString(Math.max(1, Math.round(crop.height() * sourceHeight))));
    }
    private void resetView() { viewZoom = 1; panX = panY = 0; }
    private void clampPan() {
        View view = view();
        panX = clamp(panX, -view.width / 2 - VIEW_W / 2 + 24, view.width / 2 + VIEW_W / 2 - 24);
        panY = clamp(panY, -view.height / 2 - VIEW_H / 2 + 24, view.height / 2 + VIEW_H / 2 - 24);
    }
    private void choose() {
        if (busy || closed) return;
        PersonalAvatar.choose(sourceFile == null ? "" : sourceFile.toString(), file -> {
            if (!closed) load(file);
        }, () -> { if (!closed) statusKey = "ui.personal.avatarPickerError"; });
    }
    private void load(Path file) {
        if (busy || closed || file == null) return;
        busy = true; statusKey = "ui.personal.avatarLoading"; long token = ++request;
        FILES.execute(() -> {
            PersonalAvatar.ImageData decoded = null;
            try { decoded = PersonalAvatar.decodeImage(file); } catch (Exception | OutOfMemoryError ignored) { }
            PersonalAvatar.ImageData result = decoded;
            mc.execute(() -> {
                if (closed || token != request) { if (result != null) result.pixels().close(); return; }
                busy = false;
                if (result == null) { statusKey = "ui.personal.avatarError"; return; }
                DynamicTexture image = null;
                try {
                    image = new DynamicTexture(() -> "Cheat Program texture", result.pixels()); com.cheatprogram.compat.gl.RenderSystem.setTextureFilter(image, true, false);
                    if (registered) { mc.getTextureManager().release(texture); registered = false; }
                    mc.getTextureManager().register(texture, image); registered = true;
                    sourceFile = result.file(); sourceWidth = result.width(); sourceHeight = result.height();
                    crop = sameFile(sourceFile, personal.avatarPath())
                            ? personal.hasAvatarCrop() ? personal.avatarCrop() : PersonalAvatar.legacyCrop(sourceWidth, sourceHeight)
                            : Personal.AvatarCrop.FULL;
                    resetView(); focused = null; syncDimensions(); statusKey = "ui.personal.cropReady";
                } catch (RuntimeException failed) {
                    if (image != null) image.close(); else result.pixels().close(); statusKey = "ui.personal.avatarError";
                }
            });
        });
    }
    private static boolean sameFile(Path first, String second) {
        try { return !second.isBlank() && first.equals(Path.of(second).toAbsolutePath().normalize()); }
        catch (RuntimeException invalid) { return false; }
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private void apply() {
        if (busy || !registered || sourceFile == null || closed) return;
        if (focused == pixelWidth || focused == pixelHeight) {
            applyPixelDimensions(); if (statusKey.equals("ui.personal.cropInvalidDimensions")) return;
        }
        personal.applyAvatar(sourceFile.toString(), crop); Config.saveAll(); onClose();
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (!dispatchClick(x, y, button)) focused = null; return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { if (focused != null) focused = null; else onClose(); return true; }
        if (key == GLFW.GLFW_KEY_TAB) { focused = focused == pixelWidth ? pixelHeight : focused == pixelHeight ? null : pixelWidth; return true; }
        if (focused != null) { focused.keyPressed(key, modifiers); return true; } return false;
    }
    @Override public boolean charTyped(char character, int modifiers) { return focused != null && focused.charTyped(character); }
    @Override public void onClose() { mc.setScreen(parent); }
    @Override public void removed() {
        closed = true; request++; drag = null;
        if (registered) { mc.getTextureManager().release(texture); registered = false; }
        super.removed();
    }
}
