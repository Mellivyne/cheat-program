package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.HudWorldPanel;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.MotionCurve;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Observation-only damage feedback. Clicking never creates a damage number. */
public final class DamageEffects extends Module {
	private final BoolSetting numbers = add(new BoolSetting("numbers", true));
	private final BoolSetting particles = add(new BoolSetting("particles", true));
	private final BoolSetting players = add(new BoolSetting("players", true));
	private final BoolSetting hostiles = add(new BoolSetting("hostiles", true));
	private final BoolSetting passives = add(new BoolSetting("passives", true));
	private final NumberSetting range = add(new NumberSetting("range", 32, 4, 96, 1));
	private final NumberSetting lifetime = add(new NumberSetting("lifetime", 1000, 300, 2500, 50, "ms"));
	private final NumberSetting rise = add(new NumberSetting("rise", 90, 10, 200, 5, "%"));
	private final NumberSetting size = add(new NumberSetting("size", 100, 50, 200, 5, "%"));
	private final NumberSetting particleCount = add(new NumberSetting("particleCount", 6, 1, 16, 1)).visibleWhen(particles::on);
	private final ModeSetting color = add(new ModeSetting("color", "damage_white", "damage_white", "damage_gold", "damage_red", "damage_aqua"));
	private final BoolSetting merge = add(new BoolSetting("merge", true));
	private final BoolSetting throughWalls = add(new BoolSetting("throughWalls", false));
	private final Map<UUID, Observed> observed = new HashMap<>();
	private final List<Burst> bursts = new ArrayList<>();
	private final List<Glyph> glyphs = new ArrayList<>();
	private int worldIdentity, tick;
	private record Observed(float health, Entity entity) { }
	private record Glyph(HudWorldPanel.Projection projection, String text, float alpha, float size, boolean particle) { }
	private static final class Burst {
		final UUID owner; final Vec3 origin; final int born; final double seed;
		float amount;
		Burst(UUID owner, Vec3 origin, int born, float amount) {
			this.owner = owner; this.origin = origin; this.born = born; this.amount = amount;
			seed = (owner.hashCode() & 0xffff) * .017 + born * .71;
		}
	}
	public DamageEffects() { super("damageeffects", Category.VISUAL); }
	@Override public void onEnable() { clear(); }
	@Override public void onDisable() { clear(); }
	private void clear() { observed.clear(); bursts.clear(); glyphs.clear(); worldIdentity = 0; tick = 0; }
	public void checkLifecycle() {
		if (!inGame() || !isEnabled() || worldIdentity != 0 && worldIdentity != System.identityHashCode(mc.level)) clear();
	}
	private boolean eligible(Entity entity) {
		if (!(entity instanceof LivingEntity) || entity == mc.player || entity instanceof ArmorStand
				|| entity.isRemoved() || entity.level() != mc.level
				|| !mc.level.hasChunkAt(entity.blockPosition()) || entity.distanceToSqr(mc.player) > range.val() * range.val()) return false;
		if (entity instanceof Player player) return players.on() && !player.isSpectator();
		return entity instanceof Enemy ? hostiles.on() : passives.on();
	}
	@Override public void onTick() {
		checkLifecycle(); if (!inGame() || mc.isPaused()) return;
		worldIdentity = System.identityHashCode(mc.level); tick++;
		bursts.removeIf(b -> (tick - b.born) * 50 >= lifetime.val());
		Set<UUID> seen = new HashSet<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!eligible(entity)) continue;
			LivingEntity living = (LivingEntity) entity;
			float health = living.getHealth() + living.getAbsorptionAmount();
			if (!Float.isFinite(health) || health < 0) continue;
			UUID uuid = entity.getUUID(); seen.add(uuid);
			Observed previous = observed.put(uuid, new Observed(health, entity));
			// A replaced entity or newly loaded one has no trusted baseline. Never infer a hit from it.
			if (previous == null || previous.entity != entity || previous.health - health < .05f) continue;
			float damage = previous.health - health;
			Burst existing = null;
			if (merge.on()) for (int i = bursts.size() - 1; i >= 0; i--) {
				Burst candidate = bursts.get(i);
				if (candidate.owner.equals(uuid) && tick - candidate.born <= 5) { existing = candidate; break; }
			}
			if (existing != null) existing.amount += damage;
			else {
				if (bursts.size() >= 48) bursts.removeFirst();
				bursts.add(new Burst(uuid, entity.position().add(0, entity.getBbHeight() * .75, 0), tick, damage));
			}
		}
		observed.keySet().retainAll(seen);
	}
	@Override public void onRender3D(Render3D renderer) {
		glyphs.clear();
		if (!inGame() || worldIdentity != System.identityHashCode(mc.level)) return;
		for (Burst burst : bursts) {
			double age = Math.max(0, (tick - burst.born + (mc.isPaused() ? 0 : renderer.partialTick)) * 50 / lifetime.val());
			if (age >= 1) continue;
			float alpha = 1 - MotionCurve.smootherStep((float) ((age - .7) / .3));
			Vec3 anchor = burst.origin.add(Math.sin(burst.seed) * .12, Anim.easeOutCubic((float) age) * rise.fraction(), 0);
			if (!throughWalls.on()) {
				var hit = mc.level.clip(new ClipContext(renderer.camera, anchor, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
				if (hit.getType() != HitResult.Type.MISS && renderer.camera.distanceToSqr(hit.getLocation()) + .04 < renderer.camera.distanceToSqr(anchor)) continue;
			}
			if (numbers.on()) {
				var projection = HudWorldPanel.project(renderer, anchor, range.val() + 4);
				String text = Math.abs(burst.amount - Math.round(burst.amount)) < .05 ? Integer.toString(Math.round(burst.amount)) : String.format(Locale.ROOT, "%.1f", burst.amount);
				if (projection != null) glyphs.add(new Glyph(projection, text, alpha, (float) size.fraction(), false));
			}
			if (particles.on()) for (int i = 0; i < particleCount.intVal(); i++) {
				double angle = burst.seed + i * Math.PI * 2 / particleCount.intVal();
				// These are display-only particles: ease along the same arc without changing the real damage clock.
				double flight = Anim.easeOutCubic((float) age);
				double spread = flight * (.3 + (i % 3) * .12);
				Vec3 particle = burst.origin.add(Math.cos(angle) * spread, flight * (.55 + i % 4 * .12) - flight * flight * .3, Math.sin(angle) * spread);
				var projection = HudWorldPanel.project(renderer, particle, range.val() + 4);
				if (projection != null) glyphs.add(new Glyph(projection, "", alpha, (float) size.fraction(), true));
			}
		}
	}
	@Override public void onRender2D(HudContext hud) {
		if (!inGame() || worldIdentity != System.identityHashCode(mc.level)) return;
		Canvas canvas = new Canvas(); canvas.set(1, 0, 0);
		int rgb = color.is("damage_gold") ? 0xffdf8b : color.is("damage_red") ? 0xffa7a7 : color.is("damage_aqua") ? 0xb8f1ff : 0xffffff;
		for (Glyph glyph : glyphs) {
			if (System.nanoTime() - glyph.projection.time() > 250_000_000L) continue;
			float scale = Math.max(.65f, glyph.projection.scale()) * hud.c.scale * glyph.size;
			int fill = Math.round(glyph.alpha * 255) << 24 | rgb;
			float x = glyph.projection.x(), y = glyph.projection.y();
			if (glyph.particle) {
				canvas.circle(x, y, 3 * scale, Math.round(glyph.alpha * 28) << 24 | rgb);
				canvas.circle(x, y, scale, fill);
			} else {
				canvas.textMid(Fonts.Face.BOLD, glyph.text, x + scale, y + scale, 12 * scale, Anim.black(glyph.alpha * .6f));
				canvas.textMid(Fonts.Face.BOLD, glyph.text, x, y, 12 * scale, fill);
			}
		}
	}
}
