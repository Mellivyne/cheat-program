package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.render.TrajectoryRenderer;
import com.cheatprogram.util.ProjectilePrediction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;

/** A release-now preview; it never alters the player's aim, inventory, or shooting state. */
public final class ProjectileTrajectory extends Module {
	private final BoolSetting crossbows = add(new BoolSetting("crossbows", true));
	private final BoolSetting tridents = add(new BoolSetting("tridents", true));
	private final BoolSetting pearls = add(new BoolSetting("pearls", true));
	private final BoolSetting heldPreview = add(new BoolSetting("heldPreview", false));
	private final BoolSetting hitBox = add(new BoolSetting("hitBox", true));
	private final BoolSetting impactMarker = add(new BoolSetting("impactMarker", true));
	private final BoolSetting label = add(new BoolSetting("label", true));
	private final BoolSetting movementPrediction = add(new BoolSetting("movementPrediction", false));
	private final NumberSetting leadTicks = add(new NumberSetting("leadTicks", 3, 1, 5, 1)).visibleWhen(movementPrediction::on);
	private final NumberSetting maxTicks = add(new NumberSetting("maxTicks", 160, 20, 400, 10));
	private final NumberSetting maxDistance = add(new NumberSetting("maxDistance", 256, 16, 512, 8));
	private final NumberSetting lineWidth = add(new NumberSetting("lineWidth", 1.8, 1, 4, .1));
	private final NumberSetting opacity = add(new NumberSetting("opacity", 90, 20, 100, 5, "%"));
	private final ModeSetting lineColor = add(new ModeSetting("lineColor", "trajectory_white",
			"trajectory_white", "trajectory_cyan", "trajectory_blue", "trajectory_green", "trajectory_gold", "trajectory_pink"));
	private final ModeSetting hitColor = add(new ModeSetting("hitColor", "trajectory_green",
			"trajectory_white", "trajectory_cyan", "trajectory_blue", "trajectory_green", "trajectory_gold", "trajectory_pink"));
	private final BoolSetting throughWalls = add(new BoolSetting("throughWalls", false));
	private ClientLevel world;
	private ProjectilePrediction.Shot previousShot;
	private ProjectilePrediction.Result prediction;
	private long lastPrediction;
	private int cachedTick = -1;
	private int optionsHash;

	public ProjectileTrajectory() { super("projectiletrajectory", Category.VISUAL); }
	@Override public String suffix() { return Lang.t("trajectory.prediction"); }
	@Override public void onEnable() { clear(); }
	@Override public void onDisable() { clear(); }
	private void clear() { world = null; previousShot = null; prediction = null; lastPrediction = 0; cachedTick = -1; }
	@Override public void onTick() {
		if (!inGame() || !mc.player.isAlive() || world != mc.level) clear();
	}
	@Override public void onRender3D(Render3D renderer) {
		if (!inGame() || !mc.player.isAlive() || mc.player.isSpectator()) { clear(); return; }
		if (world != mc.level) { clear(); world = mc.level; }
		ProjectilePrediction.Shot shot = ProjectilePrediction.currentShot(mc.player, heldPreview.on(), crossbows.on(), tridents.on(), pearls.on());
		if (shot == null) { prediction = null; previousShot = null; return; }
		int nextOptions = java.util.Objects.hash(maxTicks.intVal(), maxDistance.intVal(), movementPrediction.on(), leadTicks.intVal());
		boolean poseChanged = previousShot == null || previousShot.kind() != shot.kind() || previousShot.weapon() != shot.weapon()
				|| previousShot.useTicks() != shot.useTicks() || previousShot.origin().distanceToSqr(shot.origin()) > 1e-8
				|| previousShot.velocity().distanceToSqr(shot.velocity()) > 1e-8;
		long now = System.nanoTime();
		if (prediction == null || optionsHash != nextOptions || previousShot == null || previousShot.kind() != shot.kind()
				|| (cachedTick != mc.player.tickCount || poseChanged) && now - lastPrediction >= 8_000_000L) {
			prediction = ProjectilePrediction.simulate(mc.level, mc.player, shot, maxTicks.intVal(), maxDistance.val(),
					movementPrediction.on() ? leadTicks.val() : 0, System.nanoTime() + 4_000_000L);
			previousShot = shot; lastPrediction = now; cachedTick = mc.player.tickCount; optionsHash = nextOptions;
		}
		if (prediction != null && (prediction.hitEntity() == null || prediction.hitEntity().level() == mc.level))
			TrajectoryRenderer.draw(renderer, prediction, color(lineColor), color(hitColor), lineWidth.floatVal(), throughWalls.on(), hitBox.on(), impactMarker.on());
	}
	@Override public void onRender2D(HudContext hud) {
		if (!label.on() || !inGame() || world != mc.level || prediction == null || mc.screen != null || hud.editing) return;
		String text = Lang.t("trajectory.prediction");
		if (prediction.hitEntity() instanceof LivingEntity living && living.isAlive())
			text += " · " + living.getName().getString();
		if (prediction.limited()) text += " · " + Lang.t("trajectory.limited");
		hud.c.textMid(Fonts.Face.REGULAR, text, hud.width / 2, hud.height / 2 + 26, 8.5f, Theme.TEXT_2);
	}
	private int color(ModeSetting mode) {
		int rgb = switch (mode.get()) {
			case "trajectory_cyan" -> 0x70E5FF;
			case "trajectory_blue" -> 0x7AA7FF;
			case "trajectory_green" -> 0x75FFA0;
			case "trajectory_gold" -> 0xFFD478;
			case "trajectory_pink" -> 0xFF9FD4;
			default -> 0xFFFFFF;
		};
		return ((int) Math.round(255 * opacity.fraction()) << 24) | rgb;
	}
}
