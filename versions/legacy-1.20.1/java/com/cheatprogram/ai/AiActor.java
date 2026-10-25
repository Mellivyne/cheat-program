package com.cheatprogram.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/** A real server player with its own body, inventory and normal survival physics. No user input is borrowed. */
final class AiActor extends ServerPlayer {
	record Result(boolean ok, String message) { }
	private Pending pending;
	private String displayName;
	private boolean preparing;
	private boolean hardcoreDeath;

	AiActor(MinecraftServer server, ServerLevel level, GameProfile profile, String name) {
		super(server, level, profile);
		displayName = name;
		new LocalListener(server, new LocalConnection(), this);
		updateOptions(new net.minecraft.network.protocol.game.ServerboundClientInformationPacket("en_us",10,net.minecraft.world.entity.player.ChatVisiblity.FULL,true,127,net.minecraft.world.entity.HumanoidArm.RIGHT,false,false));
		
	}
	void rename(String name) { displayName = name; setCustomName(Component.literal(name)); }
	boolean hardcoreDied() { return hardcoreDeath; }
	void markHardcoreDeath() { hardcoreDeath = true; }
	void clearHardcoreDeath() { hardcoreDeath = false; }
	@Override public void readAdditionalSaveData(CompoundTag data) {
		super.readAdditionalSaveData(data); hardcoreDeath = data.getBoolean("CheatProgramHardcoreDead");
	}
	@Override public void addAdditionalSaveData(CompoundTag data) {
		super.addAdditionalSaveData(data); data.putBoolean("CheatProgramHardcoreDead", hardcoreDeath);
	}
	@Override public Component getName() { return Component.literal(displayName == null ? getGameProfile().getName() : displayName); }
	@Override public Component getTabListDisplayName() { return getName(); }
	@Override public void tick() {
		try {
			drive(0, 0, false, false, false);
			if (isAlive() && pending != null) step();
		} catch (RuntimeException ignored) { finish(false, "动作参数无效，已停止该动作。"); }
		Pending physicalAction = pending;
		Vec3 previousPosition = position();
		super.tick();
		// Ordinary ServerPlayers receive doTick from their network listener. This actor has no network client.
		doTick();
		checkMovementStatistics(getX() - previousPosition.x, getY() - previousPosition.y, getZ() - previousPosition.z);
		serverLevel().getChunkSource().move(this);
		if (!isAlive() && pending != null) finish(false, "AI 玩家已死亡，动作已停止。");
		else if (physicalAction != null && pending == physicalAction) {
			// Let this tick's physics/item update run before clearing the final tick of requested input.
			if (physicalAction.action.equals("move") && physicalAction.elapsed >= ticks(physicalAction.args, 20))
				finish(true, "已完成移动。");
			else if (physicalAction.action.equals("use_item") && (physicalAction.elapsed >= ticks(physicalAction.args, 32) || !isUsingItem())) {
				if (isUsingItem()) releaseUsingItem();
				finish(true, "已完成使用交互；效果见真实观察结果。");
			}
		}
	}
	void begin(String action, JsonObject arguments, Consumer<Result> result) {
		cancel("旧动作已替换。");
		pending = new Pending(action, arguments.deepCopy(), result);
	}
	void cancel(String message) {
		if (pending != null) finish(false, message);
		drive(0, 0, false, false, false); stopUsingItem();
	}
	boolean busy() { return pending != null; }
	String action() { return pending == null ? "" : pending.action; }
	private static final class Pending {
		final String action; final JsonObject args; final Consumer<Result> result;
		int elapsed, retries; BlockPos mining; AiActorNavigation navigation; Vec3 destination;
		Pending(String action, JsonObject args, Consumer<Result> result) { this.action = action; this.args = args; this.result = result; }
	}
	private void finish(boolean ok, String message) {
		Pending task = pending; pending = null;
		if (task == null) return;
		if (task.mining != null) gameMode.handleBlockBreakAction(task.mining,
				ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.UP, serverLevel().getMaxBuildHeight(), 0);
		drive(0, 0, false, false, false);
		task.result.accept(new Result(ok, message));
	}
	private void step() {
		Pending task = pending; task.elapsed++;
		// These calls bypass the real player's packet listener, where spectator inventory actions are rejected.
		if (isSpectator() && switch (task.action) {
			case "mine", "place", "attack", "use_item", "pickup", "container_click" -> true;
			default -> false;
		}) { finish(false, "旁观模式无法采掘、放置、攻击、使用物品、拾取或移动背包物品。"); return; }
		switch (task.action) {
			case "observe" -> finish(true, "已观察自己的真实世界状态。");
			case "look" -> {
				Entity target = entity(task.args);
				Vec3 point = target != null ? target.getEyePosition() : position(task.args);
				look(point, false); finish(true, "已转向目标。");
			}
			case "move" -> {
				float forward = (float) clamp(number(task.args, "forward", 0), -1, 1), strafe = (float) clamp(number(task.args, "strafe", 0), -1, 1);
				if (!safeAhead(forward, strafe)) {
					if (isSpectator()) setDeltaMovement(Vec3.ZERO);
					finish(false, isSpectator() ? "前方区块未加载或已到世界边界，已停止飞行。" : "前方无安全落脚点，已停止移动。"); return;
				}
				drive(forward, strafe, flag(task.args, "jump"), flag(task.args, "sneak"), flag(task.args, "sprint"));
			}
			case "walk_to", "follow", "pickup" -> walk(task);
			case "mine" -> mine(task);
			case "place" -> place(task);
			case "attack" -> attack(task);
			case "use_item" -> use(task);
			case "select_hotbar" -> {
				int slot = (int) number(task.args, "hotbar", 1);
				if (slot < 1 || slot > 9) { finish(false, "快捷栏槽位需为 1 至 9。"); return; }
				getInventory().selected = slot - 1; finish(true, "已选择自己的快捷栏槽位。");
			}
			case "container_click" -> {
				if (!containerMenu.stillValid(this)) { closeContainer(); finish(false, "该容器已不可交互。"); return; }
				int slot = (int) number(task.args, "slot", -1), button = (int) number(task.args, "button", 0);
				if (slot < 0 || slot >= containerMenu.slots.size() || button < 0 || button > 1) { finish(false, "容器槽位无效。"); return; }
				ClickType kind = text(task.args, "click", "quick_move").equals("pickup") ? ClickType.PICKUP : ClickType.QUICK_MOVE;
				containerMenu.clicked(slot, button, kind, this); containerMenu.broadcastChanges();
				finish(true, "已操作自己的容器槽位。");
			}
			case "close_container" -> { closeContainer(); finish(true, "已关闭自己的容器。"); }
			case "stop" -> { stopUsingItem(); finish(true, "已停止自己的动作。"); }
			default -> finish(false, "不支持此动作。");
		}
	}
	private void drive(float forward, float strafe, boolean jump, boolean sneak, boolean sprint) {
		boolean flying = getAbilities().flying;
		zza = forward; xxa = strafe; yya = 0;
		setJumping(jump && !flying); setShiftKeyDown(sneak); setSprinting(sprint && forward > 0 && !sneak);
		if (flying && !isPassenger()) {
			// Match LocalPlayer flight input; Player.travel preserves and damps the incoming vertical velocity.
			int vertical = (jump ? 1 : 0) - (sneak ? 1 : 0);
			if (vertical != 0) setDeltaMovement(getDeltaMovement().add(0, vertical * getAbilities().getFlyingSpeed() * 3.0F, 0));
		}
		if (forward != 0 || strafe != 0 || jump || flying && sneak) hasImpulse = true;
	}
	private boolean safeAhead(float forward, float strafe) {
		if (isSpectator()) {
			double angle = Math.toRadians(getYRot());
			Vec3 predicted = position().add(getDeltaMovement()).add(
					(-Math.sin(angle) * forward + Math.cos(angle) * strafe) * 0.75, 0,
					(Math.cos(angle) * forward + Math.sin(angle) * strafe) * 0.75);
			BlockPos ahead = BlockPos.containing(predicted);
			return serverLevel().hasChunkAt(ahead) && serverLevel().getWorldBorder().isWithinBounds(ahead);
		}
		if (Math.abs(forward) + Math.abs(strafe) < 0.01 || !onGround()) return true;
		double angle = Math.toRadians(getYRot());
		double x = -Math.sin(angle) * forward + Math.cos(angle) * strafe, z = Math.cos(angle) * forward + Math.sin(angle) * strafe;
		BlockPos ahead = BlockPos.containing(position().add(x * 0.5, 0, z * 0.5));
		return AiActorNavigation.standable(serverLevel(), this, ahead) || AiActorNavigation.standable(serverLevel(), this, ahead.above());
	}
	private void look(Vec3 point, boolean gradual) {
		Vec3 difference = point.subtract(getEyePosition());
		float yaw = (float) Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90;
		float pitch = (float) -Math.toDegrees(Math.atan2(difference.y, difference.horizontalDistance()));
		setYRot(gradual ? Mth.approachDegrees(getYRot(), yaw, 18) : yaw);
		setXRot(Mth.clamp(pitch, -90, 90)); setYHeadRot(getYRot()); yBodyRot = getYRot();
	}
	private Entity entity(JsonObject args) {
		return args.has("entityId") ? serverLevel().getEntity((int) number(args, "entityId", -1)) : null;
	}
	private void walk(Pending task) {
		Entity target = entity(task.args);
		if ((task.action.equals("follow") || task.action.equals("pickup")) && (target == null || target == this || target.isRemoved())) {
			finish(false, "目标实体不存在。"); return;
		}
		Vec3 destination = target == null ? position(task.args) : target.position();
		if (task.action.equals("pickup") && !(target instanceof ItemEntity)) { finish(false, "目标不是掉落物。"); return; }
		double stopDistance = task.action.equals("follow") ? clamp(number(task.args, "distance", 2.5), 1, 8) : task.action.equals("pickup") ? 1.15 : 1.0;
		if (position().distanceToSqr(destination) <= stopDistance * stopDistance) {
			if (target instanceof ItemEntity item) {
				int count = item.getItem().getCount(); item.playerTouch(this);
				finish(item.isRemoved() || item.getItem().getCount() < count, item.isRemoved() || item.getItem().getCount() < count ? "已捡起真实掉落物。" : "物品暂不可拾取或背包已满。");
			} else if (!task.action.equals("follow") || task.elapsed >= ticks(task.args, 100)) finish(true, "已到达目标附近。");
			return;
		}
		if (!serverLevel().hasChunkAt(BlockPos.containing(destination)) && position().distanceTo(destination) < 32) {
			finish(false, "目标所在区块尚未加载。"); return;
		}
		if (task.navigation == null || task.destination.distanceToSqr(destination) > 4) {
			task.destination = destination; task.navigation = new AiActorNavigation(this, destination);
		}
		task.navigation.search();
		if (task.navigation.searching()) return;
		if (task.navigation.failed()) {
			if (++task.retries >= 3) { finish(false, "没有可走的安全路径，已停止而未传送。"); return; }
			task.navigation = null; return;
		}
		Vec3 waypoint = task.navigation.next();
		if (waypoint == null) {
			if (task.navigation.failed() && ++task.retries >= 3) { finish(false, "路径被障碍物挡住，已停止。"); return; }
			task.navigation = null; return;
		}
		look(waypoint.add(0, getEyeHeight(), 0), true);
		if (!safeAhead(1, 0)) { task.navigation = null; if (++task.retries > 3) finish(false, "路径被障碍物挡住。"); return; }
		drive(1, 0, waypoint.y > getY() + 0.3 || horizontalCollision, false, flag(task.args, "sprint"));
		if (task.action.equals("follow") && task.elapsed >= ticks(task.args, 100)) finish(true, "已完成本次跟随。");
	}
	private boolean reachable(BlockPos block) {
		if (!serverLevel().hasChunkAt(block) || !serverLevel().getWorldBorder().isWithinBounds(block)
				|| !serverLevel().mayInteract(this, block) || !canInteractWithBlock(block, 0)) return false;
		HitResult hit = serverLevel().clip(new ClipContext(getEyePosition(), Vec3.atCenterOf(block), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this));
		return hit.getType() == HitResult.Type.MISS || hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(block);
	}
	private void mine(Pending task) {
		BlockPos block = BlockPos.containing(position(task.args));
		if (!reachable(block)) { finish(false, "目标方块超出真实交互距离或视线被挡住。"); return; }
		var state = serverLevel().getBlockState(block);
		if (state.isAir()) { finish(true, "目标方块已挖除。"); return; }
		if (blockActionRestricted(serverLevel(), block, gameMode.getGameModeForPlayer())) {
			finish(false, "当前游戏模式或手持物品不允许挖掘这个方块。"); return;
		}
		if (state.getDestroySpeed(serverLevel(), block) < 0) { finish(false, "该方块无法挖掘。"); return; }
		look(Vec3.atCenterOf(block), false);
		if (task.mining == null) {
			task.mining = block;
			gameMode.handleBlockBreakAction(block, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, serverLevel().getMaxBuildHeight(), 0);
		}
		if (task.elapsed % 5 == 1) swing(InteractionHand.MAIN_HAND);
		double progress = state.getDestroyProgress(this, serverLevel(), block) * task.elapsed;
		if (progress >= 1) gameMode.handleBlockBreakAction(block, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.UP, serverLevel().getMaxBuildHeight(), 0);
	}
	private void place(Pending task) {
		BlockPos destination = BlockPos.containing(position(task.args));
		if (!serverLevel().hasChunkAt(destination) || !serverLevel().getWorldBorder().isWithinBounds(destination)
				|| !serverLevel().mayInteract(this, destination)) { finish(false, "目标位置不可交互。"); return; }
		if (!(getMainHandItem().getItem() instanceof BlockItem) || !serverLevel().getBlockState(destination).canBeReplaced()) {
			finish(false, "主手没有方块，或目标位置不能放置。"); return;
		}
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = destination.relative(direction);
			if (serverLevel().getBlockState(neighbour).isAir() || !reachable(neighbour)) continue;
			Direction face = direction.getOpposite();
			Vec3 hit = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			look(hit, false);
			var result = gameMode.useItemOn(this, serverLevel(), getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, neighbour, false));
			if (result.consumesAction()) swing(InteractionHand.MAIN_HAND);
			finish(!serverLevel().getBlockState(destination).canBeReplaced(), "已执行原版放置交互；结果以观察中的方块为准。"); return;
		}
		finish(false, "没有可见、可交互的相邻支撑面。");
	}
	private void attack(Pending task) {
		Entity target = entity(task.args);
		if (target == null || target == this || !target.isAlive() || !canInteractWithEntity(target, 0) || !hasLineOfSight(target)) {
			finish(false, "目标超出原版攻击距离、已消失或视线被挡住。"); return;
		}
		if (isSpectator() || target instanceof ItemEntity || target instanceof ExperienceOrb
				|| target instanceof AbstractArrow arrow && !arrow.isAttackable()
				|| !serverLevel().getWorldBorder().isWithinBounds(target.blockPosition())
				|| !getMainHandItem().isItemEnabled(serverLevel().enabledFeatures())) {
			finish(false, "当前状态或目标不允许原版攻击。"); return;
		}
		look(target.getEyePosition(), false);
		if (getAttackStrengthScale(0) < 0.9) return;
		float health = target instanceof LivingEntity living ? living.getHealth() : -1;
		attack(target); swing(InteractionHand.MAIN_HAND);
		boolean damaged = !(target instanceof LivingEntity living) || living.getHealth() < health;
		finish(damaged, damaged ? "已完成原版攻击。" : "已攻击，但目标未受到伤害（例如保护、免疫或 PvP 关闭）。");
	}
	private void use(Pending task) {
		InteractionHand hand = text(task.args, "hand", "main").equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		if (!getItemInHand(hand).isItemEnabled(serverLevel().enabledFeatures())) { finish(false, "该物品在当前世界不可用。"); return; }
		if (task.elapsed == 1) {
			if (task.args.has("x") && task.args.has("y") && task.args.has("z")) {
				BlockPos block = BlockPos.containing(position(task.args));
				if (!reachable(block)) { finish(false, "使用目标超出距离或被遮挡。"); return; }
				BlockHitResult hit = (BlockHitResult) serverLevel().clip(new ClipContext(getEyePosition(), Vec3.atCenterOf(block), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this));
				look(hit.getLocation(), false); gameMode.useItemOn(this, serverLevel(), getItemInHand(hand), hand, hit);
			} else gameMode.useItem(this, serverLevel(), getItemInHand(hand), hand);
		}
		// Completion follows doTick, so a 32-tick food action receives all 32 vanilla item updates.
	}

	JsonObject observation() {
		JsonObject data = new JsonObject(), self = new JsonObject();
		self.addProperty("name", getName().getString()); self.addProperty("entityId", getId()); coordinates(self, position());
		self.addProperty("yaw", getYRot()); self.addProperty("pitch", getXRot()); self.addProperty("health", getHealth());
		self.addProperty("food", getFoodData().getFoodLevel()); self.addProperty("alive", isAlive()); self.addProperty("onGround", onGround());
		self.addProperty("dimension", serverLevel().dimension().location().toString()); self.addProperty("gameMode", gameMode.getGameModeForPlayer().getName());
		self.addProperty("spectator", isSpectator()); self.addProperty("flying", getAbilities().flying);
		self.addProperty("mayFly", getAbilities().mayfly); self.addProperty("canBuild", getAbilities().mayBuild);
		self.addProperty("selectedHotbar", getInventory().selected + 1); self.addProperty("action", action()); data.add("self", self);
		JsonArray inventory = new JsonArray();
		for (int slot = 0; slot < getInventory().getContainerSize(); slot++) {
			ItemStack item = getInventory().getItem(slot); if (item.isEmpty()) continue;
			JsonObject entry = item(item); entry.addProperty("slot", slot); if (slot < 9) entry.addProperty("hotbar", slot + 1); inventory.add(entry);
		}
		data.add("inventory", inventory);
		JsonArray nearby = new JsonArray();
		for (Entity entity : serverLevel().getEntities(this, getBoundingBox().inflate(24))) {
			if (nearby.size() >= 64) break;
			JsonObject entry = new JsonObject(); entry.addProperty("entityId", entity.getId()); entry.addProperty("name", entity.getName().getString());
			entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()); coordinates(entry, entity.position());
			entry.addProperty("visible", hasLineOfSight(entity)); if (entity instanceof LivingEntity living) entry.addProperty("health", living.getHealth());
			if (entity instanceof ItemEntity dropped) entry.add("item", item(dropped.getItem())); nearby.add(entry);
		}
		data.add("nearbyEntities", nearby);
		JsonArray blocks = new JsonArray();
		BlockPos feet = blockPosition();
		for (int y = -1; y <= 2 && blocks.size() < 180; y++) for (int x = -4; x <= 4 && blocks.size() < 180; x++) for (int z = -4; z <= 4 && blocks.size() < 180; z++) {
			BlockPos block = feet.offset(x, y, z); if (!serverLevel().hasChunkAt(block)) continue;
			var state = serverLevel().getBlockState(block); if (state.isAir()) continue;
			JsonObject entry = new JsonObject(); coordinates(entry, Vec3.atLowerCornerOf(block));
			entry.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()); entry.addProperty("reachable", canInteractWithBlock(block, 0)); blocks.add(entry);
		}
		data.add("nearbyBlocks", blocks);
		JsonObject container = new JsonObject(); container.addProperty("open", containerMenu != inventoryMenu); container.addProperty("slotCount", containerMenu.slots.size());
		if (containerMenu != inventoryMenu) {
			JsonArray slots = new JsonArray();
			for (int index = 0; index < containerMenu.slots.size(); index++) {
				ItemStack stack = containerMenu.slots.get(index).getItem(); if (stack.isEmpty()) continue;
				JsonObject entry = item(stack); entry.addProperty("slot", index); slots.add(entry);
			}
			container.add("slots", slots);
		}
		data.add("container", container); data.addProperty("worldTime", serverLevel().getDayTime());
		return data;
	}
	private static JsonObject item(ItemStack item) {
		JsonObject result = new JsonObject(); result.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
		result.addProperty("name", item.getHoverName().getString()); result.addProperty("count", item.getCount()); return result;
	}
	private static void coordinates(JsonObject value, Vec3 point) { value.addProperty("x", point.x); value.addProperty("y", point.y); value.addProperty("z", point.z); }
	private static Vec3 position(JsonObject args) {
		if (!args.has("x") || !args.has("y") || !args.has("z")) throw new IllegalArgumentException();
		return new Vec3(clamp(number(args, "x", 0), -29_999_980, 29_999_980), clamp(number(args, "y", 0), -2048, 2048), clamp(number(args, "z", 0), -29_999_980, 29_999_980));
	}
	private static int ticks(JsonObject args, int fallback) { return (int) clamp(number(args, "ticks", fallback), 1, 1200); }
	private static double number(JsonObject args, String key, double fallback) {
		double value = args.has(key) ? args.get(key).getAsDouble() : fallback; if (!Double.isFinite(value)) throw new IllegalArgumentException(); return value;
	}
	private static boolean flag(JsonObject args, String key) { return args.has(key) && args.get(key).getAsBoolean(); }
	private static String text(JsonObject args, String key, String fallback) { return args.has(key) ? args.get(key).getAsString() : fallback; }
	private static double clamp(double value, double minimum, double maximum) { return Math.max(minimum, Math.min(maximum, value)); }

	/** No channel, packet queue, keepalive or user-owned client connection exists for this server-side actor. */
	private static final class LocalConnection extends Connection {
		LocalConnection() { super(PacketFlow.SERVERBOUND); }
		@Override public boolean isConnected() { return true; }
		@Override public boolean isConnecting() { return false; }
		@Override public void send(Packet<?> packet, PacketSendListener listener) { }
		// Vanilla resumes flushing every player each server tick, including actors without a channel.
		public void flushChannel() { }
		@Override public void disconnect(Component reason) { }

	}
	private static final class LocalListener extends ServerGamePacketListenerImpl {
		LocalListener(MinecraftServer server, Connection connection, ServerPlayer player) { super(server, connection, player); }
		@Override public void tick() { }
		public void suspendFlushing() { }
		public void resumeFlushing() { }
		@Override public void send(Packet<?> packet, PacketSendListener listener) { }
		@Override public void disconnect(Component reason) { }
	}
    private boolean canInteractWithBlock(BlockPos pos,int padding){return pos.distToCenterSqr(getEyePosition())<=Math.pow(isCreative()?5:4.5,2);}
    private boolean canInteractWithEntity(Entity entity,int padding){return entity.getBoundingBox().getCenter().distanceToSqr(getEyePosition())<=9;}

}
