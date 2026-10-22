package com.cheatprogram.ai;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** A server-driven body: controls enter immediately before vanilla travel, attacks after that travel. */
final class BotActor extends ServerPlayer {
	private final String displayName;
	private final UUID ownerId;
	private final BotPlayers.Group group;
	private boolean follow;
	private double followDistance;
	private BotPlayers.Training training = BotPlayers.Training.NONE;
	private UUID trainingTarget;
	private BotNavigation navigation;
	private Vec3 navigationGoal;
	private int retryPathAt, nextAttack;
	private String activity = "bot_status_idle";
	private long attempts, hits, receivedHits, deaths;
	private double dealt, received;
	private boolean deathRecorded;
	private boolean trainingAttack;
	private int aimTick = -1;
	private float yawUsed, pitchUsed;
	private Vec3 lastDriveProgress;
	private int stalledDriveTicks;
	private int shieldDownUntil;
	private boolean shieldOwned;
	BotPlayers.Group group() { return group; }
	String activity() { return activity; }
	BotPlayers.Stats stats() { return new BotPlayers.Stats(attempts, hits, dealt, receivedHits, received, deaths); }
	void restoreStats(BotPlayers.Stats value) { attempts = value.attempts(); hits = value.hits(); dealt = value.dealt(); receivedHits = value.receivedHits(); received = value.received(); deaths = value.deaths(); }
	void recordDeath() { if (!deathRecorded) { deathRecorded = true; deaths++; } }
	void configureOrdinary(boolean follow, double distance) {
		if (group != BotPlayers.Group.NORMAL) return;
		this.follow = follow; followDistance = distance; navigation = null; navigationGoal = null;
	}
	void configureTraining(BotPlayers.Training value, boolean initial) {
		if (group != BotPlayers.Group.TRAINING) return;
		BotPlayers.Training old = training; training = value; trainingTarget = null; navigation = null; navigationGoal = null;
		lastDriveProgress=null;stalledDriveTicks=0;
		nextAttack = tickCount + 2; stopUsingItem(); shieldOwned = false;
		shieldDownUntil = tickCount + Math.floorMod(getId(), 7); clearDrive();
		if (initial || !old.kit().equals(value.kit()) || old.shield() != value.shield()) {
			Item[] kit = switch (value.kit()) {
				case "bot_kit_wood" -> new Item[]{Items.WOODEN_SWORD, Items.LEATHER_BOOTS, Items.LEATHER_LEGGINGS, Items.LEATHER_CHESTPLATE, Items.LEATHER_HELMET};
				case "bot_kit_iron" -> new Item[]{Items.IRON_SWORD, Items.IRON_BOOTS, Items.IRON_LEGGINGS, Items.IRON_CHESTPLATE, Items.IRON_HELMET};
				case "bot_kit_diamond" -> new Item[]{Items.DIAMOND_SWORD, Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET};
				case "bot_kit_netherite" -> new Item[]{Items.NETHERITE_SWORD, Items.NETHERITE_BOOTS, Items.NETHERITE_LEGGINGS, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_HELMET};
				default -> new Item[]{Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR};
			};
			getInventory().selected = 0;
			EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
			for (int i = 0; i < slots.length; i++) setItemSlot(slots[i], kit[i].getDefaultInstance());
			setItemSlot(EquipmentSlot.OFFHAND, value.shield() ? Items.SHIELD.getDefaultInstance() : Items.AIR.getDefaultInstance());
			getInventory().setChanged(); inventoryMenu.broadcastChanges(); resetAttackStrengthTicker();
		}
		activity = value.active() ? "bot_status_no_target" : "bot_status_paused";
	}
	@Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		float before = getHealth() + getAbsorptionAmount(); boolean result = super.hurtServer(level, source, amount);
		double loss = Math.max(0, before - getHealth() - getAbsorptionAmount());
		if (loss > 0) { receivedHits++; received += loss; }
		return result;
	}
	BotActor(MinecraftServer server, ServerLevel level, GameProfile profile, String name, UUID ownerId,
			BotPlayers.Group group, boolean follow, double followDistance) {
		super(server, level, profile, ClientInformation.createDefault());
		displayName = name; this.ownerId = ownerId; this.group = group; this.follow = follow; this.followDistance = followDistance;
		new LocalListener(server, new LocalConnection(), this, CommonListenerCookie.createInitial(profile, false));
		ClientInformation options = ClientInformation.createDefault();
		updateOptions(new ClientInformation(options.language(), options.viewDistance(), options.chatVisibility(), options.chatColors(),
				127, options.mainHand(), options.textFilteringEnabled(), options.allowsListing(), options.particleStatus()));
		setClientLoaded(true);
	}
	@Override public Component getName() { return Component.literal(displayName == null ? getGameProfile().getName() : displayName); }
	@Override public Component getTabListDisplayName() { return getName(); }
	@Override protected boolean considersEntityAsAlly(Entity other) {
		// Limit the vanilla sword sweep to the selected sparring partner. This temporary alliance
		// changes no scoreboard teams and leaves ordinary robots/owner out of collateral damage.
		if (trainingAttack && (trainingTarget == null || !other.getUUID().equals(trainingTarget))) return true;
		return super.considersEntityAsAlly(other);
	}
	@Override public void tick() {
		Vec3 before = position();
		super.tick();
		// A normal remote client's listener invokes this; local actors have no incoming movement packets.
		doTick();
		setKnownMovement(position().subtract(before));
		if (isAlive() && group == BotPlayers.Group.TRAINING && training.active()) attackAfterMovement();
		checkMovementStatistics(getX() - before.x, getY() - before.y, getZ() - before.z);
		serverLevel().getChunkSource().move(this);
	}
	@Override protected void serverAiStep() {
		super.serverAiStep();
		// This point is inside LivingEntity.aiStep immediately before jumping/travel. Updating controls
		// before doTick is fragile: intermediate player/AI updates may replace or clear those inputs.
		clearDrive();
		if (!isAlive() || isSpectator() || isPassenger() || isSleeping()) { activity = "bot_status_idle"; return; }
		if (group == BotPlayers.Group.TRAINING) {
			if (training.active()) trainMovement(); else { activity = "bot_status_paused"; stopUsingItem(); }
		} else if (follow) followOwner(); else activity = "bot_status_idle";
		setLastClientInput(new Input(zza > .01f, zza < -.01f, xxa > .01f, xxa < -.01f, jumping, isShiftKeyDown(), isSprinting()));
	}
	private void clearDrive() {
		zza = xxa = yya = 0; setJumping(false); setSprinting(false); setShiftKeyDown(false); setLastClientInput(Input.EMPTY);
	}
	private ServerPlayer target() { return BotPlayers.trainingTarget(this, training, trainingTarget); }
	private void trainMovement() {
		ServerPlayer target = BotPlayers.trainingMovementTarget(this,training,trainingTarget);
		if (target == null) {
			trainingTarget = null; navigation = null; lowerShield(false);
			activity = BotPlayers.noTrainingTargetActivity(this, training);
			return;
		}
		trainingTarget = target.getUUID(); lookAt(target.getEyePosition(), 18, 12);
		boolean guard = updateShield(target);
		double radius = Math.min(training.radius(), Math.max(1, getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) - .2));
		double distance = target.position().subtract(position()).horizontalDistance();
		boolean moved = false;
		if (training.behavior().equals("bot_strafe") && distance < radius + 2) {
			double angle = Math.atan2(getZ() - target.getZ(), getX() - target.getX()) + .24;
			moved = steer(target.position().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), training.speed() * (guard ? .25 : 1));
		} else if (distance > radius || Math.abs(target.getY() - getY()) > .8) {
			moved = steer(target.position(), training.speed() * (guard ? .25 : 1));
		} else navigation = null;
		activity = guard ? isBlocking() ? "bot_status_guarding" : "bot_status_shield_raising"
				: distance > radius + .2 ? moved ? "bot_status_approaching" : "bot_status_path_blocked" : "bot_status_combat";
		if (!guard && training.shield() && training.shieldMode().equals("bot_shield_only")) {
			if (!(getOffhandItem().getItem() instanceof ShieldItem)) activity = "bot_status_no_shield";
			else if (getCooldowns().isOnCooldown(getOffhandItem())) activity = "bot_status_shield_cooldown";
		}
	}
	/** Shared by duel, strafe and guard behavior. Vanilla owns use time, durability and axe disables. */
	private boolean updateShield(ServerPlayer target) {
		if (!training.shield() || isCreative() || isSpectator() || target.isCreative() || target.isSpectator()
				|| !canHarmPlayer(target) || !target.canHarmPlayer(this)) { lowerShield(false); return false; }
		var shield = getOffhandItem();
		if (shield.isEmpty() || !(shield.getItem() instanceof ShieldItem) || getCooldowns().isOnCooldown(shield)) {
			// An axe can stop item use between control ticks. Never bypass the original cooldown
			// or regenerate a broken shield just because the equipment option remains enabled.
			lowerShield(false); return false;
		}
		double distance = position().distanceTo(target.position());
		double threatRange = Math.max(4, Math.min(7, target.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) + 1.5));
		boolean near = distance <= threatRange && hasLineOfSight(target);
		if (!near) { lowerShield(false); return false; }
		boolean only = training.shieldMode().equals("bot_shield_only");
		boolean rhythm = training.shieldMode().equals("bot_shield_rhythm");
		int holdTicks = Math.max(ShieldItem.EFFECTIVE_BLOCK_DELAY + 3, (int) Math.round(60 * training.shieldUptime()));
		double reach = getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		boolean canStrike = target.getBoundingBox().distanceToSqr(getEyePosition()) <= reach * reach;
		boolean ready = tickCount >= nextAttack && (!training.cooldown() || getAttackStrengthScale(.5f) >= .98f);
		Vec3 enemyLook = target.getViewVector(1).multiply(1, 0, 1).normalize();
		Vec3 towardUs = position().subtract(target.position()).multiply(1, 0, 1).normalize();
		boolean facingUs = enemyLook.dot(towardUs) > .1;
		// Start before the opponent's next full-strength hit, leaving the real five-tick warm-up.
		boolean threat = facingUs && (target.swinging || target.getAttackStrengthScale(.5f) >= .5f) || hurtTime > 0;
		boolean usingShield = isUsingItem() && getUsedItemHand() == InteractionHand.OFF_HAND && getUseItem().getItem() instanceof ShieldItem;
		if (usingShield) {
			int used = getTicksUsingItem();
			boolean keep = only || used < ShieldItem.EFFECTIVE_BLOCK_DELAY + 3
					|| rhythm && used < holdTicks
					|| !rhythm && used < holdTicks && (!ready || !canStrike) && threat;
			if (keep) return true;
			lowerShield(true);
			// Leave an actual unblocked attack window. Smart mode reacts again after eight ticks;
			// rhythm mode follows the configured proportion in a sixty-tick cycle.
			shieldDownUntil = tickCount + (rhythm ? Math.max(8, 60 - holdTicks) : 8);
			return false;
		}
		if (shieldOwned) shieldOwned = false;
		if (isUsingItem() || tickCount < shieldDownUntil || !only && !rhythm && !threat) return false;
		gameMode.useItem(this, serverLevel(), shield, InteractionHand.OFF_HAND);
		if (isUsingItem() && getUsedItemHand() == InteractionHand.OFF_HAND) {
			shieldOwned = true; return true;
		}
		return false;
	}
	private void lowerShield(boolean attackWindow) {
		if (isUsingItem() && getUsedItemHand() == InteractionHand.OFF_HAND && (shieldOwned || getUseItem().getItem() instanceof ShieldItem)) stopUsingItem();
		shieldOwned = false;
		if (!attackWindow) shieldDownUntil = Math.max(shieldDownUntil, tickCount + 1);
	}
	private void attackAfterMovement() {
		ServerPlayer target = target();
		if (target == null) {
			if(BotPlayers.trainingMovementTarget(this,training,trainingTarget)!=null) {
				if(!activity.equals("bot_status_approaching")&&!activity.equals("bot_status_path_blocked"))activity="bot_status_follow_no_attack";
			}else activity = BotPlayers.noTrainingTargetActivity(this, training);
			return;
		}
		if (training.shield() && training.shieldMode().equals("bot_shield_only")) return;
		if (isCreative() || isSpectator() || isPassenger() || isSleeping() || isUsingItem()) return;
		// Both bodies have advanced since the controller aimed before travel. Re-aim the remaining
		// angle budget from their actual post-travel eyes; fast strafing must not use a stale ray.
		trainingTarget = target.getUUID(); lookAt(target.getEyePosition(), 18, 12);
		if (tickCount < nextAttack || training.cooldown() && getAttackStrengthScale(.5f) < .98f) { activity = "bot_status_cooldown"; return; }
		if (target.invulnerableTime > 10 || target.isInvulnerableTo(serverLevel(), damageSources().playerAttack(this))) { activity = "bot_status_target_protected"; return; }
		double reach = getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		Vec3 eye = getEyePosition(), end = eye.add(getViewVector(1).scale(reach));
		var targetBox = target.getBoundingBox().inflate(.03);
		var intersection = targetBox.clip(eye, end);
		if (!targetBox.contains(eye) && intersection.isEmpty()) { activity = "bot_status_aiming"; return; }
		double targetDistance = targetBox.contains(eye) ? 0 : eye.distanceToSqr(intersection.get());
		HitResult block = serverLevel().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		if (block.getType() != HitResult.Type.MISS && eye.distanceToSqr(block.getLocation()) + 1e-6 < targetDistance) { activity = "bot_status_sight_blocked"; return; }
		// A third body between the sword and selected target blocks this attack. In particular,
		// ordinary robots are never accidentally selected as training victims in a crowded scene.
		for (Entity other : serverLevel().getEntities(this, getBoundingBox().expandTowards(end.subtract(eye)).inflate(1),
				entity -> entity != target && entity.isAlive() && entity.isPickable() && !entity.isSpectator())) {
			var box = other.getBoundingBox().inflate(other.getPickRadius()); var hit = box.clip(eye, end);
			if (box.contains(eye) || hit.isPresent() && eye.distanceToSqr(hit.get()) < targetDistance) { activity = "bot_status_body_blocked"; return; }
		}
		float before = target.getHealth() + target.getAbsorptionAmount(); attempts++;
		// ServerPlayer.swing resets the cooldown! Damage must be computed first, as in the real
		// interact packet followed by the animate packet, rather than swing -> zero-strength attack.
		trainingAttack = true;
		try { attack(target); } finally { trainingAttack = false; }
		swing(InteractionHand.MAIN_HAND); nextAttack = tickCount + training.interval();
		double loss = Math.max(0, before - target.getHealth() - target.getAbsorptionAmount());
		if (loss > 0) { hits++; dealt += loss; activity = "bot_status_combat"; }
		else activity = target.isBlocking() ? "bot_status_target_blocking" : "bot_status_attack_rejected";
	}
	private void lookAt(Vec3 point, float yawStep, float pitchStep) {
		Vec3 offset = point.subtract(getEyePosition()); float yaw = (float) Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90;
		if (aimTick != tickCount) { aimTick = tickCount; yawUsed = pitchUsed = 0; }
		float previousYaw = getYRot(), previousPitch = getXRot();
		setYRot(Mth.approachDegrees(previousYaw, yaw, Math.max(0, yawStep - yawUsed))); setYHeadRot(getYRot()); yBodyRot = getYRot();
		setXRot(Mth.approach(previousPitch, Mth.clamp((float) -Math.toDegrees(Math.atan2(offset.y, Math.max(.01, offset.horizontalDistance()))), -90, 90), Math.max(0, pitchStep - pitchUsed)));
		yawUsed += Math.abs(Mth.wrapDegrees(getYRot() - previousYaw)); pitchUsed += Math.abs(getXRot() - previousPitch);
	}
	private boolean steer(Vec3 destination, double speed) {
		Vec3 direction = destination.subtract(position()).multiply(1, 0, 1);
		if (direction.lengthSqr() < .04) return false;
		Vec3 next = destination; Vec3 normalized = direction.normalize();
		BlockPos ahead = BlockPos.containing(position().add(normalized.scale(.65)).add(0, .05, 0));
		boolean level = AiActorNavigation.standable(serverLevel(), this, ahead);
		boolean step = AiActorNavigation.standable(serverLevel(), this, ahead.above());
		boolean down = AiActorNavigation.standable(serverLevel(), this, ahead.below());
		if(lastDriveProgress==null||position().distanceToSqr(lastDriveProgress)>.05*.05){lastDriveProgress=position();stalledDriveTicks=0;}
		else stalledDriveTicks++;
		boolean stuck=onGround()&&(horizontalCollision&&!step||stalledDriveTicks>=16);
		if(stalledDriveTicks>=32){navigation=null;navigationGoal=null;retryPathAt=tickCount;lastDriveProgress=position();stalledDriveTicks=0;}
		if (onGround() && !level && !step && !down || stuck || navigation != null) {
			if (navigationGoal == null || navigationGoal.distanceToSqr(destination) > 4 || navigation == null && tickCount >= retryPathAt) {
				navigationGoal = destination; navigation = new BotNavigation(this, destination);
			}
			if (navigation == null) return false;
			navigation.search(); next = navigation.next();
			if (next == null) {
				if (!navigation.searching()) { navigation = null; retryPathAt = tickCount + 10; }
				return false;
			}
			direction = next.subtract(position()).multiply(1, 0, 1);
			if (direction.lengthSqr() < .01) return false;
			normalized = direction.normalize();
			ahead = BlockPos.containing(position().add(normalized.scale(.55)).add(0, .05, 0));
			level = AiActorNavigation.standable(serverLevel(), this, ahead); step = AiActorNavigation.standable(serverLevel(), this, ahead.above());
			down = AiActorNavigation.standable(serverLevel(), this, ahead.below());
			if (onGround() && !level && !step && !down) return false;
		}
		double yaw = Math.toRadians(getYRot());
		zza = (float) ((normalized.x * -Math.sin(yaw) + normalized.z * Math.cos(yaw)) * speed);
		xxa = (float) ((normalized.x * Math.cos(yaw) + normalized.z * Math.sin(yaw)) * speed);
		setJumping(onGround() && (!level && step || horizontalCollision && step || next.y > getY() + .45));
		hasImpulse = true; return true;
	}
	private void followOwner() {
		ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
		if (owner == null || !owner.isAlive() || owner.serverLevel() != serverLevel()) { activity = "bot_status_idle"; return; }
		double distance = position().distanceTo(owner.position());
		if (distance <= followDistance || distance > 64) { navigation = null; activity = "bot_status_idle"; return; }
		lookAt(owner.getEyePosition(), 12, 8);
		activity = steer(owner.position(), 1) ? "bot_status_following" : "bot_status_path_blocked";
	}
	private static final class LocalConnection extends Connection {
		LocalConnection() { super(PacketFlow.SERVERBOUND); }
		@Override public boolean isConnected() { return true; }
		@Override public boolean isConnecting() { return false; }
		@Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) { }
		@Override public void flushChannel() { }
		@Override public void disconnect(Component reason) { }
		@Override public void disconnect(DisconnectionDetails reason) { }
	}
	private static final class LocalListener extends ServerGamePacketListenerImpl {
		LocalListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) { super(server, connection, player, cookie); }
		@Override public void tick() { }
		@Override public void suspendFlushing() { }
		@Override public void resumeFlushing() { }
		@Override public void send(Packet<?> packet, PacketSendListener listener) { }
		@Override public void disconnect(Component reason) { }
		@Override public void disconnect(DisconnectionDetails reason) { }
	}
}
