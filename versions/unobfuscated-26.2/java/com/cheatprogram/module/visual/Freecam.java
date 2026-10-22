package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** An unregistered camera pose; the real player retains movement and network authority. */
public final class Freecam extends Module {
	private final NumberSetting speed = add(new NumberSetting("speed", 8, 1, 48, 1));
	private CameraEntity view;
	private ClientLevel world;
	private LocalPlayer body;
	private Entity previousCamera;
	private CameraType previousPerspective;

	public Freecam() { super("freecam", Category.VISUAL); }

	private static Freecam instance() { return ModuleManager.get(Freecam.class); }
	public static boolean isActive() {
		Freecam module = instance();
		return module != null && module.isEnabled() && module.view != null && module.world == mc.level
				&& module.body == mc.player && mc.player != null && mc.player.isAlive();
	}

	/** Runs even when the normal module tick loop has no world, to release a detached view. */
	public static void tickLifecycle() {
		Freecam module = instance();
		if (module != null && module.view != null && (module.world != mc.level || module.body != mc.player
				|| mc.player == null || !mc.player.isAlive())) module.setEnabled(false);
	}

	@Override public void onEnable() { if (inGame() && mc.player.isAlive()) attach(); }
	private void attach() {
		world = mc.level; body = mc.player;
		previousCamera = mc.getCameraEntity(); previousPerspective = mc.options.getCameraType();
		view = new CameraEntity(world);
		var camera = mc.gameRenderer.mainCamera();
		view.setPos(camera.isInitialized() ? camera.position() : body.getEyePosition());
		view.setYRot(camera.isInitialized() ? camera.yRot() : body.getYRot());
		view.setXRot(camera.isInitialized() ? camera.xRot() : body.getXRot());
		view.setOldPosAndRot();
		// Keep cameraEntity == body: LocalPlayer uses it to send movement packets and run physics.
		mc.setCameraEntity(body);
		mc.options.setCameraType(CameraType.FIRST_PERSON);
		body.setDeltaMovement(Vec3.ZERO);
		clearPhysicalBodyInput();
	}

	@Override public void onDisable() {
		if (view == null) return;
		if (previousPerspective != null) mc.options.setCameraType(previousPerspective);
		if (world == mc.level && previousCamera != null && !previousCamera.isRemoved()) mc.setCameraEntity(previousCamera);
		else mc.setCameraEntity(mc.player);
		// The body's current yaw/pitch may belong to AI; returning the view must preserve it.
		view = null; world = null; body = null; previousCamera = null; previousPerspective = null;
	}

	@Override public void onTick() {
		if (view == null) { if (inGame() && mc.player.isAlive()) attach(); else return; }
		if (!isActive()) return;
		clearPhysicalBodyInput(); // AI writes its own body input after modules finish ticking.
		view.setOldPosAndRot();
		if (mc.gui.screen() != null || mc.gui.overlay() != null || mc.isPaused() || !mc.isWindowActive()) return;
		long window = mc.getWindow().handle();
		double forward = pressed(window, GLFW.GLFW_KEY_W) - pressed(window, GLFW.GLFW_KEY_S);
		double strafe = pressed(window, GLFW.GLFW_KEY_D) - pressed(window, GLFW.GLFW_KEY_A);
		double vertical = pressed(window, GLFW.GLFW_KEY_SPACE)
				- Math.max(pressed(window, GLFW.GLFW_KEY_LEFT_SHIFT), pressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT));
		double yaw = Math.toRadians(view.getYRot());
		Vec3 right = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		Vec3 movement = view.getViewVector(1).scale(forward).add(right.scale(strafe)).add(0, vertical, 0);
		if (movement.lengthSqr() > 1.0e-8) view.setPos(view.position().add(movement.normalize().scale(speed.val() / 20)));
	}

	private static int pressed(long window, int key) { return com.cheatprogram.compat.KeyboardBridge.isKeyDown(window, key) ? 1 : 0; }
	private void clearPhysicalBodyInput() {
		if (body == null) return;
		body.input.keyPresses = Input.EMPTY; com.cheatprogram.compat.InputBridge.set(body.input, 0, 0);
		mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false); mc.options.keyPickItem.setDown(false);
	}

	/** Receives vanilla sensitivity-adjusted mouse deltas, without rotating the body. */
	public static void look(double yaw, double pitch) {
		Freecam module = instance();
		if (isActive() && mc.gui.screen() == null && mc.gui.overlay() == null && !mc.isPaused()) module.view.turn(yaw, pitch);
	}
	public static Vec3 cameraPosition(float partialTick) { return instance().view.getPosition(partialTick); }
	public static float cameraYaw(float partialTick) { return instance().view.getViewYRot(partialTick); }
	public static float cameraPitch(float partialTick) { return instance().view.getViewXRot(partialTick); }

	private static final class CameraEntity extends Entity {
		CameraEntity(ClientLevel level) { super(net.minecraft.world.entity.EntityTypes.MARKER, level); noPhysics = true; setNoGravity(true); }
		@Override public boolean hurtServer(ServerLevel level, DamageSource source, float damage) { return false; }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
		@Override protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput tag) { }
		@Override protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput tag) { }
	}
}
