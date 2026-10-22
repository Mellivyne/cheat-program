package com.cheatprogram.module.world;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.mixin.MultiPlayerGameModeAccessor;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.util.InteractionLook;
import com.cheatprogram.util.InvUtil;
import com.cheatprogram.util.InventoryAutomation;
import com.cheatprogram.util.InventoryProtection;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Client-thread, ordered vanilla inventory/use packets, with no item duplication or retained slot. */
final class WorldInteractSupport {
    private static final Minecraft MC = Minecraft.getInstance();
    private Object player;
    private int selected = -1;
    private long manualUntil;

    void reset() { player = null; selected = -1; manualUntil = 0; }

    boolean ready() {
        if (MC.player == null || MC.level == null || MC.gameMode == null || MC.getConnection() == null) return false;
        int nowSelected = MC.player.getInventory().selected;
        if (player != MC.player) {
            player = MC.player;
            selected = nowSelected;
            manualUntil = 0;
        } else if (selected != nowSelected) {
            selected = nowSelected;
            // Yield when the user scrolls or presses a hotbar key, even without an inventory screen.
            manualUntil = System.currentTimeMillis() + 350;
        }
        return MC.screen == null && MC.getOverlay() == null && MC.isWindowActive() && !MC.isPaused()
                && MC.player.isAlive() && !MC.player.isSpectator() && !MC.player.isPassenger()
                && !MC.player.isUsingItem() && !MC.gameMode.isDestroying()
                && System.currentTimeMillis() >= manualUntil && InventoryAutomation.ownInventoryReady();
    }

    /** Follow reaches the target first; exclusive AI/freecam camera ownership stays silent. */
    static boolean aimReady(Vec3 point, String mode) {
        if (!"follow".equals(mode) || AiController.isRunning() || Freecam.isActive()) return true;
        Vec3 delta = point.subtract(MC.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))), -90, 90);
        float dy = Mth.wrapDegrees(yaw - MC.player.getYRot());
        float dp = pitch - MC.player.getXRot();
        if (Math.abs(dy) <= 1.5f && Math.abs(dp) <= 1.5f) return true;
        float yawStep = Math.abs(dy) < 2 ? dy : Mth.clamp(dy * .34f, -18, 18);
        float pitchStep = Math.abs(dp) < 2 ? dp : Mth.clamp(dp * .34f, -12, 12);
        MC.player.turn(yawStep / .15, pitchStep / .15);
        return false;
    }

    static boolean reachable(BlockHitResult hit) {
        if (!MC.level.hasChunkAt(hit.getBlockPos()) || !MC.level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return false;
        Vec3 eye = MC.player.getEyePosition();
        // Read the server-synchronised attribute directly, without the separate Reach module's view patch.
        double reach = MC.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        if (eye.distanceToSqr(hit.getLocation()) > reach * reach) return false;
        BlockHitResult obstruction = MC.level.clip(new ClipContext(eye,
                hit.getLocation().add(hit.getLocation().subtract(eye).normalize().scale(.001)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
        return obstruction.getType() == HitResult.Type.MISS || obstruction.getBlockPos().equals(hit.getBlockPos());
    }

    static boolean contains(Item item) {
        return findAvailable(item) >= 0 || MC.player.getOffhandItem().is(item) && !InventoryProtection.protectedSlot(40);
    }

    private static int findAvailable(Item item) {
        for (int i = 0; i < 36; i++) if (!InventoryProtection.protectedSlot(i) && InvUtil.stack(i).is(item) && !InvUtil.stack(i).isEmpty()) return i;
        return -1;
    }

    /** Borrow, interact and restore in the same stack frame, before input or a render can run. */
    static InteractionResult use(Item item, BlockHitResult hit) {
        return use(item, hit, false);
    }

    /** Some blocks run their empty-hand fallback only for a main-hand interaction. */
    static InteractionResult useMainHand(Item item, BlockHitResult hit) {
        return use(item, hit, true);
    }

    /** A single inventory claim/borrow, stopping as soon as actual client prediction does not advance. */
    static int fillAnchorFast(BlockHitResult hit) {
        return useSeries(net.minecraft.world.item.Items.GLOWSTONE,hit,true,RespawnAnchorBlock.MAX_CHARGES).interactions();
    }

    /** A full anchor's main-hand block action consumes no glowstone, including the last-four case. */
    static InteractionResult useFullAnchor(BlockHitResult hit) {
        if(!InvUtil.canUseInventory()||!reachable(hit))return InteractionResult.FAIL;
        var state=MC.level.getBlockState(hit.getBlockPos());
        if(!state.is(Blocks.RESPAWN_ANCHOR)||state.getValue(RespawnAnchorBlock.CHARGE)!=RespawnAnchorBlock.MAX_CHARGES
                ||!InventoryAutomation.claim(MC.player.inventoryMenu,InvUtil.menuSlot(MC.player.getInventory().selected)))return InteractionResult.FAIL;
        return InteractionLook.interact(hit.getLocation(),"silent",()->MC.gameMode.useItemOn(MC.player,InteractionHand.MAIN_HAND,hit));
    }

    private record UseSeries(InteractionResult result,int interactions) { }

    private static InteractionResult use(Item item, BlockHitResult hit, boolean forceMainHand) {
        return useSeries(item,hit,forceMainHand,1).result();
    }

    private static UseSeries useSeries(Item item,BlockHitResult hit,boolean forceMainHand,int maximum) {
        UseSeries failed=new UseSeries(InteractionResult.FAIL,0);
        if (!InvUtil.canUseInventory() || !reachable(hit)) return failed;
        var player = MC.player;
        var gameMode = MC.gameMode;
        var level = MC.level;
        int originalSlot = player.getInventory().selected;
        InteractionHand hand = InteractionHand.MAIN_HAND;
        int index = -1;
        if (!player.getMainHandItem().is(item) || InventoryProtection.protectedSlot(originalSlot)) {
            if (player.getOffhandItem().is(item) && !forceMainHand && !InventoryProtection.protectedSlot(40)) hand = InteractionHand.OFF_HAND;
            else {
                index = findAvailable(item);
                if (index < 0 && forceMainHand && player.getOffhandItem().is(item) && !InventoryProtection.protectedSlot(40)) index = 40;
            }
            if (hand == InteractionHand.MAIN_HAND && index < 0) return failed;
        }
        // Validate both ends before a temporary swap, including the original hand's sword.
        // The central click guard remains a final safety net, rather than splitting a transaction.
        if (index >= 9 && (InventoryProtection.protectedSlot(index) || InventoryProtection.protectedSlot(originalSlot))) return failed;
        int source = index < 0 ? InvUtil.menuSlot(hand == InteractionHand.OFF_HAND ? 40 : originalSlot) : InvUtil.menuSlot(index);
        if (!InventoryAutomation.claim(player.inventoryMenu, source)) return failed;
        boolean swapped = false;
        try {
            if (index >= 9) {
                // Main-inventory or offhand SWAP precedes use on the same ordered connection.
                // The inverse SWAP moves the predicted remaining count back, including an empty stack.
                ItemStack displaced = player.getMainHandItem().copy();
                InvUtil.click(source, originalSlot, ClickType.SWAP);
                swapped = ItemStack.matches(InvUtil.stack(index), displaced);
                if (!swapped) return failed;
            } else if (index >= 0) {
                InvUtil.select(index);
                ((MultiPlayerGameModeAccessor) gameMode).cheatprogram$ensureHasSentCarriedItem();
            }
            if (!player.getItemInHand(hand).is(item) || player.getItemInHand(hand).isEmpty()
                    || player.getCooldowns().isOnCooldown(player.getItemInHand(hand))) return failed;
            InteractionHand useHand = hand;
            InteractionResult result=InteractionResult.FAIL;
            int interactions=0;
            for(int i=0;i<Math.min(RespawnAnchorBlock.MAX_CHARGES,Math.max(1,maximum));i++) {
                if(MC.player!=player||MC.level!=level||MC.gameMode!=gameMode||MC.getConnection()==null
                        ||!player.getItemInHand(useHand).is(item)||player.getItemInHand(useHand).isEmpty())break;
                var before=level.getBlockState(hit.getBlockPos());
                if(maximum>1&&(!before.is(Blocks.RESPAWN_ANCHOR)
                        ||before.getValue(RespawnAnchorBlock.CHARGE)>=RespawnAnchorBlock.MAX_CHARGES))break;
                result=InteractionLook.interact(hit.getLocation(),"silent",()->gameMode.useItemOn(player,useHand,hit));
                if(!result.consumesAction())break;
                interactions++;
                if(maximum==1)break;
                var after=level.getBlockState(hit.getBlockPos());
                // A refused or deferred prediction waits for the real server update instead of flooding packets.
                if(!after.is(Blocks.RESPAWN_ANCHOR)
                        ||after.getValue(RespawnAnchorBlock.CHARGE)<=before.getValue(RespawnAnchorBlock.CHARGE))break;
            }
            return new UseSeries(result,interactions);
        } finally {
            if (MC.player == player && MC.level == level && MC.gameMode == gameMode && MC.getConnection() != null) {
                if (swapped && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty())
                    InvUtil.click(source, originalSlot, ClickType.SWAP);
                player.getInventory().selected = originalSlot;
                ((MultiPlayerGameModeAccessor) gameMode).cheatprogram$ensureHasSentCarriedItem();
            }
        }
    }
}
