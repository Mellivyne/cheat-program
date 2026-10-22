package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.InteractionLook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** A single outstanding placement, followed only by the newly received crystal at that exact base. */
public final class AutoCrystal extends Module {
    private final NumberSetting delay = add(new NumberSetting("delay", 50, 0, 2000, 10, "ms"));
    private final ModeSetting rotation = add(new ModeSetting("rotation", "silent", "silent", "follow"));
    private final WorldInteractSupport interaction = new WorldInteractSupport();
    private final SmartExplosions smart = new SmartExplosions();
    private ClientLevel world;
    private Object player;
    private BlockPos base;
    private UUID crystal;
    private long placedAt;
    private long nextAction;
    private int actionTick = -1;
    private boolean awaitingSpawn;
    private boolean attacked;
    private EndCrystal pendingAttack;
    private boolean attackCompleted;

    public AutoCrystal() {
        super("autocrystal", Category.WORLD);
        add(smart.enabled); add(smart.range.visibleWhen(smart.enabled::on));
        add(smart.minDamage.visibleWhen(smart.enabled::on)); add(smart.maxSelf.visibleWhen(smart.enabled::on));
        add(smart.marker.visibleWhen(smart.enabled::on));
    }
    @Override public void onRender3D(Render3D r) { smart.render(r); }
    @Override public void onRender2D(com.cheatprogram.hud.HudContext hud) { smart.hud(hud,false); }
    @Override public String suffix() { return delay.format() + ", " + rotation.display(); }
    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }

    private void clearPlacement() { base = null; crystal = null; awaitingSpawn = attacked = false; placedAt = 0; }
    private void reset() {
        clearPlacement(); interaction.reset(); world = null; player = null; nextAction = 0; actionTick = -1;
        pendingAttack = null; attackCompleted = false;
        smart.reset();
    }

    @Override public void onTick() {
        if (!inGame()) { reset(); return; }
        if (world != mc.level || player != mc.player) { reset(); world = mc.level; player = mc.player; }
        if (!interaction.ready()) return;
        long now = System.currentTimeMillis();
        if (actionTick == mc.player.tickCount || now < nextAction) return;
        if (base != null) {
            if (!mc.level.hasChunkAt(base) || !mc.level.getBlockState(base).is(Blocks.OBSIDIAN)) { clearPlacement(); return; }
            if (now - placedAt > 5000) { clearPlacement(); nextAction = now + 250; return; }
            if (awaitingSpawn) {
                List<EndCrystal> arrived = mc.level.getEntitiesOfClass(EndCrystal.class, crystalBounds(base),
                        entity -> !entity.isRemoved() && atBase(entity, base));
                // Multiple arrivals are ambiguous: do not choose a player's unrelated crystal.
                if (arrived.size() > 1 || now - placedAt > 2500) { clearPlacement(); nextAction = now + 250; return; }
                if (arrived.isEmpty()) return;
                crystal = arrived.get(0).getUUID(); awaitingSpawn = false;
            }
            EndCrystal target = findCrystal();
            if (target == null) { clearPlacement(); return; }
            if (smart.enabled.on() && !smart.safe(target.position(), false, null)) return;
            if (!attackReachable(target) || !WorldInteractSupport.aimReady(target.position().add(0, .55, 0), rotation.get())) return;
            long attemptDelay = attacked ? Math.max(100, delay.intVal()) : delay.intVal();
            pendingAttack = target; attackCompleted = false;
            try {
                InteractionLook.interact(target.position().add(0, .55, 0), "silent", () -> {
                    mc.gameMode.attack(mc.player, target);
                    return null;
                });
            } finally { pendingAttack = null; }
            actionTick = mc.player.tickCount;
            if (!attackCompleted) { nextAction = now + 100; return; }
            mc.player.swing(InteractionHand.MAIN_HAND);
            attacked = true;
            // Wait for the server to remove this exact entity before placing the next crystal.
            nextAction = now + attemptDelay;
            return;
        }
        SmartExplosions.Choice selected = smart.enabled.on() ? smart.find(false, this::canPlace) : null;
        BlockHitResult target = smart.enabled.on() ? (selected == null ? null : selected.hit()) : lookedObsidian();
        if (target == null || !WorldInteractSupport.contains(Items.END_CRYSTAL)) return;
        BlockPos position = target.getBlockPos();
        BlockHitResult top = new BlockHitResult(Vec3.atCenterOf(position).add(0, .5, 0), Direction.UP, position, false);
        if (!WorldInteractSupport.reachable(top) || !canPlace(position)
                || smart.enabled.on() && !smart.safe(Vec3.atBottomCenterOf(position.above()), false, null)
                || !WorldInteractSupport.aimReady(top.getLocation(), rotation.get())) return;
        if (!WorldInteractSupport.use(Items.END_CRYSTAL, top).consumesAction()) return;
        mc.player.swing(InteractionHand.MAIN_HAND);
        base = position.immutable(); awaitingSpawn = true; crystal = null; attacked = false;
        placedAt = now; actionTick = mc.player.tickCount; nextAction = now + delay.intVal();
    }

    @Override public void onPostAttack(Entity entity) {
        if (entity == pendingAttack) attackCompleted = true;
    }

    private BlockHitResult lookedObsidian() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !mc.level.getBlockState(hit.getBlockPos()).is(Blocks.OBSIDIAN)) return null;
        return hit;
    }

    private boolean canPlace(BlockPos position) {
        if (!mc.level.hasChunkAt(position.above()) || !mc.level.hasChunkAt(position.above(2))
                || !mc.level.isEmptyBlock(position.above())) return false;
        // This is the same two-block entity collision box used by vanilla EndCrystalItem.
        return mc.level.getEntities(null, crystalBounds(position)).isEmpty();
    }

    private static AABB crystalBounds(BlockPos position) {
        return new AABB(position.getX(), position.getY() + 1, position.getZ(),
                position.getX() + 1, position.getY() + 3, position.getZ() + 1);
    }

    private static boolean atBase(EndCrystal entity, BlockPos position) {
        return Math.abs(entity.getX() - (position.getX() + .5)) < .03
                && Math.abs(entity.getY() - (position.getY() + 1)) < .03
                && Math.abs(entity.getZ() - (position.getZ() + .5)) < .03;
    }

    private EndCrystal findCrystal() {
        if (crystal == null) return null;
        for (EndCrystal entity : mc.level.getEntitiesOfClass(EndCrystal.class, crystalBounds(base)))
            if (crystal.equals(entity.getUUID()) && !entity.isRemoved() && atBase(entity, base)) return entity;
        return null;
    }

    private boolean attackReachable(EndCrystal target) {
        Vec3 eye = mc.player.getEyePosition();
        AABB bounds = target.getBoundingBox();
        Vec3 closest = new Vec3(Math.max(bounds.minX, Math.min(bounds.maxX, eye.x)),
                Math.max(bounds.minY, Math.min(bounds.maxY, eye.y)), Math.max(bounds.minZ, Math.min(bounds.maxZ, eye.z)));
        double reach = mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        if (eye.distanceToSqr(closest) > reach * reach) return false;
        BlockHitResult ray = mc.level.clip(new ClipContext(eye, target.position().add(0, .55, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return ray.getType() == HitResult.Type.MISS;
    }
}
