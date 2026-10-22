package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.AssistedInteractions;
import com.cheatprogram.util.InvUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Predicts real movement and requests ordinary water/web/ladder placements before impact. */
public final class LandingRescue extends Module {
    private final ModeSetting priority = add(new ModeSetting("priority", "rescue_water", "rescue_water", "rescue_web", "rescue_ladder"));
    private final BoolSetting water = add(new BoolSetting("water", true));
    private final BoolSetting web = add(new BoolSetting("web", true));
    private final BoolSetting ladder = add(new BoolSetting("ladder", true));
    private final NumberSetting minimumFall = add(new NumberSetting("minimumFall", 3, 2, 30, .5));
    private final NumberSetting triggerTicks = add(new NumberSetting("triggerTicks", 3, 1, 8, 1));
    private final NumberSetting horizon = add(new NumberSetting("horizon", 35, 5, 60, 1));
    private final ModeSetting rotation = add(new ModeSetting("rotation", "silent", "silent", "follow"));
    private final BoolSetting preview = add(new BoolSetting("preview", true));
    private final BoolSetting returnSlot = add(new BoolSetting("returnSlot", true));
    private final AssistedInteractions interaction = new AssistedInteractions();
    private final LandingRescueTrajectory trajectory = new LandingRescueTrajectory();
    private final List<Vec3> path = new ArrayList<>();
    private Object world, player;
    private Plan plan;
    private BlockPos target;
    private int nextUseTick;
    private record Plan(Vec3 landing, double remainingDrop) { }

    public LandingRescue() { super("landingrescue", Category.MOVEMENT); }
    /** A same-world server teleport starts a new observation and invalidates any prepared placement. */
    public static void onPositionCorrection() {
        LandingRescue module = ModuleManager.get(LandingRescue.class);
        if (module != null) module.reset();
    }

    @Override public String suffix() { return priority.display(); }
    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    private void reset() {
        interaction.reset(); trajectory.reset(); world = player = null; plan = null;
        target = null; path.clear(); nextUseTick = 0;
    }

    @Override public void onTick() {
        if (!inGame()) { reset(); return; }
        if (world != mc.level || player != mc.player) { reset(); world = mc.level; player = mc.player; }
        boolean safe = mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()
                || LandingRescueTrajectory.flightProtected(mc.player.getAbilities().flying, Flight.serverAllowsFlight())
                || mc.player.isFallFlying() || mc.player.isPassenger()
                || mc.player.isCreative() || mc.player.isSpectator() || mc.player.getInBlockState().is(Blocks.COBWEB)
                || mc.player.hasEffect(MobEffects.SLOW_FALLING) || mc.player.hasEffect(MobEffects.LEVITATION);
        // NoFall and motion modules may clear the client field; it is not proof that the server cleared its fall.
        trajectory.observe(mc.player.getY(), safe);
        Vec3 controlled = FlatFlight.plannedMotion();
        Vec3 motion = controlled == null ? mc.player.getDeltaMovement() : controlled;
        if (safe || motion.y >= -1e-5) { plan = null; target = null; path.clear(); return; }
        plan = predict(motion, controlled != null);
        target = null;
        if (plan == null || !trajectory.dangerous(mc.player.fallDistance, plan.remainingDrop(), minimumFall.val())
                || !interaction.ready()) return;
        var samples = new ArrayList<LandingRescueTrajectory.Point>(path.size());
        for (Vec3 point : path) samples.add(new LandingRescueTrajectory.Point(point.x, point.y, point.z));
        // Also prepare follow view before the placement window; a last-tick 90-degree turn is too late.
        var cells = LandingRescueTrajectory.cells(samples, Math.min(horizon.intVal(), triggerTicks.intVal() + 4));
        Item[] order = priority.is("rescue_web") ? new Item[]{Items.COBWEB, Items.WATER_BUCKET, Items.LADDER}
                : priority.is("rescue_ladder") ? new Item[]{Items.LADDER, Items.WATER_BUCKET, Items.COBWEB}
                : new Item[]{Items.WATER_BUCKET, Items.COBWEB, Items.LADDER};
        for (Item item : order) {
            if (item == Items.WATER_BUCKET && !water.on()
                    || item == Items.COBWEB && !web.on() || item == Items.LADDER && !ladder.on()) continue;
            int source = AssistedInteractions.find(item);
            if (source < 0 || mc.player.getCooldowns().isOnCooldown(InvUtil.stack(source))) continue;
            ItemStack stack = InvUtil.stack(source);
            for (var cell : cells) {
                BlockPos destination = new BlockPos(cell.x(), cell.y(), cell.z());
                if (item == Items.WATER_BUCKET && mc.level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES, destination)) continue;
                if (!candidateSpace(destination, item)) continue;
                for (BlockHitResult hit : supportingFaces(destination, item == Items.LADDER, item == Items.WATER_BUCKET)) {
                    if (item == Items.WATER_BUCKET ? !bucketLandsIn(hit, destination) : !canPlace(item, stack, hit, destination)) continue;
                    target = destination;
                    boolean facing = AssistedInteractions.followReady(hit.getLocation(), rotation.get());
                    if (cell.tick() > triggerTicks.intVal() || mc.player.tickCount < nextUseTick) return;
                    // Keep visible follow when time permits; ordinary scoped aim is necessary for an imminent impact.
                    if (!facing && cell.tick() > 1) return;
                    boolean requested = interaction.use(this, item, hit, facing ? rotation.get() : "silent",
                            returnSlot.on(), item == Items.WATER_BUCKET).consumesAction();
                    // This is a request, not server confirmation. Re-evaluate the real world after a short bounded interval.
                    nextUseTick = mc.player.tickCount + (requested ? 2 : 1);
                    return;
                }
            }
        }
    }

    private Plan predict(Vec3 motion, boolean controlled) {
        path.clear();
        AABB box = mc.player.getBoundingBox();
        Vec3 position = mc.player.position();
        double startY = position.y, gravity = mc.player.getAttributeValue(Attributes.GRAVITY);
        Vec3 input = new Vec3(mc.player.xxa, 0, mc.player.zza);
        if (input.lengthSqr() > 1) input = input.normalize();
        input = input.scale(mc.player.isSprinting() ? .025999999 : .02);
        double yaw = Math.toRadians(mc.player.getYRot());
        Vec3 airInput = new Vec3(input.x * Math.cos(yaw) - input.z * Math.sin(yaw), 0,
                input.z * Math.cos(yaw) + input.x * Math.sin(yaw));
        path.add(position);
        for (int tick = 1; tick <= horizon.intVal(); tick++) {
            Vec3 step = controlled ? motion : motion.add(airInput);
            AABB swept = box.expandTowards(step);
            if (!loaded(swept) || swept.minY < mc.level.getMinY()) return null;
            // Vanilla air travel moves with current velocity, then applies gravity and drag.
            Vec3 allowed = Entity.collideBoundingBox(mc.player, step, box, mc.level, List.of());
            box = box.move(allowed); position = position.add(allowed); path.add(position);
            if (step.y < 0 && Math.abs(allowed.y - step.y) > 1e-6)
                return new Plan(position, Math.max(0, startY - position.y));
            if (mc.level.getFluidState(BlockPos.containing(position)).is(FluidTags.WATER)) return null;
            if (!controlled) motion = new Vec3(Math.abs(allowed.x - step.x) > 1e-6 ? 0 : step.x * .91,
                    (step.y - gravity) * .98, Math.abs(allowed.z - step.z) > 1e-6 ? 0 : step.z * .91);
        }
        // A side-supported ladder/web/water can still intercept a fall before any ground enters the horizon.
        return new Plan(position, Math.max(0, startY - position.y));
    }

    private boolean candidateSpace(BlockPos destination, Item item) {
        if (!mc.level.hasChunkAt(destination) || !mc.level.getWorldBorder().isWithinBounds(destination)
                || !mc.level.isInWorldBounds(destination)) return false;
        var state = mc.level.getBlockState(destination);
        if (!state.canBeReplaced() && !(item == Items.WATER_BUCKET
                && state.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(mc.player, mc.level, destination, state, Fluids.WATER))) return false;
        double reach = mc.player.blockInteractionRange();
        return new AABB(destination).distanceToSqr(mc.player.getEyePosition()) <= reach * reach;
    }

    /** Try real outline faces, including separate stair/slab pieces and sturdy horizontal ladder supports. */
    private List<BlockHitResult> supportingFaces(BlockPos destination, boolean horizontalOnly, boolean bucket) {
        var hits = new ArrayList<BlockHitResult>();
        if (bucket && mc.level.getBlockState(destination).getBlock() instanceof LiquidBlockContainer)
            addFaces(hits, destination, Direction.UP);
        for (Direction towardSupport : Direction.values()) {
            if (horizontalOnly && !towardSupport.getAxis().isHorizontal()) continue;
            BlockPos support = destination.relative(towardSupport);
            if (!mc.level.hasChunkAt(support)) continue;
            var state = mc.level.getBlockState(support);
            Direction face = towardSupport.getOpposite();
            if (state.canBeReplaced() || state.getMenuProvider(mc.level, support) != null
                    || horizontalOnly && !state.isFaceSturdy(mc.level, support, face)) continue;
            addFaces(hits, support, face);
        }
        hits.sort(Comparator.comparingDouble(hit -> mc.player.getEyePosition().distanceToSqr(hit.getLocation())));
        return hits;
    }

    private void addFaces(List<BlockHitResult> hits, BlockPos support, Direction face) {
        for (AABB shape : mc.level.getBlockState(support).getShape(mc.level, support).toAabbs()) {
            Vec3 center = shape.getCenter().add(support.getX(), support.getY(), support.getZ());
            Vec3 point = switch (face) {
                case UP -> new Vec3(center.x, support.getY() + shape.maxY, center.z);
                case DOWN -> new Vec3(center.x, support.getY() + shape.minY, center.z);
                case EAST -> new Vec3(support.getX() + shape.maxX, center.y, center.z);
                case WEST -> new Vec3(support.getX() + shape.minX, center.y, center.z);
                case SOUTH -> new Vec3(center.x, center.y, support.getZ() + shape.maxZ);
                case NORTH -> new Vec3(center.x, center.y, support.getZ() + shape.minZ);
            };
            var hit = new BlockHitResult(point, face, support, false);
            if (AssistedInteractions.reachable(hit)) hits.add(hit);
        }
    }

    private boolean canPlace(Item item, ItemStack stack, BlockHitResult hit, BlockPos destination) {
        if (!(item instanceof BlockItem blockItem)) return false;
        var context = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, hit);
        var state = blockItem.getBlock().getStateForPlacement(context);
        return context.getClickedPos().equals(destination) && context.canPlace() && state != null
                && state.canSurvive(mc.level, destination)
                && mc.level.isUnobstructed(state, destination, net.minecraft.world.phys.shapes.CollisionContext.of(mc.player));
    }

    private boolean bucketLandsIn(BlockHitResult hit, BlockPos destination) {
        if (!bucketRayMatches(hit)) return false;
        var state = mc.level.getBlockState(hit.getBlockPos());
        // BucketItem.use raycasts the hit again; waterloggable supports can consume the water themselves.
        if (state.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(mc.player, mc.level, hit.getBlockPos(), state, Fluids.WATER))
            return hit.getBlockPos().equals(destination)
                    && plan.landing().y < destination.getY() + Fluids.WATER.getSource(false).getHeight(mc.level, destination) - 1e-5;
        var targetState = mc.level.getBlockState(destination);
        return hit.getBlockPos().relative(hit.getDirection()).equals(destination)
                && (targetState.isAir() || targetState.canBeReplaced(Fluids.WATER));
    }

    /** Match Item.use's full ray using the same float look that InteractionLook will transmit. */
    private boolean bucketRayMatches(BlockHitResult hit) {
        Vec3 eye = mc.player.getEyePosition(), delta = hit.getLocation().subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))), -90, 90);
        Vec3 end = eye.add(mc.player.calculateViewVector(pitch, yaw).scale(mc.player.blockInteractionRange()));
        BlockHitResult actual = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return actual.getType() == HitResult.Type.BLOCK && actual.getBlockPos().equals(hit.getBlockPos())
                && actual.getDirection() == hit.getDirection();
    }
    private boolean loaded(AABB box) {
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++)
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++) if (!mc.level.hasChunk(x, z)) return false;
        return true;
    }

    @Override public void onRender3D(Render3D renderer) {
        if (!preview.on() || plan == null || !inGame() || world != mc.level) return;
        renderer.begin();
        try {
            for (int i = 1; i < path.size(); i++) renderer.line(path.get(i - 1), path.get(i), 0x907BE8FF, 1.2f);
            renderer.box(new AABB(target == null ? BlockPos.containing(plan.landing()) : target), 0x207BE8FF, 0xD0B3F3FF, 1.5f);
        } finally { renderer.end(); }
    }
}
