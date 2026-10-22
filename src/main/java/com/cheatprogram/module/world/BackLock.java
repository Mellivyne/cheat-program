package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import net.minecraft.world.entity.LivingEntity;

/** Follow the rear of a target's actual torso direction, without attacking it. */
public final class BackLock extends Module {
    private final ModeSetting targets = add(new ModeSetting("targets", "line_players", "line_players", "line_passives", "line_hostiles"));
    private final NumberSetting range = add(new NumberSetting("range", 16, 2, 256, 1));
    private final BoolSetting ignoreFriends = add(new BoolSetting("ignoreFriends", true)).visibleWhen(() -> targets.is("line_players"));
    private final ModeSetting movementMode = add(new ModeSetting("movementMode", "motion_walk", "motion_walk", "motion_burst", "motion_teleport"));
    private final NumberSetting behindDistance = add(new NumberSetting("behindDistance", 2, 0.1, 64, 0.1));
    private final NumberSetting sideOffset = add(new NumberSetting("sideOffset", 0, -32, 32, 0.1));
    private final NumberSetting moveSpeed = add(new NumberSetting("moveSpeed", 4.3, 0.1, 160, 0.1, "b/s")).visibleWhen(() -> movementMode.is("motion_walk"));
    private final NumberSetting acceleration = add(new NumberSetting("acceleration", 80, 0, 3200, 10, "b/s²")).visibleWhen(() -> movementMode.is("motion_walk"));
    private final NumberSetting maxStep = add(new NumberSetting("maxStep", 3, 0.1, 32, 0.1)).visibleWhen(() -> movementMode.is("motion_burst"));
    private final NumberSetting teleportDelay = add(new NumberSetting("teleportDelay", 50, 0, 2000, 5, "ms")).visibleWhen(() -> !movementMode.is("motion_walk"));
    private final NumberSetting heightOffset = add(new NumberSetting("heightOffset", 0, -32, 32, 0.1));
    private final NumberSetting leadTicks = add(new NumberSetting("leadTicks", 0, 0, 10, 0.25));
    private final NumberSetting tolerance = add(new NumberSetting("tolerance", 0.08, 0.01, 1, 0.01));
    private final NumberSetting stepHeight = add(new NumberSetting("stepHeight", 1.25, 0.1, 4, 0.05));
    private final BoolSetting edgeGuard = add(new BoolSetting("edgeGuard", true));
    private final BoolSetting autoJump = add(new BoolSetting("autoJump", true)).visibleWhen(() -> movementMode.is("motion_walk"));
    private final NumberSetting correctionPause = add(new NumberSetting("correctionPause", 250, 0, 3000, 50, "ms"));
    private final BoolSetting lookAt = add(new BoolSetting("lookAt", true));
    private final NumberSetting lookRate = add(new NumberSetting("lookRate", 720, 1, 7200, 10, "°/s")).visibleWhen(lookAt::on);
    private final TargetMotionSupport motion = new TargetMotionSupport(this, targets, range, ignoreFriends);

    public BackLock() { super("backlock", Category.WORLD); }
    @Override public String suffix() { return targets.display() + ", " + movementMode.display() + motion.status(); }
    TargetMotionSupport motionSupport() { return motion; }
    @Override public void onEnable() {
        motion.reset();
        EntityOrbit other = ModuleManager.get(EntityOrbit.class);
        if (other != null && other.isEnabled()) other.setEnabled(false);
    }
    @Override public void onDisable() { motion.reset(); }
    public void checkLifecycle() { motion.checkLifecycle(); }
    @Override public void onTick() {
        checkLifecycle();
        if (!motion.ready(movementMode.get())) return;
        EntityOrbit other = ModuleManager.get(EntityOrbit.class);
        if (other != null && other.isEnabled()) other.setEnabled(false);
        LivingEntity target = motion.acquire();
        if (target == null) return;
        TargetMotionSupport.Goal goal = new TargetMotionSupport.Goal(false, 0, behindDistance.val(), sideOffset.val(), heightOffset.val());
        motion.configure(acceleration.val(), tolerance.val(), stepHeight.val(), lookRate.val(), leadTicks.val(), edgeGuard.on(), autoJump.on(), correctionPause.intVal());
        if (movementMode.is("motion_walk")) motion.smooth(goal, moveSpeed.val(), lookAt.on());
        else if (movementMode.is("motion_burst")) motion.burst(goal, maxStep.val(), teleportDelay.intVal(), lookAt.on());
        else motion.teleport(goal, teleportDelay.intVal(), lookAt.on());
    }
}
