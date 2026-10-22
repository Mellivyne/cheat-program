package com.cheatprogram.module.combat;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.module.utility.Blink;
import com.cheatprogram.util.AntiBot;
import com.cheatprogram.util.AuraKnockbackFilter;
import com.cheatprogram.util.AuraRotations;
import com.cheatprogram.util.Clock;
import com.cheatprogram.util.ServerActionGate;
import com.cheatprogram.util.Targets;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;

public class KillAura extends Module {
	private final NumberSetting range = add(new NumberSetting("range", 3.0, 2.0, 6.0, 0.1));
	private final NumberSetting searchRange = add(new NumberSetting("searchRange", 16, 16, 64, 1));
	private final BoolSetting cooldown = add(new BoolSetting("cooldown", true));
	private final RangeSetting cps = add(new RangeSetting("cps", 8, 12, 1, 20, 1, "")).visibleWhen(() -> !cooldown().on());
	private final ModeSetting priority = add(new ModeSetting("priority", "distance", "distance", "health", "angle"));
	private final NumberSetting fov = add(new NumberSetting("fov", 360, 30, 360, 10, "°"));
	private final BoolSetting antiBot = add(new BoolSetting("antiBot", true));
	private final BoolSetting antiBotNoKnockback = add(new BoolSetting("antiBotNoKnockback", true)).visibleWhen(() -> antiBot.on());
	private final ModeSetting attackStyle = add(new ModeSetting("attackStyle", "aura_normal", "aura_normal", "aura_spin"));
	private final ModeSetting turnSide = add(new ModeSetting("turnSide", "aura_both", "aura_left", "aura_both", "aura_right"))
			.visibleWhen(() -> attackStyle.is("aura_spin"));
	private final RangeSetting turnAngle = add(new RangeSetting("turnAngle", 120, 180, 0, 360, 1, "°"))
			.visibleWhen(() -> attackStyle.is("aura_spin"));
	private final NumberSetting turnSpeed = add(new NumberSetting("turnSpeed", 1440, 720, 7200, 30, "°/s"))
			.visibleWhen(() -> attackStyle.is("aura_spin"));
	// Retained for loading older profiles; Config migrates it to the explicit rotation mode.
	private final BoolSetting rotate = add(new BoolSetting("rotate", false)).visibleWhen(() -> false);
	private final ModeSetting rotation = add(new ModeSetting("rotation", "silent", "silent", "follow"));
	private final NumberSetting rotationSpeed = add(new NumberSetting("rotationSpeed", 540, 90, 1440, 10, "°/s"));
	private final BoolSetting throughWalls = add(new BoolSetting("throughWalls", false));
	private final BoolSetting swing = add(new BoolSetting("swing", true));
	private final BoolSetting airSwing = add(new BoolSetting("airSwing", false));
	private final Clock clock = new Clock();
	private final ServerActionGate serverActions = new ServerActionGate();
	private net.minecraft.client.player.LocalPlayer serverActionPlayer;
	private net.minecraft.client.multiplayer.ClientLevel serverActionLevel;
	private net.minecraft.client.multiplayer.ClientPacketListener serverActionConnection;
	private LivingEntity pendingAttack;
	private boolean attackCompleted;
	private boolean manualAttackThisTick;
	private int lastPostTick = Integer.MIN_VALUE;
	private int lastTargetScanTick = Integer.MIN_VALUE;
	private int actionTick = Integer.MIN_VALUE;
	private net.minecraft.client.player.LocalPlayer actionOwner;
	private net.minecraft.client.multiplayer.ClientLevel actionWorld;
	private net.minecraft.client.multiplayer.ClientPacketListener actionConnection;
	private LivingEntity turnTarget;
	private int turnStartTick, turnDurationTicks;
	private float turnDegrees;
	private String turnViewMode;

	/** Current eligible target, also shown by the target HUD. */
	public static LivingEntity target;

	public KillAura() {
		super("killaura", Category.COMBAT, GLFW.GLFW_KEY_R);
	}

	private BoolSetting cooldown() {
		return cooldown;
	}

	@Override
	public String suffix() {
		return attackStyle.is("aura_spin") ? attackStyle.display() + ", " + priority.display() : priority.display();
	}

	@Override
	public void onEnable() {
		clearActionClaim();
		manualAttackThisTick = false;
		lastPostTick = Integer.MIN_VALUE;
		synchronizeServerContext();
		AuraKnockbackFilter.clear();
		HundredMeterAura extended = ModuleManager.get(HundredMeterAura.class);
		if (extended != null && extended.isEnabled()) extended.setEnabled(false);
		clearTarget();
		clock.reset(0);
	}

	@Override
	public void onDisable() {
		clearActionClaim();
		AuraKnockbackFilter.configure(false);
		clearTarget();
	}

	/** Called after vanilla accepts and acknowledges an authoritative server position. */
	public static void onServerCorrection() {
		AuraRotations.onServerCorrection();
		KillAura module = ModuleManager.get(KillAura.class);
		if (module == null || !module.isEnabled()) return;
		module.serverActions.corrected(System.nanoTime());
		module.clearActionClaim();
		module.clearTarget();
		module.clock.reset(500);
		AuraKnockbackFilter.clear();
	}

	private void synchronizeServerContext() {
		if (serverActionPlayer == mc.player && serverActionLevel == mc.level && serverActionConnection == mc.getConnection()) return;
		serverActions.reset(); serverActionPlayer = mc.player; serverActionLevel = mc.level; serverActionConnection = mc.getConnection();
	}

	private void clearTarget() {
		target = null;
		pendingAttack = null;
		attackCompleted = false;
		lastTargetScanTick = Integer.MIN_VALUE;
		clearTurn();
		AuraRotations.clear();
	}

	private boolean acceptsTarget(LivingEntity entity) {
		return entity != null && (!(entity instanceof Player player) || !antiBot.on() || !AntiBot.isBot(player))
				&& (!antiBot.on() || !antiBotNoKnockback.on() || AuraKnockbackFilter.accepts(entity));
	}

	private void clearTurn() {
		AuraRotations.cancelYawOffset();
		turnTarget = null;
		turnStartTick = turnDurationTicks = 0;
		turnDegrees = 0;
		turnViewMode = null;
	}

	private void beginTurn(LivingEntity victim) {
		if (!attackStyle.is("aura_spin")) return;
		float angle = (float) turnAngle.random();
		if (angle < .01f) return;
		turnTarget = victim;
		turnStartTick = mc.player.tickCount;
		// Two smoothstep halves have a maximum angular speed of 3 * angle / duration.
		// A full return must finish before another attack; low speeds may extend the cycle.
		// Equal whole-tick halves reach the selected angle exactly, including a full 360-degree turn.
		turnDurationTicks = 2 * (int) Math.ceil(Math.max(2, 30 * angle / turnSpeed.val()));
		int direction = turnSide.is("aura_left") ? -1 : turnSide.is("aura_right") ? 1
				: ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
		turnDegrees = angle * direction;
		turnViewMode = rotation.get();
	}

	private boolean canFinishTurn(double reach) {
		// Knockback may move the victim outside attack range. Finish the visual return without
		// granting any extra attack reach, and ignore only the FOV changed by our own turn.
		return attackStyle.is("aura_spin") && AuraRotations.active() && turnTarget == target
				&& rotation.get().equals(turnViewMode) && acceptsTarget(turnTarget)
				&& Targets.isValid(turnTarget) && mc.level.getEntity(turnTarget.getId()) == turnTarget
				&& Targets.distance(turnTarget) <= reach + 4;
	}

	private float turnOffset() {
		float phase = Mth.clamp((mc.player.tickCount - turnStartTick) / (float) turnDurationTicks, 0, 1);
		float t = phase <= .5f ? phase * 2 : (1 - phase) * 2;
		return turnDegrees * t * t * (3 - 2 * t);
	}

	private boolean canAct() {
		HundredMeterAura extended = ModuleManager.get(HundredMeterAura.class);
		return (extended == null || !extended.isEnabled()) && mc.player != null && mc.level != null && mc.gameMode != null && mc.getConnection() != null
				&& serverActions.ready(System.nanoTime())
				&& mc.gui.screen() == null && mc.gui.overlay() == null && mc.isWindowActive() && !mc.isPaused()
				&& mc.player.isAlive() && !mc.player.isDeadOrDying() && !mc.player.isSpectator()
				&& !mc.player.isRemoved() && !mc.player.isSleeping() && !mc.player.isUsingItem()
				&& !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown() && !manualAttackThisTick
				&& !mc.gameMode.isDestroying() && !AiController.isRunning() && !Freecam.isActive() && !FlatFlight.usesVanillaGlide()
				&& !ModuleManager.on(Blink.class);
	}

	private double attackRange() {
		// Reach patches both local/server twins in single player, but cannot extend a remote server's range.
		double serverRange = mc.hasSingleplayerServer() ? mc.player.entityInteractionRange()
				: mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		return Math.max(0, Math.min(range.val(), serverRange));
	}

	private boolean permitsWalls() { return mc.hasSingleplayerServer() && throughWalls.on(); }

	/** Keep a small motion lead inside the current, actual hitbox instead of aiming ahead of it. */
	private Vec3 aimPoint(LivingEntity entity) {
		Vec3 point = Targets.aimPoint(entity);
		Vec3 velocity = entity.getDeltaMovement();
		AABB box = entity.getBoundingBox();
		double insetX = Math.min(.03, box.getXsize() * .1);
		double insetY = Math.min(.03, box.getYsize() * .1);
		double insetZ = Math.min(.03, box.getZsize() * .1);
		return new Vec3(
				Mth.clamp(point.x + Mth.clamp(velocity.x * .5, -.12, .12), box.minX + insetX, box.maxX - insetX),
				Mth.clamp(point.y + Mth.clamp(velocity.y * .25, -.06, .06), box.minY + insetY, box.maxY - insetY),
				Mth.clamp(point.z + Mth.clamp(velocity.z * .5, -.12, .12), box.minZ + insetZ, box.maxZ - insetZ));
	}

	private boolean attackReady() {
		return cooldown.on() ? mc.player.getAttackStrengthScale(0f) >= 1f : clock.ready();
	}

	private void swingHand() {
		if (swing.on()) mc.player.swing(InteractionHand.MAIN_HAND);
		else mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
	}

	private void consumeActionDelay() {
		clock.reset(Clock.cpsDelay(cps.random()));
	}
	private void markAction(){actionTick=mc.player.tickCount;actionOwner=mc.player;actionWorld=mc.level;actionConnection=mc.getConnection();}
	private void clearActionClaim(){actionTick=Integer.MIN_VALUE;actionOwner=null;actionWorld=null;actionConnection=null;}
	/** Combat gets one action phase; distant tracking alone never blocks building. */
	public static boolean ownsNetworkRotation() { return AuraRotations.requestsNetwork() || com.cheatprogram.util.InteractionLook.ownsNetwork("aura"); }
	public static boolean actedThisTick(){KillAura aura=ModuleManager.get(KillAura.class);return aura!=null&&aura.isEnabled()&&mc.player!=null&&aura.actionOwner==mc.player&&aura.actionWorld==mc.level&&aura.actionConnection==mc.getConnection()&&(aura.actionTick==mc.player.tickCount||aura.manualAttackThisTick);}

	@Override
	public void onTick() {
		synchronizeServerContext();
		clearActionClaim();
		manualAttackThisTick = false;
		lastPostTick = Integer.MIN_VALUE;
		AuraKnockbackFilter.configure(antiBot.on() && antiBotNoKnockback.on());
		if (!canAct()) clearTarget();
		else prepareAim();
	}

	private void prepareAim() {
		double reach = attackRange();
		if (turnTarget != null && !canFinishTurn(reach)) clearTurn();
		boolean turning = turnTarget != null;
		// Keep a nearby victim stable, but do not let a distant search target monopolize the lock.
		if (!turning && (!acceptsTarget(target) || !Targets.canAttackTarget(target, reach, fov.val(), permitsWalls()))) {
			if (!acceptsTarget(target) || !Targets.canAttackTarget(target, searchRange.val(), fov.val(), permitsWalls())) target = null;
			int tick = mc.player.tickCount;
			// Empty/wide searches are bounded to 5 Hz; an existing lock is still checked each tick.
			if (lastTargetScanTick == Integer.MIN_VALUE || tick - lastTargetScanTick >= 4 || tick < lastTargetScanTick) {
				lastTargetScanTick = tick;
				LivingEntity nearby = Targets.bestAttackTarget(reach, fov.val(), priority.get(), permitsWalls(), this::acceptsTarget);
				if (nearby != null) target = nearby;
				else if (target == null) target = Targets.bestAttackTarget(searchRange.val(), fov.val(), priority.get(), permitsWalls(), this::acceptsTarget);
			}
		}
		if (target == null) {
			clearTurn();
			AuraRotations.clear();
			return;
		}
		if (!AutoWeapon.prepareTarget(target)) {
			if (AutoWeapon.manualOverrideActive()) { clearTurn(); AuraRotations.clear(); }
			return;
		}
		// Aim independently of the cooldown so moving targets are followed without an attack-time snap.
		AuraRotations.update(aimPoint(target), rotationSpeed.val(), rotation.is("silent"), turning ? turnOffset() : 0);
		AuraRotations.requestNetwork(Targets.canAttackTarget(target, reach, fov.val(), permitsWalls()));
		if (turning && mc.player.tickCount - turnStartTick >= turnDurationTicks) clearTurn();
	}

	@Override
	public void onPostTick() {
		// Vanilla movement has now sent this tick's real position. Automatic combat needs only
		// its action direction, and must yield to any manual attack performed during the tick.
		if (!canAct()) {
			clearTarget();
			return;
		}
		if (lastPostTick == mc.player.tickCount) return;
		lastPostTick = mc.player.tickCount;
		double reach = attackRange();
		if (target == null || turnTarget != null) return;
		if (!AuraKnockbackFilter.canAttackNow(target) || !attackReady() || (attackStyle.is("aura_spin") && target.hurtTime > 0)
				|| !Targets.canAttackTarget(target, reach, fov.val(), permitsWalls()) || !mc.player.isWithinEntityInteractionRange(target, 0)) return;

		var player = mc.player;
		var level = mc.level;
		var gameMode = mc.gameMode;
		LivingEntity victim = target;
		// Revalidate immediately before committing; stale or disallowed contexts never get a swing.
		if (!canAct() || mc.player != player || mc.level != level || mc.gameMode != gameMode || target != victim
				|| !acceptsTarget(victim) || !AuraKnockbackFilter.canAttackNow(victim)
				|| !Targets.canAttackTarget(victim, attackRange(), fov.val(), permitsWalls())
				|| !player.isWithinEntityInteractionRange(victim, 0) || !attackReady()
				|| (attackStyle.is("aura_spin") && victim.hurtTime > 0)) return;
		if (!AuraRotations.rayHits(victim, attackRange(), permitsWalls())) {
			// A bounded missed swing is an animation only. Never route it through attack(Entity),
			// and consume vanilla weapon cooldown as a normal empty click does.
			if (airSwing.on() && AuraRotations.active()) {
				swingHand();
				markAction();
				player.resetAttackStrengthTicker();
				consumeActionDelay();
				beginTurn(victim);
			}
			return;
		}

		AuraKnockbackFilter.Attempt knockbackAttempt = AuraKnockbackFilter.begin(victim);
		if (antiBot.on() && antiBotNoKnockback.on() && knockbackAttempt == null) return;
		pendingAttack = victim;
		attackCompleted = false;
		try {
			// Use the normal attack path exactly once: HitSelect can cancel it, Criticals and WTap keep their hooks.
			if (!AuraRotations.beforeAttack()) {
				AuraKnockbackFilter.cancel(knockbackAttempt);
				return;
			}
			markAction();
			gameMode.attack(player, pendingAttack);
		} catch (RuntimeException | Error failure) {
			AuraKnockbackFilter.cancel(knockbackAttempt);
			throw failure;
		} finally {
			pendingAttack = null;
			AuraRotations.afterAttack();
		}
		// A cancelled HEAD hook does not reach onPostAttack, so it must not cause a fake swing or consume the timer.
		if (!attackCompleted) {
			AuraKnockbackFilter.cancel(knockbackAttempt);
			return;
		}
		AuraKnockbackFilter.commit(knockbackAttempt);
		if (mc.player == player && mc.level == level && mc.getConnection() != null) {
			swingHand();
		}
		consumeActionDelay();
		if (mc.player == player && mc.level == level && canAct()) beginTurn(victim);
	}

	@Override
	public boolean onAttack(Entity attacked) {
		if (pendingAttack == null) {
			manualAttackThisTick = true;
			clearTarget();
			AuraRotations.beforeManualAttack();
		}
		return false;
	}

	@Override
	public void onPostAttack(Entity attacked) {
		if(mc.player!=null&&mc.level!=null)markAction();
		if (attacked == pendingAttack) attackCompleted = true;
	}
}
