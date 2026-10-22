package com.cheatprogram.module.movement;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.module.utility.Blink;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.module.world.BackLock;
import com.cheatprogram.module.world.EntityOrbit;
import com.cheatprogram.render.Render3D;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Player-directed position experiments, without attacks or commands. Inspired by the
 * ordinary movement-packet mechanism documented in Meteor's ClickTP/Blink sources;
 * independently implemented. Vanilla corrections and teleport acknowledgments always run.
 * A local arrival is an attempted position, never proof that the server accepted it.
 */
public final class TeleportAura extends Module {
    private final TextSetting target = add(new TextSetting("target", "", 80));
    private final ModeSetting behavior = add(new ModeSetting("behavior", "tpa_once", "tpa_once", "tpa_follow", "tpa_orbit"));
    private final ModeSetting movementMode = add(new ModeSetting("movementMode", "tpa_segment", "tpa_segment", "tpa_direct"));
    private final NumberSetting range = add(new NumberSetting("range", 128, 4, 1024, 4));
    private final NumberSetting distance = add(new NumberSetting("distance", 2.5, .7, 128, .1));
    private final NumberSetting heightOffset = add(new NumberSetting("heightOffset", 0, -64, 64, .1));
    private final NumberSetting step = add(new NumberSetting("step", 2, .1, 256, .1)).visibleWhen(() -> movementMode.is("tpa_segment"));
    private final NumberSetting speed = add(new NumberSetting("speed", 20, .5, 5000, .5, "b/s")).visibleWhen(() -> movementMode.is("tpa_segment"));
    private final NumberSetting interval = add(new NumberSetting("interval", 100, 0, 5000, 10, "ms"));
    private final NumberSetting orbitSpeed = add(new NumberSetting("orbitSpeed", 90, 1, 7200, 5, "°/s")).visibleWhen(() -> behavior.is("tpa_orbit"));
    private final BoolSetting clockwise = add(new BoolSetting("clockwise", true)).visibleWhen(() -> behavior.is("tpa_orbit"));
    private final BoolSetting collision = add(new BoolSetting("collision", true));
    private final BoolSetting edgeGuard = add(new BoolSetting("edgeGuard", true));
    private final BoolSetting preview = add(new BoolSetting("preview", true));
    private final NumberSetting correctionPause = add(new NumberSetting("correctionPause", 1000, 100, 30000, 100, "ms"));
    private final NumberSetting correctionLimit = add(new NumberSetting("correctionLimit", 4, 1, 32, 1));
    private ClientLevel world;
    private LocalPlayer body;
    private Player selected;
    private String boundTarget = "", boundBehavior = "", status = "idle";
    private Vec3 destination;
    private final List<Vec3> route = new ArrayList<>();
    private boolean routeSafe, localArrival, stopped;
    private long nextMove, lastMove, lastTick, pauseUntil;
    private int corrections, submitted;
    private double angle, adaptiveScale = 1;

    public TeleportAura() { super("teleportaura", Category.MOVEMENT); }
    public List<String> helpKeys() { return List.of("ui.teleportaura.help", "ui.teleportaura.helpCoordinates", "ui.teleportaura.helpPreview"); }
    @Override public String suffix() {
        return Lang.t("ui.teleportaura." + status) + " · " + Lang.t("ui.teleportaura.counts", submitted, corrections);
    }
    @Override public void onEnable() {
        reset();
        if (AiController.isRunning()) AiController.stop();
        for (Class<? extends Module> type : List.of(Freecam.class, FlatFlight.class, EntityOrbit.class, BackLock.class)) {
            Module other = ModuleManager.get(type);
            if (other != null && other.isEnabled()) other.setEnabled(false);
        }
    }
    @Override public void onDisable() { reset(); }
    private void reset() {
        world = null; body = null; selected = null; destination = null; route.clear();
        nextMove = lastMove = lastTick = pauseUntil = 0; corrections = submitted = 0;
        angle = 0; adaptiveScale = 1; routeSafe = localArrival = stopped = false;
        boundTarget = boundBehavior = ""; status = "idle";
    }
    /** Global tick hook must also run while no world is available. */
    public static void checkLifecycleAll() {
        TeleportAura module = ModuleManager.get(TeleportAura.class);
        if (module != null) module.checkLifecycle();
    }
    public void checkLifecycle() {
        if (!isEnabled() || body != null && (body != mc.player || world != mc.level || !body.isAlive() || body.isRemoved())) reset();
    }
    private boolean controlsReady() {
        return isEnabled() && inGame() && mc.getConnection() != null && mc.player.isAlive()
                && !mc.player.isSpectator() && !mc.player.isPassenger() && !mc.player.isSleeping()
                && !AiController.isRunning() && !ModuleManager.on(Freecam.class) && !ModuleManager.on(FlatFlight.class)
                && !ModuleManager.on(EntityOrbit.class) && !ModuleManager.on(BackLock.class) && !ModuleManager.on(Blink.class)
                && mc.screen == null && mc.getOverlay() == null && mc.isWindowActive() && !mc.isPaused();
    }
    /** Other movement/input owners may yield to this module without changing vanilla input. */
    public static boolean ownsMotion() {
        TeleportAura module = ModuleManager.get(TeleportAura.class);
        return module != null && module.controlsReady() && module.body == mc.player && module.world == mc.level
                && !module.stopped && !module.localArrival && module.selected != null && module.routeSafe
                && System.nanoTime() >= module.pauseUntil;
    }
    /** Invoke at TAIL of vanilla handleMovePlayer, after its acknowledgement and position apply. */
    public static void onServerCorrection() {
        TeleportAura module = ModuleManager.get(TeleportAura.class);
        if (module == null || !module.isEnabled() || module.body != mc.player || module.world != mc.level || module.submitted == 0) return;
        module.corrections++;
        module.localArrival = false; module.routeSafe = false; module.route.clear(); module.destination = null;
        module.lastMove = 0; module.adaptiveScale = Math.max(.125, module.adaptiveScale * .5);
        long delay = (long)Math.min(30000, module.correctionPause.val() * Math.pow(2, Math.min(5, module.corrections - 1)));
        module.pauseUntil = System.nanoTime() + delay * 1_000_000L;
        module.nextMove = module.pauseUntil;
        module.stopped = module.corrections >= module.correctionLimit.intVal();
        module.status = module.stopped ? "stopped" : "backoff";
        // No position restoration, canceled packet, spoofed acknowledgement or deferred retry here.
    }
    @Override public void onTick() {
        checkLifecycle();
        if (!inGame()) return;
        if (body != mc.player || world != mc.level) { reset(); body = mc.player; world = mc.level; }
        String text = target.get().trim();
        if (!text.equals(boundTarget) || !behavior.get().equals(boundBehavior)) {
            selected = null; destination = null; route.clear(); localArrival = false; stopped = false;
            corrections = submitted = 0; pauseUntil = nextMove = lastMove = 0; adaptiveScale = 1;
            boundTarget = text; boundBehavior = behavior.get();
        }
        if (!controlsReady()) { routeSafe = false; route.clear(); status = "paused"; lastTick = 0; return; }
        long now = System.nanoTime();
        double dt = lastTick == 0 ? .05 : Math.max(0, Math.min(.15, (now - lastTick) / 1_000_000_000d));
        lastTick = now;
        if (stopped) { status = "stopped"; return; }
        if (now < pauseUntil) { status = "backoff"; return; }
        Player found = find(text);
        if (found == null) { selected = null; destination = null; routeSafe = false; route.clear(); localArrival = false; return; }
        if (found != selected) {
            selected = found; localArrival = false;
            angle = Math.atan2(body.getZ() - selected.getZ(), body.getX() - selected.getX());
        }
        if (body.distanceToSqr(selected) > range.val() * range.val()) {
            routeSafe = false; route.clear(); destination = null; status = "outOfRange"; return;
        }
        if (behavior.is("tpa_orbit")) angle = Math.IEEEremainder(angle + Math.toRadians(orbitSpeed.val()) * dt * (clockwise.on() ? 1 : -1), Math.PI * 2);
        else if (behavior.is("tpa_follow")) angle = Math.toRadians(selected.yBodyRot - 90);
        Vec3 wanted = selected.position().add(Math.cos(angle) * distance.val(), heightOffset.val(), Math.sin(angle) * distance.val());
        destination = landing(wanted);
        if (destination == null) { routeSafe = false; route.clear(); status = "noLanding"; return; }
        Vec3 start = body.position(), delta = destination.subtract(start);
        if (delta.lengthSqr() <= .01) {
            localArrival = behavior.is("tpa_once"); routeSafe = false; route.clear();
            status = submitted == 0 ? "alreadyNear" : "localArrival"; return;
        }
        if (localArrival && behavior.is("tpa_once")) { status = "localArrival"; return; }
        double elapsed = lastMove == 0 ? .05 : Math.max(.05, Math.min(.25, (now - lastMove) / 1_000_000_000d));
        double length = movementMode.is("tpa_direct") ? delta.length()
                : Math.min(step.val() * adaptiveScale, speed.val() * adaptiveScale * elapsed);
        Vec3 next = start.add(delta.normalize().scale(Math.min(delta.length(), length)));
        routeSafe = safePath(start, next);
        plan(start, destination, Math.max(.1, length));
        if (!routeSafe) { status = "blocked"; return; }
        status = "attempting";
        if (now < nextMove) return;
        // The actual coordinates are ordinary movement data. Vanilla still decides whether
        // they are legal, applies corrections, and sends any required teleport confirmation.
        boolean grounded = !world.noCollision(body, box(next).move(0, -.025, 0));
        body.setPos(next); body.setDeltaMovement(Vec3.ZERO);
        body.connection.send(new ServerboundMovePlayerPacket.Pos(next.x, next.y, next.z, grounded, body.horizontalCollision));
        submitted++; lastMove = now; nextMove = now + Math.max(0, interval.intVal()) * 1_000_000L;
        if (next.distanceToSqr(destination) <= .01 && behavior.is("tpa_once")) {
            localArrival = true; routeSafe = false; status = "localArrival";
        }
    }
    private Player find(String text) {
        if (text.isEmpty()) { status = "enterTarget"; return null; }
        UUID uuid = null;
        try { uuid = UUID.fromString(text); } catch (IllegalArgumentException ignored) { }
        for (Player player : world.players()) if (player != body && !player.isRemoved() && player.isAlive()
                && (uuid != null ? uuid.equals(player.getUUID()) : player.getGameProfile().name().equalsIgnoreCase(text))) return player;
        boolean tabOnly = false;
        if (mc.getConnection() != null) for (var info : mc.getConnection().getOnlinePlayers()) {
            if (uuid != null ? uuid.equals(info.getProfile().id()) : info.getProfile().name().equalsIgnoreCase(text)) { tabOnly = true; break; }
        }
        status = tabOnly ? "unknownCoordinates" : "notLoaded";
        return null;
    }
    private AABB box(Vec3 feet) { return body.getBoundingBox().move(feet.subtract(body.position())).deflate(.001); }
    private boolean loaded(AABB bounds) {
        if (bounds.minY < world.getMinY() || bounds.maxY > world.getMaxY() + 1 || !world.getWorldBorder().isWithinBounds(bounds)) return false;
        int x0 = Mth.floor(bounds.minX) >> 4, x1 = Mth.floor(bounds.maxX - .0001) >> 4;
        int z0 = Mth.floor(bounds.minZ) >> 4, z1 = Mth.floor(bounds.maxZ - .0001) >> 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++)
            if (world.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) return false;
        return true;
    }
    private boolean validFeet(Vec3 feet) {
        AABB bounds = box(feet);
        if (!loaded(bounds.inflate(0, .05, 0))) return false;
        if (collision.on() && (!world.noCollision(body, bounds)
                || !world.getEntities(body, bounds, entity -> entity.isPickable() && !entity.isRemoved()).isEmpty())) return false;
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(bounds.minX), Mth.floor(bounds.minY - .03), Mth.floor(bounds.minZ),
                Mth.floor(bounds.maxX - .0001), Mth.floor(bounds.maxY - .0001), Mth.floor(bounds.maxZ - .0001))) {
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.POWDER_SNOW)
                    || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)) return false;
        }
        return !edgeGuard.on() || supported(bounds);
    }
    private boolean supported(AABB bounds) {
        double[] xs = {bounds.minX + .05, (bounds.minX + bounds.maxX) * .5, bounds.maxX - .05};
        double[] zs = {bounds.minZ + .05, (bounds.minZ + bounds.maxZ) * .5, bounds.maxZ - .05};
        for (double x : xs) for (double z : zs) {
            AABB contact = new AABB(x - .012, bounds.minY - .04, z - .012, x + .012, bounds.minY + .002, z + .012);
            boolean hit = false;
            for (VoxelShape shape : world.getBlockCollisions(body, contact)) if (!shape.isEmpty()) { hit = true; break; }
            if (!hit) return false;
        }
        return true;
    }
    private Vec3 landing(Vec3 wanted) {
        if (validFeet(wanted)) return wanted;
        if (!edgeGuard.on()) return null;
        Vec3 best = null; double nearest = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -2; dy <= 1; dy++) {
            double x = wanted.x + dx * .5, z = wanted.z + dz * .5;
            BlockPos support = BlockPos.containing(x, wanted.y + dy - .05, z);
            if (!loaded(new AABB(support).inflate(.01))) continue;
            var shape = world.getBlockState(support).getCollisionShape(world, support);
            if (shape.isEmpty()) continue;
            Vec3 candidate = new Vec3(x, support.getY() + shape.max(net.minecraft.core.Direction.Axis.Y), z);
            double d = candidate.distanceToSqr(wanted);
            if (d < nearest && validFeet(candidate)) { best = candidate; nearest = d; }
        }
        return best;
    }
    private boolean safePath(Vec3 start, Vec3 end) {
        double distance = start.distanceTo(end);
        int samples = Math.max(1, (int)Math.ceil(distance / (collision.on() ? .25 : .75)));
        if (samples > 4096) return false;
        for (int i = 1; i <= samples; i++) if (!validFeet(start.lerp(end, i / (double)samples))) return false;
        return true;
    }
    private void plan(Vec3 start, Vec3 end, double spacing) {
        route.clear();
        int count = movementMode.is("tpa_direct") ? 1 : Math.max(1, Math.min(64, (int)Math.ceil(start.distanceTo(end) / spacing)));
        double length = start.distanceTo(end);
        for (int i = 1; i <= count; i++) route.add(start.lerp(end,
                movementMode.is("tpa_direct") ? 1 : Math.min(1, spacing * i / length)));
    }
    @Override public void onRender3D(Render3D render) {
        if (!preview.on() || world != mc.level || body != mc.player || destination == null || selected == null) return;
        Vec3 from = body.position().add(0, .08, 0);
        int color = routeSafe ? 0xBB55DDE5 : 0xCCEF6F78;
        for (int i = 0; i < route.size(); i++) {
            Vec3 point = route.get(i); int nodeColor = i == 0 ? color : 0x668B9BA6;
            Vec3 to = point.add(0, .08, 0); render.line(from, to, nodeColor, 1); from = to;
            render.box(box(point), 0x0839C5D6, nodeColor, 1);
        }
        render.box(box(destination), 0x188B9BA6, 0xAA8B9BA6, 1.5f);
    }
}
