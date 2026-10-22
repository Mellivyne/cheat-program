package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Rejects proven knockback immunity, without treating hidden or late server feedback as proof. */
public final class AuraKnockbackFilter {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Set<LivingEntity> REJECTED = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final Map<LivingEntity, PositionSample> POSITIONS = new IdentityHashMap<>();
	private static final ThreadLocal<ServerProbe> SERVER_ATTACK = new ThreadLocal<>();
	private static ClientLevel world;
	private static ClientPacketListener connection;
	private static LocalPlayer owner;
	private static boolean enabled;
	// The server thread reads only this token's immutable identity fields.
	private static volatile Attempt pending;
	private static int pruneTick;

	private AuraKnockbackFilter() { }
	private record PositionSample(Vec3 position, Vec3 velocity, long timeNanos) { }

	public static final class Attempt {
		private final LivingEntity victim;
		private final int entityId;
		private final UUID entityUuid, ownerUuid;
		private final ResourceKey<Level> dimension;
		private final MinecraftServer server;
		private final Vec3 velocity, position, baselineMotion, awayDirection;
		private final long startedNanos;
		private final long timeoutNanos;
		// Client-thread-only feedback and deadlines.
		private long deadlineNanos;
		private boolean committed, ownerHurtConfirmed, motionConfirmed, positionConfirmed;
		private Boolean serverVerdict;

		private Attempt(LivingEntity victim, long timeoutMillis) {
			this.victim = victim;
			entityId = victim.getId();
			entityUuid = victim.getUUID();
			ownerUuid = owner.getUUID();
			dimension = world.dimension();
			server = MC.getSingleplayerServer();
			velocity = victim.getDeltaMovement();
			position = victim.getPositionCodec().getBase();
			startedNanos = System.nanoTime();
			PositionSample sample = POSITIONS.get(victim);
			baselineMotion = sample != null && startedNanos - sample.timeNanos < 350_000_000L ? sample.velocity : Vec3.ZERO;
			awayDirection = position.subtract(owner.position()).multiply(1, 0, 1).normalize();
			timeoutNanos = timeoutMillis * 1_000_000L;
			deadlineNanos = System.nanoTime() + timeoutNanos;
		}
	}

	/** Lives only on the integrated-server thread, around the exact owner's Player.attack call. */
	public static final class ServerProbe {
		private final Attempt attempt;
		private final LivingEntity victim;
		private final Vec3 velocity;
		private final boolean temporaryHitImmunity;
		private boolean impulse;

		private ServerProbe(Attempt attempt, LivingEntity victim) {
			this.attempt = attempt;
			this.victim = victim;
			velocity = victim.getDeltaMovement();
			temporaryHitImmunity = victim.invulnerableTime > 10;
		}
	}

	public static void configure(boolean value) {
		if (enabled != value) { clear(); enabled = value; }
		tickLifecycle();
	}

	public static void tickLifecycle() {
		if (world != MC.level || connection != MC.getConnection() || owner != MC.player
				|| owner != null && (!owner.isAlive() || owner.isDeadOrDying())) {
			clear(); world = MC.level; connection = MC.getConnection(); owner = MC.player;
		}
		if (!enabled || world == null || owner == null || connection == null) return;
		if (owner.tickCount != pruneTick && owner.tickCount % 20 == 0) {
			pruneTick = owner.tickCount;
			REJECTED.removeIf(entity -> entity.level() != world || entity.isRemoved()
					|| world.getEntity(entity.getId()) != entity);
			POSITIONS.keySet().removeIf(entity -> entity.level() != world || entity.isRemoved()
					|| world.getEntity(entity.getId()) != entity);
		}
		Attempt attempt = pending;
		if (attempt == null) return;
		if (attempt.victim.level() != world || attempt.victim.isRemoved()
				|| world.getEntity(attempt.entityId) != attempt.victim || !attempt.victim.isAlive()) {
			pending = null; return;
		}
		if (attempt.server != null && MC.isPaused()) {
			attempt.deadlineNanos = System.nanoTime() + attempt.timeoutNanos; return;
		}
		if (!attempt.committed) return;
		if (attempt.serverVerdict != null) finish(attempt, attempt.serverVerdict);
		else if (attempt.server == null && attempt.ownerHurtConfirmed && (attempt.motionConfirmed || attempt.positionConfirmed)) finish(attempt, true);
		else if (System.nanoTime() >= attempt.deadlineNanos) {
			// Other players' impulses are not always sent to this client. No packet, no inferred
			// zero knockback: release the token and keep the normal cooldown/reach/raycast rules.
			pending = null;
		}
	}

	public static void clear() {
		pending = null; REJECTED.clear(); POSITIONS.clear();
		world = null; connection = null; owner = null; pruneTick = Integer.MIN_VALUE;
	}

	public static boolean accepts(LivingEntity entity) {
		if (!enabled) return true;
		if (entity == null || REJECTED.contains(entity) || entity.isInvulnerable()
				|| entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) >= 1.0) return false;
		if (entity instanceof Player player) {
			if (player.isSpectator() || player.isCreative() || player.getAbilities().invulnerable) return false;
			var info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
			if (info != null && (info.getGameMode() == GameType.CREATIVE || info.getGameMode() == GameType.SPECTATOR)) return false;
		}
		return true;
	}

	/** Do not test immunity during the temporary window created by the previous real hit. */
	public static boolean canAttackNow(LivingEntity victim) {
		return !enabled || pending == null && victim != null && victim.hurtTime <= 0 && victim.invulnerableTime <= 10;
	}

	public static Attempt begin(LivingEntity entity) {
		if (!enabled || !canAttackNow(entity) || world == null || owner == null || connection == null || !accepts(entity)) return null;
		var info = connection.getPlayerInfo(owner.getUUID());
		long latency = info == null ? 0 : Math.max(0, info.getLatency());
		Attempt attempt = new Attempt(entity, Math.max(450, Math.min(3000, latency * 2 + 350)));
		pending = attempt;
		return attempt;
	}

	/** TAIL confirms the packet was issued, including normal cancellation hooks. */
	public static void commit(Attempt attempt) {
		if (attempt != null && pending == attempt) { attempt.committed = true; tickLifecycle(); }
	}

	public static void cancel(Attempt attempt) {
		if (attempt != null && pending == attempt) pending = null;
	}

	private static void finish(Attempt attempt, boolean knockedBack) {
		if (pending != attempt) return;
		if (!knockedBack) REJECTED.add(attempt.victim);
		pending = null;
	}

	private static Attempt remoteAttempt(ClientPacketListener listener, int entityId) {
		Attempt attempt = pending;
		return attempt != null && attempt.server == null && listener == connection && owner != null
				&& entityId == attempt.entityId && world != null && MC.level == world
				&& world.getEntity(entityId) == attempt.victim ? attempt : null;
	}

	/** A damage event is attribution only. Damage, a hurt animation or ordinary walking is not knockback. */
	public static void receivedDamageEvent(ClientPacketListener listener, ClientboundDamageEventPacket packet) {
		Attempt attempt = remoteAttempt(listener, packet.entityId());
		if (attempt == null || packet.sourceCauseId() != owner.getId() || packet.sourceDirectId() != owner.getId()) return;
		attempt.ownerHurtConfirmed = true;
		tickLifecycle();
	}

	/** Observe the actual server velocity packet, independently of local Velocity module changes. */
	public static void receivedMotion(ClientPacketListener listener, ClientboundSetEntityMotionPacket packet) {
		Attempt attempt = remoteAttempt(listener, packet.getId());
		if (attempt == null) return;
		Vec3 motion = new Vec3(packet.getXa(), packet.getYa(), packet.getZa());
		if (finite(motion) && motion.horizontalDistanceSqr() > 1.0E-8 && motion.distanceToSqr(attempt.velocity) > 1.0E-8)
			attempt.motionConfirmed = true;
		tickLifecycle();
	}

	/** Track packet-decoded positions, not render interpolation or the client's simulated movement. */
	public static void receivedPosition(ClientPacketListener listener, Entity entity, boolean continuous) {
		if (!enabled || listener != connection || world == null || MC.level != world
				|| !(entity instanceof LivingEntity living) || world.getEntity(entity.getId()) != entity) return;
		Vec3 position = entity.getPositionCodec().getBase();
		if (!finite(position)) return;
		long now = System.nanoTime();
		PositionSample previous = POSITIONS.get(living);
		Vec3 velocity = Vec3.ZERO;
		if (continuous && previous != null) {
			double seconds = (now - previous.timeNanos) / 1.0E9;
			if (seconds >= .025 && seconds <= .5 && position.distanceToSqr(previous.position) <= 16)
				velocity = position.subtract(previous.position).scale(1 / seconds);
		}
		POSITIONS.put(living, new PositionSample(position, velocity, now));
		Attempt attempt = remoteAttempt(listener, entity.getId());
		if (attempt == null || !continuous) return;
		Vec3 displacement = position.subtract(attempt.position);
		double seconds = Math.max(0, (now - attempt.startedNanos) / 1.0E9);
		// Observers normally receive a player's changed position, while the impulse packet
		// goes only to that player. Require our attributed hit and movement exceeding the
		// pre-hit walking trend; position changes alone never acknowledge a melee attack.
		double expectedAway = Math.max(0, attempt.baselineMotion.dot(attempt.awayDirection)) * seconds;
		double expectedRise = Math.max(0, attempt.baselineMotion.y) * seconds;
		if (displacement.lengthSqr() <= 16 && (displacement.dot(attempt.awayDirection) > expectedAway + .04
				|| displacement.y > expectedRise + .06)) attempt.positionConfirmed = true;
		tickLifecycle();
	}

	public static ServerProbe beginServerAttack(Player attacker, Entity target) {
		Attempt attempt = pending;
		if (attempt == null || attempt.server == null || !(attacker instanceof ServerPlayer serverPlayer)
				|| !(target instanceof LivingEntity living) || serverPlayer.getServer() != attempt.server
				|| !attacker.getUUID().equals(attempt.ownerUuid) || target.getId() != attempt.entityId
				|| !target.getUUID().equals(attempt.entityUuid) || !target.level().dimension().equals(attempt.dimension)) return null;
		return new ServerProbe(attempt, living);
	}

	/** Suspend an outer attack's attribution while any nested unrelated Player.attack runs. */
	public static ServerProbe enterServerAttack(ServerProbe probe) {
		ServerProbe previous = SERVER_ATTACK.get();
		if (probe == null) SERVER_ATTACK.remove(); else SERVER_ATTACK.set(probe);
		return previous;
	}

	public static ServerProbe beginKnockback(LivingEntity victim) {
		ServerProbe probe = SERVER_ATTACK.get();
		return probe != null && probe.victim == victim ? probe : null;
	}

	/** Runs before Player.attack can send a ServerPlayer's velocity and restore its previous value. */
	public static void observedKnockback(ServerProbe probe, Vec3 before, Vec3 after) {
		if (probe != null && finite(before) && finite(after) && after.distanceToSqr(before) > 1.0E-12) probe.impulse = true;
	}

	public static void finishServerAttack(ServerProbe probe, ServerProbe previous, boolean completed) {
		if (previous == null) SERVER_ATTACK.remove(); else SERVER_ATTACK.set(previous);
		if (probe == null) return;
		Vec3 after = probe.victim.getDeltaMovement();
		boolean knockedBack = probe.impulse || finite(after) && after.distanceToSqr(probe.velocity) > 1.0E-12;
		MC.execute(() -> {
			if (pending != probe.attempt) return;
			if (!completed || !knockedBack && probe.temporaryHitImmunity) pending = null;
			else { probe.attempt.serverVerdict = knockedBack; tickLifecycle(); }
		});
	}

	private static boolean finite(Vec3 vector) {
		return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
	}
}
