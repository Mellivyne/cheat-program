package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudLayout;
import com.cheatprogram.hud.WorldTargetPanel;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.HudWorldPanel;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Nearby observable targets; no attack or combat-module state is needed to show them. */
public final class TargetHud extends Module {
	private final ModeSetting displayMode = add(new ModeSetting("displayMode", "target_hud", "target_hud", "target_world"));
	private final ModeSetting targets = add(new ModeSetting("targets", "line_living", "line_passives", "line_hostiles", "line_living", "line_players"));
	private final NumberSetting range = add(new NumberSetting("range", 16, 1, 64, 1));
	private final NumberSetting maxTargets = add(new NumberSetting("maxTargets", 4, 1, 8, 1)).visibleWhen(this::worldMode);
	private final NumberSetting panelWidth = add(new NumberSetting("panelWidth", 170, 120, 320, 1));
	private final NumberSetting panelHeight = add(new NumberSetting("panelHeight", 64, 44, 140, 1));
	private final NumberSetting worldOffsetX = add(new NumberSetting("worldOffsetX", 0, -160, 160, 1)).visibleWhen(this::worldMode);
	private final NumberSetting worldOffsetY = add(new NumberSetting("worldOffsetY", 0, -100, 100, 1)).visibleWhen(this::worldMode);
	private final Anim visible = new Anim(0);
	private List<LivingEntity> selected = List.of();
	private List<Projected> projected = List.of();
	private final Map<UUID, Food> food = new HashMap<>();
	private ClientLevel world;
	private long epoch, nextFoodSample, lastFrame = System.nanoTime();
	private boolean foodRequest;
	private record Food(int entityId, int level, long sampledAt) { }
	private record PlayerKey(UUID uuid, int entityId) { }
	private record Projected(LivingEntity entity, HudWorldPanel.Projection projection) { }

	public TargetHud() { super("targethud", Category.VISUAL); }
	public boolean worldMode() { return displayMode.is("target_world"); }
	public float panelWidth() { return panelWidth.floatVal(); }
	public float panelHeight() { return panelHeight.floatVal(); }
	public float worldOffsetX() { return worldOffsetX.floatVal(); }
	public float worldOffsetY() { return worldOffsetY.floatVal(); }
	public void resizePanel(float width, float height) {
		panelWidth.set((double) Math.max(120, Math.min(320, width)));
		panelHeight.set((double) Math.max(44, Math.min(140, height)));
	}
	public void moveWorldPanel(float dx, float dy) { setWorldPanelOffset(worldOffsetX() + dx, worldOffsetY() + dy); }
	public void setWorldPanelOffset(float x, float y) {
		worldOffsetX.set((double) Math.max(-160, Math.min(160, x)));
		worldOffsetY.set((double) Math.max(-100, Math.min(100, y)));
	}
	private void clear() {
		epoch++; world = null; selected = List.of(); projected = List.of(); food.clear(); foodRequest = false;
		nextFoodSample = 0; visible.snap(0);
	}
	@Override public void onEnable() { clear(); }
	@Override public void onDisable() { clear(); }

	private boolean matches(Entity entity) {
		if (!(entity instanceof LivingEntity living) || entity == mc.player || entity instanceof ArmorStand
				|| entity.isRemoved() || entity.level() != mc.level || !living.isAlive() || living.isDeadOrDying()
				|| entity.isInvisible() || !mc.level.hasChunkAt(entity.blockPosition())) return false;
		if (entity instanceof Player player) return targets.is("line_players") && !player.isSpectator();
		return targets.is("line_living") || targets.is("line_hostiles") && entity instanceof Enemy
				|| targets.is("line_passives") && !(entity instanceof Enemy);
	}
	private Vec3 camera() { return mc.gameRenderer.getMainCamera().isInitialized() ? mc.gameRenderer.getMainCamera().getPosition() : mc.player.getEyePosition(); }
	private boolean observable(LivingEntity entity, Vec3 eye) {
		Vec3 body = entity.position().add(0, entity.getBbHeight() * 0.65, 0);
		Vec3 anchor = entity.position().add(0, entity.getBbHeight() + 0.2, 0);
		return mc.level.clip(new ClipContext(eye, body, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS
				&& mc.level.clip(new ClipContext(eye, anchor, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
	}
	@Override public void onTick() {
		if (!inGame() || !mc.player.isAlive()) { clear(); return; }
		if (world != mc.level) { clear(); world = mc.level; }
		Vec3 eye = camera();
		List<LivingEntity> candidates = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) if (matches(entity)
				&& eye.distanceToSqr(entity.position()) <= range.val() * range.val()) candidates.add((LivingEntity) entity);
		candidates.sort(Comparator.comparingDouble(entity -> eye.distanceToSqr(entity.position())));
		List<LivingEntity> next = new ArrayList<>();
		int checked = 0, count = worldMode() ? maxTargets.intVal() : 1;
		for (LivingEntity candidate : candidates) {
			if (checked++ >= 32 || next.size() >= count) break;
			if (observable(candidate, eye)) next.add(candidate);
		}
		selected = List.copyOf(next);
		food.keySet().removeIf(uuid -> selected.stream().noneMatch(entity -> entity.getUUID().equals(uuid)));
		sampleFood();
	}

	/** Read only real ServerPlayers on their server thread, then publish bounded immutable samples. */
	private void sampleFood() {
		var server = mc.getSingleplayerServer();
		long now = System.currentTimeMillis();
		if (server == null || foodRequest || now < nextFoodSample) return;
		List<PlayerKey> players = selected.stream().filter(entity -> entity instanceof Player)
				.map(entity -> new PlayerKey(entity.getUUID(), entity.getId())).toList();
		if (players.isEmpty()) return;
		long expectedEpoch = epoch; ClientLevel expectedWorld = world; var dimension = world.dimension();
		foodRequest = true; nextFoodSample = now + 200;
		server.execute(() -> {
			Map<UUID, Food> samples = new HashMap<>();
			for (PlayerKey key : players) {
				var player = server.getPlayerList().getPlayer(key.uuid);
				if (player != null && player.getId() == key.entityId && player.serverLevel().dimension().equals(dimension))
					samples.put(key.uuid, new Food(key.entityId, player.getFoodData().getFoodLevel(), System.currentTimeMillis()));
			}
			Map<UUID, Food> result = Map.copyOf(samples);
			mc.execute(() -> {
				if (epoch != expectedEpoch || world != expectedWorld || mc.level != expectedWorld || !isEnabled()) return;
				foodRequest = false;
				for (LivingEntity entity : selected) {
					Food value = result.get(entity.getUUID());
					if (value != null && value.entityId == entity.getId() && mc.level.getEntity(value.entityId) == entity) food.put(entity.getUUID(), value);
				}
			});
		});
	}
	private WorldTargetPanel.Info info(LivingEntity entity) {
		Integer hunger = null;
		if (entity == mc.player) hunger = mc.player.getFoodData().getFoodLevel();
		else if (entity instanceof Player && mc.getSingleplayerServer() != null) {
			Food value = food.get(entity.getUUID());
			if (value != null && value.entityId == entity.getId() && System.currentTimeMillis() - value.sampledAt <= 1000) hunger = value.level;
		}
		return new WorldTargetPanel.Info(entity.getName().getString(), String.format(Locale.ROOT, "%.1fm", camera().distanceTo(entity.position())),
				Math.max(0, entity.getHealth()), entity.getMaxHealth(), entity instanceof Player,
				entity instanceof AbstractClientPlayer player ? player.getSkin().texture() : null, hunger);
	}
	@Override public void onRender3D(Render3D renderer) {
		projected = List.of();
		if (!worldMode() || !inGame() || !mc.player.isAlive() || world != mc.level) return;
		List<Projected> next = new ArrayList<>();
		for (LivingEntity entity : selected) {
			if (!matches(entity)) continue;
			Vec3 anchor = entity.getPosition(renderer.partialTick).add(0, entity.getBbHeight() + 0.2, 0);
			var projection = HudWorldPanel.project(renderer, anchor, range.val());
			if (projection != null) next.add(new Projected(entity, projection));
		}
		projected = List.copyOf(next);
	}
	@Override public void onRender2D(HudContext ctx) {
		if (!inGame() || !mc.player.isAlive()) return;
		if (ctx.editing) { renderFlat(ctx, mc.player); return; }
		if (world != mc.level) return;
		if (worldMode()) {
			List<WorldTargetPanel.Plate> plates = new ArrayList<>();
			for (Projected value : projected) if (matches(value.entity)) plates.add(new WorldTargetPanel.Plate(value.projection, info(value.entity)));
			WorldTargetPanel.draw(plates, panelWidth(), panelHeight(), worldOffsetX(), worldOffsetY());
		} else {
			LivingEntity target = selected.isEmpty() ? null : selected.getFirst();
			if (target == null || !matches(target)) { visible.snap(0); return; }
			renderFlat(ctx, target);
		}
	}
	private void renderFlat(HudContext ctx, LivingEntity target) {
		long now = System.nanoTime(); float dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f); lastFrame = now;
		float alpha = ctx.editing ? 1 : visible.to(1, dt, 12);
		Canvas canvas = ctx.c;
		float width = panelWidth(), height = panelHeight(), x = HudLayout.x("targethud", width, ctx.width), y = HudLayout.y("targethud", height, ctx.height);
		R2D.pushAlpha(alpha); canvas.push();
		try {
			canvas.scaleAround(0.92f + 0.08f * Anim.easeOutCubic(alpha), x + width / 2, y + height / 2);
			ctx.panel(x, y, width, height, 14);
			WorldTargetPanel.contents(canvas, x, y, width, height, info(target));
		} finally { canvas.pop(); R2D.popAlpha(); }
		HudLayout.report("targethud", x, y, width, height);
	}
}
