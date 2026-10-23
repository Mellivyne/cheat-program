package com.cheatprogram.util;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Module;
import com.cheatprogram.mixin.MultiPlayerGameModeAccessor;
import com.cheatprogram.module.visual.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Real, bounded inventory transactions shared by the optional placement assistants. */
public final class AssistedInteractions {
    private static final Minecraft MC = Minecraft.getInstance();
    private Object player, world;
    private int retainedSlot = -1, retainedMaterial = -1;
    private int observedSlot = -1;
    private long manualUntil;

    public boolean ready() {
        if (MC.player == null || MC.level == null || MC.gameMode == null || MC.getConnection() == null) return false;
        if (player != MC.player || world != MC.level) {
            reset(); player = MC.player; world = MC.level; observedSlot = MC.player.getInventory().selected;
        }
        if (observedSlot != MC.player.getInventory().selected) {
            observedSlot = MC.player.getInventory().selected; manualUntil = System.currentTimeMillis() + 300;
            retainedSlot = retainedMaterial = -1;
        }
        return MC.screen == null && MC.getOverlay() == null && MC.isWindowActive() && !MC.isPaused()
                && MC.player.isAlive() && !MC.player.isSpectator() && !MC.player.isPassenger()
                && !MC.player.isUsingItem() && !MC.gameMode.isDestroying()
                && !AiController.isRunning() && !Freecam.isActive()
                && System.currentTimeMillis() >= manualUntil && InventoryAutomation.ownInventoryReady();
    }

    public void reset() {
        if (player == MC.player && world == MC.level && MC.player != null && retainedSlot >= 0
                && MC.player.getInventory().selected == retainedMaterial) {
            MC.player.getInventory().selected = retainedSlot;
            if (MC.gameMode != null && MC.getConnection() != null)
                ((MultiPlayerGameModeAccessor) MC.gameMode).cheatprogram$ensureHasSentCarriedItem();
        }
        retainedSlot = retainedMaterial = observedSlot = -1; player = world = null; manualUntil = 0;
    }

    public static int find(Item item) {
        if (MC.player == null) return -1;
        for (int i = 0; i < 36; i++)
            if (!false && InvUtil.stack(i).is(item) && !InvUtil.stack(i).isEmpty()) return i;
        return -1;
    }
    public static boolean contains(Item item) { return find(item) >= 0; }

    public static boolean reachable(BlockHitResult hit) {
        if (MC.player == null || MC.level == null || !MC.level.hasChunkAt(hit.getBlockPos())
                || !MC.level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return false;
        Vec3 eye = MC.player.getEyePosition();
        double reach = MC.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        if (eye.distanceToSqr(hit.getLocation()) > reach * reach) return false;
        Vec3 end = hit.getLocation().add(hit.getLocation().subtract(eye).normalize().scale(.002));
        var obstacle = MC.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
        return obstacle.getType() == HitResult.Type.BLOCK && obstacle.getBlockPos().equals(hit.getBlockPos())
                && obstacle.getDirection() == hit.getDirection();
    }

    /** Finds a real supporting face; never invents a support block or a block update. */
    public static BlockHitResult placement(BlockPos destination, boolean horizontalOnly) {
        if (MC.level == null || MC.player == null || !MC.level.hasChunkAt(destination)
                || !MC.level.getBlockState(destination).canBeReplaced()) return null;
        BlockHitResult best = null; double distance = Double.MAX_VALUE;
        for (Direction towardSupport : Direction.values()) {
            if (horizontalOnly && !towardSupport.getAxis().isHorizontal()) continue;
            BlockPos support = destination.relative(towardSupport);
            Direction face = towardSupport.getOpposite();
            if (!MC.level.hasChunkAt(support)) continue;
            var state = MC.level.getBlockState(support);
            if (state.canBeReplaced()) continue;
            // Farmland is 15/16 high and has no full top boundary face. Crop planting still
            // uses its real outline; ladders alone require a sturdy horizontal attachment.
            if (horizontalOnly && !state.isFaceSturdy(MC.level, support, face)
                    || state.getMenuProvider(MC.level, support) != null) continue;
            var shape = state.getShape(MC.level, support);
            if (shape.isEmpty()) continue;
            var bounds = shape.bounds();
            Vec3 center = bounds.getCenter().add(support.getX(), support.getY(), support.getZ());
            Vec3 point = switch (face) {
                case UP -> new Vec3(center.x, support.getY() + bounds.maxY, center.z);
                case DOWN -> new Vec3(center.x, support.getY() + bounds.minY, center.z);
                case EAST -> new Vec3(support.getX() + bounds.maxX, center.y, center.z);
                case WEST -> new Vec3(support.getX() + bounds.minX, center.y, center.z);
                case SOUTH -> new Vec3(center.x, center.y, support.getZ() + bounds.maxZ);
                case NORTH -> new Vec3(center.x, center.y, support.getZ() + bounds.minZ);
            };
            BlockHitResult hit = new BlockHitResult(point, face, support, false);
            double d = MC.player.getEyePosition().distanceToSqr(point);
            if (d < distance && reachable(hit)) { best = hit; distance = d; }
        }
        return best;
    }

    /** Visible follow is smooth; the exact use is still scoped to its real supporting face. */
    public static boolean followReady(Vec3 point, String mode) {
        if (!"follow".equals(mode)) return true;
        float[] rotation = Targets.rotationTo(point);
        float dy = Mth.wrapDegrees(rotation[0] - MC.player.getYRot()), dp = rotation[1] - MC.player.getXRot();
        if (Math.abs(dy) < 2 && Math.abs(dp) < 2) return true;
        MC.player.turn(Mth.clamp(dy * .42f, -25, 25) / .15, Mth.clamp(dp * .42f, -18, 18) / .15);
        return false;
    }

    /** Buckets use useItem (their ray trace is in Item.use); block items use useItemOn. */
    public InteractionResult use(Module owner, Item item, BlockHitResult hit, String rotation,
                                 boolean returnSlot, boolean bucket) {
        if (!ready() || !reachable(hit) || !followReady(hit.getLocation(), rotation)) return InteractionResult.FAIL;
        int source = find(item);
        if (source < 0 || !InvUtil.canUseInventory()) return InteractionResult.FAIL;
        var p = MC.player; var level = MC.level; var gameMode = MC.gameMode;
        int original = p.getInventory().selected;
        if (source >= 9 && false) return InteractionResult.FAIL;
        int menuSlot = InvUtil.menuSlot(source);
        if (!InventoryAutomation.claim(p.inventoryMenu, menuSlot)) return InteractionResult.FAIL;
        boolean swapped = false, keepSelected = false;
        try {
            if (source >= 9) {
                ItemStack displaced = p.getMainHandItem().copy();
                InvUtil.click(menuSlot, original, ClickType.SWAP);
                swapped = ItemStack.matches(InvUtil.stack(source), displaced);
                if (!swapped) return InteractionResult.FAIL;
            } else {
                InvUtil.select(source);
                ((MultiPlayerGameModeAccessor) gameMode).cheatprogram$ensureHasSentCarriedItem();
            }
            ItemStack held = p.getMainHandItem();
            if (!held.is(item) || held.isEmpty() || p.getCooldowns().isOnCooldown(held)) return InteractionResult.FAIL;
            InteractionResult result = InteractionLook.interact(hit.getLocation(), "silent", () -> {
                if (item instanceof BlockItem blockItem) {
                    var context = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, p.getMainHandItem(), hit);
                    var state = blockItem.getBlock().getStateForPlacement(context);
                    if (!context.canPlace() || state == null || !state.canSurvive(level, context.getClickedPos())
                            || !level.isUnobstructed(state, context.getClickedPos(), net.minecraft.world.phys.shapes.CollisionContext.of(p)))
                        return InteractionResult.FAIL;
                }
                return bucket ? gameMode.useItem(p, InteractionHand.MAIN_HAND)
                        : gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            });
            if (result.consumesAction()) {
                InventoryPoses.operated(owner, rotation, hit.getLocation(), true);
                if (!returnSlot && source < 9) {
                    if (retainedSlot < 0) retainedSlot = original;
                    retainedMaterial = source; keepSelected = true;
                }
            }
            return result;
        } finally {
            if (MC.player == p && MC.level == level && MC.gameMode == gameMode && MC.getConnection() != null) {
                if (swapped && p.containerMenu == p.inventoryMenu && p.inventoryMenu.getCarried().isEmpty())
                    InvUtil.click(menuSlot, original, ClickType.SWAP);
                if (!keepSelected) p.getInventory().selected = original;
                observedSlot = p.getInventory().selected;
                ((MultiPlayerGameModeAccessor) gameMode).cheatprogram$ensureHasSentCarriedItem();
            }
        }
    }
}
