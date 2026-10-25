package com.cheatprogram.module.legit;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudLayout;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.util.Anim;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Locale;

/** Accessibility cues from sounds the server actually delivered to this client. */
public final class SoundDirections extends Module {
    private final BoolSetting footsteps = add(new BoolSetting("footsteps", true));
    private final BoolSetting explosions = add(new BoolSetting("explosions", true));
    private final BoolSetting projectiles = add(new BoolSetting("projectiles", true));
    private final BoolSetting other = add(new BoolSetting("other", false));
    private final BoolSetting ignoreOwn = add(new BoolSetting("ignoreOwn", true));
    private final NumberSetting range = add(new NumberSetting("range", 48, 4, 128, 1));
    private final NumberSetting lifetime = add(new NumberSetting("lifetime", 3000, 500, 10000, 100, "ms"));
    private final NumberSetting rows = add(new NumberSetting("rows", 6, 1, 12, 1));
    private final NumberSetting size = add(new NumberSetting("size", 100, 60, 200, 5, "%"));
    private final ArrayDeque<Cue> cues = new ArrayDeque<>();
    private ClientLevel world;
    public SoundDirections() { super("sounddirections", Category.LEGIT); }
    private record Cue(ResourceLocation id, String subtitle, Vec3 position, long created) {}
    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    private void reset() { cues.clear(); world = mc.level; }
    @Override public void onTick() {
        if (world != mc.level) reset();
        long now = System.nanoTime(); cues.removeIf(cue -> (now - cue.created) / 1_000_000 > lifetime.val());
    }
    public static void received(ResourceLocation id, Vec3 position, int entityId, float volume) {
        SoundDirections module = ModuleManager.get(SoundDirections.class);
        if (module != null && module.isEnabled()) module.addSound(id, position, entityId, volume);
    }
    private void addSound(ResourceLocation id, Vec3 position, int entityId, float volume) {
        if (!inGame() || volume <= 0 || position == null || !Double.isFinite(position.x)
                || !Double.isFinite(position.y) || !Double.isFinite(position.z)) return;
        if (world != mc.level) reset();
        double distance = mc.player.position().distanceTo(position);
        if (distance > range.val() || (ignoreOwn.on() && (entityId == mc.player.getId() || distance < .65))) return;
        String path = id.getPath();
        boolean step = path.contains(".step") || path.contains(".swim"), boom = path.contains("explode") || path.contains("explosion") || path.contains("sonic_boom");
        boolean arrow = path.contains("arrow") || path.contains("trident") || path.contains("crossbow.shoot") || path.contains("bow.shoot");
        if (step ? !footsteps.on() : boom ? !explosions.on() : arrow ? !projectiles.on() : !other.on()) return;
        String text;
        var event = mc.getSoundManager().getSoundEvent(id);
        Component subtitle = event == null ? null : event.getSubtitle();
        if (subtitle != null) text = subtitle.getString();
        else text = Lang.t(step ? "ui.sounddirections.step" : boom ? "ui.sounddirections.explosion" : arrow ? "ui.sounddirections.projectile" : "ui.sounddirections.other");
        long now = System.nanoTime();
        cues.removeIf(cue -> cue.id.equals(id) && cue.position.distanceToSqr(position) < 2.25 && now - cue.created < 250_000_000L);
        cues.addFirst(new Cue(id, text, position, now)); while (cues.size() > 12) cues.removeLast();
    }
    @Override public void onRender2D(HudContext ctx) {
        if (!inGame()) return;
        if (world != mc.level) reset();
        long now = System.nanoTime(); cues.removeIf(cue -> (now - cue.created) / 1_000_000 > lifetime.val());
        if (cues.isEmpty() && !ctx.editing) return;
        float scale = size.floatVal() / 100F, width = 224 * scale;
        int count = Math.max(1, Math.min(rows.intVal(), cues.size()));
        float height = (29 + count * 23) * scale;
        float x = HudLayout.x(id, width, ctx.width), y = HudLayout.y(id, height, ctx.height);
        ctx.panel(x, y, width, height, 14 * scale);
        ctx.c.push(); ctx.c.translate(x, y); ctx.c.scaleAround(scale, 0, 0);
        try {
            ctx.c.textC(Fonts.Face.MEDIUM, name(), 12, 15, 10, Theme.TEXT);
            if (cues.isEmpty()) ctx.c.textC(Fonts.Face.REGULAR, Lang.t("ui.sounddirections.waiting"), 12, 40, 9, Theme.TEXT_3);
            int index = 0;
            for (Cue cue : cues) {
                if (index >= rows.intVal()) break;
                Vec3 relative = cue.position.subtract(mc.player.getEyePosition(ctx.partialTick));
                float bearing = (float)Math.toDegrees(Math.atan2(-relative.x, relative.z));
                float angle = Mth.wrapDegrees(bearing - mc.player.getViewYRot(ctx.partialTick));
                int direction = Math.floorMod(Math.round(angle / 45), 8);
                String arrow = new String[]{"↑", "↖", "←", "↙", "↓", "↘", "→", "↗"}[direction];
                float age = (now - cue.created) / 1_000_000F;
                float fade = Math.min(1, Math.max(0, (lifetime.floatVal() - age) / 500));
                int color = alpha(Theme.TEXT, fade);
                float line = 40 + index * 23;
                ctx.c.textC(Fonts.Face.MEDIUM, arrow, 13, line, 14, color);
                ctx.c.textC(Fonts.Face.REGULAR, ctx.c.ellipsize(Fonts.Face.REGULAR, cue.subtitle, 9, 132), 35, line - 3, 9, color);
                String distance = String.format(Locale.ROOT, "%.0fm", relative.length());
                ctx.c.textC(Fonts.Face.REGULAR, distance, 212 - ctx.c.textWidth(Fonts.Face.REGULAR, distance, 8.5f), line, 8.5f, alpha(Theme.TEXT_2, fade));
                String vertical = Math.abs(relative.y) < 2 ? "" : Lang.t(relative.y > 0 ? "ui.sounddirections.above" : "ui.sounddirections.below");
                ctx.c.textC(Fonts.Face.REGULAR, vertical, 35, line + 7, 7, alpha(Theme.TEXT_3, fade));
                index++;
            }
        } finally { ctx.c.pop(); }
        HudLayout.report(id, x, y, width, height);
    }
    private static int alpha(int color, float fraction) { return (Math.round((color >>> 24) * fraction) << 24) | (color & 0xffffff); }
}
