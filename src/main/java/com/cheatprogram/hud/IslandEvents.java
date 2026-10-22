package com.cheatprogram.hud;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.gui.GuiBase;
import com.cheatprogram.gui.HudEditorScreen;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.MotionCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Local, confirmed gameplay events; all visual contents share DynamicIsland's one outer surface. */
public final class IslandEvents {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int MAX_PENDING = 8, MAX_WATCHED = 12;
    private static final long WATCH_NANOS = 8_000_000_000L, LOW_HEALTH_COOLDOWN = 10_000_000_000L;
    private static final ArrayDeque<Event> PENDING = new ArrayDeque<>();
    private static final LinkedHashMap<UUID, WatchedTarget> WATCHED = new LinkedHashMap<>();
    private static Object connection;
    private static ClientLevel level;
    private static LocalPlayer player;
    private static ResourceKey<Level> dimension;
    private static boolean previousAlive, deathSeen, lowLatched, observing;
    private static long lastLowHealth;
    private static Event active;
    private static float age;
    private static String configuredStyle;
    private static int previewSequence;

    private enum Kind { DEATH, RESPAWN, DIMENSION, TOTEM, LOW_HEALTH, TARGET_DOWN, PREVIEW }
    private record WatchedTarget(LivingEntity entity, long attackedAt) { }
    private record Event(Kind kind, String key, String title, String detail, Icon icon, String style, String automaticStyle,
                         int priority, long created, float timeScale, float hold, float intensity,
                         float value, boolean positive) { }

    private IslandEvents() { }

    /** Updates even while the death screen is open. Hidden/disabled HUD states consume events. */
    public static void tickLifecycle() {
        Object nextConnection = MC.getConnection();
        if (nextConnection == null) { reset(); return; }
        if (nextConnection != connection) {
            reset();
            connection = nextConnection;
        }
        Hud hud = hud();
        syncStyle(hud);
        boolean displayAvailable = displayAvailable(hud);
        boolean available = displayAvailable && MC.level != null && MC.player != null;
        if (!displayAvailable) { clearVisuals(); observing = false; }
        if (MC.level == null || MC.player == null) {
            WATCHED.clear();
            // Respawn and dimension loads can briefly replace these objects on the same connection.
            // Keep the prior alive/death observation so a genuine respawn can still be shown afterward.
            return;
        }

        ClientLevel nextLevel = MC.level;
        LocalPlayer nextPlayer = MC.player;
        ResourceKey<Level> nextDimension = nextLevel.dimension();
        boolean alive = nextPlayer.isAlive() && !nextPlayer.isDeadOrDying();
        long now = System.nanoTime();
        if (player == null) {
            previousAlive = alive;
            deathSeen = !alive;
            lowLatched = nextPlayer.getHealth() <= threshold(hud);
        } else {
            // A replacement LocalPlayer is normal on respawn; keep the prior death flag across it.
            if (previousAlive && !alive) {
                deathSeen = true;
                if (available && observing && hud.islandDeathEventsEnabled()) {
                    var pos = nextPlayer.blockPosition();
                    enqueue(Kind.DEATH, "death", Lang.t("ui.island.event.death"),
                            Lang.t("ui.island.event.death_position", pos.getX(), pos.getY(), pos.getZ()),
                            Icon.CLOSE, "island_focus", 100, 0, false, hud);
                }
            } else if (alive && deathSeen) {
                deathSeen = false;
                lowLatched = false;
                if (available && observing && hud.islandRespawnEventsEnabled())
                    enqueue(Kind.RESPAWN, "respawn", Lang.t("ui.island.event.respawn"),
                            Lang.t("ui.island.event.respawn_detail"), Icon.REFRESH, "island_elastic", 70, 1, true, hud);
            }
            if (dimension != null && dimension != nextDimension && !dimension.equals(nextDimension)) {
                if (available && observing && hud.islandDimensionEventsEnabled())
                    enqueue(Kind.DIMENSION, "dimension", Lang.t("ui.island.event.dimension"),
                            dimensionName(nextDimension), Icon.GLOBE, "island_flip", 60, 1, true, hud);
            }
            if (alive) {
                float health = nextPlayer.getHealth();
                float limit = threshold(hud);
                if (health > limit + 2) lowLatched = false;
                if (health <= limit && !lowLatched) {
                    lowLatched = true;
                    if (available && observing && (lastLowHealth == 0 || now - lastLowHealth >= LOW_HEALTH_COOLDOWN)
                            && hud.islandLowHealthEventsEnabled()) {
                        lastLowHealth = now;
                        enqueue(Kind.LOW_HEALTH, "low_health", Lang.t("ui.island.event.low_health"),
                                Lang.t("ui.island.event.health", number(health), number(nextPlayer.getMaxHealth())),
                                Icon.ACTIVITY, "island_focus", 80,
                                health / Math.max(1, nextPlayer.getMaxHealth()), false, hud);
                    }
                }
            }
        }
        if (level != nextLevel) WATCHED.clear();
        level = nextLevel;
        player = nextPlayer;
        dimension = nextDimension;
        previousAlive = alive;

        // Resuming a hidden HUD starts a clean observation period, with no old event replay.
        if (!available || !observing) WATCHED.clear();
        observing = available;
        if (available) {
            if (active != null && !allowed(active.kind, hud)) { active = null; age = 0; }
            PENDING.removeIf(event -> !allowed(event.kind, hud) || expired(event, now));
            Iterator<Map.Entry<UUID, WatchedTarget>> iterator = WATCHED.entrySet().iterator();
            while (iterator.hasNext()) {
                WatchedTarget watch = iterator.next().getValue();
                LivingEntity target = watch.entity;
                if (target.level() != nextLevel || now - watch.attackedAt > WATCH_NANOS) {
                    iterator.remove();
                } else if (target.isDeadOrDying()) {
                    // An entity removal alone could mean chunk unload or teleport, never a confirmed death.
                    if (hud.islandTargetDownEventsEnabled())
                        enqueue(Kind.TARGET_DOWN, "target:" + target.getUUID(), Lang.t("ui.island.event.target_down"),
                                target.getDisplayName().getString(), Icon.SWORD, "island_liquid", 45, 1, true, hud);
                    iterator.remove();
                } else if (target.isRemoved()) iterator.remove();
            }
        }
    }

    /** Module toggles always use Notifications' original compact animated switch rows. */
    public static void moduleToggled(Module module) {
    }

    public static void attacked(Entity entity) {
        Hud hud = hud();
        if (!available(hud) || !hud.islandTargetDownEventsEnabled() || !(entity instanceof LivingEntity target)
                || target == MC.player || target.level() != MC.level || !target.isAlive()) return;
        WATCHED.put(target.getUUID(), new WatchedTarget(target, System.nanoTime()));
        while (WATCHED.size() > MAX_WATCHED) WATCHED.remove(WATCHED.keySet().iterator().next());
    }

    /** A harmless local sample, using the current style without toggling a module or gameplay state. */
    public static void preview() {
        Hud hud = hud();
        if (!available(hud)) return;
        if (active != null && active.kind == Kind.PREVIEW) { active = null; age = 0; }
        PENDING.removeIf(event -> event.kind == Kind.PREVIEW);
        String[] automatic = {"island_elastic", "island_ripple", "island_flip", "island_liquid", "island_focus", "island_slide"};
        String style = automatic[previewSequence++ % automatic.length];
        enqueue(Kind.PREVIEW, "preview", Lang.t("ui.island.event.preview"),
                Lang.t("ui.island.event.preview_detail"), Icon.SPARKLE, style, 110, 1, true, hud);
    }

    /** Vanilla confirms an actual local totem use with entity event 35. */
    public static void entityEvent(ClientboundEntityEventPacket packet) {
        Hud hud = hud();
        if (!available(hud) || !hud.islandTotemEventsEnabled() || packet.getEventId() != 35
                || packet.getEntity(MC.level) != MC.player) return;
        enqueue(Kind.TOTEM, "totem", Lang.t("ui.island.event.totem"), Lang.t("ui.island.event.totem_detail"),
                Icon.SHIELD, "island_ripple", 90, 1, true, hud);
    }

    /** Kept for compatibility; event animations never replace a module's original notification. */
    public static boolean replaces(Notifications.Entry entry) {
        return false;
    }

    private static void enqueue(Kind kind, String key, String title, String detail, Icon icon, String automaticStyle,
                                int priority, float value, boolean positive, Hud hud) {
        String style = hud.islandEventStyle();
        if (style.equals("island_original")) {
            Notifications.push(icon, title, detail);
            return;
        }
        if (style.equals("island_auto")) style = automaticStyle;
        float timeScale = Math.max(.5f, Math.min(2, hud.islandEventTimeScale()));
        Event event = new Event(kind, key, title, detail, icon, style, automaticStyle, priority, System.nanoTime(),
                timeScale, Math.max(.6f, Math.min(5, hud.islandEventHoldMillis() / 1000f)) / timeScale,
                Math.max(0, Math.min(1.5f, hud.islandEventIntensity())), Anim.clamp01(value), positive);
        PENDING.removeIf(previous -> previous.key.equals(key));
        if (active != null && active.key.equals(key)) {
            // Keep a fast series of key presses current without restarting its entrance forever.
            active = event;
            age = Math.min(age, entrance() + event.hold * .5f);
            return;
        }
        if (active == null || priority > active.priority) {
            active = event;
            age = 0;
            return;
        }
        PENDING.addLast(event);
        while (PENDING.size() > MAX_PENDING) {
            Event lowest = null;
            for (Event candidate : PENDING)
                if (lowest == null || candidate.priority < lowest.priority) lowest = candidate;
            PENDING.remove(lowest);
        }
    }

    /** Called exactly once per rendered island frame; welcome keeps priority without a second shell. */
    public static void update(HudContext context, float dt, boolean welcome) {
        Hud hud = hud();
        if (context.editing || !available(hud)) { clearVisuals(); return; }
        syncStyle(hud);
        // Settings pages keep the full entrance ready. DeathScreen is deliberately not a GuiBase.
        if (welcome || MC.screen instanceof GuiBase || MC.isPaused() || !MC.isWindowActive() || MC.getOverlay() != null) return;
        if (active == null) next();
        if (active == null) return;
        age += Math.max(0, Math.min(.1f, Float.isFinite(dt) ? dt : 0)) / active.timeScale;
        if (age >= entrance() + active.hold + exit()) {
            active = null;
            age = 0;
            next();
        }
    }

    private static void syncStyle(Hud hud) {
        if (hud == null) return;
        String next = hud.islandEventStyle();
        if (configuredStyle == null) { configuredStyle = next; return; }
        if (configuredStyle.equals(next)) return;
        configuredStyle = next;
        if (next.equals("island_original")) { clearVisuals(); return; }
        if (active != null) active = restyle(active, next, hud);
        ArrayDeque<Event> revised = new ArrayDeque<>();
        for (Event event : PENDING) revised.addLast(restyle(event, next, hud));
        PENDING.clear();
        PENDING.addAll(revised);
        age = 0;
    }

    private static Event restyle(Event event, String choice, Hud hud) {
        String style = choice.equals("island_auto") ? event.automaticStyle : choice;
        float timeScale = Math.max(.5f, Math.min(2, hud.islandEventTimeScale()));
        return new Event(event.kind, event.key, event.title, event.detail, event.icon, style, event.automaticStyle,
                event.priority, System.nanoTime(), timeScale,
                Math.max(.6f, Math.min(5, hud.islandEventHoldMillis() / 1000f)) / timeScale,
                Math.max(0, Math.min(1.5f, hud.islandEventIntensity())), event.value, event.positive);
    }

    private static void next() {
        long now = System.nanoTime();
        Event best = null;
        PENDING.removeIf(event -> expired(event, now));
        for (Event event : PENDING) if (best == null || event.priority > best.priority) best = event;
        if (best != null) { PENDING.remove(best); active = best; age = 0; }
    }

    public static boolean active() { return active != null; }
    public static float contentVisibility() {
        if (active == null) return 0;
        if (expandedStyle(active)) return smooth(age, .035f, .18f)
                * (1 - MotionCurve.smootherStep((exitProgress() - .42f) / .58f));
        return smooth(age, .09f, .34f) * (1 - smooth(age, entrance() + active.hold, entrance() + active.hold + .30f));
    }
    public static float normalVisibility() { return 1 - contentVisibility(); }
    private static float shape() {
        return active == null ? 0 : smooth(age, 0, entrance())
                * (1 - smooth(age, entrance() + active.hold, entrance() + active.hold + exit()));
    }
    public static float expandedWidth(Canvas canvas, float normalWidth, float availableWidth) {
        if (active == null) return normalWidth;
        float title = canvas.textWidth(Fonts.Face.BOLD, active.title, 10);
        float detail = canvas.textWidth(Fonts.Face.MEDIUM, active.detail, 8.5f);
        float goal = Math.max(190, Math.min(340, Math.max(title, detail) + 66));
        float width = Anim.lerp(normalWidth, Math.min(availableWidth, goal), shape());
        if (active.style.equals("island_elastic")) width += entrancePulse() * 10 * active.intensity;
        if (active.style.equals("island_liquid")) width += entrancePulse() * 16 * active.intensity;
        float cue = transitionCue() * active.intensity;
        width += switch (active.style) {
            case "island_split" -> cue * 10;
            case "island_shutter" -> cue * 4;
            case "island_orbit" -> cue * 15;
            case "island_wave" -> cue * 7;
            case "island_scan" -> cue * 5;
            case "island_bloom" -> -cue * 10;
            default -> 0;
        };
        return width;
    }
    public static float expandedHeight(float normalHeight, float musicHeight) {
        if (active == null) return normalHeight;
        float goal = (active.kind == Kind.DEATH || active.kind == Kind.LOW_HEALTH ? 47 : 43) + musicHeight;
        float height = Anim.lerp(normalHeight, goal, shape());
        if (active.style.equals("island_liquid")) height -= entrancePulse() * 4 * active.intensity;
        if (active.style.equals("island_elastic")) height += entrancePulse() * 3 * active.intensity;
        float cue = transitionCue() * active.intensity;
        height += switch (active.style) {
            case "island_split" -> -cue * 2;
            case "island_shutter" -> cue * 3;
            case "island_orbit" -> cue * 4;
            case "island_wave" -> (float) Math.sin(entryProgress() * Math.PI * 3) * (1 - entryProgress()) * 4 * active.intensity;
            case "island_bloom" -> cue * 6;
            default -> 0;
        };
        return height;
    }
    public static float radius(float normalRadius) {
        float goal = active == null ? normalRadius : switch (active.style) {
            case "island_bloom" -> 19;
            case "island_scan", "island_shutter" -> 12;
            default -> 15;
        };
        return Anim.lerp(normalRadius, goal, shape());
    }
    public static float damping(float original) {
        if (active == null || active.style.equals("island_original")) return original;
        return switch (active.style) {
            case "island_elastic" -> 19;
            case "island_liquid" -> 21;
            case "island_split" -> 22;
            case "island_shutter", "island_scan" -> 30;
            case "island_orbit" -> 24;
            case "island_wave" -> 23;
            case "island_bloom" -> 20;
            default -> 28;
        };
    }
    public static R2D.GlassStyle glassStyle(float scale) {
        R2D.GlassStyle base = Theme.chipGlass(scale);
        float intensity = active == null ? 0 : active.intensity * shape();
        if (active != null && active.style.equals("island_original")) intensity = 0;
        float sweep = active != null && expandedStyle(active) ? transitionCue() * active.intensity : 0;
        return new R2D.GlassStyle(base.refraction() * (1 + .30f * intensity + .25f * sweep),
                base.bevel() * (1 + .16f * intensity), base.dispersion(), base.feather(), base.haze(), base.clarity());
    }
    public static float lift() { return Theme.lift(.07f + .012f * shape()); }

    /** Contents and accents only. Clips and Canvas/alpha stacks are balanced on every branch. */
    public static void draw(HudContext context, float x, float y, float width, float height, float musicHeight) {
        Event event = active;
        float visibility = contentVisibility();
        if (event == null || visibility < .001f || context.editing) return;
        Canvas canvas = context.c;
        float usableHeight = Math.max(22, height - musicHeight);
        canvas.clip(x + 2, y + 1, Math.max(1, width - 4), Math.max(1, height - 2));
        R2D.pushAlpha(visibility);
        canvas.push();
        try {
            if (expandedStyle(event)) {
                drawExpandedStyle(context, event, x, y, width, usableHeight);
                return;
            }
            float appear = smooth(age, .06f, .46f);
            float disappear = smooth(age, entrance() + event.hold, entrance() + event.hold + exit());
            float intensity = event.intensity;
            switch (event.style) {
                case "island_elastic" -> {
                    canvas.scaleAround(1 - .12f * intensity * (1 - Anim.easeOutBack(appear)) - .10f * disappear * intensity,
                            x + width / 2, y + usableHeight / 2);
                    canvas.translate(0, disappear * 5 * intensity);
                }
                case "island_slide" -> canvas.translate(-(1 - appear) * 28 * intensity + disappear * 16 * intensity, 0);
                case "island_focus" -> {
                    canvas.scaleAround(1 + .16f * intensity * (1 - appear) - .08f * disappear * intensity,
                            x + width / 2, y + usableHeight / 2);
                    float pulse = (float) Math.sin(Math.PI * smooth(age, .06f, .65f))
                            + (float) Math.sin(Math.PI * disappear);
                    canvas.rrect(x + 7, y + 5, Math.max(1, width - 14), Math.max(1, usableHeight - 10), 12,
                            Anim.white(.11f * pulse * intensity));
                }
                case "island_liquid" -> {
                    float fill = smooth(age, .08f, .58f) * (1 - disappear);
                    float front = x + 6 + (width - 12) * fill;
                    float fluid = Math.max(1 - appear, disappear);
                    canvas.gradientH(x + 4, y + 3, Math.max(1, front - x - 4), Math.max(1, usableHeight - 6),
                            Anim.white(.025f * intensity * fluid), Anim.white(.14f * intensity * fluid));
                    canvas.rrect(front - 1.5f, y + 5, 3, Math.max(1, usableHeight - 10), 1.5f,
                            Anim.white(.40f * intensity * fluid));
                    canvas.translate(0, ((1 - appear) * 5 - disappear * 6) * intensity);
                }
                default -> { }
            }
            boolean flip = event.style.equals("island_flip");
            if (flip) {
                // A centre hinge reveals the face; no text is stretched or mirrored during its turn.
                float turn = smooth(age, .09f, .55f);
                float face = (float) Math.sin(turn * (1 - disappear) * Math.PI / 2);
                float revealed = Math.max(1, width * face);
                float edge = x + (width - revealed) / 2;
                canvas.shadow(edge, y + 4, Math.max(1, 2 * (1 - face)), Math.max(1, usableHeight - 8), 1, 4,
                        Anim.black(.28f * (1 - face)));
                canvas.rrect(edge, y + 4, 1, Math.max(1, usableHeight - 8), .5f, Anim.white(.6f * (1 - face)));
                canvas.clip(edge, y, revealed, usableHeight);
            }
            try { drawCard(canvas, event, x, y, width, usableHeight, appear); }
            finally { if (flip) canvas.unclip(); }
            if (event.style.equals("island_ripple")) drawRipple(canvas, event, x + 22, y + usableHeight / 2);
        } finally {
            canvas.pop();
            R2D.popAlpha();
            canvas.unclip();
        }
    }

    private static void drawExpandedStyle(HudContext context, Event event, float x, float y, float width, float height) {
        Canvas canvas = context.c;
        float in = entryProgress(), out = exitProgress();
        switch (event.style) {
            case "island_split" -> drawSplit(canvas, event, x, y, width, height, in, out);
            case "island_shutter" -> drawShutter(canvas, event, x, y, width, height, in, out);
            case "island_orbit" -> drawOrbit(canvas, event, x, y, width, height, in, out);
            case "island_wave" -> drawWave(canvas, event, x, y, width, height, in, out);
            case "island_scan" -> drawScan(context, event, x, y, width, height, in, out);
            case "island_bloom" -> drawBloom(canvas, event, x, y, width, height, in, out);
            default -> drawCard(canvas, event, x, y, width, height, in);
        }
    }

    private static void drawSplit(Canvas canvas, Event event, float x, float y, float width, float height, float in, float out) {
        float motion = (1 - in + out) * event.intensity;
        float middle = x + width / 2;
        float gap = Math.min(32, width * .15f) * motion;
        float accent = Math.min(1, motion) * .18f;
        canvas.gradientH(x + 5, y + 5, Math.max(1, width / 2 - gap - 5), height - 10,
                Anim.white(.01f), Anim.white(accent));
        canvas.gradientH(middle + gap, y + 5, Math.max(1, width / 2 - gap - 5), height - 10,
                Anim.white(accent), Anim.white(.01f));
        canvas.rrect(middle - gap - .6f, y + 7, 1.2f, Math.max(1, height - 14), .6f, Anim.white(accent * 2));
        canvas.rrect(middle + gap - .6f, y + 7, 1.2f, Math.max(1, height - 14), .6f, Anim.white(accent * 2));
        canvas.push();
        try {
            canvas.translate(-22 * motion, 0);
            drawCardIcon(canvas, event, x + 23, y + height / 2);
        } finally { canvas.pop(); }
        canvas.push();
        try {
            canvas.translate(30 * motion, 0);
            drawCardLabels(canvas, event, x, y, width, height);
        } finally { canvas.pop(); }
        drawCardStatus(canvas, event, x, y, width, height, in);
    }

    private static void drawShutter(Canvas canvas, Event event, float x, float y, float width, float height, float in, float out) {
        float icon = shutterPhase(in, out, 0), title = shutterPhase(in, out, 1), detail = shutterPhase(in, out, 2);
        float cy = y + height / 2;
        canvas.clip(x + 8, cy - 13 * icon, 30, Math.max(.01f, 26 * icon));
        try { drawCardIcon(canvas, event, x + 23, cy); }
        finally { canvas.unclip(); }
        float textWidth = Math.max(1, width - 48);
        canvas.clip(x + 43, cy - 12, Math.max(.01f, textWidth * title), 14);
        try { drawCardTitle(canvas, event, x, y, width, height); }
        finally { canvas.unclip(); }
        canvas.clip(x + 43, cy + 1, Math.max(.01f, textWidth * detail), 14);
        try { drawCardDetail(canvas, event, x, y, width, height); }
        finally { canvas.unclip(); }
        for (int band = 0; band < 4; band++) {
            float phase = shutterPhase(in, out, band % 3);
            float stripY = y + 4 + band * Math.max(1, height - 8) / 4;
            canvas.rrect(x + 6, stripY, Math.max(1, (width - 12) * phase), 1.3f, .65f,
                    Anim.white(.23f * (1 - phase) * event.intensity));
        }
        drawCardStatus(canvas, event, x, y, width, height, Math.min(title, detail));
    }

    private static float shutterPhase(float in, float out, int order) {
        float reveal = MotionCurve.smootherStep((in - order * .12f) / (1 - order * .12f));
        float close = MotionCurve.smootherStep((out - (2 - order) * .12f) / (1 - (2 - order) * .12f));
        return reveal * (1 - close);
    }

    private static void drawOrbit(Canvas canvas, Event event, float x, float y, float width, float height, float in, float out) {
        float motion = (1 - in + out) * event.intensity;
        float dockX = x + 23, dockY = y + height / 2;
        double angle = Math.PI * 2 * (in * 1.25f + out * .85f);
        float radiusX = 18 * motion, radiusY = Math.min(11, height * .22f) * motion;
        // The orbit can pass behind the left rim, while its tile never crosses the sharp text.
        canvas.clip(x + 3, y + 1, 39, Math.max(1, height - 2));
        try {
            for (int point = 9; point >= 1 && motion > .002f; point--) {
                double tail = angle - point * .12;
                float px = dockX + (float) Math.cos(tail) * radiusX;
                float py = dockY + (float) Math.sin(tail) * radiusY;
                canvas.circle(px, py, 1.1f + (10 - point) * .07f, Anim.white((10 - point) / 10f * .35f * Math.min(1, motion)));
            }
            float iconX = dockX + (float) Math.cos(angle) * radiusX;
            float iconY = dockY + (float) Math.sin(angle) * radiusY;
            drawCardIcon(canvas, event, iconX, iconY);
        } finally { canvas.unclip(); }
        canvas.push();
        try {
            canvas.translate((1 - in) * 12 * event.intensity - out * 8 * event.intensity, 0);
            drawCardLabels(canvas, event, x, y, width, height);
        } finally { canvas.pop(); }
        drawCardStatus(canvas, event, x, y, width, height, in);
    }

    private static void drawWave(Canvas canvas, Event event, float x, float y, float width, float height, float in, float out) {
        float motion = Math.min(1, 1 - in + out);
        float waveAmplitude = (2 + motion * 4) * event.intensity;
        float phase = in * (float) Math.PI * 2 - out * (float) Math.PI * 1.5f;
        float strength = transitionCue() * .18f * event.intensity;
        for (int row = 0; row < 5; row++) {
            float baseline = y + 5 + (height - 10) * row / 4;
            for (int segment = 0; segment < 12; segment++) {
                float f = segment / 11f;
                float wave = (float) Math.sin(f * Math.PI * 2 + phase + row * .72f) * waveAmplitude;
                canvas.rrect(x + 4 + (width - 8) * f, baseline + wave, Math.max(1.5f, (width - 8) / 13),
                        1.5f, .75f, Anim.white(strength * (.45f + .55f * (1 - Math.abs(f - .5f) * 2))));
            }
        }
        float reveal = in * (1 - out);
        canvas.clip(x, y + height * (1 - reveal), width, Math.max(.01f, height * reveal));
        try { drawCard(canvas, event, x, y, width, height, in); }
        finally { canvas.unclip(); }
    }

    private static void drawScan(HudContext context, Event event, float x, float y, float width, float height, float in, float out) {
        Canvas canvas = context.c;
        float reveal = in * (1 - out);
        float front = x + width * reveal;
        float strength = transitionCue() * event.intensity;
        canvas.gradientH(front - 18, y + 3, 18, Math.max(1, height - 6), 0x00FFFFFF, Anim.white(.18f * strength));
        canvas.gradientH(front, y + 3, 10, Math.max(1, height - 6), Anim.white(.23f * strength), 0x00FFFFFF);
        if (context.glass() && strength > .001f) {
            R2D.GlassStyle base = Theme.chipGlass(canvas.scale);
            canvas.lens(front - 3, y + 3, 6, Math.max(1, height - 6), 2, .018f * strength,
                    new R2D.GlassStyle(base.refraction() * 1.8f * strength, base.bevel(), 0,
                            base.feather(), 0, base.clarity()));
        }
        canvas.rrect(front - .7f, y + 5, 1.4f, Math.max(1, height - 10), .7f, Anim.white(.72f * strength));
        canvas.clip(x, y, Math.max(.01f, width * reveal), height);
        try { drawCard(canvas, event, x, y, width, height, in); }
        finally { canvas.unclip(); }
    }

    private static void drawBloom(Canvas canvas, Event event, float x, float y, float width, float height, float in, float out) {
        float dockX = x + 23, dockY = y + height / 2;
        float open = (float) Math.sin(in * Math.PI) + (float) Math.sin(out * Math.PI);
        float distance = 9 + 10 * open * event.intensity;
        float petalAlpha = Math.max(0, open) * .48f * event.intensity;
        for (int petal = 0; petal < 8; petal++) {
            double angle = Math.PI * 2 * petal / 8 + (in - out) * .35;
            float px = dockX + (float) Math.cos(angle) * distance;
            float py = dockY + (float) Math.sin(angle) * distance;
            float small = 2.6f + 1.1f * open, longSide = 4.6f + 1.8f * open;
            float petalWidth = petal % 2 == 1 ? small * 1.25f : petal % 4 == 0 ? longSide : small;
            float petalHeight = petal % 2 == 1 ? small * 1.25f : petal % 4 == 0 ? small : longSide;
            canvas.rrect(px - petalWidth / 2, py - petalHeight / 2, petalWidth, petalHeight,
                    Math.min(petalWidth, petalHeight) / 2, Anim.white(petalAlpha));
        }
        canvas.push();
        try {
            float scale = 1 - (1 - in) * .32f * event.intensity + out * .18f * event.intensity;
            canvas.scaleAround(Math.max(.5f, scale), dockX, dockY);
            drawCardIcon(canvas, event, dockX, dockY);
        } finally { canvas.pop(); }
        float reveal = MotionCurve.smootherStep(in) * (1 - MotionCurve.smootherStep(out));
        float textWidth = Math.max(1, width - 44);
        float textMid = x + 44 + textWidth / 2;
        canvas.clip(textMid - textWidth * reveal / 2, y, Math.max(.01f, textWidth * reveal), height);
        try { drawCardLabels(canvas, event, x, y, width, height); }
        finally { canvas.unclip(); }
        drawCardStatus(canvas, event, x, y, width, height, in * (1 - out));
    }

    private static void drawCard(Canvas canvas, Event event, float x, float y, float width, float height, float appear) {
        drawCardIcon(canvas, event, x + 23, y + height / 2);
        drawCardLabels(canvas, event, x, y, width, height);
        drawCardStatus(canvas, event, x, y, width, height, appear);
    }

    private static void drawCardIcon(Canvas canvas, Event event, float iconX, float iconY) {
        canvas.rrect(iconX - 12, iconY - 12, 24, 24, 8, Anim.white(.12f));
        if (event.kind == Kind.TOTEM) {
            // A white totem silhouette fits the existing monochrome icon language.
            canvas.rrect(iconX - 3, iconY - 7, 6, 6, 2, Anim.white(.96f));
            canvas.rrect(iconX - 6, iconY - 1, 12, 3, 1, Anim.white(.96f));
            canvas.rrect(iconX - 2, iconY + 1, 4, 6, 1, Anim.white(.96f));
        } else canvas.icon(event.icon, iconX, iconY, 14, Theme.TEXT);
    }

    private static void drawCardLabels(Canvas canvas, Event event, float x, float y, float width, float height) {
        drawCardTitle(canvas, event, x, y, width, height);
        drawCardDetail(canvas, event, x, y, width, height);
    }

    private static void drawCardTitle(Canvas canvas, Event event, float x, float y, float width, float height) {
        float tx = x + 44, maxText = Math.max(12, width - 56);
        String title = canvas.ellipsize(Fonts.Face.BOLD, event.title, 10, maxText);
        canvas.textC(Fonts.Face.BOLD, title, tx, y + height / 2 - 5, 10, Theme.TEXT);
    }

    private static void drawCardDetail(Canvas canvas, Event event, float x, float y, float width, float height) {
        float tx = x + 44, maxText = Math.max(12, width - 56);
        String detail = canvas.ellipsize(Fonts.Face.MEDIUM, event.detail, 8.5f, maxText);
        canvas.textC(Fonts.Face.MEDIUM, detail, tx, y + height / 2 + 7, 8.5f, Theme.TEXT_2);
    }

    private static void drawCardStatus(Canvas canvas, Event event, float x, float y, float width, float height, float appear) {
        if (event.kind == Kind.LOW_HEALTH) {
            float bw = Math.max(1, width - 22);
            canvas.rrect(x + 11, y + height - 4, bw, 1.5f, .75f, Anim.white(.14f));
            canvas.rrect(x + 11, y + height - 4, Math.max(1, bw * event.value * appear), 1.5f, .75f, Anim.white(.92f));
        } else if (event.kind == Kind.TARGET_DOWN || event.kind == Kind.RESPAWN) {
            float sweep = smooth(age, .16f, .85f);
            float fade = 1 - smooth(age, .80f, 1.12f);
            if (fade > .001f) canvas.rrect(x + 11, y + height - 3, Math.max(1, (width - 22) * sweep), 1, .5f, Anim.white(.40f * fade));
        }
    }

    private static void drawRipple(Canvas canvas, Event event, float cx, float cy) {
        for (int index = 0; index < 3; index++) {
            float progress = Anim.clamp01((age - .10f - index * .16f) / .85f);
            if (progress <= 0 || progress >= 1) continue;
            float radius = 10 + 30 * MotionCurve.smootherStep(progress) * event.intensity;
            canvas.rrect4(cx - radius, cy - radius, radius * 2, radius * 2, radius, radius, radius, radius,
                    0, 0, canvas.scale, Anim.white((1 - progress) * .30f * event.intensity),
                    Anim.white((1 - progress) * .07f * event.intensity));
        }
        float out = exitProgress();
        if (out > .001f && out < .999f) {
            float radius = 10 + 24 * (1 - out) * event.intensity;
            canvas.rrect4(cx - radius, cy - radius, radius * 2, radius * 2, radius, radius, radius, radius,
                    0, 0, canvas.scale, Anim.white((float) Math.sin(out * Math.PI) * .36f * event.intensity),
                    Anim.white((float) Math.sin(out * Math.PI) * .09f * event.intensity));
        }
    }

    private static float entrancePulse() {
        if (active == null) return 0;
        float t = Anim.clamp01(age / .68f);
        return (float) Math.sin(t * Math.PI * 2) * (1 - t) * (1 - t);
    }
    private static boolean expandedStyle(Event event) {
        return switch (event.style) {
            case "island_split", "island_shutter", "island_orbit", "island_wave", "island_scan", "island_bloom" -> true;
            default -> false;
        };
    }
    private static float entryProgress() { return smooth(age, 0, entrance()); }
    private static float exitProgress() {
        return active == null ? 0 : smooth(age, entrance() + active.hold, entrance() + active.hold + exit());
    }
    private static float transitionCue() {
        if (active == null) return 0;
        return Math.max(0, (float) Math.sin(entryProgress() * Math.PI)
                + (float) Math.sin(exitProgress() * Math.PI));
    }
    private static float entrance() {
        if (active == null) return .48f;
        return switch (active.style) {
            case "island_split" -> .64f;
            case "island_shutter" -> .72f;
            case "island_orbit" -> .82f;
            case "island_wave", "island_scan" -> .70f;
            case "island_bloom" -> .72f;
            default -> .48f;
        };
    }
    private static float exit() {
        if (active == null) return .42f;
        return switch (active.style) {
            case "island_split", "island_scan" -> .52f;
            case "island_shutter", "island_bloom" -> .60f;
            case "island_orbit" -> .64f;
            case "island_wave" -> .56f;
            default -> .42f;
        };
    }
    private static float smooth(float value, float start, float end) {
        return MotionCurve.smootherStep((value - start) / Math.max(.001f, end - start));
    }
    private static float threshold(Hud hud) { return hud == null ? 6 : Math.max(1, hud.islandLowHealthThreshold()); }
    private static Hud hud() { return ModuleManager.get(Hud.class); }
    private static boolean displayAvailable(Hud hud) {
        return hud != null && hud.islandEventsEnabled() && !MC.options.hideGui && !(MC.screen instanceof HudEditorScreen);
    }
    private static boolean available(Hud hud) {
        return displayAvailable(hud) && MC.player != null && MC.level != null && MC.getConnection() != null;
    }
    private static boolean allowed(Kind kind, Hud hud) {
        return switch (kind) {
            case DEATH -> hud.islandDeathEventsEnabled();
            case RESPAWN -> hud.islandRespawnEventsEnabled();
            case DIMENSION -> hud.islandDimensionEventsEnabled();
            case TOTEM -> hud.islandTotemEventsEnabled();
            case LOW_HEALTH -> hud.islandLowHealthEventsEnabled();
            case TARGET_DOWN -> hud.islandTargetDownEventsEnabled();
            case PREVIEW -> hud.islandEventsEnabled();
        };
    }
    private static boolean expired(Event event, long now) {
        return now - event.created > 10_000_000_000L;
    }
    private static String dimensionName(ResourceKey<Level> key) {
        if (key.equals(Level.OVERWORLD)) return Lang.t("ui.island.event.overworld");
        if (key.equals(Level.NETHER)) return Lang.t("ui.island.event.nether");
        if (key.equals(Level.END)) return Lang.t("ui.island.event.end");
        return key.location().toString();
    }
    private static String number(float value) {
        return Math.abs(value - Math.round(value)) < .01f ? Integer.toString(Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
    }
    private static void clearVisuals() {
        active = null;
        age = 0;
        PENDING.clear();
    }
    private static void reset() {
        clearVisuals();
        WATCHED.clear();
        connection = null;
        level = null;
        player = null;
        dimension = null;
        previousAlive = false;
        deathSeen = false;
        lowLatched = false;
        observing = false;
        lastLowHealth = 0;
        configuredStyle = null;
    }
}
