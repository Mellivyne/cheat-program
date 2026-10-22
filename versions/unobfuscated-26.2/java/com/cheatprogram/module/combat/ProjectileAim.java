package com.cheatprogram.module.combat;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.AssistantTargets;
import com.cheatprogram.util.AuraRotations;
import com.cheatprogram.util.ProjectilePrediction;
import com.cheatprogram.util.ScaffoldRotations;
import com.cheatprogram.util.Targets;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Gentle, held-use-only assistance using the actual 1.21.4 launch speed, drag and gravity. */
public final class ProjectileAim extends Module {
    private final BoolSetting players = add(new BoolSetting("players", true));
    private final BoolSetting friendly = add(new BoolSetting("friendly", false));
    private final BoolSetting hostile = add(new BoolSetting("hostile", true));
    private final NumberSetting range = add(new NumberSetting("range", 48, 4, 128, 1));
    private final NumberSetting fov = add(new NumberSetting("fov", 65, 5, 180, 1, "°"));
    private final NumberSetting strength = add(new NumberSetting("strength", 75, 5, 100, 1, "%"));
    private final NumberSetting smoothness = add(new NumberSetting("smoothness", 9, 1, 30, .5));
    private final NumberSetting turnSpeed = add(new NumberSetting("turnSpeed", 180, 10, 720, 5, "°/s"));
    private final NumberSetting lead = add(new NumberSetting("lead", 100, 0, 150, 5, "%"));
    private final NumberSetting maxFlight = add(new NumberSetting("maxFlight", 60, 10, 100, 1));
    private final NumberSetting manualThreshold = add(new NumberSetting("manualThreshold", 5, 1, 30, .5, "°"));
    private final BoolSetting preview = add(new BoolSetting("preview", true));
    private LivingEntity target;
    private Vec3 sampledPosition, velocity = Vec3.ZERO;
    private int sampleTick = -1;
    private Object world;
    private Solution solution;
    private long manualUntil;
    private float appliedYaw, appliedPitch;
    private boolean applied;
    private record Solution(Vec3 direction, Vec3 point, AABB box, double ticks) { }

    public ProjectileAim() { super("projectileaim", Category.COMBAT); }
    @Override public String suffix() { return target == null ? "" : target.getName().getString(); }
    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    private void reset() {
        target = null; world = null; solution = null; sampledPosition = null; velocity = Vec3.ZERO;
        sampleTick = -1; applied = false; manualUntil = 0;
    }
    private boolean allowed() {
        return inGame() && mc.gameMode != null && mc.gui.screen() == null && mc.gui.overlay() == null
                && mc.isWindowActive() && !mc.isPaused() && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.player.isUsingItem() && mc.options.keyUse.isDown() && !mc.player.isInWater()
                && !Freecam.isActive() && !AiController.isRunning() && !AuraRotations.active()
                && !ScaffoldRotations.active() && !mc.gameMode.isDestroying();
    }
    @Override public void onTick() {
        if (!allowed()) { solution = null; applied = false; target = null; return; }
        if (world != mc.level) { reset(); world = mc.level; }
        var item = mc.player.getUseItem().getItem();
        if (!(item instanceof BowItem) && !(item instanceof TridentItem)) { solution = null; applied = false; return; }
        long now = System.currentTimeMillis();
        if (applied && (Math.abs(Mth.wrapDegrees(mc.player.getYRot() - appliedYaw)) > manualThreshold.val()
                || Math.abs(mc.player.getXRot() - appliedPitch) > manualThreshold.val())) manualUntil = now + 400;
        if (now < manualUntil) { solution = null; applied = false; return; }
        ProjectilePrediction.Shot shot = ProjectilePrediction.currentShot(mc.player, false, false, true, false);
        if (shot == null) { solution = null; applied = false; return; }
        LivingEntity selected = select();
        if (selected == null) { solution = null; target = null; applied = false; return; }
        if (selected != target) { target = selected; sampledPosition = target.position(); sampleTick = mc.player.tickCount; velocity = Vec3.ZERO; }
        else if (sampleTick != mc.player.tickCount) {
            int elapsed = Math.max(1, mc.player.tickCount - sampleTick);
            Vec3 observed = target.position().subtract(sampledPosition).scale(1d / elapsed);
            // Teleports are not a future velocity. Bound uncertainty rather than promising impossible shots.
            if (observed.lengthSqr() > 2.25) observed = Vec3.ZERO;
            velocity = velocity.scale(.4).add(observed.scale(.6));
            sampledPosition = target.position(); sampleTick = mc.player.tickCount;
        }
        solution = solve(shot, target, velocity.scale(lead.fraction()));
        if (solution == null) { applied = false; return; }
        Vec3 dir = solution.direction();
        float yaw = (float) Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, Math.hypot(dir.x, dir.z)));
        float dy = Mth.wrapDegrees(yaw - mc.player.getYRot()), dp = pitch - mc.player.getXRot();
        float factor = (float) ((1 - Math.exp(-smoothness.val() * .05)) * strength.fraction());
        float maximum = turnSpeed.floatVal() / 20;
        float angular = (float) Math.hypot(dy, dp);
        float scale = angular < .001 ? 0 : Math.min(factor, maximum / angular);
        mc.player.turn(dy * scale / .15, dp * scale / .15);
        appliedYaw = mc.player.getYRot(); appliedPitch = mc.player.getXRot(); applied = true;
    }
    private LivingEntity select() {
        LivingEntity best = null; double score = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!AssistantTargets.accepts(entity, players.on(), friendly.on(), hostile.on())
                    || entity.distanceToSqr(mc.player) > range.val() * range.val()) continue;
            double angle = Targets.angleTo(entity);
            if (angle > fov.val() / 2 || !mc.player.hasLineOfSight(entity)) continue;
            double rank = angle + mc.player.distanceTo(entity) * .04;
            if (rank < score) { score = rank; best = (LivingEntity) entity; }
        }
        return best;
    }
    private Solution solve(ProjectilePrediction.Shot shot, LivingEntity entity, Vec3 motion) {
        Vec3 inherited = mc.player.getKnownMovement();
        inherited = new Vec3(inherited.x, mc.player.onGround() ? 0 : inherited.y, inherited.z);
        double speed = shot.velocity().subtract(inherited).length();
        if (speed < .1) return null;
        AABB actualBox = entity.getBoundingBox();
        Vec3 center = actualBox.getCenter();
        double previousTime = .25, previousError = Double.POSITIVE_INFINITY, bestTime = -1;
        for (double t = .5; t <= maxFlight.intVal(); t += .5) {
            double error = required(shot, center.add(motion.scale(t)), inherited, t).length() - speed;
            if (error <= 0 && previousError > 0) {
                double low = previousTime, high = t;
                for (int i = 0; i < 14; i++) {
                    double mid = (low + high) / 2;
                    if (required(shot, center.add(motion.scale(mid)), inherited, mid).length() > speed) low = mid;
                    else high = mid;
                }
                bestTime = (low + high) / 2; break;
            }
            previousTime = t; previousError = error;
        }
        if (bestTime < 0) return null;
        Vec3 point = center.add(motion.scale(bestTime));
        Vec3 direction = required(shot, point, inherited, bestTime).normalize();
        Vec3 pos = shot.origin(), movement = direction.scale(speed).add(inherited);
        // Verify the actual curved path against loaded collision shapes, rather than a straight aim ray.
        for (int tick = 0; tick < Math.ceil(bestTime); tick++) {
            double fraction = Math.min(1, bestTime - tick);
            Vec3 next = pos.add(movement.scale(fraction));
            if (!mc.level.hasChunkAt(net.minecraft.core.BlockPos.containing(pos))
                    || !mc.level.hasChunkAt(net.minecraft.core.BlockPos.containing(next))) return null;
            var hit = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) return null;
            // Air model is used only while every sampled point is outside water.
            if (mc.level.getFluidState(net.minecraft.core.BlockPos.containing(next)).is(net.minecraft.tags.FluidTags.WATER)) return null;
            pos = next; movement = movement.scale(.99F).add(0, -shot.kind().gravity, 0);
        }
        return new Solution(direction, point, actualBox.move(motion.scale(bestTime)), bestTime);
    }
    /** p[n] = v[0] A[n] - g (n-A[n])/(1-d), for movement -> drag -> gravity. */
    private Vec3 required(ProjectilePrediction.Shot shot, Vec3 destination, Vec3 inherited, double ticks) {
        double drag = .99F, a = (1 - Math.pow(drag, ticks)) / (1 - drag);
        double fall = shot.kind().gravity * (ticks - a) / (1 - drag);
        return destination.subtract(shot.origin()).add(0, fall, 0).scale(1 / a).subtract(inherited);
    }
    @Override public void onRender3D(Render3D renderer) {
        if (!preview.on() || solution == null || !inGame() || world != mc.level || target == null || !target.isAlive()) return;
        renderer.begin();
        try {
            renderer.box(solution.box().inflate(.02), 0x147BE9FF, 0xC07BE9FF, 1.2f);
            renderer.line(target.getBoundingBox().getCenter(), solution.point(), 0xA0FFFFFF, 1.2f);
        } finally { renderer.end(); }
    }
}
