package com.cheatprogram.module.combat;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.util.ClientLightning;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Single-player power: the integrated server applies real player attacks to loaded targets within 100 m. */
public final class HundredMeterAura extends Module {
	private final ModeSetting mode = add(new ModeSetting("mode", "aura_hostiles",
			"aura_hostiles", "aura_living", "aura_players", "aura_named"));
	private final NumberSetting range = add(new NumberSetting("range", 100, 1, 100, 1));
	private final BoolSetting cooldown = add(new BoolSetting("cooldown", true));
	private final NumberSetting cps = add(new NumberSetting("cps", 2, 1, 20, 1)).visibleWhen(() -> !cooldown.on());
	private final TextSetting players = add(new TextSetting("players", "", 512)).visibleWhen(() -> mode.is("aura_named"));
	private final BoolSetting lightning = add(new BoolSetting("lightning", false));
	private final ClientLightning lightningVisuals = new ClientLightning();
	private final AtomicLong epoch = new AtomicLong();
	private final AtomicReference<AttackRequest> inFlight = new AtomicReference<>();
	private volatile boolean active;
	private IntegratedServer clientServer;
	private UUID clientPlayerId;
	private ClientLevel clientLevel;

	// These fields are used only on the integrated server thread.
	private long stateEpoch = -1;
	private IntegratedServer stateServer;
	private ServerPlayer statePlayer;
	private ServerLevel stateLevel;
	private long lastAttackTick = Long.MIN_VALUE;
	private UUID lockedTarget;

	private record AttackRequest(IntegratedServer server, UUID playerId, ResourceKey<Level> dimension,
			long epoch, String mode, double range, boolean cooldown, int intervalTicks, Set<String> names,
			boolean lightning, Consumer<Runnable> clientExecutor, Consumer<Vec3> afterAttack) {
	}

	public HundredMeterAura() {
		super("hundredmeteraura", Category.COMBAT);
	}

	@Override
	public String suffix() {
		return mode.display();
	}

	@Override
	public void onEnable() {
		active = true;
		epoch.incrementAndGet();
		inFlight.set(null);
		clientServer = null;
		clientPlayerId = null;
		clientLevel = null;
		lightningVisuals.clear();
		KillAura ordinary = ModuleManager.get(KillAura.class);
		if (ordinary != null && ordinary.isEnabled()) ordinary.setEnabled(false);
	}

	@Override
	public void onDisable() {
		active = false;
		epoch.incrementAndGet();
		inFlight.set(null);
		mc.execute(lightningVisuals::clear);
	}

	private void cancelPending() {
		if (inFlight.getAndSet(null) != null) epoch.incrementAndGet();
	}

	@Override
	public void onTick() {
		// Profiles loaded silently at startup do not run onEnable; initialise the lifecycle on the first tick.
		if (!active) onEnable();
		lightningVisuals.sync(mc.level, lightning.on());
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || mc.level == null || mc.gui.screen() != null
				|| mc.gui.overlay() != null || mc.isPaused() || !mc.player.isAlive()
				|| mc.player.isDeadOrDying() || mc.player.isSpectator() || mc.player.isUsingItem()) {
			cancelPending();
			return;
		}
		KillAura ordinary = ModuleManager.get(KillAura.class);
		if (ordinary != null && ordinary.isEnabled()) ordinary.setEnabled(false);
		UUID playerId = mc.player.getUUID();
		ClientLevel level = mc.level;
		ResourceKey<Level> dimension = level.dimension();
		if (clientServer != server || !playerId.equals(clientPlayerId) || clientLevel != level) {
			epoch.incrementAndGet();
			inFlight.set(null);
			clientServer = server;
			clientPlayerId = playerId;
			clientLevel = level;
			lightningVisuals.clear();
		}
		long ticket = epoch.get();
		String selectedMode = mode.get();
		Set<String> names = mode.is("aura_named") ? parseNames(players.get()) : Set.of();
		int interval = Math.max(1, (int) Math.ceil(20.0 / Math.max(1, Math.min(20, cps.val()))));
		// Only the immutable snapshot crosses to the server. The callback below runs on the client thread.
		Consumer<Vec3> afterAttack = confirmedPosition -> {
			if (active && epoch.get() == ticket && mc.getSingleplayerServer() == server
					&& mc.level == level && level.dimension().equals(dimension)
					&& mc.player != null && mc.player.getUUID().equals(playerId)) {
				mc.player.resetAttackStrengthTicker();
				if (confirmedPosition != null && lightning.on()) lightningVisuals.strike(level, confirmedPosition);
			}
		};
		AttackRequest request = new AttackRequest(server, playerId, dimension, ticket, selectedMode,
				Math.max(1, Math.min(100, range.val())), cooldown.on(), interval, names, lightning.on(), mc::execute, afterAttack);
		if (!inFlight.compareAndSet(null, request)) return;
		try {
			server.execute(() -> {
				try {
					attackOnServer(request);
				} catch (Throwable t) {
					CheatProgram.LOGGER.error("Hundred meter aura server task failed", t);
				} finally {
					inFlight.compareAndSet(request, null);
				}
			});
		} catch (RuntimeException ex) {
			inFlight.compareAndSet(request, null);
			CheatProgram.LOGGER.warn("Hundred meter aura could not schedule its server task", ex);
		}
	}

	private static Set<String> parseNames(String text) {
		Set<String> names = new HashSet<>();
		for (String entry : text.split("[,，]")) {
			String name = entry.strip().toLowerCase(Locale.ROOT);
			if (!name.isEmpty()) names.add(name);
		}
		return Set.copyOf(names);
	}

	private void attackOnServer(AttackRequest request) {
		if (!active || request.epoch() != epoch.get() || request.server().isPaused()) return;
		ServerPlayer player = request.server().getPlayerList().getPlayer(request.playerId());
		if (player == null || !player.isAlive() || player.isDeadOrDying() || player.isSpectator() || player.isUsingItem()) return;
		ServerLevel level = player.level();
		if (!level.dimension().equals(request.dimension())) return;
		if (stateEpoch != request.epoch() || stateServer != request.server() || statePlayer != player || stateLevel != level) {
			stateEpoch = request.epoch();
			stateServer = request.server();
			statePlayer = player;
			stateLevel = level;
			lastAttackTick = Long.MIN_VALUE;
			lockedTarget = null;
		}
		long tick = level.getGameTime();
		if (lastAttackTick == tick) return;
		if (request.cooldown()) {
			if (player.getAttackStrengthScale(0f) < 1f) return;
		} else if (lastAttackTick != Long.MIN_VALUE && tick - lastAttackTick < request.intervalTicks()) {
			return;
		}

		Entity current = lockedTarget == null ? null : level.getEntity(lockedTarget);
		LivingEntity target = eligible(current, player, level, request) ? (LivingEntity) current : null;
		if (target == null) {
			double closest = Double.MAX_VALUE;
			for (Entity entity : level.getAllEntities()) {
				if (!eligible(entity, player, level, request)) continue;
				double distance = entity.getBoundingBox().distanceToSqr(player.getEyePosition());
				if (distance < closest) {
					closest = distance;
					target = (LivingEntity) entity;
				}
			}
		}
		lockedTarget = target == null ? null : target.getUUID();
		if (target == null || !active || request.epoch() != epoch.get()) return;
		Vec3 attackPosition = request.lightning() ? target.position() : null;
		float healthBefore = request.lightning() ? target.getHealth() : 0;
		float absorptionBefore = request.lightning() ? target.getAbsorptionAmount() : 0;
		DamageSource previousDamage = request.lightning() ? target.getLastDamageSource() : null;
		// Player.attack uses the server's real damage/cooldown rules; no out-of-range interaction packet is sent.
		player.attack(target);
		player.swing(InteractionHand.MAIN_HAND);
		lastAttackTick = tick;
		DamageSource acceptedDamage = request.lightning() ? target.getLastDamageSource() : null;
		// A new accepted player damage source also covers a lethal hit saved by a totem.
		boolean confirmed = request.lightning() && (target.getHealth() < healthBefore
				|| target.getAbsorptionAmount() < absorptionBefore
				|| acceptedDamage != null && acceptedDamage != previousDamage && acceptedDamage.getEntity() == player);
		Vec3 confirmedPosition = confirmed ? attackPosition : null;
		request.clientExecutor().accept(() -> request.afterAttack().accept(confirmedPosition));
	}

	private static boolean eligible(Entity entity, ServerPlayer player, ServerLevel level, AttackRequest request) {
		if (!(entity instanceof LivingEntity living) || entity == player || entity.getUUID().equals(request.playerId())
				|| entity.level() != level || entity.isRemoved() || !entity.isAttackable() || entity instanceof ArmorStand
				|| !living.isAlive() || living.isDeadOrDying() || living.isSpectator()) return false;
		if (entity.getBoundingBox().distanceToSqr(player.getEyePosition()) > request.range() * request.range()) return false;
		return switch (request.mode()) {
			case "aura_hostiles" -> entity instanceof Enemy;
			case "aura_living" -> !(entity instanceof Player);
			case "aura_players" -> entity instanceof Player;
			case "aura_named" -> entity instanceof Player named
					&& request.names().contains(named.getGameProfile().name().toLowerCase(Locale.ROOT));
			default -> false;
		};
	}
}
