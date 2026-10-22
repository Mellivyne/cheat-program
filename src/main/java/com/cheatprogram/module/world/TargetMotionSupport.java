package com.cheatprogram.module.world;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Friends;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.util.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns only the real local player's movement; camera poses and AI actors never enter this helper. */
public final class TargetMotionSupport {
    private static final Minecraft MC = Minecraft.getInstance();
    private static TargetMotionSupport inputOwner;
    private final Module owner;
    private final ModeSetting targets;
    private final NumberSetting range;
    private final BoolSetting ignoreFriends;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean queued = new AtomicBoolean();
    private ClientLevel world;
    private LocalPlayer body;
    private LivingEntity target;
    private String previousMode = "";
    private boolean driving;
    private long nextTeleport;
    private double acceleration = 80, tolerance = 0.08, stepHeight = 1.25, lookRate = 720;
    private double leadTicks;
    private boolean edgeGuard = true, autoJump = true;
    private long correctionPause;
    private long pauseUntil;
    private int corrections;
    private Vec3 smoothedVelocity = Vec3.ZERO;

    void configure(double acceleration, double tolerance, double stepHeight, double lookRate,
                   double leadTicks, boolean edgeGuard, boolean autoJump, long correctionPause) {
        this.acceleration = acceleration; this.tolerance = tolerance; this.stepHeight = stepHeight;
        this.lookRate = lookRate; this.leadTicks = leadTicks; this.edgeGuard = edgeGuard; this.autoJump = autoJump;
        this.correctionPause = correctionPause;
    }
    int correctionSerial() { return corrections; }
    String status() { return System.nanoTime() < pauseUntil ? " · " + com.cheatprogram.core.Lang.t("ui.targetmotion.rebasing")
            : corrections > 0 ? " · " + com.cheatprogram.core.Lang.t("ui.targetmotion.corrections", corrections) : ""; }

    /** Vanilla already accepted the teleport and sent its acknowledgement before this hook. */
    public static void onPositionCorrection() {
        for (Class<? extends Module> type : java.util.List.of(EntityOrbit.class, BackLock.class)) {
            Module module = com.cheatprogram.core.ModuleManager.get(type);
            TargetMotionSupport motion = module instanceof EntityOrbit orbit ? orbit.motionSupport()
                    : module instanceof BackLock back ? back.motionSupport() : null;
            if (motion != null && module.isEnabled() && motion.body == MC.player && motion.world == MC.level) {
                // This is the expected acknowledgment of our own authoritative integrated-server teleport.
                if (motion.previousMode.equals("motion_teleport") && MC.getSingleplayerServer() != null) continue;
                motion.corrections++;
                motion.release();
                motion.pauseUntil = System.nanoTime() + motion.correctionPause * 1_000_000L;
                motion.nextTeleport = motion.pauseUntil;
                motion.smoothedVelocity = Vec3.ZERO;
            }
        }
    }

    record Goal(boolean orbit, double angle, double distance, double sideOffset, double heightOffset) {
        Vec3 destination(LivingEntity target) {
            if (orbit) return target.position().add(Math.cos(angle) * distance, heightOffset, Math.sin(angle) * distance);
            // Body yaw describes the torso's facing, rather than a briefly turned head.
            double yaw = Math.toRadians(target.yBodyRot);
            return target.position().add(Math.sin(yaw) * distance + Math.cos(yaw) * sideOffset,
                    heightOffset, -Math.cos(yaw) * distance + Math.sin(yaw) * sideOffset);
        }
    }

    TargetMotionSupport(Module owner, ModeSetting targets, NumberSetting range, BoolSetting ignoreFriends) {
        this.owner = owner; this.targets = targets; this.range = range; this.ignoreFriends = ignoreFriends;
    }

    void reset() {
        release(); target = null; world = null; body = null; previousMode = ""; nextTeleport = 0;
        pauseUntil = 0; corrections = 0; smoothedVelocity = Vec3.ZERO;
    }

    void checkLifecycle() {
        if (world != null && (world != MC.level || body != MC.player || body == null || !body.isAlive()
                || body.isDeadOrDying())) reset();
        if (!owner.isEnabled() && (driving || target != null)) reset();
    }

    boolean ready(String mode) {
        checkLifecycle();
        if (MC.player == null || MC.level == null) { release(); return false; }
        if (world == null) { world = MC.level; body = MC.player; }
        if (!previousMode.equals(mode)) { release(); previousMode = mode; }
        boolean freeView = Freecam.isActive();
        if (!owner.isEnabled() || System.nanoTime() < pauseUntil || !body.isAlive() || body.isDeadOrDying() || body.isSpectator()
                || body.isPassenger() || body.isSleeping() || MC.screen != null || MC.getOverlay() != null
                || MC.isPaused() || !MC.mouseHandler.isMouseGrabbed() || AiController.isRunning() || FlatFlight.ownsMotion()
                || !mode.equals("motion_teleport") && (freeView || body.getAbilities().flying || body.isFallFlying())) {
            release(); return false;
        }
        if (mode.equals("motion_teleport") && MC.getSingleplayerServer() == null) {
            release(); return false;
        }
        return true;
    }

    LivingEntity acquire() {
        if (world == null || body == null) return null;
        LivingEntity chosen = eligible(MC.crosshairPickEntity) ? (LivingEntity) MC.crosshairPickEntity : null;
        if (chosen == null && eligible(target)) return target;
        if (chosen == null) {
            double closest = range.val() * range.val();
            for (Entity entity : world.entitiesForRendering()) {
                if (!eligible(entity)) continue;
                double distance = body.distanceToSqr(entity);
                if (distance < closest) { closest = distance; chosen = (LivingEntity) entity; }
            }
        }
        if (target != chosen) { release(); target = chosen; }
        if (target == null) release();
        return target;
    }

    private boolean eligible(Entity entity) {
        if (!matches(entity, targets.get()) || entity.level() != world || entity == body
                || !world.hasChunkAt(entity.blockPosition()) || body.distanceToSqr(entity) > range.val() * range.val()) return false;
        return !ignoreFriends.on() || !Friends.isFriend(entity);
    }

    private static boolean matches(Entity entity, String kind) {
        if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand || entity.isRemoved()
                || !living.isAlive() || living.isDeadOrDying()) return false;
        if (entity instanceof Player player) return kind.equals("line_players") && !player.isSpectator();
        return kind.equals("line_hostiles") ? entity instanceof Enemy : kind.equals("line_passives") && !(entity instanceof Enemy);
    }

    /** Called after vanilla has read the keyboard. KeyMapping states are never forced or latched. */
    public static void applyInput(ClientInput input) {
        TargetMotionSupport motion = inputOwner;
        if (motion == null || !motion.driving) return;
        if (MC.player == null || input != MC.player.input) { motion.release(); return; }
        if (!motion.ready(motion.previousMode) || !motion.eligible(motion.target)) { motion.release(); return; }
        Input manual = input.keyPresses;
        input.keyPresses = new Input(false, false, false, false, manual.jump(), manual.shift(), false);
        input.forwardImpulse = input.leftImpulse = 0;
        MC.player.setSprinting(false);
    }

    void stopMoving() { release(); }

    private void release() {
        generation.incrementAndGet();
        if (!driving) return;
        driving = false;
        smoothedVelocity = Vec3.ZERO;
        if (inputOwner == this) inputOwner = null;
        if (body != null && body == MC.player && world == MC.level && !AiController.isRunning()) {
            Vec3 movement = body.getDeltaMovement();
            body.setDeltaMovement(0, movement.y, 0);
            restoreInput(body.input);
        }
    }

    private static void restoreInput(ClientInput input) {
        if (MC.options == null || input == null) return;
        boolean active = MC.screen == null && MC.getOverlay() == null && MC.mouseHandler.isMouseGrabbed();
        boolean forward = active && Keys.physical(MC.options.keyUp), backward = active && Keys.physical(MC.options.keyDown);
        boolean left = active && Keys.physical(MC.options.keyLeft), right = active && Keys.physical(MC.options.keyRight);
        input.keyPresses = new Input(forward, backward, left, right, active && Keys.physical(MC.options.keyJump),
                active && Keys.physical(MC.options.keyShift), active && Keys.physical(MC.options.keySprint));
        input.forwardImpulse = forward == backward ? 0 : forward ? 1 : -1;
        input.leftImpulse = left == right ? 0 : left ? 1 : -1;
    }

    void smooth(Goal goal, double blocksPerSecond, boolean lookAt) {
        if (!ready("motion_walk") || !eligible(target)) { release(); return; }
        Vec3 desired = destination(goal);
        // Height offsets name a walkable elevation, never an artificial flying velocity.
        Vec3 landing = standing(world, body, desired, stepHeight, stepHeight);
        if (landing == null && !edgeGuard && loaded(world, box(body, desired))) landing = desired;
        if (landing == null) { release(); return; }
        Vec3 position = body.position();
        double dx = landing.x - position.x, dz = landing.z - position.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < tolerance) { release(); if (lookAt) look(body, target, lookRate); return; }
        double step = Math.min(blocksPerSecond / 20.0, distance);
        double sx = dx / distance * step, sz = dz / distance * step;
        Vec3 requested = new Vec3(sx, 0, sz);
        Vec3 delta = requested.subtract(smoothedVelocity);
        double allowed = acceleration <= 0 ? Double.MAX_VALUE : acceleration / 400;
        if (delta.length() > allowed) delta = delta.normalize().scale(allowed);
        requested = smoothedVelocity.add(delta);
        sx = requested.x; sz = requested.z;
        // Several probes across the next step stop before holes, liquids or an unloaded edge.
        Vec3 previous = position;
        int probes = Math.max(1, (int) Math.ceil(requested.horizontalDistance() / 0.25));
        for (int i = 1; i <= probes; i++) {
            Vec3 probe = new Vec3(position.x + sx * i / probes, position.y, position.z + sz * i / probes);
            Vec3 ground = edgeGuard ? standing(world, body, probe, stepHeight, stepHeight) : probe;
            if (ground == null || !loaded(world, box(body, ground))) {
                sx *= (i - 1.0) / probes; sz *= (i - 1.0) / probes; break;
            }
            previous = ground;
        }
        if (inputOwner != null && inputOwner != this) inputOwner.release();
        inputOwner = this; driving = true;
        Vec3 current = body.getDeltaMovement();
        body.setDeltaMovement(sx, current.y, sz);
        if (autoJump && body.onGround() && (landing.y > position.y + 0.6 || body.horizontalCollision)) body.jumpFromGround();
        smoothedVelocity = new Vec3(sx, 0, sz);
        body.setSprinting(false);
        if (lookAt) look(body, target, lookRate);
    }

    private Vec3 destination(Goal goal) {
        Vec3 lead = target.getDeltaMovement().scale(leadTicks);
        return goal.destination(target).add(lead.x, 0, lead.z);
    }

    /** Ordinary movement and ordinary collision resolution; remote servers can reject this displacement. */
    void burst(Goal goal, double maxStep, int delayMillis, boolean lookAt) {
        if (!ready("motion_burst") || !eligible(target)) { release(); return; }
        long now = System.nanoTime();
        if (now < nextTeleport) return;
        nextTeleport = now + Math.max(0, delayMillis) * 1_000_000L;
        Vec3 wanted = destination(goal);
        Vec3 landing = standing(world, body, wanted, stepHeight, stepHeight);
        if (landing == null && !edgeGuard && loaded(world, box(body, wanted))) landing = wanted;
        if (landing == null) return;
        Vec3 displacement = landing.subtract(body.position());
        if (displacement.length() > maxStep) displacement = displacement.normalize().scale(maxStep);
        // Test ground along the full route, so a large step never skips an unseen hole.
        int samples = Math.max(1, (int) Math.ceil(displacement.length() / 0.25));
        for (int i = 1; i <= samples; i++) {
            Vec3 probe = body.position().add(displacement.scale((double) i / samples));
            if (!loaded(world, box(body, probe)) || edgeGuard && standing(world, body, probe, stepHeight, stepHeight) == null) {
                displacement = displacement.scale((i - 1.0) / samples); break;
            }
        }
        if (inputOwner != null && inputOwner != this) inputOwner.release();
        inputOwner = this; driving = true;
        body.move(MoverType.SELF, displacement);
        body.setDeltaMovement(0, body.getDeltaMovement().y, 0);
        if (lookAt) look(body, target, lookRate);
    }

    void teleport(Goal goal, int delayMillis, boolean lookAt) {
        if (!ready("motion_teleport") || !eligible(target)) { release(); return; }
        if (driving) release();
        long now = System.nanoTime();
        if (now < nextTeleport || !queued.compareAndSet(false, true)) return;
        IntegratedServer server = MC.getSingleplayerServer();
        ClientLevel clientLevel = world;
        LocalPlayer clientBody = body;
        UUID ownerId = body.getUUID(), targetId = target.getUUID();
        ResourceKey<Level> dimension = world.dimension();
        String kind = targets.get();
        double maximumRange = range.val();
        long token = generation.get();
        boolean rotate = lookAt && !Freecam.isActive();
        nextTeleport = now + Math.max(0, delayMillis) * 1_000_000L;
        server.execute(() -> {
            try {
                if (!valid(token, clientLevel, clientBody, server)) return;
                ServerLevel level = server.getLevel(dimension);
                // Resolve only the UUID of the real networked client, never an arbitrary AI player.
                ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
                if (level == null || player == null || player.serverLevel() != level || player.isSpectator()
                        || player.isPassenger() || player.isSleeping() || !player.isAlive() || player.isDeadOrDying()) return;
                Entity entity = level.getEntity(targetId);
                if (!matches(entity, kind) || entity == player || !loaded(level, entity.getBoundingBox())
                        || player.distanceToSqr(entity) > maximumRange * maximumRange) return;
                LivingEntity actualTarget = (LivingEntity) entity;
                Vec3 landing = standing(level, player, goal.destination(actualTarget), stepHeight, stepHeight);
                if (landing == null || !valid(token, clientLevel, clientBody, server)) return;
                if (player.position().distanceToSqr(landing) < 0.0025) return;
                float yaw = player.getYRot(), pitch = player.getXRot();
                if (rotate) {
                    Vec3 to = actualTarget.getEyePosition().subtract(landing.add(0, player.getEyeHeight(), 0));
                    yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90);
                    pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
                }
                player.teleportTo(level, landing.x, landing.y, landing.z, Set.of(), yaw, pitch, false);
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0;
            } finally { queued.set(false); }
        });
    }

    private boolean valid(long token, ClientLevel level, LocalPlayer player, IntegratedServer server) {
        return generation.get() == token && owner.isEnabled() && world == level && body == player
                && MC.level == level && MC.player == player && MC.getSingleplayerServer() == server
                && MC.screen == null && MC.getOverlay() == null && !MC.isPaused()
                && MC.mouseHandler.isMouseGrabbed() && !AiController.isRunning();
    }

    private static void look(LocalPlayer player, LivingEntity target, double degreesPerSecond) {
        if (Freecam.isActive()) return;
        Vec3 to = target.getEyePosition().subtract(player.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        float limit = (float) (degreesPerSecond / 20);
        player.setYRot(player.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - player.getYRot()), -limit, limit));
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(pitch - player.getXRot(), -limit, limit), -90, 90));
    }

    private static AABB box(LivingEntity player, Vec3 position) {
        return player.getBoundingBox().move(position.subtract(player.position()));
    }

    /** No chunk loads, world-border crossing, liquid, harmful block or unsupported human-sized landing. */
    private static Vec3 standing(Level level, LivingEntity player, Vec3 wanted, double up, double down) {
        if (!Double.isFinite(wanted.x) || !Double.isFinite(wanted.y) || !Double.isFinite(wanted.z)) return null;
        AABB footprint = box(player, wanted);
        if (!loaded(level, footprint.inflate(0.01, 1.5, 0.01))) return null;
        Vec3 closest = null;
        double best = Double.MAX_VALUE;
        for (int x = Mth.floor(footprint.minX); x <= Mth.floor(footprint.maxX - 0.0001); x++) {
            for (int z = Mth.floor(footprint.minZ); z <= Mth.floor(footprint.maxZ - 0.0001); z++) {
                for (int y = Mth.floor(wanted.y + up); y >= Mth.floor(wanted.y - down) - 1; y--) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (!level.isInWorldBounds(position)) continue;
                    BlockState state = level.getBlockState(position);
                    if (danger(state)) continue;
                    VoxelShape shape = state.getCollisionShape(level, position, CollisionContext.of(player));
                    if (shape.isEmpty()) continue;
                    double feet = y + shape.max(Direction.Axis.Y);
                    if (feet > wanted.y + up + 0.001 || feet < wanted.y - down - 0.001) continue;
                    Vec3 candidate = new Vec3(wanted.x, feet, wanted.z);
                    AABB body = box(player, candidate).deflate(0.001);
                    double difference = Math.abs(feet - wanted.y);
                    if (difference >= best || !loaded(level, body) || !level.noCollision(player, body)
                            || !safeBlocks(level, body) || !supported(level, player, body, feet)) continue;
                    closest = candidate; best = difference;
                }
            }
        }
        return closest;
    }

    private static boolean supported(Level level, LivingEntity player, AABB body, double feet) {
        double inset = Math.min(0.06, (body.maxX - body.minX) * 0.2);
        double[] xs = {body.minX + inset, (body.minX + body.maxX) * 0.5, body.maxX - inset};
        double[] zs = {body.minZ + inset, (body.minZ + body.maxZ) * 0.5, body.maxZ - inset};
        for (double x : xs) for (double z : zs) {
            AABB contact = new AABB(x - 0.012, feet - 0.04, z - 0.012, x + 0.012, feet + 0.001, z + 0.012);
            boolean solid = false;
            for (VoxelShape shape : level.getBlockCollisions(player, contact)) if (!shape.isEmpty()) { solid = true; break; }
            if (!solid) return false;
        }
        return true;
    }

    private static boolean safeBlocks(Level level, AABB body) {
        for (BlockPos p : BlockPos.betweenClosed(Mth.floor(body.minX), Mth.floor(body.minY - 0.04), Mth.floor(body.minZ),
                Mth.floor(body.maxX - 0.0001), Mth.floor(body.maxY - 0.0001), Mth.floor(body.maxZ - 0.0001))) {
            if (!level.getFluidState(p).isEmpty() || danger(level.getBlockState(p))) return false;
        }
        return true;
    }

    private static boolean danger(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.WATER) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private static boolean loaded(Level level, AABB box) {
        if (box.minY < level.getMinY() || box.maxY > level.getMaxY() + 1 || !level.getWorldBorder().isWithinBounds(box)) return false;
        int x0 = Mth.floor(box.minX) >> 4, x1 = Mth.floor(box.maxX - 0.0001) >> 4;
        int z0 = Mth.floor(box.minZ) >> 4, z1 = Mth.floor(box.maxZ - 0.0001) >> 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++)
            if (!level.hasChunkAt(new BlockPos(x << 4, Mth.floor(box.minY), z << 4))) return false;
        return true;
    }
}
