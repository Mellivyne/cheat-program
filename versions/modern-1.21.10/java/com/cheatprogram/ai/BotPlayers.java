package com.cheatprogram.ai;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.Lang;
import com.cheatprogram.mixin.AiPlayerListAccessor;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Shared lifecycle only: ordinary and training robots have independent queues and management. */
public final class BotPlayers {
	public enum Group { NORMAL, TRAINING }
	private static final Minecraft MC = Minecraft.getInstance();
	private static volatile Session current;
	private static volatile View normalView = View.EMPTY, trainingView = View.EMPTY;
	private static volatile Map<UUID, String> displayNames = Map.of();
	public record Stats(long attempts, long hits, double dealt, long receivedHits, double received, long deaths) {
		public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0);
		public Stats plus(Stats other) { return new Stats(attempts + other.attempts, hits + other.hits, dealt + other.dealt,
				receivedHits + other.receivedHits, received + other.received, deaths + other.deaths); }
	}
	public record Training(boolean enabled, String behavior, String target, String kit, double radius, double speed, int interval,
			boolean cooldown, boolean shield, double shieldUptime, String shieldMode) {
		public static final Training NONE = new Training(false, "bot_duel", "bot_target_robots", "bot_kit_none", 2.3, .75, 12, true, false, .35, "bot_shield_smart");
		public Training {
			behavior = switch (behavior) { case "bot_strafe", "bot_guard" -> behavior; default -> "bot_duel"; };
			target = switch(target){case "bot_target_owner","bot_target_all"->target;default->"bot_target_robots";};
			kit = switch (kit) { case "bot_kit_none", "bot_kit_wood", "bot_kit_diamond", "bot_kit_netherite" -> kit; default -> "bot_kit_iron"; };
			radius = Math.max(1, Math.min(3, radius)); speed = Math.max(.1, Math.min(1, speed)); interval = Math.max(2, Math.min(100, interval));
			shieldUptime = Math.max(.1, Math.min(.8, shieldUptime));
			shieldMode = switch (shieldMode) { case "bot_shield_rhythm", "bot_shield_only" -> shieldMode; default -> "bot_shield_smart"; };
		}
		public boolean active() { return enabled; }
	}
	public record Row(UUID id, String name, String mode, boolean alive, String behavior, String activity, Stats stats) { }
	private record View(String status, String pending, int active, List<Row> rows, Stats stats) {
		static final View EMPTY = new View("ui.robotplayers.ready", "0", 0, List.of(), Stats.EMPTY);
	}
	private record Options(Group group, String prefix, String mode, boolean follow, double spacing, int rate, double distance,
			boolean respawn, boolean randomId, Training training) {
		Options withTraining(Training value) { return new Options(group, prefix, mode, follow, spacing, rate, distance, respawn, randomId, value); }
		Options withOrdinary(boolean follow, double distance) { return new Options(group, prefix, mode, follow, spacing, rate, distance, respawn, randomId, training); }
	}
	private record Actor(BotActor body, Options options) { }
	private record Respawn(UUID id, String name, Options options, long tick, Stats stats) { }
	private static final class Batch {
		BigInteger remaining; Options options;
		Batch(BigInteger count, Options options) { remaining = count; this.options = options; }
	}
	private static final class Cohort {
		final Group group;
		final Map<UUID, Actor> actors = new LinkedHashMap<>();
		final ArrayDeque<Batch> batches = new ArrayDeque<>();
		final ArrayDeque<Respawn> respawns = new ArrayDeque<>();
		BigInteger nextName = BigInteger.ONE;
		long retryAfter;
		Stats retired = Stats.EMPTY;
		String status = "ui.robotplayers.ready";
		boolean removing;
		Cohort(Group group) { this.group = group; }
	}
	private static final class Session {
		final MinecraftServer server; final UUID owner;
		final EnumMap<Group, Cohort> cohorts = new EnumMap<>(Group.class);
		long tick;
		volatile boolean valid = true;
		Session(MinecraftServer server, UUID owner) {
			this.server = server; this.owner = owner;
			for (Group group : Group.values()) cohorts.put(group, new Cohort(group));
		}
	}
	private BotPlayers() { }
	public static boolean isActor(Entity entity) { return entity instanceof BotActor; }
	private static View view(Group group) { return group == Group.TRAINING ? trainingView : normalView; }
	public static int activeCount() { return activeCount(Group.NORMAL); }
	public static int activeCount(Group group) { return view(group).active; }
	public static String pendingCount() { return pendingCount(Group.NORMAL); }
	public static String pendingCount(Group group) { return view(group).pending; }
	public static List<Row> rows() { return rows(Group.NORMAL); }
	public static List<Row> rows(Group group) { return view(group).rows; }
	public static String name(UUID id) { return displayNames.get(id); }
	public static String status() { return status(Group.NORMAL); }
	public static String status(Group group) { return Lang.t(view(group).status); }
	public static Stats trainingStats() { return trainingView.stats; }
	public static boolean available() { return MC.player != null && MC.level != null && MC.getSingleplayerServer() != null; }
	/** Called globally, including disconnect: a new world never resumes an old cohort. */
	public static void onTick() {
		MinecraftServer server = available() ? MC.getSingleplayerServer() : null;
		UUID owner = server == null ? null : MC.player.getUUID(); Session previous = current;
		if (previous == null && server == null || previous != null && previous.server == server && previous.owner.equals(owner)) return;
		if (previous != null) { previous.valid = false; previous.server.execute(() -> removeEverything(previous)); }
		current = server == null ? null : new Session(server, owner);
		normalView = View.EMPTY; trainingView = View.EMPTY; displayNames = Map.of();
	}
	public static void spawnBatch(String count, String prefix, String mode, String behavior, double spacing, int rate,
			double distance, boolean respawn, boolean randomId) {
		spawn(Group.NORMAL, count, prefix, mode, "bot_follow".equals(behavior), spacing, rate, distance, respawn, randomId, Training.NONE);
	}
	public static void spawnTrainingBatch(String count, String prefix, String mode, double spacing, int rate,
			boolean respawn, boolean randomId, Training training) {
		spawn(Group.TRAINING, count, prefix, mode, false, spacing, rate, 3, respawn, randomId, training);
	}
	private static void spawn(Group group, String count, String prefix, String mode, boolean follow, double spacing, int rate,
			double distance, boolean respawn, boolean randomId, Training training) {
		onTick(); Session session = current;
		if (session == null) { setClientStatus(group, "ui.robotplayers.needWorld"); return; }
		BigInteger amount;
		try {
			String text = count == null ? "" : count.strip(); if (!text.matches("[0-9]+")) throw new NumberFormatException();
			amount = new BigInteger(text); if (amount.signum() <= 0) throw new NumberFormatException();
		} catch (NumberFormatException invalid) { setClientStatus(group, "ui.robotplayers.invalidCount"); return; }
		String name = prefix == null || prefix.isBlank() ? (group == Group.NORMAL ? "Robot" : "Training") : prefix.replaceAll("[\\p{Cntrl}\\r\\n]", "").strip();
		if (name.length() > 64) name = name.substring(0, 64);
		String selectedMode = group == Group.TRAINING ? ("bot_adventure".equals(mode) ? mode : "bot_survival") : normalizedMode(mode);
		Options options = new Options(group, name, selectedMode, follow, Math.max(1, Math.min(8, spacing)), Math.max(1, Math.min(4, rate)),
				Math.max(1.5, Math.min(12, distance)), respawn, randomId, training);
		session.server.execute(() -> {
			if (!valid(session)) return; Cohort cohort = session.cohorts.get(group);
			cohort.removing = false; cohort.batches.addLast(new Batch(amount, options)); cohort.retryAfter = 0;
			cohort.status = "ui.robotplayers.spawning"; publish(session);
		});
	}
	private static void setClientStatus(Group group, String status) {
		View old = view(group); View update = new View(status, old.pending, old.active, old.rows, old.stats);
		if (group == Group.NORMAL) normalView = update; else trainingView = update;
	}
	public static void applyOrdinary(String behavior, double distance) {
		Session session = current; if (session == null) return;
		boolean follow = "bot_follow".equals(behavior); double range = Math.max(1.5, Math.min(12, distance));
		session.server.execute(() -> {
			if (!valid(session)) return; Cohort cohort = session.cohorts.get(Group.NORMAL);
			for (var entry : new ArrayList<>(cohort.actors.entrySet())) {
				Actor actor = entry.getValue(); actor.body.configureOrdinary(follow, range);
				cohort.actors.put(entry.getKey(), new Actor(actor.body, actor.options.withOrdinary(follow, range)));
			}
			for (Batch batch : cohort.batches) batch.options = batch.options.withOrdinary(follow, range);
			ArrayDeque<Respawn> updated = new ArrayDeque<>();
			for (Respawn revival : cohort.respawns) updated.add(new Respawn(revival.id, revival.name, revival.options.withOrdinary(follow, range), revival.tick, revival.stats));
			cohort.respawns.clear(); cohort.respawns.addAll(updated); cohort.status = "ui.robotplayers.applied"; publish(session);
		});
	}
	/** Applies only to existing/pending training robots. Ordinary actors are not even iterated. */
	public static void applyTraining(Training training) {
		Session session = current; if (session == null) return;
		session.server.execute(() -> {
			if (!valid(session)) return; Cohort cohort = session.cohorts.get(Group.TRAINING);
			for (var entry : new ArrayList<>(cohort.actors.entrySet())) {
				Actor actor = entry.getValue(); actor.body.configureTraining(training, false);
				cohort.actors.put(entry.getKey(), new Actor(actor.body, actor.options.withTraining(training)));
			}
			for (Batch batch : cohort.batches) batch.options = batch.options.withTraining(training);
			ArrayDeque<Respawn> updated = new ArrayDeque<>();
			for (Respawn revival : cohort.respawns) updated.add(new Respawn(revival.id, revival.name, revival.options.withTraining(training), revival.tick, revival.stats));
			cohort.respawns.clear(); cohort.respawns.addAll(updated); cohort.status = training.enabled ? "ui.robottraining.applied" : "ui.robottraining.paused"; publish(session);
		});
	}
	public static void resetTrainingStats() {
		Session session = current; if (session == null) return;
		session.server.execute(() -> {
			if (!valid(session)) return; Cohort cohort = session.cohorts.get(Group.TRAINING);
			for (Actor actor : cohort.actors.values()) actor.body.restoreStats(Stats.EMPTY); cohort.retired = Stats.EMPTY;
			ArrayDeque<Respawn> updated = new ArrayDeque<>();
			for (Respawn revival : cohort.respawns) updated.add(new Respawn(revival.id, revival.name, revival.options, revival.tick, Stats.EMPTY));
			cohort.respawns.clear(); cohort.respawns.addAll(updated); publish(session);
		});
	}
	static ServerPlayer trainingTarget(BotActor body, Training training, UUID preferred) {
		Session session = current;
		if (session == null || !valid(session) || body.level().getServer() != session.server || body.group() != Group.TRAINING) return null;
		Cohort cohort = session.cohorts.get(Group.TRAINING);
		if (!cohort.actors.containsKey(body.getUUID())) return null;
		if (training.target.equals("bot_target_owner")) {
			ServerPlayer owner = session.server.getPlayerList().getPlayer(session.owner); return eligibleTarget(body, owner) ? owner : null;
		}
		ServerPlayer owner=session.server.getPlayerList().getPlayer(session.owner);
		if(training.target.equals("bot_target_all") && owner!=null && owner.getUUID().equals(preferred) && eligibleTarget(body,owner)) return owner;
		Actor previous = preferred == null ? null : cohort.actors.get(preferred);
		if (previous != null && eligibleTarget(body, previous.body)) return previous.body;
		ServerPlayer closest=body.level().getEntitiesOfClass(BotActor.class, body.getBoundingBox().inflate(32),
				other -> other.group() == Group.TRAINING && cohort.actors.containsKey(other.getUUID()) && eligibleTarget(body, other))
				.stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
		if(training.target.equals("bot_target_all") && eligibleTarget(body,owner)
				&&(closest==null||body.distanceToSqr(owner)<body.distanceToSqr(closest)))closest=owner;
		return closest;
	}
	/** The owner's creative mode prevents damage, but must not silently disable all following. */
	static ServerPlayer trainingMovementTarget(BotActor body,Training training,UUID preferred) {
		ServerPlayer attack=trainingTarget(body,training,preferred);
		if(attack!=null || training.target.equals("bot_target_robots"))return attack;
		Session session=current;if(session==null||!valid(session)||session.server!=body.level().getServer())return null;
		ServerPlayer owner=session.server.getPlayerList().getPlayer(session.owner);
		return owner!=null&&owner.isAlive()&&!owner.isRemoved()&&!owner.isSpectator()
				&&owner.level()==body.level()&&owner.distanceToSqr(body)<=32*32?owner:null;
	}
	private static boolean eligibleTarget(BotActor body, ServerPlayer target) {
		return target != null && target != body && !target.isRemoved() && target.isAlive() && !target.isSpectator() && !target.isCreative()
				&& target.level() == body.level() && target.distanceToSqr(body) <= 32 * 32 && body.canHarmPlayer(target)
				&& target.canHarmPlayer(body) && body.level().hasChunkAt(target.blockPosition());
	}
	/** Server-owned diagnostic: no guess based on the client's creative camera or a stale row count. */
	static String noTrainingTargetActivity(BotActor body, Training training) {
		Session session = current;
		if (session == null || !valid(session)) return "bot_status_no_target";
		if (!session.server.isPvpAllowed()) return "bot_status_pvp_off";
		if (!training.target.equals("bot_target_robots")) {
			ServerPlayer owner = session.server.getPlayerList().getPlayer(session.owner);
			if (owner == null || !owner.isAlive()) return "bot_status_owner_missing";
			if (owner.isCreative() || owner.isSpectator()) return "bot_status_owner_unavailable";
			if (owner.level() != body.level() || owner.distanceToSqr(body) > 32 * 32) return "bot_status_target_far";
			return "bot_status_pvp_blocked";
		}
		Cohort cohort = session.cohorts.get(Group.TRAINING);
		boolean hasOther = false;
		for (Actor actor : cohort.actors.values()) {
			BotActor other = actor.body;
			if (other == body || !other.isAlive() || other.isRemoved() || other.isCreative() || other.isSpectator()) continue;
			hasOther = true;
			if (other.level() == body.level() && other.distanceToSqr(body) <= 32 * 32) return "bot_status_pvp_blocked";
		}
		return hasOther ? "bot_status_target_far" : "bot_status_missing_opponent";
	}
	public static void stopQueue() { stopQueue(Group.NORMAL); }
	public static void stopQueue(Group group) {
		Session session = current; if (session == null) return;
		session.server.execute(() -> {
			if (!valid(session)) return; Cohort cohort = session.cohorts.get(group);
			cohort.batches.clear(); cohort.respawns.clear(); cohort.status = "ui.robotplayers.queueStopped"; publish(session);
		});
	}
	public static void remove(UUID id) { remove(Group.NORMAL, id); }
	public static void remove(Group group, UUID id) {
		Session session = current; if (session == null) return;
		session.server.execute(() -> {
			if (!valid(session)) return; Cohort cohort = session.cohorts.get(group);
			cohort.respawns.removeIf(value -> value.id.equals(id)); removeActor(session, cohort, id); publish(session);
		});
	}
	public static void removeAll() { removeAll(Group.NORMAL); }
	public static void removeAll(Group group) {
		Session session = current; if (session == null) return;
		session.server.execute(() -> {
			if (!valid(session)) return; Cohort cohort = session.cohorts.get(group);
			cohort.batches.clear(); cohort.respawns.clear(); cohort.removing = true; cohort.status = "ui.robotplayers.removing"; publish(session);
		});
	}
	private static boolean valid(Session session) { return session.valid && current == session; }
	public static void onServerTick(MinecraftServer server) {
		Session session = current; if (session == null || session.server != server || !valid(session)) return;
		session.tick++;
		for (Cohort cohort : session.cohorts.values()) tickCohort(session, cohort);
		if (session.tick % 5 == 0) publish(session);
	}
	private static void tickCohort(Session session, Cohort cohort) {
		if (cohort.removing) {
			int work = 0; for (UUID id : new ArrayList<>(cohort.actors.keySet())) { removeActor(session, cohort, id); if (++work == 4) break; }
			if (cohort.actors.isEmpty()) { cohort.removing = false; cohort.status = "ui.robotplayers.removed"; }
			publish(session); return;
		}
		for (Map.Entry<UUID, Actor> entry : new ArrayList<>(cohort.actors.entrySet())) {
			Actor actor = entry.getValue(); if (actor.body.isAlive() && !actor.body.isRemoved()) continue;
			if (!actor.body.isAlive()) actor.body.recordDeath();
			String name = actor.body.getName().getString(); removeActor(session, cohort, entry.getKey());
			if (actor.options.respawn) cohort.respawns.addLast(new Respawn(entry.getKey(), name, actor.options, session.tick + 80, actor.body.stats()));
			else cohort.retired = cohort.retired.plus(actor.body.stats());
		}
		ServerPlayer owner = session.server.getPlayerList().getPlayer(session.owner);
		if (owner == null || !owner.isAlive() || session.tick < cohort.retryAfter) return;
		int work = 0, budget = cohort.batches.isEmpty() ? 2 : cohort.batches.getFirst().options.rate;
		while (work < budget && (!cohort.batches.isEmpty() || !cohort.respawns.isEmpty())) {
			Respawn revival = cohort.respawns.peekFirst();
			if (revival != null && revival.tick <= session.tick) {
				if (!create(session, cohort, owner, revival.id, revival.name, revival.options, true, revival.stats)) { pauseForLand(session, cohort); break; }
				cohort.respawns.removeFirst(); work++; continue;
			}
			Batch batch = cohort.batches.peekFirst(); if (batch == null) break;
			UUID id = UUID.randomUUID(); String name = batch.options.randomId ? randomName(session) : batch.options.prefix + " " + cohort.nextName;
			if (!create(session, cohort, owner, id, name, batch.options, false, Stats.EMPTY)) { pauseForLand(session, cohort); break; }
			cohort.nextName = cohort.nextName.add(BigInteger.ONE); batch.remaining = batch.remaining.subtract(BigInteger.ONE);
			if (batch.remaining.signum() == 0) cohort.batches.removeFirst();
			cohort.status = cohort.batches.isEmpty() ? "ui.robotplayers.finished" : "ui.robotplayers.spawning"; work++;
		}
		if (work > 0) publish(session);
	}
	private static void pauseForLand(Session session, Cohort cohort) {
		if (!cohort.batches.isEmpty() || !cohort.respawns.isEmpty()) { cohort.retryAfter = session.tick + 20; cohort.status = "ui.robotplayers.noSpace"; }
		publish(session);
	}
	private static boolean create(Session session, Cohort cohort, ServerPlayer owner, UUID id, String name, Options options, boolean load, Stats stats) {
		ServerLevel level = owner.level(); BlockPos ground = findGround(level, owner, options.spacing);
		if (ground == null || session.server.getPlayerList().getPlayer(id) != null) return false;
		BotActor actor = null; boolean registered = false;
		try {
			String profileName = options.randomId ? name : "Bot_" + id.toString().replace("-", "").substring(0, 12);
			actor = new BotActor(session.server, level, new GameProfile(id, profileName), name, session.owner, options.group, options.follow, options.distance);
			if (load) cheatprogram$loadActorData(actor);
			actor.setServerLevel(level); actor.setHealth(actor.getMaxHealth()); actor.getFoodData().setFoodLevel(20); actor.removeAllEffects();
			actor.snapTo(Vec3.atBottomCenterOf(ground), owner.getYRot(), 0); actor.setClientLoaded(true); actor.initInventoryMenu();
			actor.setCustomName(Component.literal(name)); actor.setCustomNameVisible(true); actor.configureTraining(options.training, true); actor.restoreStats(stats);
			cohort.actors.put(id, new Actor(actor, options)); session.server.getPlayerList().getPlayers().add(actor); registered = true;
			((AiPlayerListAccessor) session.server.getPlayerList()).cheatprogram$playersByUuid().put(id, actor);
			session.server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(actor)));
			level.addNewPlayer(actor); actor.gameMode.changeGameModeForPlayer(gameType(options.mode)); return true;
		} catch (RuntimeException error) {
			if (registered) removeActor(session, cohort, id); else if (actor != null) { cohort.actors.remove(id); actor.discard(); }
			CheatProgram.LOGGER.error("Local robot {} could not be spawned", id, error);
			cohort.status = "ui.robotplayers.failed"; cohort.batches.clear(); cohort.respawns.clear(); return false;
		}
	}
	private static BlockPos findGround(ServerLevel level, ServerPlayer owner, double spacing) {
		for (int radius = 2; radius <= 16; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
			if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
			for (int dy = 2; dy >= -3; dy--) {
				BlockPos feet = owner.blockPosition().offset(dx, dy, dz); if (!AiActorNavigation.standable(level, owner, feet)) continue;
				AABB personalSpace = AiActorNavigation.box(feet).inflate(Math.max(0, spacing - .6) / 2, 0, Math.max(0, spacing - .6) / 2);
				if (personalSpace.intersects(owner.getBoundingBox()) || !level.getEntities(owner, personalSpace, Entity::isAlive).isEmpty()) continue;
				return feet;
			}
		}
		return null;
	}
	private static void removeActor(Session session, Cohort cohort, UUID id) {
		Actor record = cohort.actors.remove(id); if (record != null && record.body.level().getServer() == session.server) session.server.getPlayerList().remove(record.body);
	}
	private static void removeEverything(Session session) {
		for (Cohort cohort : session.cohorts.values()) {
			cohort.batches.clear(); cohort.respawns.clear(); for (UUID id : new ArrayList<>(cohort.actors.keySet())) removeActor(session, cohort, id);
		}
	}
	public static void onServerStopping(MinecraftServer server) {
		Session session = current; if (session == null || session.server != server) return;
		session.valid = false; removeEverything(session);
		if (current == session) { current = null; normalView = View.EMPTY; trainingView = View.EMPTY; displayNames = Map.of(); }
	}
	private static void publish(Session session) {
		if (!valid(session)) return;
		Map<UUID, String> names = new LinkedHashMap<>();
		for (Cohort cohort : session.cohorts.values()) {
			BigInteger queued = BigInteger.valueOf(cohort.respawns.size()); for (Batch batch : cohort.batches) queued = queued.add(batch.remaining);
			List<Row> snapshot = new ArrayList<>(cohort.actors.size()); Stats total = cohort.retired;
			for (Map.Entry<UUID, Actor> entry : cohort.actors.entrySet()) {
				BotActor actor = entry.getValue().body; Options options = entry.getValue().options;
				String behavior = cohort.group == Group.TRAINING ? options.training.behavior : options.follow ? "bot_follow" : "bot_stand";
				snapshot.add(new Row(entry.getKey(), actor.getName().getString(), options.mode, actor.isAlive(), behavior, actor.activity(), actor.stats()));
				total = total.plus(actor.stats()); names.put(entry.getKey(), actor.getName().getString());
			}
			for (Respawn revival : cohort.respawns) total = total.plus(revival.stats);
			View view = new View(cohort.status, queued.toString(), cohort.actors.size(), List.copyOf(snapshot), total);
			if (cohort.group == Group.NORMAL) normalView = view; else trainingView = view;
		}
		displayNames = Map.copyOf(names);
	}
	private static String normalizedMode(String mode) { return switch (mode) { case "bot_creative", "bot_adventure", "bot_spectator" -> mode; default -> "bot_survival"; }; }
	private static String randomName(Session session) {
		Set<String> occupied = new HashSet<>();
		for (ServerPlayer player : session.server.getPlayerList().getPlayers()) { occupied.add(player.getGameProfile().name().toLowerCase(Locale.ROOT)); occupied.add(player.getName().getString().toLowerCase(Locale.ROOT)); }
		for (Cohort cohort : session.cohorts.values()) for (Respawn revival : cohort.respawns) occupied.add(revival.name.toLowerCase(Locale.ROOT));
		for (int attempt = 0; attempt < 32; attempt++) { String candidate = randomCharacters(ThreadLocalRandom.current().nextInt(8, 17)); if (!occupied.contains(candidate.toLowerCase(Locale.ROOT))) return candidate; }
		String stem = randomCharacters(8);
		for (long suffix = 0; suffix <= occupied.size(); suffix++) { String candidate = stem + "_" + Long.toString(suffix, 36); if (!occupied.contains(candidate.toLowerCase(Locale.ROOT))) return candidate; }
		throw new IllegalStateException("No unique local robot name");
	}
	private static String randomCharacters(int length) {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_"; StringBuilder result = new StringBuilder(length);
		for (int i = 0; i < length; i++) result.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length()))); return result.toString();
	}
	private static GameType gameType(String mode) { return switch (mode) { case "bot_creative" -> GameType.CREATIVE; case "bot_adventure" -> GameType.ADVENTURE; case "bot_spectator" -> GameType.SPECTATOR; default -> GameType.SURVIVAL; }; }

    /** Mirrors PrepareSpawnTask's registry-aware load without replacing the local actor's connection. */
    private static void cheatprogram$loadActorData(net.minecraft.server.level.ServerPlayer actor) {
        var server = actor.level().getServer();
        try (var errors = new net.minecraft.util.ProblemReporter.ScopedCollector(
                actor.problemPath(), com.cheatprogram.CheatProgram.LOGGER)) {
            server.getPlayerList().loadPlayerData(actor.nameAndId()).ifPresent(data ->
                    actor.load(net.minecraft.world.level.storage.TagValueInput.create(
                            errors, server.registryAccess(), data)));
        }
    }

}
