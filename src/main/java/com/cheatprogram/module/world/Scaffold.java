package com.cheatprogram.module.world;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.mixin.MultiPlayerGameModeAccessor;
import com.cheatprogram.util.InvUtil;
import com.cheatprogram.util.InteractionLook;
import com.cheatprogram.util.ScaffoldRotations;
import com.cheatprogram.util.ServerActionGate;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.module.movement.AirWalk;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.module.movement.Speed;
import com.cheatprogram.module.utility.Blink;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Predicts the next foothold instead of waiting until the player is already over a gap. */
public class Scaffold extends Module {
	private static final Direction[] SIDES = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
	private final ModeSetting mode = add(new ModeSetting("mode", "legit", "legit", "normal", "telly", "speed_telly", "crouch_bridge"));
	private final ModeSetting rotation = add(new ModeSetting("rotation", "silent", "silent", "follow"));
	private final BoolSetting keepHeldItem = add(new BoolSetting("keepHeldItem", true));
	private final BoolSetting bridgeBoost = add(new BoolSetting("bridgeBoost", false));
	private final NumberSetting bridgeMultiplier = add(new NumberSetting("bridgeMultiplier", 2, 1, 10, .05, "x"))
			.visibleWhen(bridgeBoost::on);
	private final BoolSetting autoSwitch = add(new BoolSetting("autoSwitch", true)).visibleWhen(() -> !keepHeldItem.on());
	private final BoolSetting tower = add(new BoolSetting("tower", false));
	private final BoolSetting safeWalk = add(new BoolSetting("safeWalk", true));
	private final BoolSetting swing = add(new BoolSetting("swing", true));
	private int peakBlocks;
	private double speed;
	private double lastX = Double.NaN;
	private double lastZ;
	private ClientLevel lastLevel;
	private int bridgeY;
	private int attempts;
	private int placed;
	private int age;
	private int airTicks;
	private boolean jumpPulse;
	private boolean sneakLatch;
	private enum TellyPhase { READY, LAUNCH, TURN_BACK, PLACE, TURN_FORWARD, RECOVER }
	private TellyPhase tellyPhase = TellyPhase.READY;
	private int phaseSince;
	private int returnSince;
	private boolean cyclePlaced;
	private boolean cycleInterrupted;
	private Vec3 launchPosition;
	private Vec3 lastGroundPosition;
	private BlockPos lastGroundSupport;
	private Vec3 launchDirection = Vec3.ZERO;
	private BlockPos launchAnchor;
	private BlockPos lastCycleSupport;
	private float cyclePitch;
	private float cycleForwardYaw, cycleBasisYaw;
	private float turnYaw, turnPitch;
	private String previousRotation = "";
	private String previousMode = "";
	private float travelYaw;
	private float lastBodyYaw;
	private boolean yawTracked;
	private record SupportShape(List<AABB> boxes, boolean large, boolean movingPiston) { }
	private final Map<BlockPos, SupportShape> supportShapes = new HashMap<>();
	private final BlockPos.MutableBlockPos supportCursor = new BlockPos.MutableBlockPos();
	private final Map<Item, Boolean> safeMaterials = new IdentityHashMap<>();
	private final Set<BlockPos> rejectedTargets = new LinkedHashSet<>();
	private int blockCountAge = -1;
	private int cachedBlockCount;
	private final ServerActionGate serverActions = new ServerActionGate();
	private ClientLevel serverActionLevel;
	private LocalPlayer serverActionPlayer;
	private net.minecraft.client.multiplayer.ClientPacketListener serverActionConnection;
	private BlockPos pendingBlock;
	private Block pendingMaterial;
	private int lastRemotePlacementTick = Integer.MIN_VALUE;
	private BlockPos dispatchingTarget;
	private Block dispatchingMaterial;
	private boolean remotePlacementPhase;
	private record MaterialSelection(InteractionHand hand, int temporarySlot) { }
	private record LandingForecast(Vec3 position, int ticks) { }

	public Scaffold() {
		super("scaffold", Category.WORLD, GLFW.GLFW_KEY_V);
	}

	public boolean safeWalk() { return safeWalk.on() && cameraAvailable() && !tellyFlight(); }
	/** Only an active, player-controlled Telly jump may override the separate edge guard. */
	public boolean isTellyAirborne() { return isEnabled() && cameraAvailable() && canBuild() && tellyFlight(); }
	/** Telly owns its jump timing; Speed's optional auto-jump must not start a different cycle first. */
	public static boolean ownsJumpCycle() {
		Scaffold module = ModuleManager.get(Scaffold.class);
		return module != null && module.isEnabled() && module.telly() && module.cameraAvailable() && module.canBuild();
	}

	/** Called by Speed's existing movement hook. Neither module can compound the other's multiplier. */
	public static float accelerationMultiplier() {
		Scaffold module = ModuleManager.get(Scaffold.class);
		return mc.hasSingleplayerServer() && module != null && module.isEnabled() && module.bridgeBoost.on() && module.canBuild()
				&& module.materialReady() && module.movementPressed() && module.atBridgeHeight()
				? module.bridgeMultiplier.floatVal() : 1f;
	}

	@Override
	public String suffix() { return mode.display() + (bridgeBoost.on() ? " · " + bridgeMultiplier.format() : ""); }

	@Override
	public void onEnable() { resetTracking(); }

	@Override
	public void onDisable() {
		InteractionLook.restoreSyncedLook();
		if (telly() && tellyPhase != TellyPhase.READY && rotation.is("follow") && cameraAvailable())
			turnCamera(travelYaw, cyclePitch, 180, 90);
		resetTracking();
	}

	private void resetTracking() {
		// A toggle or local tracking reset cannot confirm a block that vanilla still predicts.
		// Only changing the player/world retires that world's acknowledgement sequence.
		if (serverActionLevel != mc.level || serverActionPlayer != mc.player || serverActionConnection != mc.getConnection()) {
			serverActions.reset(); pendingBlock = null; pendingMaterial = null;
			lastRemotePlacementTick = Integer.MIN_VALUE;
			serverActionLevel = mc.level; serverActionPlayer = mc.player; serverActionConnection = mc.getConnection();
		}
		ScaffoldRotations.clear();
		supportShapes.clear(); safeMaterials.clear(); rejectedTargets.clear();
		blockCountAge = -1;
		peakBlocks = 0;
		speed = 0;
		lastX = Double.NaN;
		lastLevel = null;
		attempts = placed = 0;
		age = airTicks = 0;
		jumpPulse = sneakLatch = false;
		previousMode = "";
		previousRotation = "";
		resetTelly();
		lastGroundPosition = null;
		lastGroundSupport = null;
		yawTracked = false;
	}

	/** Vanilla has already acknowledged the correction. Drop every stale target/turn without moving the player. */
	public static void onServerCorrection() {
		ScaffoldRotations.onServerCorrection();
		Scaffold module = ModuleManager.get(Scaffold.class);
		if (module == null || !module.isEnabled()) return;
		if (module.lastLevel != mc.level) module.resetTracking();
		module.serverActions.corrected(System.nanoTime());
		module.resetTelly(); module.jumpPulse = module.sneakLatch = false;
		module.supportShapes.clear(); module.rejectedTargets.clear(); module.blockCountAge = -1;
		module.lastGroundPosition = null; module.lastGroundSupport = null; module.yawTracked = false;
		if (inGame()) {
			module.lastLevel = mc.level;
			module.lastX = mc.player.getX(); module.lastZ = mc.player.getZ();
			module.bridgeY = Mth.floor(mc.player.getY() - .001);
		}
		// An outstanding prediction stays unconfirmed until its real server acknowledgement arrives.
	}

	/** Called after vanilla ends block predictions and restores the authoritative block state. */
	public static void onBlockChangedAck(int sequence) {
		Scaffold module = ModuleManager.get(Scaffold.class);
		if (module == null || !inGame() || module.serverActionLevel != mc.level || module.serverActionPlayer != mc.player || module.serverActionConnection != mc.getConnection() || module.pendingBlock == null
				|| !module.serverActions.acknowledge(sequence)) return;
		BlockPos at = module.pendingBlock;
		Block expected = module.pendingMaterial;
		module.pendingBlock = null; module.pendingMaterial = null;
		module.supportShapes.clear(); module.rejectedTargets.clear(); module.blockCountAge = -1;
		BlockState actual = mc.level.getBlockState(at);
		if (!actual.is(expected) || !actual.isCollisionShapeFullBlock(mc.level, at)) {
			module.serverActions.corrected(System.nanoTime());
			module.resetTelly(); module.jumpPulse = false;
		} else if (module.tellyPhase != TellyPhase.READY) {
			module.cyclePlaced = true; module.lastCycleSupport = at;
		}
	}

	private boolean unconfirmed(BlockPos at) { return pendingBlock != null && pendingBlock.equals(at); }

	@Override
	public boolean onPacketSend(Packet<?> packet) {
		if (dispatchingTarget != null && mc.isSameThread() && packet instanceof ServerboundUseItemOnPacket use
				&& !mc.hasSingleplayerServer() && serverActions.predict(use.getSequence(), System.nanoTime())) {
			pendingBlock = dispatchingTarget; pendingMaterial = dispatchingMaterial;
		}
		return false;
	}

	/** Safe, solid building blocks in the hotbar and offhand. */
	public int blocksLeft() {
		if (!inGame()) return 0;
		if (isEnabled() && blockCountAge == age) return cachedBlockCount;
		int count = isPlaceable(mc.player.getOffhandItem()) ? mc.player.getOffhandItem().getCount() : 0;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = InvUtil.stack(i);
			if (isPlaceable(stack)) count += stack.getCount();
		}
		blockCountAge = age;
		return cachedBlockCount = count;
	}

	public float progress() {
		int left = blocksLeft();
		return peakBlocks <= 0 ? 0f : Math.min(1f, left / (float) peakBlocks);
	}

	/** Horizontal speed in blocks per second. */
	public double speed() { return speed; }

	@Override
	public void onTick() {
		ScaffoldRotations.tickLifecycle();
		if (!inGame()) return;
		if (serverActionConnection != mc.getConnection()) resetTracking();
		var p = mc.player;
		if (serverActions.pollTimeout(System.nanoTime())) {
			resetTelly(); jumpPulse = sneakLatch = false; ScaffoldRotations.clear();
		}
		// A legitimate boosted step is not a teleport. A reset used to erase the Telly phase
		// and bridge height repeatedly at large speed settings, leaving placement in READY.
		double displacement = Double.isNaN(lastX) ? 0 : Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
		double teleportThreshold = Math.max(8, p.getDeltaMovement().horizontalDistance() * 3 + 3);
		if (lastLevel != mc.level || displacement > teleportThreshold) {
			resetTracking();
			lastLevel = mc.level;
			bridgeY = Mth.floor(p.getY() - 0.001);
		}
		if (!Double.isNaN(lastX)) speed = speed * 0.7 + Math.hypot(p.getX() - lastX, p.getZ() - lastZ) * 20 * 0.3;
		lastX = p.getX();
		lastZ = p.getZ();
		attempts = placed = 0;
		age++;
		supportShapes.clear(); rejectedTargets.clear();
		peakBlocks = Math.max(peakBlocks, blocksLeft());
		jumpPulse = false;
		if (!mode.get().equals(previousMode) || !rotation.get().equals(previousRotation)) {
			if (tellyPhase != TellyPhase.READY && previousRotation.equals("follow") && cameraAvailable())
				turnCamera(travelYaw, cyclePitch, 180, 90);
			previousMode = mode.get();
			previousRotation = rotation.get();
			airTicks = 0;
			sneakLatch = false;
			resetTelly();
			ScaffoldRotations.clear();
			yawTracked = false;
		}
		if (!rotation.is("silent")) ScaffoldRotations.clear();
		if (rotation.is("follow") && yawTracked && !AiController.isRunning() && !Freecam.isActive())
			travelYaw += Mth.wrapDegrees(p.getYRot() - lastBodyYaw);
		else travelYaw = p.getYRot();
		yawTracked = true;
		if (p.onGround() || tower.on() && mc.options.keyJump.isDown() && p.getDeltaMovement().y > 0) {
			bridgeY = Mth.floor(p.getY() - 0.001);
		}
		if (p.onGround() && cameraAvailable() && supported(p.position())) {
			lastGroundPosition = p.position();
			lastGroundSupport = findLaunchSupport(movementDirection());
		}
		updateRhythm();
		build();
		// LocalPlayer computes its crouching pose immediately before KeyboardInput.tick. Prime only
		// our current Shift intent so Java's pose/sneak speed does not lag the edge decision by a tick.
		if (mode.is("crouch_bridge") && cameraAvailable() && canBuild()) {
			Input old = p.input.keyPresses;
			p.input.keyPresses = new Input(old.forward(), old.backward(), old.left(), old.right(), old.jump(),
					mc.options.keyShift.isDown() || sneakLatch, old.sprint());
		}
		lastBodyYaw = p.getYRot();
	}

	@Override
	public void onPostTick() {
		// Fill a newly crossed edge after movement using the same per-tick placement budget.
		remotePlacementPhase = true;
		try { build(); }
		finally { remotePlacementPhase = false; }
		if (inGame()) lastBodyYaw = mc.player.getYRot();
	}

	/** Called after vanilla KeyboardInput.tick and before AI applies its exclusive body input. */
	public static void applyInput(ClientInput input) {
		Scaffold scaffold = ModuleManager.get(Scaffold.class);
		if (scaffold == null || !scaffold.isEnabled() || !scaffold.canBuild() || AiController.isRunning() || Freecam.isActive()
				|| input != mc.player.input) return;
		Input original = input.keyPresses;
		float forward = input.forwardImpulse, strafe = input.leftImpulse;
		// Keep the player's intended travel direction while follow rotates the view toward a placement.
		if (scaffold.rotation.is("follow") && !InteractionLook.movementScoped() && (forward != 0 || strafe != 0)) {
			var direction = ServerActionGate.keyboardDirection(forward, strafe, mc.player.getYRot() - scaffold.travelYaw);
			forward = direction.forward(); strafe = direction.strafe();
		}
		boolean sprint = original.sprint() || scaffold.mode.is("speed_telly") && input.forwardImpulse > .8f && !original.shift()
				&& mc.player.getFoodData().getFoodLevel() > 6;
		if (scaffold.mode.is("crouch_bridge") && scaffold.sneakLatch) sprint = false;
		input.forwardImpulse = forward;
		input.leftImpulse = strafe;
		boolean jump = scaffold.telly() && scaffold.tellyPhase != TellyPhase.READY && !original.shift()
				? scaffold.jumpPulse : original.jump() || scaffold.jumpPulse;
		input.keyPresses = new Input(forward > .001, forward < -.001, strafe > .001, strafe < -.001,
				jump, original.shift() || scaffold.sneakLatch, sprint);
	}

	/** Called only while vanilla owns this body tick; basis preserves the player's intended travel. */
	public InteractionLook.MovementAim movementAim(float silentYaw, float silentPitch) {
		if (!isEnabled() || !canBuild() || !cameraAvailable()) return null;
		boolean follow = rotation.is("follow");
		return new InteractionLook.MovementAim(follow ? mc.player.getYRot() : silentYaw,
				follow ? mc.player.getXRot() : silentPitch, follow ? travelYaw : mc.player.getYRot());
	}

	private boolean canBuild() {
		if(com.cheatprogram.module.combat.KillAura.actedThisTick() || com.cheatprogram.module.combat.KillAura.ownsNetworkRotation())return false;
		return inGame() && mc.gameMode != null && mc.getConnection() != null && mc.screen == null && mc.getOverlay() == null && !mc.isPaused() && mc.isWindowActive()
				&& !serverActions.recovering(System.nanoTime())
				&& mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.isPassenger() && !mc.player.isUsingItem()
				&& !mc.gameMode.isDestroying() && !mc.options.keyUse.isDown()
				&& !mc.player.getAbilities().flying && !mc.player.isFallFlying() && !AirWalk.ownsMotion() && !FlatFlight.ownsMotion()
				&& !ModuleManager.on(Blink.class);
	}

	private boolean telly() { return mode.is("telly") || mode.is("speed_telly"); }
	private boolean speedTelly() { return mode.is("speed_telly"); }
	private boolean tellyFlight() { return telly() && tellyPhase != TellyPhase.READY && inGame() && !mc.player.onGround(); }
	private boolean cameraAvailable() { return inGame() && !AiController.isRunning() && !Freecam.isActive() && !AirWalk.ownsMotion() && !FlatFlight.ownsMotion(); }
	private boolean atBridgeHeight() { return mc.player.getY() >= bridgeY + .65 && mc.player.getY() <= bridgeY + 3; }
	private int placementLimit() {
		if (!mc.hasSingleplayerServer()) return 1;
		// An airborne catch-up window is short. Count real placements rather than spending one
		// whole tick per block; a diagonal bridge can need two connected blocks at the same edge.
		int base = mode.is("speed_telly") ? 6 : mode.is("telly") ? 4 : mode.is("crouch_bridge") ? 2 : mode.is("legit") ? 1 : 2;
		return Math.min(16, Math.max(base, (int) Math.ceil(Speed.effectiveMultiplier() * base)));
	}
	private int attemptLimit() {
		if (!mc.hasSingleplayerServer()) return 1;
		int base = telly() ? 18 : mode.is("crouch_bridge") ? 10 : 6;
		return Math.min(48, Math.max(base, (int) Math.ceil(Speed.effectiveMultiplier() * base)));
	}
	private boolean movementPressed() {
		return mc.options.keyUp.isDown() != mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() != mc.options.keyRight.isDown();
	}
	private boolean materialReady() {
		return isPlaceable(mc.player.getMainHandItem()) || isPlaceable(mc.player.getOffhandItem())
				|| (keepHeldItem.on() || autoSwitch.on()) && InvUtil.findHotbar(this::isPlaceable) >= 0;
	}

	private void updateRhythm() {
		if (!cameraAvailable()) {
			ScaffoldRotations.clear();
			jumpPulse = sneakLatch = false;
			airTicks = 0;
			resetTelly();
			return;
		}
		if (!canBuild()) {
			ScaffoldRotations.clear();
			jumpPulse = sneakLatch = false;
			if (tellyPhase != TellyPhase.READY && rotation.is("follow")) turnCamera(travelYaw, cyclePitch, 180, 90);
			resetTelly();
			yawTracked = false;
			return;
		}
		airTicks = mc.player.onGround() ? 0 : airTicks + 1;
		if (telly()) { sneakLatch = false; updateTelly(); return; }
		resetTelly();
		if (!mode.is("crouch_bridge")) { sneakLatch = false; return; }
		updateCrouchBridge();
	}

	private void resetTelly() {
		tellyPhase = TellyPhase.READY; phaseSince = returnSince = 0; cyclePlaced = cycleInterrupted = false;
		launchPosition = null; launchAnchor = lastCycleSupport = null; launchDirection = Vec3.ZERO;
	}
	private void phase(TellyPhase next) {
		tellyPhase = next; phaseSince = age;
		if (next == TellyPhase.TURN_FORWARD) returnSince = age;
	}

	/** A jump, a backward placement window and a forward recovery are separate phases. */
	private void updateTelly() {
		var p = mc.player;
		boolean moving = movementPressed(), blocks = materialReady(), manualSneak = mc.options.keyShift.isDown();
		boolean reversed = tellyPhase != TellyPhase.READY && moving && movementDirection().dot(launchDirection) < -.1;
		if (!moving || !blocks || manualSneak || reversed) {
			cycleInterrupted = true;
			jumpPulse = false;
			if (tellyPhase != TellyPhase.READY && tellyPhase != TellyPhase.RECOVER && tellyPhase != TellyPhase.TURN_FORWARD)
				phase(TellyPhase.TURN_FORWARD);
		}
		// A landing is a new cycle boundary, even when the previous prediction was corrected
		// by a block acknowledgement. Do not spend separate grounded ticks in PLACE, turning,
		// RECOVER and READY while the edge guard repeatedly removes horizontal movement.
		if (p.onGround() && tellyPhase != TellyPhase.READY && tellyPhase != TellyPhase.LAUNCH) {
			if (tellyPhaseAim(forwardAimYaw(), cyclePitch, speedTelly() ? 135 : 100, 45))
				phase(TellyPhase.READY);
			else { phase(TellyPhase.TURN_FORWARD); return; }
		}
		switch (tellyPhase) {
			case READY -> {
				if (!moving || !blocks || manualSneak
						|| p.isInWater() || p.isInLava() || p.onClimbable()) return;
				Vec3 direction = movementDirection();
				if (direction.lengthSqr() < .5) return;
				if (!p.onGround()) {
					// A physical Space jump near the edge is still a real Telly launch. Adopt its
					// last grounded support instead of leaving READY unable to place throughout it.
					if (airTicks > 3 || p.getDeltaMovement().y <= 0 || lastGroundPosition == null
							|| !solidBridge(lastGroundSupport) || p.position().distanceToSqr(lastGroundPosition) > 5.76
							|| stableAtBridge(p.position().add(direction.scale(.35)))) return;
					beginCycle(direction, lastGroundSupport, lastGroundPosition);
					phase(TellyPhase.TURN_BACK);
					if (aimBackward()) phase(TellyPhase.PLACE);
					return;
				}
				if (!supported(p.position())) return;
				// Take off near the actual front face, not 0.65+ blocks before it. The eye must cross
				// that face before Java can click its side. A short first step then leaves room to turn.
				double edge = Mth.clamp(p.getDeltaMovement().horizontalDistance() + .08, .16, speedTelly() ? .38 : .32);
				if (stableAtBridge(p.position().add(direction.scale(edge)))) return;
				BlockPos anchor = findLaunchSupport(direction);
				if (anchor == null) return;
				beginCycle(direction, anchor, p.position());
				phase(TellyPhase.LAUNCH);
				jumpPulse = tellyPhaseAim(forwardAimYaw(), cyclePitch, speedTelly() ? 130 : 95, 45);
			}
			case LAUNCH -> {
				if (p.onGround()) {
					if (age - phaseSince <= 6 && moving && blocks && !manualSneak && supported(p.position()))
						jumpPulse = tellyPhaseAim(forwardAimYaw(), cyclePitch, speedTelly() ? 130 : 95, 45);
					else phase(TellyPhase.READY);
				} else {
					phase(TellyPhase.TURN_BACK);
					// Turn on the first airborne update, rather than consuming three launch ticks
					// followed by a separate multi-tick turn before any placement is permitted.
					if (aimBackward()) phase(TellyPhase.PLACE);
				}
			}
			case TURN_BACK -> {
				if (p.onGround()) { phase(TellyPhase.TURN_FORWARD); break; }
				if (aimBackward()) phase(TellyPhase.PLACE);
			}
			case PLACE -> {
				// Keep a backward heading for the whole placement window. Newly attached blocks may
				// lie under/ahead of the feet; aiming at their centres caused repeated 180-degree flips.
				aimBackward();
				if (cyclePlaced && mc.player.getDeltaMovement().y <= 0 && landingCovered()) phase(TellyPhase.TURN_FORWARD);
				else if (p.onGround()) phase(TellyPhase.TURN_FORWARD);
			}
			case TURN_FORWARD -> {
				if (needsLandingRepair(moving, blocks, manualSneak)) { phase(TellyPhase.TURN_BACK); break; }
				if (tellyPhaseAim(forwardAimYaw(), cyclePitch, speedTelly() ? 135 : 100, 45))
					phase(TellyPhase.RECOVER);
			}
			case RECOVER -> {
				if (needsLandingRepair(moving, blocks, manualSneak)) { phase(TellyPhase.TURN_BACK); break; }
				tellyPhaseAim(forwardAimYaw(), cyclePitch, speedTelly() ? 135 : 100, 45);
				if (p.onGround()) {
					phase(TellyPhase.READY);
					// No fixed three-tick pause at a freshly built edge. Ordinary Telly still has
					// its longer turn; Speed Telly is the same sequence with faster reactions.
					if (moving && blocks && !manualSneak) updateTelly();
				}
			}
		}
	}
	private boolean needsLandingRepair(boolean moving, boolean blocks, boolean manualSneak) {
		if (mc.player.onGround() || cycleInterrupted || !moving || !blocks || manualSneak || landingCovered()) return false;
		// A fractional change in a far landing estimate must not alternate backward/forward
		// turns every tick. Let the return finish unless the next physical step or imminent
		// landing needs a foothold; a sustained correction may restart placement afterward.
		Vec3 next = mc.player.position().add(capHorizontal(mc.player.getDeltaMovement(), tellyStepLimit()));
		return !supportedAtBridge(next) || predictedLanding(null).ticks() <= 3 || age - returnSince >= 3;
	}
	private void beginCycle(Vec3 direction, BlockPos anchor, Vec3 position) {
		launchPosition = position; launchDirection = direction; launchAnchor = lastCycleSupport = anchor;
		cyclePitch = mc.player.getXRot(); turnYaw = mc.player.getYRot(); turnPitch = mc.player.getXRot();
		cycleForwardYaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90;
		cycleBasisYaw = travelYaw;
		cyclePlaced = cycleInterrupted = false;
	}
	private float forwardAimYaw() { return cycleForwardYaw + Mth.wrapDegrees(travelYaw - cycleBasisYaw); }
	private boolean aimBackward() {
		Vec3 eye = mc.player.getEyePosition();
		float pitch = (float) Math.toDegrees(Math.atan2(eye.y - (bridgeY + .7), .75));
		return tellyPhaseAim(forwardAimYaw() + 180, pitch, speedTelly() ? 145 : 100, speedTelly() ? 55 : 42);
	}

	private BlockPos findLaunchSupport(Vec3 direction) {
		// The player's centre can already overhang a side/corner while Java still supports its
		// feet. Searching only a line behind the centre misses that real block and leaves READY
		// permanently waiting for an anchor, especially after a diagonal turn.
		AABB feet = mc.player.getBoundingBox();
		BlockPos best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (int x = Mth.floor(feet.minX + .04); x <= Mth.floor(feet.maxX - .04); x++) {
			for (int z = Mth.floor(feet.minZ + .04); z <= Mth.floor(feet.maxZ - .04); z++) {
				BlockPos at = new BlockPos(x, bridgeY, z);
				if (!solidBridge(at)) continue;
				Vec3 offset = mc.player.position().subtract(Vec3.atCenterOf(at)).multiply(1, 0, 1);
				double score = offset.lengthSqr() - direction.dot(offset) * .15;
				if (score < bestScore) { best = at; bestScore = score; }
			}
		}
		if (best != null) return best;
		for (double back = 0; back <= 1.2; back += .2) {
			Vec3 point = mc.player.position().subtract(direction.scale(back));
			BlockPos at = BlockPos.containing(point.x, bridgeY, point.z);
			if (solidBridge(at)) return at;
		}
		return null;
	}
	private boolean solidBridge(BlockPos at) {
		return at != null && !unconfirmed(at) && mc.level.hasChunkAt(at) && mc.level.getWorldBorder().isWithinBounds(at)
				&& mc.level.getBlockState(at).isCollisionShapeFullBlock(mc.level, at);
	}
	private boolean tellyPhaseAim(float yaw, float pitch, float yawStep, float pitchStep) {
		if (rotation.is("follow")) {
			turnCamera(yaw, pitch, yawStep, pitchStep);
			turnYaw = mc.player.getYRot(); turnPitch = mc.player.getXRot();
		} else {
			// Independent phase angles only: silent mode never owns the player's visible camera.
			turnYaw = Mth.approachDegrees(turnYaw, yaw, yawStep); turnPitch = Mth.approach(turnPitch, pitch, pitchStep);
			ScaffoldRotations.update(turnYaw, turnPitch, yawStep);
		}
		return Math.abs(Mth.wrapDegrees(turnYaw - yaw)) <= 8 && Math.abs(turnPitch - pitch) <= 9;
	}
	private void turnCamera(float yaw, float pitch, float yawStep, float pitchStep) {
		var p = mc.player;
		float nextYaw = Mth.approachDegrees(p.getYRot(), yaw, yawStep);
		float nextPitch = Mth.approach(p.getXRot(), Mth.clamp(pitch, -90, 90), pitchStep);
		p.turn(Mth.wrapDegrees(nextYaw - p.getYRot()) / .15, (nextPitch - p.getXRot()) / .15);
	}

	/** Java speed-bridging: stay crouched at an edge until a connected foothold really exists. */
	private void updateCrouchBridge() {
		if (!movementPressed() || !materialReady() || !mc.player.onGround()) {
			sneakLatch = false; return;
		}
		Vec3 position = mc.player.position(), direction = movementDirection();
		double caution = Math.min(3.5, Math.max(.42, mc.player.getDeltaMovement().horizontalDistance() + .24));
		boolean nextSupported = coveredRunway(position, position.add(direction.scale(caution)));
		if (sneakLatch) {
			// Release from real geometry, not from a private "I placed it" flag. Another player,
			// a server acknowledgement or an earlier build hook can fill this cell first. The
			// longer release probe also prevents Shift oscillating before a foothold is added.
			if (coveredRunway(position, position.add(direction.scale(Math.min(3.65, caution + .10))))) {
				sneakLatch = false;
			}
		} else if (!nextSupported) {
			sneakLatch = true;
		}
	}

	private boolean supported(Vec3 position) {
		AABB feet = mc.player.getBoundingBox();
		double dx = position.x - mc.player.getX(), dz = position.z - mc.player.getZ();
		AABB probe = new AABB(feet.minX + dx + .04, position.y - .12, feet.minZ + dz + .04,
				feet.maxX + dx - .04, position.y - .01, feet.maxZ + dz - .04);
		// Every edge check used to allocate BlockCollisions and recompute the same shape. Reuse
		// local-space collision boxes for this tick, including slabs/stairs, and invalidate new blocks.
		int minX = Mth.floor(probe.minX - 1.0e-7), maxX = Mth.floor(probe.maxX + 1.0e-7);
		int minY = Mth.floor(probe.minY - 1.0e-7), maxY = Mth.floor(probe.maxY + 1.0e-7);
		int minZ = Mth.floor(probe.minZ - 1.0e-7), maxZ = Mth.floor(probe.maxZ + 1.0e-7);
		// Ordinary full-block supports are found first. The second pass preserves vanilla's
		// padded search for fences and moving-piston shapes extending outside their block cell.
		for (int margin = 0; margin <= 1; margin++) {
			for (int x = minX - margin; x <= maxX + margin; x++) {
				for (int y = minY - margin; y <= maxY + margin; y++) {
					for (int z = minZ - margin; z <= maxZ + margin; z++) {
						int outside = (x < minX || x > maxX ? 1 : 0) + (y < minY || y > maxY ? 1 : 0) + (z < minZ || z > maxZ ? 1 : 0);
						if (margin == 1 && outside == 0 || outside == 3) continue;
						supportCursor.set(x, y, z);
						if (unconfirmed(supportCursor)) continue;
						SupportShape shape = supportShapes.get(supportCursor);
						if (shape == null) {
							BlockPos at = supportCursor.immutable();
							if (!mc.level.hasChunkAt(at)) shape = new SupportShape(List.of(), false, false);
							else {
								BlockState state = mc.level.getBlockState(at);
								shape = new SupportShape(CollisionContext.of(mc.player).getCollisionShape(state, mc.level, at).toAabbs(),
										state.hasLargeCollisionShape(), state.is(Blocks.MOVING_PISTON));
							}
							supportShapes.put(at, shape);
						}
						if (outside == 1 && !shape.large() || outside == 2 && !shape.movingPiston()) continue;
						for (AABB box : shape.boxes()) {
							if (probe.maxX > x + box.minX && probe.minX < x + box.maxX
									&& probe.maxY > y + box.minY && probe.minY < y + box.maxY
									&& probe.maxZ > z + box.minZ && probe.minZ < z + box.maxZ) return true;
						}
					}
				}
			}
		}
		return false;
	}

	private void build() {
		build(null);
	}

	/** Also run immediately before the real movement vector is collision-tested. */
	public static void prepareMove(Vec3 movement) {
		Scaffold module = ModuleManager.get(Scaffold.class);
		if (module != null && module.isEnabled()
				&& !AiController.isRunning() && !Freecam.isActive()) module.build(movement);
	}

	/** At high speed, a supported endpoint alone must not allow jumping across an unfilled hole. */
	public static Vec3 constrainMove(Vec3 movement) {
		Scaffold module = ModuleManager.get(Scaffold.class);
		if (module == null || !module.isEnabled() || !module.canBuild() || !module.cameraAvailable()) return movement;
		// Remote motion keeps vanilla gravity/inertia and the ordinary grounded edge guard. Never brake a jump in mid-air.
		if (!mc.hasSingleplayerServer()) return movement;
		// maybeBackOffFromEdge runs before the first jump movement too, while onGround is still
		// true. Constrain that launch step as well as subsequent airborne movement.
		if (module.telly() && module.tellyPhase != TellyPhase.READY && (module.tellyFlight() || movement.y > 0))
			return module.reachableTellyMotion(movement);
		if ((!module.safeWalk.on() && !module.mode.is("crouch_bridge"))
				|| movement.y > 0 || mc.player.getY() > module.bridgeY + 1.2 || !module.atBridgeHeight()) return movement;
		double length = movement.horizontalDistance();
		if (length < .05 || !Double.isFinite(length)) return movement;
		double checkedLength = Math.min(8, length);
		if (module.mode.is("crouch_bridge") && module.sneakLatch) checkedLength = Math.min(checkedLength, .32);
		// A 0.15-block grid can stop a crouching player before the eye crosses the attachment
		// face. Fine steps allow Java's small, safe overhang, where the visible side is clickable.
		int samples = Math.max(1, (int) Math.ceil(checkedLength / (module.mode.is("crouch_bridge") ? .025 : .08)));
		double safe = 0;
		for (int i = 1; i <= samples; i++) {
			double t = checkedLength * i / samples / length;
			Vec3 point = mc.player.position().add(movement.x * t, 0, movement.z * t);
			if (!module.supportedAtBridge(point)) {
				// Resolve the last supported fraction rather than stopping one coarse sample early.
				// Java's visible side may only be reachable at the safe overhang of a block edge.
				safe = module.lastSupportedFraction(movement, safe, t);
				break;
			}
			safe = t;
		}
		Vec3 limited = safe == 1 ? movement : new Vec3(movement.x * safe, movement.y, movement.z * safe);
		if (module.mode.is("crouch_bridge") && safe < 1 && !module.sneakLatch) {
			module.sneakLatch = true;
		}
		return limited;
	}

	private Vec3 reachableTellyMotion(Vec3 movement) {
		BlockPos anchor = solidBridge(lastCycleSupport) ? lastCycleSupport : launchAnchor;
		if (anchor == null || movement.horizontalDistanceSqr() < 1.0e-8) return movement;
		Vec3 result = capHorizontal(movement, tellyStepLimit());
		// Before the turn is ready, do not spend the whole jump flying several blocks away from
		// the only attachment face. This is a horizontal pace limit, never a change to gravity.
		if (tellyPhase == TellyPhase.LAUNCH || tellyPhase == TellyPhase.TURN_BACK) {
			double runout = speedTelly() ? .90 : .72;
			Vec3 delta = mc.player.position().add(result).subtract(launchPosition).multiply(1, 0, 1);
			double travelled = mc.player.position().subtract(launchPosition).horizontalDistance();
			if (delta.horizontalDistance() > runout && delta.horizontalDistance() > travelled) {
				double remaining = Math.max(0, runout - travelled);
				result = capHorizontal(result, remaining);
			}
		}
		// At large speed settings, keep the next attachment face within real block reach until the
		// runway is extended. This limits horizontal drift; it never cancels the jump's Y motion.
		Vec3 face = Vec3.atCenterOf(anchor).add(launchDirection.scale(.5));
		double vertical = mc.player.getEyeY() - face.y, reach = mc.player.blockInteractionRange();
		double horizontal = Math.max(.5, Math.sqrt(Math.max(0, reach * reach - vertical * vertical)) - .35);
		Vec3 next = mc.player.position().add(result);
		double distance = Math.hypot(next.x - face.x, next.z - face.z);
		double current = Math.hypot(mc.player.getX() - face.x, mc.player.getZ() - face.z);
		if (distance > horizontal && distance > current) {
			Vec3 limited = new Vec3(face.x + (next.x - face.x) * horizontal / distance, next.y,
					face.z + (next.z - face.z) * horizontal / distance);
			Vec3 delta = limited.subtract(mc.player.position());
			result = current >= horizontal ? new Vec3(0, result.y, 0) : new Vec3(delta.x, result.y, delta.z);
		}
		// On the final descent, do not walk off the runway that was actually placed. prepareMove
		// has just tried to fill the crossed cells; rejected/unavailable blocks stay unsupported.
		if (result.y <= 0 && mc.player.getY() + result.y <= bridgeY + 1.08 && supportedAtBridge(mc.player.position()))
			result = continuousSupportedMotion(result);
		return result;
	}
	private double tellyStepLimit() { return speedTelly() ? .78 : .60; }
	private Vec3 capHorizontal(Vec3 vector, double limit) {
		double length = vector.horizontalDistance();
		return length <= limit || length < 1.0e-8 ? vector : new Vec3(vector.x * limit / length, vector.y, vector.z * limit / length);
	}
	private Vec3 continuousSupportedMotion(Vec3 movement) {
		int samples = Math.max(1, (int) Math.ceil(movement.horizontalDistance() / .08));
		double fraction = 0;
		for (int i = 1; i <= samples; i++) {
			double t = i / (double) samples;
			if (!supportedAtBridge(mc.player.position().add(movement.x * t, 0, movement.z * t))) {
				fraction = lastSupportedFraction(movement, fraction, t);
				break;
			}
			fraction = t;
		}
		return fraction == 1 ? movement : new Vec3(movement.x * fraction, movement.y, movement.z * fraction);
	}
	private double lastSupportedFraction(Vec3 movement, double supported, double unsupported) {
		if (!supportedAtBridge(mc.player.position().add(movement.x * supported, 0, movement.z * supported))) return 0;
		for (int i = 0; i < 8; i++) {
			double middle = (supported + unsupported) * .5;
			if (supportedAtBridge(mc.player.position().add(movement.x * middle, 0, movement.z * middle))) supported = middle;
			else unsupported = middle;
		}
		return supported;
	}

	private boolean supportedAtBridge(Vec3 point) {
		return supported(new Vec3(point.x, bridgeY + 1, point.z));
	}
	/** A full support under the centre is needed to release crouch/finish a landing prediction. */
	private boolean stableAtBridge(Vec3 point) {
		return solidBridge(BlockPos.containing(point.x, bridgeY, point.z));
	}

	private void build(Vec3 actualMotion) {
		if (!canBuild() || !cameraAvailable()) return;
		// Remote reach and the use-item ray must share vanilla's already transmitted body
		// position. Pre-move placement required a duplicate movement packet on the same tick.
		// The HEAD pass only plans a face; remote dispatch remains in the TAIL pass.
		if (!mc.hasSingleplayerServer() && (!serverActions.ready(System.nanoTime())
				|| (long) mc.player.tickCount - lastRemotePlacementTick < 4)) return;
		if (!atBridgeHeight()) return;
		if (telly()) { buildTelly(actualMotion); return; }
		int limit = placementLimit();
		if (placed >= limit || attempts >= attemptLimit()) return;

		Vec3 position = mc.player.position();
		double actualStep = actualMotion == null ? mc.player.getDeltaMovement().horizontalDistance() : actualMotion.horizontalDistance();
		double lookAhead = Math.min(.95, .42 + actualStep * 1.8);
		float boost = Speed.effectiveMultiplier();
		Vec3 direction = actualMotion != null && actualMotion.horizontalDistanceSqr() > 1.0e-6
				? new Vec3(actualMotion.x, 0, actualMotion.z).normalize() : movementDirection();
		if (boost > 1f) {
			double forecast = Math.max(.4 + boost * .16, actualStep * 1.7 + .45);
			// The normal reach check still rejects a support face outside the actual interaction range.
			lookAhead = Math.min(Math.max(.65, Math.min(3.65, mc.player.blockInteractionRange() - .6)), Math.max(lookAhead, forecast));
		}
		Vec3 projected = position.add(direction.scale(lookAhead));
		BlockPos current = BlockPos.containing(position.x, bridgeY, position.z);
		BlockPos next = BlockPos.containing(projected.x, bridgeY, projected.z);
		Set<BlockPos> candidates = new LinkedHashSet<>();
		candidates.add(current);
		if (boost > 1f) {
			// Walk every crossed cell in order. Sampling just the endpoint can leave a one-block gap.
			int steps = Math.max(1, (int) Math.ceil(lookAhead / .35));
			BlockPos previous = current;
			for (int i = 1; i <= steps; i++) {
				Vec3 point = position.add(direction.scale(lookAhead * i / steps));
				BlockPos crossed = BlockPos.containing(point.x, bridgeY, point.z);
				if (crossed.getX() != previous.getX() && crossed.getZ() != previous.getZ()) {
					candidates.add(new BlockPos(crossed.getX(), bridgeY, previous.getZ()));
					candidates.add(new BlockPos(previous.getX(), bridgeY, crossed.getZ()));
				}
				candidates.add(crossed);
				previous = crossed;
			}
		} else {
			candidates.add(next);
			// A diagonal cell may only touch at a corner: first add an adjacent side support.
			candidates.add(new BlockPos(next.getX(), bridgeY, current.getZ()));
			candidates.add(new BlockPos(current.getX(), bridgeY, next.getZ()));
		}
		for (BlockPos target : candidates) {
			if (placed >= limit || attempts >= attemptLimit() || mc.screen != null) break;
			if (!mc.level.hasChunkAt(target) || !mc.level.getWorldBorder().isWithinBounds(target)
					|| !mc.level.getBlockState(target).canBeReplaced()) continue;
			// Normal/crouch bridging can still fill the real foothold immediately in an emergency.
			boolean emergency = target.equals(current) && !supported(position) && (mc.player.onGround()
					|| mc.player.getDeltaMovement().y <= 0 && mc.player.getY() <= bridgeY + 1.65);
			if (mode.is("crouch_bridge") && !AiController.isRunning() && !Freecam.isActive() && !sneakLatch && !emergency) continue;
			MaterialSelection material = buildingMaterial();
			if (material == null) break;
			if (placeWithMaterial(target, material)) {
				placed++;
			}
		}
	}

	private LandingForecast predictedLanding(Vec3 firstStep) {
		Vec3 position = mc.player.position(), velocity = mc.player.getDeltaMovement();
		Vec3 direction = movementPressed() ? movementDirection() : Vec3.ZERO;
		double acceleration = (mc.player.isSprinting() ? .026 : .02) * .98 * Speed.effectiveMultiplier();
		double x = position.x, y = position.y, z = position.z;
		double vx = velocity.x, vy = velocity.y, vz = velocity.z;
		double gravity = mc.player.getAttributeValue(Attributes.GRAVITY);
		int ticks = 0;
		// LivingEntity adds input acceleration, moves, then applies gravity/.98 and horizontal
		// .91 drag. The old v*t extrapolation both overshot a released key and undershot 10x input.
		for (; ticks < 24; ticks++) {
			Vec3 step = ticks == 0 && firstStep != null ? capHorizontal(firstStep, tellyStepLimit())
					: capHorizontal(new Vec3(vx + direction.x * acceleration, vy, vz + direction.z * acceleration), tellyStepLimit());
			x += step.x; y += step.y; z += step.z;
			if (step.y <= 0 && y <= bridgeY + 1.02) { ticks++; break; }
			double effectiveGravity = step.y <= 0 && mc.player.hasEffect(MobEffects.SLOW_FALLING) ? Math.min(gravity, .01) : gravity;
			vy = (step.y - effectiveGravity) * .98;
			if (mc.player.hasEffect(MobEffects.LEVITATION)) {
				var levitation = mc.player.getEffect(MobEffects.LEVITATION);
				vy = (step.y + (.05 * (levitation.getAmplifier() + 1) - step.y) * .2) * .98;
			}
			vx = step.x * .91; vz = step.z * .91;
		}
		return new LandingForecast(new Vec3(x, bridgeY + 1, z), ticks);
	}
	private boolean landingCovered() {
		Vec3 position = mc.player.position(), landing = predictedLanding(null).position();
		return stableAtBridge(position) && coveredRunway(position, landing);
	}
	private boolean coveredRunway(Vec3 from, Vec3 to) {
		if (!supportedAtBridge(from) || !stableAtBridge(to)) return false;
		double length = to.subtract(from).horizontalDistance();
		int samples = Math.max(1, (int) Math.ceil(length / .35));
		for (int i = 1; i < samples; i++)
			if (!supportedAtBridge(from.lerp(to, i / (double) samples))) return false;
		return true;
	}
	private void buildTelly(Vec3 actualMotion) {
		if (!cameraAvailable() || launchPosition == null || launchAnchor == null
				|| (mc.player.onGround() && (actualMotion == null || actualMotion.y <= 0)) || !materialReady()) return;
		boolean backwardReady = tellyPhase == TellyPhase.PLACE || tellyPhase == TellyPhase.TURN_BACK
				&& Math.abs(Mth.wrapDegrees(turnYaw - (forwardAimYaw() + 180))) <= 36;
		boolean landingEmergency = mc.player.getDeltaMovement().y <= 0 && predictedLanding(actualMotion).ticks() <= 3;
		if (!backwardReady && !landingEmergency) return;
		if (placed >= placementLimit() || attempts >= attemptLimit()) return;
		Vec3 position = mc.player.position(), landing = predictedLanding(actualMotion).position();
		// Start at the last attached bridge block, rather than only the landing cell: every new block
		// must be attached to a real neighbour, including all diagonal connecting cells.
		Vec3 from = Vec3.atCenterOf(launchAnchor), destination = landing;
		Vec3 horizontal = destination.subtract(from).multiply(1, 0, 1);
		double length = Math.min(7, horizontal.horizontalDistance());
		if (length < .01) return;
		Vec3 direction = horizontal.normalize();
		Set<BlockPos> candidates = new LinkedHashSet<>();
		BlockPos previous = launchAnchor;
		int samples = Math.max(1, (int) Math.ceil(length / .3));
		for (int i = 1; i <= samples; i++) {
			Vec3 point = from.add(direction.scale(length * i / samples));
			BlockPos at = BlockPos.containing(point.x, bridgeY, point.z);
			if (at.getX() != previous.getX() && at.getZ() != previous.getZ()) {
				candidates.add(new BlockPos(at.getX(), bridgeY, previous.getZ()));
				candidates.add(new BlockPos(previous.getX(), bridgeY, at.getZ()));
			}
			candidates.add(at); previous = at;
		}
		for (BlockPos target : candidates) {
			if (placed >= placementLimit() || attempts >= attemptLimit()) break;
			if (!mc.level.hasChunkAt(target) || !mc.level.getWorldBorder().isWithinBounds(target)
					|| !mc.level.getBlockState(target).canBeReplaced()) continue;
			MaterialSelection material = buildingMaterial();
			if (material == null) break;
			if (placeWithMaterial(target, material)) {
				placed++;
				if (mc.hasSingleplayerServer()) { cyclePlaced = true; lastCycleSupport = target; }
			}
		}
		// The prediction is a goal, not an attachment claim. Fill the current/next physical step
		// as well when a changed key direction makes the old launch-to-landing line miss the feet.
		for (Vec3 point : new Vec3[] { position, actualMotion == null ? position : position.add(capHorizontal(actualMotion, tellyStepLimit())) }) {
			if (placed >= placementLimit() || attempts >= attemptLimit()) break;
			BlockPos target = BlockPos.containing(point.x, bridgeY, point.z);
			if (!mc.level.hasChunkAt(target) || !mc.level.getWorldBorder().isWithinBounds(target)
					|| !mc.level.getBlockState(target).canBeReplaced()) continue;
			MaterialSelection material = buildingMaterial();
			if (material == null) break;
			if (placeWithMaterial(target, material)) {
				placed++;
				if (mc.hasSingleplayerServer()) { cyclePlaced = true; lastCycleSupport = target; }
			}
		}
	}

	private Vec3 movementDirection() {
		boolean owned = AiController.isRunning() || Freecam.isActive();
		double forward = owned ? mc.player.input.forwardImpulse : (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
		double strafe = owned ? mc.player.input.leftImpulse : (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
		if (forward != 0 || strafe != 0) {
			double yaw = Math.toRadians(!owned && rotation.is("follow") && yawTracked ? travelYaw : mc.player.getYRot());
			return new Vec3(strafe * Math.cos(yaw) - forward * Math.sin(yaw), 0,
					forward * Math.cos(yaw) + strafe * Math.sin(yaw)).normalize();
		}
		Vec3 velocity = mc.player.getDeltaMovement();
		return new Vec3(velocity.x, 0, velocity.z).normalize();
	}

	private MaterialSelection buildingMaterial() {
		if (isPlaceable(mc.player.getMainHandItem())) return new MaterialSelection(InteractionHand.MAIN_HAND, -1);
		if (isPlaceable(mc.player.getOffhandItem())) return new MaterialSelection(InteractionHand.OFF_HAND, -1);
		if (!keepHeldItem.on() && !autoSwitch.on()) return null;
		int slot = InvUtil.findHotbar(this::isPlaceable);
		if (slot < 0) return null;
		// Selection remains a plan until a real useItemOn dispatch can consume this stack.
		return new MaterialSelection(InteractionHand.MAIN_HAND, slot);
	}

	/** Borrow only for a real dispatch; keepHeldItem controls restoration after a successful placement. */
	private boolean placeWithMaterial(BlockPos target, MaterialSelection material) {
		var player = mc.player;
		var gameMode = mc.gameMode;
		int originalSlot = player.getInventory().selected;
		boolean preserve = keepHeldItem.on();
		boolean planning = !mc.hasSingleplayerServer() && !remotePlacementPhase;
		if (planning) {
			ItemStack planned = material.temporarySlot() >= 0 ? InvUtil.stack(material.temporarySlot()) : player.getItemInHand(material.hand());
			return isPlaceable(planned) && place(target, material.hand(), planned, true);
		}
		int attemptsBefore = attempts;
		boolean success = false;
		try {
			if (material.temporarySlot() >= 0) {
				player.getInventory().selected = material.temporarySlot();
			}
			// Never dispatch the held sword, shield or food as a building interaction.
			if (!isPlaceable(player.getItemInHand(material.hand()))) return false;
			success = place(target, material.hand(), player.getItemInHand(material.hand()), false);
			// Send the animation while this stack is still selected, using the hand that placed.
			if (success && swing.on()) player.swing(material.hand());
			return success;
		} finally {
			if ((preserve || !success) && mc.player == player && mc.gameMode == gameMode) {
				player.getInventory().selected = originalSlot;
				if (attempts != attemptsBefore && mc.getConnection() != null)
					((MultiPlayerGameModeAccessor) gameMode).cheatprogram$ensureHasSentCarriedItem();
			}
		}
	}

	private boolean place(BlockPos target, InteractionHand hand, ItemStack stack, boolean planning) {
		if (rejectedTargets.contains(target)) return false;
		BlockState material = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
		if (!material.canSurvive(mc.level, target)
				|| !mc.level.isUnobstructed(material, target, CollisionContext.of(mc.player))) return false;
		for (Direction side : SIDES) {
			if (attempts >= attemptLimit()) return false;
			BlockPos neighbor = target.relative(side);
			BlockState support = mc.level.getBlockState(neighbor);
			Direction face = side.getOpposite();
			if (unconfirmed(neighbor) || support.canBeReplaced() || !support.isFaceSturdy(mc.level, neighbor, face)
					|| support.hasBlockEntity() || support.getMenuProvider(mc.level, neighbor) != null
					|| support.getBlock() instanceof NoteBlock || support.getBlock() instanceof RespawnAnchorBlock) continue;
			Vec3 hit = placementHit(neighbor, face);
			if (hit == null) continue;
			if (planning) {
				if (rotation.is("follow") && !telly()) {
					Vec3 delta = hit.subtract(mc.player.getEyePosition());
					turnCamera((float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90,
							(float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))), 18, 10);
				}
				ScaffoldRotations.aimAt(hit, 60);
				return false;
			}
			BlockHitResult result = new BlockHitResult(hit, face, neighbor, false);
			if (rotation.is("silent") && !telly()) ScaffoldRotations.aimAt(hit, 60);
			// Telly's state machine already steers the visible follow camera. Do not apply a second,
			// unrelated easing from InteractionLook while it is turning back or recovering forward.
			java.util.function.Supplier<InteractionResult> placement = () -> {
				BlockPlaceContext context = new BlockPlaceContext(mc.player, hand, stack, result);
				if (!context.canPlace() || !context.getClickedPos().equals(target)) return InteractionResult.FAIL;
				attempts++;
				dispatchingTarget = target.immutable(); dispatchingMaterial = material.getBlock();
				try {
					if (!mc.hasSingleplayerServer()) lastRemotePlacementTick = mc.player.tickCount;
					return mc.gameMode.useItemOn(mc.player, hand, result);
				} finally { dispatchingTarget = null; dispatchingMaterial = null; }
			};
			String lookMode = telly() ? "silent" : rotation.get();
			InteractionResult action = mc.hasSingleplayerServer()
					? InteractionLook.interact(hit, lookMode, placement)
					: InteractionLook.interactPublished("scaffold", placement, InteractionResult.FAIL);
			// Activating a support can consume an action too: only an actual foothold counts.
			BlockState after = mc.level.getBlockState(target);
			if (action.consumesAction() && after.is(material.getBlock()) && after.isCollisionShapeFullBlock(mc.level, target)) {
				supportShapes.remove(target);
				blockCountAge = -1;
				// A new neighbouring face can make a previous rejected target placeable in this tick.
				rejectedTargets.remove(target);
				for (Direction adjacent : SIDES) {
					BlockPos changed = target.relative(adjacent);
					rejectedTargets.remove(changed);
					supportShapes.remove(changed);
				}
				if (rotation.is("silent") && !telly()) ScaffoldRotations.placed(hit);
				return true;
			}
			// Retry another face now, but never spam the same failed cell at all three build hooks.
			// Missing/invisible support is deliberately not cached: post-move may expose its side.
			rejectedTargets.add(target);
			if (!mc.hasSingleplayerServer() && serverActions.pending()) return false;
		}
		return false;
	}

	private Vec3 placementHit(BlockPos neighbor, Direction face) {
		double reach = mc.hasSingleplayerServer() ? mc.player.blockInteractionRange()
				: mc.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		return !mc.hasSingleplayerServer() && remotePlacementPhase
				? ScaffoldRotations.publishedFacePoint(neighbor, face, reach)
				: ScaffoldRotations.visibleFacePoint(neighbor, face, reach);
	}

	private boolean isPlaceable(ItemStack stack) {
		if (!inGame() || stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return false;
		Boolean cached = safeMaterials.get(item);
		if (cached != null) return cached;
		var block = item.getBlock();
		BlockState state = block.defaultBlockState();
		boolean safe = !(block instanceof FallingBlock) && !(block instanceof NoteBlock) && !(block instanceof RespawnAnchorBlock)
				&& !state.hasBlockEntity() && block.isEnabled(mc.level.enabledFeatures())
				&& state.isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)
				&& state.getMenuProvider(mc.level, BlockPos.ZERO) == null;
		safeMaterials.put(item, safe);
		return safe;
	}
}
