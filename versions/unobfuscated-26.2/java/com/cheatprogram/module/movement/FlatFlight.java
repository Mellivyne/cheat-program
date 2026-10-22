package com.cheatprogram.module.movement;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.utility.Blink;
import com.cheatprogram.module.visual.Freecam;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Horizontal flight experiments and a genuine vanilla Elytra pitch controller.
 * No movement packets, permission flags, collision results or teleport acknowledgments
 * are fabricated. Hover/descent still change physics and may be rejected by a server.
 */
public final class FlatFlight extends Module {
    private final ModeSetting mode = add(new ModeSetting("mode", "flat_descent", "flat_descent", "flat_hover", "flat_elytra"));
    private final NumberSetting speed = add(new NumberSetting("speed", 4.3, 0.5, 20, 0.1)).visibleWhen(() -> !elytra());
    private final NumberSetting acceleration = add(new NumberSetting("acceleration", 10, 1, 50, 0.5)).visibleWhen(() -> !elytra());
    private final NumberSetting braking = add(new NumberSetting("braking", 16, 1, 80, 0.5)).visibleWhen(() -> !elytra());
    private final NumberSetting descent = add(new NumberSetting("descent", 0.04, 0.01, 0.25, 0.01)).visibleWhen(() -> mode.is("flat_descent"));
    private final BoolSetting antiFall = add(new BoolSetting("antiFall", true)).visibleWhen(() -> !elytra());
    private final BoolSetting keyHeight = add(new BoolSetting("keyHeight", true)).visibleWhen(() -> !elytra());
    private final NumberSetting verticalSpeed = add(new NumberSetting("verticalSpeed", 3, 0.5, 12, 0.5)).visibleWhen(() -> !elytra() && keyHeight.on());
    private final BoolSetting downwardPulse = add(new BoolSetting("downwardPulse", false)).visibleWhen(() -> !elytra());
    private final NumberSetting pulseDepth = add(new NumberSetting("pulseDepth", 0.06, 0.04, 0.1, 0.01)).visibleWhen(() -> !elytra() && downwardPulse.on());
    private final NumberSetting pulseInterval = add(new NumberSetting("pulseInterval", 20, 10, 60, 1)).visibleWhen(() -> !elytra() && downwardPulse.on());
    private final BoolSetting autoTakeoff = add(new BoolSetting("autoTakeoff", true)).visibleWhen(this::elytra);
    private final BoolSetting glidePitchControl = add(new BoolSetting("glidePitchControl", true)).visibleWhen(this::elytra);
    private final NumberSetting glidePitchLimit = add(new NumberSetting("glidePitchLimit", 35, 10, 60, 1)).visibleWhen(() -> elytra() && glidePitchControl.on());
    private final NumberSetting pitchRate = add(new NumberSetting("pitchRate", 90, 10, 360, 5)).visibleWhen(() -> elytra() && glidePitchControl.on());
    private final NumberSetting cruiseThreshold = add(new NumberSetting("cruiseThreshold", 0.3, 0.1, 1.0, 0.05)).visibleWhen(() -> elytra() && glidePitchControl.on());
    private final BoolSetting chunkGuard = add(new BoolSetting("chunkGuard", true)).visibleWhen(() -> !elytra());
    private final ModeSetting correctionAction = add(new ModeSetting("correctionAction", "flat_pause", "flat_pause", "flat_disable"));
    private final NumberSetting correctionPause = add(new NumberSetting("correctionPause", 0.75, 0.25, 2, 0.05));
    private final NumberSetting correctionLimit = add(new NumberSetting("correctionLimit", 3, 1, 12, 1)).visibleWhen(() -> correctionAction.is("flat_disable"));
    private final BoolSetting adaptiveSpeed = add(new BoolSetting("adaptiveSpeed", true)).visibleWhen(() -> !elytra());
    private final BoolSetting correctionNotice = add(new BoolSetting("correctionNotice", true));

    private ClientLevel world;
    private LocalPlayer body;
    private String boundMode;
    private double heldY, desiredY, velocityX, velocityZ;
    private double adaptiveScale = 1;
    private int lastDriveTick = Integer.MIN_VALUE, lastMoveTick = Integer.MIN_VALUE;
    private Vec3 moveStart;
    private Vec3 moveRequest;
    private boolean takeoffAttempted, waitingForChunk, recoveringAirspeed;
    private int correctionCount;
    private long pausedUntil, lastCorrectionAt, lastNoticeAt;
    private final ArrayDeque<Long> corrections = new ArrayDeque<>();

    public FlatFlight() { super("flatflight", Category.MOVEMENT); }

    private boolean elytra() { return mode.is("flat_elytra"); }

    @Override public void onEnable() {
        Module old = ModuleManager.get(Flight.class);
        if (old != null && old.isEnabled()) old.setEnabled(false);
        old = ModuleManager.get(AirWalk.class);
        if (old != null && old.isEnabled()) old.setEnabled(false);
        reset();
        if (canOwnMotion()) attach();
    }

    @Override public void onDisable() { reset(); }

    private void reset() {
        world = null;
        body = null;
        boundMode = null;
        velocityX = velocityZ = 0;
        adaptiveScale = 1;
        lastDriveTick = lastMoveTick = Integer.MIN_VALUE;
        moveStart = moveRequest = null;
        takeoffAttempted = waitingForChunk = recoveringAirspeed = false;
        correctionCount = 0;
        pausedUntil = lastCorrectionAt = lastNoticeAt = 0;
        corrections.clear();
    }

    private static boolean baseReady() {
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isRemoved()
                && !mc.player.isSpectator() && !mc.player.isPassenger() && !mc.player.noPhysics
                && !mc.player.getAbilities().flying && !mc.player.isInWater() && !mc.player.isInLava()
                && !mc.player.onClimbable() && !mc.player.hasEffect(MobEffects.LEVITATION)
                && !AiController.isRunning() && !Freecam.isActive() && !ModuleManager.on(Blink.class)
                && !ModuleManager.on(Flight.class);
    }

    private static boolean equippedElytra() {
        if (mc.player == null) return false;
        var chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return chest.is(Items.ELYTRA) && LivingEntity.canGlideUsing(chest, EquipmentSlot.CHEST);
    }

    private boolean canOwnMotion() {
        return baseReady() && (elytra() ? equippedElytra() : !mc.player.isFallFlying());
    }

    private boolean controlsReady() {
        return canOwnMotion() && mc.gui.screen() == null && mc.gui.overlay() == null
                && !mc.isPaused() && mc.isWindowActive() && System.nanoTime() >= pausedUntil;
    }

    private void attach() {
        world = mc.level;
        body = mc.player;
        boundMode = mode.get();
        heldY = desiredY = body.getY();
        velocityX = body.getDeltaMovement().x;
        velocityZ = body.getDeltaMovement().z;
        lastDriveTick = lastMoveTick = Integer.MIN_VALUE;
        moveStart = moveRequest = null;
        takeoffAttempted = body.isFallFlying();
        waitingForChunk = recoveringAirspeed = false;
    }

    /** Called globally even when the module has no world tick, so no stale flight survives a reconnect. */
    public static void tickLifecycle() {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        if (module == null) return;
        if (!module.isEnabled() || !baseReady() || module.body != null
                && (module.body != mc.player || module.world != mc.level)) module.reset();
    }

    public static boolean ownsMotion() {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        return module != null && module.isEnabled() && module.canOwnMotion();
    }

    /** Silent look packet owners must yield, or the server would simulate a different Elytra heading. */
    public static boolean usesVanillaGlide() {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        return ownsMotion() && module.elytra();
    }

    /** Keep the shared fall guard active while controls pause or a flight mode is changing. */
    public static boolean requestsFallProtection() {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        return module != null && module.isEnabled() && !module.elytra() && module.antiFall.on();
    }

    /**
     * The current controlled displacement, before real collision clipping. Rescue prediction must
     * use this instead of applying vanilla gravity to last tick's travel velocity.
     * Returns null whenever this module is yielding to vanilla movement.
     */
    public static Vec3 plannedMotion() {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        if (module == null || !module.isEnabled() || module.elytra() || !module.controlsReady()
                || module.body != mc.player || module.world != mc.level
                || module.lastDriveTick != mc.player.tickCount && module.lastDriveTick != mc.player.tickCount - 1) return null;
        return module.plannedMove(mc.player, false);
    }

    @Override public void onTick() {
        if (!canOwnMotion()) { reset(); return; }
        if (body != mc.player || world != mc.level || !mode.get().equals(boundMode)) attach();
        if (lastDriveTick == body.tickCount) return;
        lastDriveTick = body.tickCount;
        waitingForChunk = false;
        if (!controlsReady()) {
            heldY = desiredY = body.getY();
            velocityX = body.getDeltaMovement().x;
            velocityZ = body.getDeltaMovement().z;
            return;
        }
        long now = System.nanoTime();
        if (now - lastCorrectionAt > 5_000_000_000L) adaptiveScale = Math.min(1, adaptiveScale + 0.0025);
        if (elytra()) { tickElytra(); return; }

        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double magnitude = Math.hypot(forward, strafe);
        double targetX = 0, targetZ = 0;
        if (magnitude > 0) {
            forward /= magnitude;
            strafe /= magnitude;
            double yaw = Math.toRadians(body.getYRot());
            double step = speed.val() / 20 * (adaptiveSpeed.on() ? adaptiveScale : 1);
            targetX = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * step;
            targetZ = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * step;
        }
        double deltaX = targetX - velocityX, deltaZ = targetZ - velocityZ;
        double delta = Math.hypot(deltaX, deltaZ);
        double limit = (magnitude > 0 ? acceleration.val() : braking.val()) / 400;
        if (delta > limit && delta > 0) { deltaX *= limit / delta; deltaZ *= limit / delta; }
        velocityX += deltaX;
        velocityZ += deltaZ;

        double vertical = mode.is("flat_descent") ? -descent.val() : 0;
        if (keyHeight.on()) {
            int direction = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);
            if (direction != 0) vertical = direction * verticalSpeed.val() / 20;
            clearVerticalKeys(body.input);
        }
        if (downwardPulse.on() && !body.onGround() && body.tickCount % pulseInterval.intVal() == 0)
            vertical -= pulseDepth.val();
        desiredY = (mode.is("flat_hover") ? heldY : body.getY()) + vertical;
        desiredY = Mth.clamp(desiredY, world.getMinY(), world.getMaxY() - body.getBbHeight() - 0.01);
    }

    private void tickElytra() {
        if (body.onGround()) { takeoffAttempted = false; heldY = body.getY(); }
        if (!body.isFallFlying()) {
            heldY = body.getY();
            // One ordinary start request per airborne attempt, never a repeated packet fly sequence.
            if (autoTakeoff.on() && !takeoffAttempted && !body.onGround() && body.getDeltaMovement().y < -0.01) {
                takeoffAttempted = true;
                if (body.tryToStartFallFlying()) body.connection.send(new ServerboundPlayerCommandPacket(
                        body, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            return;
        }
        takeoffAttempted = true;
        if (!glidePitchControl.on()) { heldY = body.getY(); return; }

        Vec3 velocity = body.getDeltaMovement();
        float currentPitch = body.getXRot();
        float bestPitch = currentPitch;
        double gravity = body.getGravity();
        if (velocity.y <= 0 && body.hasEffect(MobEffects.SLOW_FALLING)) gravity = Math.min(gravity, 0.01);
        double airspeed = velocity.horizontalDistance();
        if (airspeed < cruiseThreshold.val()) recoveringAirspeed = true;
        else if (airspeed > cruiseThreshold.val() * 1.25) recoveringAirspeed = false;
        if (recoveringAirspeed) {
            // With no propulsion, regain airspeed by descending rather than inventing lift.
            bestPitch = (float) Math.min(18, glidePitchLimit.val());
            heldY = body.getY();
        } else {
            double targetDelta = Mth.clamp(heldY - body.getY(), -2, 2);
            double bestCost = Double.POSITIVE_INFINITY;
            for (double pitch = -glidePitchLimit.val(); pitch <= glidePitchLimit.val() + 1.0e-6; pitch += 2) {
                Vec3 predicted = velocity;
                double height = 0;
                for (int step = 0; step < 5; step++) {
                    predicted = predictGlide(predicted, (float) pitch, body.getYRot(), gravity);
                    height += predicted.y;
                }
                double targetVelocity = Mth.clamp(targetDelta * 0.12, -0.15, 0.15);
                double cost = Math.abs(height - targetDelta * 0.4) + Math.abs(predicted.y - targetVelocity) * 2
                        + Math.abs(pitch - currentPitch) * 0.002;
                if (cost < bestCost) { bestCost = cost; bestPitch = (float) pitch; }
            }
        }
        float step = pitchRate.floatVal() / 20;
        body.setXRot(currentPitch + Mth.clamp(bestPitch - currentPitch, -step, step));
    }

    /** Same gravity, lift and drag as 1.21.4 LivingEntity.updateFallFlyingMovement; prediction only. */
    private static Vec3 predictGlide(Vec3 velocity, float pitch, float yaw, double gravity) {
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        double horizontalLook = Math.hypot(look.x, look.z);
        double horizontalSpeed = velocity.horizontalDistance();
        double radians = Math.toRadians(pitch);
        double cosineSquared = Math.cos(radians) * Math.cos(radians);
        velocity = velocity.add(0, gravity * (-1 + cosineSquared * 0.75), 0);
        if (velocity.y < 0 && horizontalLook > 0) {
            double lift = velocity.y * -0.1 * cosineSquared;
            velocity = velocity.add(look.x * lift / horizontalLook, lift, look.z * lift / horizontalLook);
        }
        if (pitch < 0 && horizontalLook > 0) {
            double lift = horizontalSpeed * -Math.sin(radians) * 0.04;
            velocity = velocity.add(-look.x * lift / horizontalLook, lift * 3.2, -look.z * lift / horizontalLook);
        }
        if (horizontalLook > 0) velocity = velocity.add((look.x / horizontalLook * horizontalSpeed - velocity.x) * 0.1,
                0, (look.z / horizontalLook * horizontalSpeed - velocity.z) * 0.1);
        return velocity.multiply(0.99F, 0.98F, 0.99F);
    }

    /** Filters only the genuine local travel move. Vanilla still clips it against real collision shapes. */
    public static Vec3 applyMove(Entity entity, MoverType type, Vec3 original) {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        if (module == null || !module.isEnabled() || module.elytra() || entity != mc.player
                || type != MoverType.SELF && type != MoverType.PLAYER || !module.controlsReady()
                || module.body != mc.player || module.world != mc.level
                || module.lastDriveTick != mc.player.tickCount && module.lastDriveTick != mc.player.tickCount - 1
                || module.lastMoveTick == mc.player.tickCount) return original;
        module.lastMoveTick = mc.player.tickCount;
        Vec3 movement = module.plannedMove(entity, true);
        module.moveStart = entity.position();
        module.moveRequest = movement;
        entity.setDeltaMovement(movement);
        return movement;
    }

    private Vec3 plannedMove(Entity entity, boolean updateChunkStatus) {
        // A held descent and a scheduled pulse can occur together in the same tick.
        double maxVertical = Math.max(verticalSpeed.val() / 20, descent.val())
                + (downwardPulse.on() ? pulseDepth.val() : 0);
        double y = Mth.clamp(desiredY - entity.getY(), -maxVertical, maxVertical);
        // A tiny real downward contact probe preserves ordinary ground collision/step behavior.
        if (Math.abs(y) < 1.0e-7 && entity.onGround()) y = -0.0001;
        Vec3 movement = new Vec3(velocityX, y, velocityZ);
        if (chunkGuard.on() && !loaded(entity.getBoundingBox().expandTowards(movement))) {
            double low = 0, high = 1;
            for (int step = 0; step < 12; step++) {
                double mid = (low + high) * 0.5;
                if (loaded(entity.getBoundingBox().expandTowards(movement.x * mid, movement.y, movement.z * mid))) low = mid;
                else high = mid;
            }
            movement = new Vec3(movement.x * low, movement.y, movement.z * low);
            if (updateChunkStatus) waitingForChunk = true;
        }
        return movement;
    }

    private boolean loaded(AABB box) {
        int minX = Mth.floor(box.minX) >> 4, maxX = Mth.floor(box.maxX - 1.0e-7) >> 4;
        int minZ = Mth.floor(box.minZ) >> 4, maxZ = Mth.floor(box.maxZ - 1.0e-7) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (!world.hasChunkAt(new BlockPos(x << 4, 0, z << 4))) return false;
        return true;
    }

    @Override public void onPostTick() {
        if (body != mc.player || world != mc.level || !canOwnMotion()) return;
        if (!elytra()) {
            // Rebase to the collision-resolved position; a ceiling cannot store a delayed upward leap.
            heldY = desiredY = body.getY();
            if (moveStart != null && moveRequest != null) {
                Vec3 actual = body.position().subtract(moveStart);
                if (Math.abs(actual.x - moveRequest.x) > 1.0e-5) velocityX = actual.x;
                if (Math.abs(actual.z - moveRequest.z) > 1.0e-5) velocityZ = actual.z;
            }
        }
        moveStart = moveRequest = null;
    }

    public static void applyInput(KeyboardInput input) {
        if (capturesVerticalInput()) clearVerticalKeys(input);
    }

    public static boolean capturesVerticalInput() {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        return module != null && module.isEnabled() && !module.elytra() && module.keyHeight.on() && module.controlsReady();
    }

    private static void clearVerticalKeys(ClientInput input) {
        Input original = input.keyPresses;
        input.keyPresses = new Input(original.forward(), original.backward(), original.left(), original.right(), false, false, original.sprint());
    }

    /** Called after vanilla applies the position and sends its real teleport acknowledgment. */
    public static void onPositionCorrection() {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        if (module == null || !module.isEnabled() || module.body != mc.player || module.world != mc.level
                || !module.canOwnMotion()) return;
        long now = System.nanoTime();
        module.correctionCount++;
        module.corrections.addLast(now);
        while (!module.corrections.isEmpty() && now - module.corrections.peekFirst() > 10_000_000_000L) module.corrections.removeFirst();
        module.lastCorrectionAt = now;
        module.pausedUntil = now + (long) (module.correctionPause.val() * 1_000_000_000L);
        module.heldY = module.desiredY = mc.player.getY();
        module.velocityX = mc.player.getDeltaMovement().x;
        module.velocityZ = mc.player.getDeltaMovement().z;
        // No pre-correction travel target may be reused during the pause or on its first tick.
        module.lastDriveTick = module.lastMoveTick = Integer.MIN_VALUE;
        module.moveStart = module.moveRequest = null;
        if (module.adaptiveSpeed.on() && !module.elytra()) module.adaptiveScale = Math.max(0.2, module.adaptiveScale * 0.65);
        boolean disable = module.correctionAction.is("flat_disable") && module.corrections.size() >= module.correctionLimit.intVal();
        if (module.correctionNotice.on() && (disable || module.lastNoticeAt == 0 || now - module.lastNoticeAt >= 5_000_000_000L)) {
            module.lastNoticeAt = now;
            Notifications.push(module.name(), Lang.t(disable ? "ui.flatflight.disabled" : "ui.flatflight.corrected", module.correctionCount), false);
        }
        if (disable) module.setEnabled(false);
    }

    /** Authoritative server abilities remain untouched; switching to real flight releases this controller. */
    public static void onAbilitiesChanged() {
        FlatFlight module = ModuleManager.get(FlatFlight.class);
        if (module == null || !module.isEnabled() || mc.player == null) return;
        if (mc.player.getAbilities().flying) module.reset();
        else if (module.body == mc.player) {
            module.heldY = module.desiredY = mc.player.getY();
            module.velocityX = mc.player.getDeltaMovement().x;
            module.velocityZ = mc.player.getDeltaMovement().z;
            module.lastDriveTick = module.lastMoveTick = Integer.MIN_VALUE;
            module.moveStart = module.moveRequest = null;
        }
    }

    @Override public String suffix() { return mode.display(); }

    @Override public String summary() {
        String status;
        if (System.nanoTime() < pausedUntil) status = Lang.t("ui.flatflight.paused");
        else if (waitingForChunk) status = Lang.t("ui.flatflight.chunks");
        else if (elytra() && !equippedElytra()) status = Lang.t("ui.flatflight.needElytra");
        else if (elytra() && mc.player != null && !mc.player.isFallFlying()) status = Lang.t("ui.flatflight.takeoff");
        else status = body == mc.player && body != null ? String.format(Locale.ROOT, "Y %.1f", body.getY()) : Lang.t("ui.flatflight.waiting");
        return mode.display() + ", " + status + (elytra() ? "" : ", " + String.format(Locale.ROOT, "%.1f", speed.val() * (adaptiveSpeed.on() ? adaptiveScale : 1)) + " " + Lang.t("ui.flatflight.blocksPerSecond"))
                + ", " + Lang.t("ui.flatflight.corrections", correctionCount);
    }
}
