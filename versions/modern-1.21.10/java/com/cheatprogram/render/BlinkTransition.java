package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.util.MotionCurve;
import com.cheatprogram.compat.gl.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A short-lived display effect survives disabling Blink without moving the camera or a real player. */
public final class BlinkTransition {
	private static final Minecraft MC = Minecraft.getInstance();
	private static Transition current;
	private record Transition(ClientLevel world, LocalPlayer owner, Vec3 from, Vec3 to, float width, float height,
			long began, long duration, BlinkPlayerAfterimage ghost, float opacity, boolean throughWalls) { }
	private BlinkTransition() { }
	public static void clear() { current = null; }
	public static void start(Vec3 from, Vec3 to, int durationMillis, boolean showGhost, float opacity, boolean throughWalls) {
		clear();
		if (MC.level == null || MC.player == null || !MC.player.isAlive() || from == null || to == null || from.distanceToSqr(to) < .0001) return;
		if (!finite(from) || !finite(to)) return;
		BlinkPlayerAfterimage ghost = null;
		if (showGhost) {
			try { ghost = BlinkPlayerAfterimage.capture(MC.player, from, to); }
			catch (RuntimeException failure) { CheatProgram.LOGGER.warn("Unable to snapshot Blink player afterimage", failure); }
		}
		current = new Transition(MC.level, MC.player, from, to, MC.player.getBbWidth(), MC.player.getBbHeight(), System.nanoTime(),
				Math.max(1, durationMillis) * 1_000_000L, ghost, Math.max(0, Math.min(1, opacity)), throughWalls);
	}
	private static boolean finite(Vec3 point) { return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
	/** Root calls this on every client tick, including the title screen. */
	public static void tickLifecycle() {
		Transition transition = current;
		if (transition != null && (transition.world != MC.level || transition.owner != MC.player || MC.player == null
				|| !MC.player.isAlive() || System.nanoTime() - transition.began >= transition.duration)) clear();
	}
	/** Root calls this after normal modules, even when Blink itself is disabled. */
	public static void render(Render3D renderer) {
		tickLifecycle(); Transition transition = current; if (transition == null) return;
		float progress = Math.max(0, Math.min(1, (float)((double)(System.nanoTime() - transition.began) / transition.duration)));
		float smooth = progress * progress * (3 - 2 * progress);
		Vec3 position = transition.from.add(transition.to.subtract(transition.from).scale(smooth));
		float fade = 1 - MotionCurve.smootherStep((progress - .82f) / .18f);
		float halfWidth = transition.width / 2;
		renderer.begin();
		try {
			if (transition.throughWalls) RenderSystem.disableDepthTest(); else RenderSystem.enableDepthTest();
			RenderSystem.depthMask(false);
			AABB box = new AABB(position.x - halfWidth, position.y, position.z - halfWidth, position.x + halfWidth,
					position.y + transition.height, position.z + halfWidth);
			renderer.box(box, color(.055f * fade), color(.65f * fade), 1.5f);
			// Avoid filling first-person eyes when the effect arrives at the real player's body.
			if (transition.ghost != null && position.add(0, transition.height * .65, 0).distanceToSqr(renderer.camera) > .64) {
				try { transition.ghost.draw(renderer, position, transition.opacity * fade, transition.throughWalls); }
				catch (RuntimeException failure) { clear(); CheatProgram.LOGGER.warn("Blink player afterimage rendering failed", failure); }
			}
		} finally { RenderSystem.setShaderColor(1, 1, 1, 1); renderer.end(); }
	}
	private static int color(float alpha) { return Math.max(0, Math.min(255, Math.round(alpha * 255))) << 24 | 0xFFFFFF; }
}
