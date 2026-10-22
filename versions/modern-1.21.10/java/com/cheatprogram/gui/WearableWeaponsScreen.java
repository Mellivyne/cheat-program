package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.visual.WearableWeapons;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.WearableWeaponsPreview;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Visual selection of real backpack slots, with a compact orbitable player preview. */
public final class WearableWeaponsScreen extends GuiBase {
    private static final float W = 820;
    private final Screen parent;
    private final WearableWeapons module;
    private final WearableWeaponsPreview preview = new WearableWeaponsPreview();
    private final Scroller controls = new Scroller();
    private long lastPreviewClick;
    public WearableWeaponsScreen(Screen parent, WearableWeapons module) {
        super(Lang.t("ui.wearable.title")); this.parent = parent; this.module = module;
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        beginFrame(); if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; }
        R2D.begin(graphics);
        try {
            float h = layoutPanel(W, 650); drawPanelSurface(graphics, W, h, 20);
            c.textC(BOLD, Lang.t("ui.wearable.title"), 24, 29, 14, Theme.TEXT);
            iconButton("wear.close", Icon.CLOSE, W - 28, 29, 14, Theme.TEXT_2, this::onClose);
            for (int i = 0; i < 4; i++) {
                int part = i;
                button("wear.part." + i, Lang.mode(WearableWeapons.PARTS[i]), null, 24 + i * 194, 53, 186, 29,
                        module.selectedPart() == i, () -> { module.selectPart(part); controls.target = 0; controls.value.snap(0); });
            }
            float top = 98, bottom = h - 62, previewHeight = Math.max(120, bottom - top - 190);
            c.lens(24, top, 352, previewHeight, 14, Theme.lift(.035f), Theme.lensGlass(c.scale));
            if (module.showPreview()) {
                preview.draw(graphics, c.px(24), c.py(top), 352 * c.scale, previewHeight * c.scale, module);
                R2D.applyState();
                if (!preview.ready()) c.textMid(REG, Lang.t(preview.statusKey()), 200, top + previewHeight / 2, 10, Theme.TEXT_3);
            } else c.textMid(REG, Lang.t("ui.wearable.previewOff"), 200, top + previewHeight / 2, 10, Theme.TEXT_3);
            hit(24, top, 352, previewHeight).scroll(amount -> preview.zoom = Math.max(.35f, Math.min(2.4f, preview.zoom * (float)Math.pow(1.12, amount))))
                    .click(button -> beginPreviewDrag(button));
            c.textC(REG, Lang.t("ui.wearable.previewHint"), 27, top + previewHeight + 14, 8, Theme.TEXT_3);
            inventory(graphics, 24, top + previewHeight + 29);
            transformPane(398, top, 398, bottom - top);
            button("wear.back", Lang.t("ui.back"), Icon.BACK, 24, h - 42, 90, 28, false, this::onClose);
            button("wear.enable", Lang.t(module.isEnabled() ? "ui.wearable.disable" : "ui.wearable.enable"), Icon.POWER,
                    126, h - 42, 140, 28, module.isEnabled(), module::toggle);
            c.textC(REG, Lang.t("ui.wearable.localHint"), 280, h - 28, 8, Theme.TEXT_3);
        } finally { R2D.end(); endFrame(); }
    }
    private void beginPreviewDrag(int button) {
        if (button != 0 && button != 1) return;
        long now = System.currentTimeMillis();
        if (button == 0 && now - lastPreviewClick < 260) { preview.reset(); lastPreviewClick = 0; return; }
        if (button == 0) lastPreviewClick = now;
        float sx = mx, sy = my, yaw = preview.yaw, pitch = preview.pitch, px = preview.panX, py = preview.panY;
        drag = (u, v) -> {
            if (button == 0) { preview.yaw = (yaw + (u - sx) * .8f) % 360; preview.pitch = Math.max(-65, Math.min(65, pitch + (v - sy) * .8f)); }
            else { preview.panX = Math.max(-.45f, Math.min(.45f, px + (u - sx) / 350)); preview.panY = Math.max(-.45f, Math.min(.45f, py + (v - sy) / 350)); }
        };
    }
    private void inventory(GuiGraphics graphics, float x, float y) {
        WearableWeapons.Attachment part = module.attachment(module.selectedPart());
        button("wear.auto", Lang.t("ui.wearable.autoSelect"), null, x, y, 169, 26, part.slot.intVal() == 0, () -> part.slot.set(0D));
        button("wear.show", Lang.t(part.shown.on() ? "ui.wearable.hidePart" : "ui.wearable.showPart"), null, x + 183, y, 169, 26,
                part.shown.on(), part.shown::toggle);
        y += 34;
        if (mc.player == null) { c.textC(REG, Lang.t("ui.wearable.noworld"), x, y + 14, 10.5f, Theme.TEXT_3); return; }
        String hovered = "";
        for (int row = 0; row < 4; row++) for (int column = 0; column < 9; column++) {
            int slot = row == 3 ? column : 9 + row * 9 + column;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            float sx = x + column * 39, sy = y + row * 27 + (row == 3 ? 4 : 0);
            boolean selected = part.slot.intVal() == slot + 1, valid = part.accepts(stack);
            c.rrect(sx, sy, 37, 25, 5, Anim.white(selected ? .28f : valid ? .10f : .035f));
            if (valid) hit(sx, sy, 37, 25).left(() -> { part.slot.set((double)slot + 1); part.shown.set(true); });
            if (over(sx, sy, 37, 25) && !stack.isEmpty()) hovered = (slot + 1) + " · " + stack.getHoverName().getString();
        }
        var pose = graphics.pose(); pose.pushMatrix();
        try {
            float scale = (float)mc.getWindow().getGuiScale();
            pose.translate(c.px(x) / scale, c.py(y) / scale); pose.scale(c.scale / scale, c.scale / scale);
            for (int row = 0; row < 4; row++) for (int column = 0; column < 9; column++) {
                int slot = row == 3 ? column : 9 + row * 9 + column;
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (!stack.isEmpty()) graphics.renderItem(stack, column * 39 + 10, row * 27 + (row == 3 ? 4 : 0) + 4);
            }
            com.cheatprogram.compat.GuiBridge.flush(graphics);
        } finally { pose.popMatrix(); R2D.applyState(); }
        c.textC(REG, c.ellipsize(REG, hovered.isEmpty() ? Lang.t("ui.wearable.inventoryHint") : hovered, 8.5f, 352), x, y + 124, 8.5f, Theme.TEXT_3);
    }
    private void transformPane(float x, float y, float width, float height) {
        c.lens(x, y, width, height, 14, Theme.lift(.025f), Theme.lensGlass(c.scale));
        hit(x, y, width, height).scroll(controls::scroll);
        c.clip(x + 2, y + 2, width - 4, height - 4); pushHitClip(x + 2, y + 2, width - 4, height - 4);
        float cy = y + 22 - controls.get(), start = cy;
        WearableWeapons.Attachment a = module.attachment(module.selectedPart());
        c.textC(MED, a.name(), x + 17, cy, 12, Theme.TEXT); cy += 25;
        for (NumberSetting setting : a.transforms()) {
            c.textC(REG, setting.name(), x + 17, cy, 11, Theme.TEXT_2);
            c.textC(REG, setting.format(), x + width - c.textWidth(REG, setting.format(), 11) - 17, cy, 11, Theme.TEXT);
            slider(setting, x + 22, cy + 23, width - 44); cy += 52;
        }
        button("wear.reset", Lang.t("ui.wearable.resetPart"), Icon.REFRESH, x + 17, cy, width - 34, 28, false, a::resetTransform); cy += 42;
        popHitClip(); c.unclip(); controls.max = Math.max(0, cy - start - height + 34); controls.update(dt);
        panelScrollBar("wear.controls.scroll", controls, x + width - 5, y + 7, height - 14);
    }
    @Override public boolean mouseClicked(double x, double y, int button) { dispatchClick(x, y, button); return true; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { dispatchScroll(x, y, vertical); return true; }
    @Override public boolean keyPressed(int key, int scan, int modifiers) { if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; } return super.keyPressed(key, scan, modifiers); }
    @Override public void onClose() { mc.setScreen(parent); }
    @Override public void removed() { drag = null; preview.close(); super.removed(); }
}
