package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.AssistedInteractions;
import com.cheatprogram.util.AuraRotations;
import com.cheatprogram.util.InteractionLook;
import com.cheatprogram.util.InventoryAutomation;
import com.cheatprogram.util.InventoryPoses;
import com.cheatprogram.util.InvUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Harvest -> actual server update -> plant -> actual server update, with bounded refusal backoff. */
public final class FarmAssist extends Module {
    private final BoolSetting wheat = add(new BoolSetting("wheat", true));
    private final BoolSetting carrots = add(new BoolSetting("carrots", true));
    private final BoolSetting potatoes = add(new BoolSetting("potatoes", true));
    private final BoolSetting beetroot = add(new BoolSetting("beetroot", true));
    private final BoolSetting netherWart = add(new BoolSetting("netherWart", true));
    private final BoolSetting replant = add(new BoolSetting("replant", true));
    private final BoolSetting keepSeeds = add(new BoolSetting("keepSeeds", true));
    private final NumberSetting range = add(new NumberSetting("range", 4, 1, 6, .1));
    private final NumberSetting delay = add(new NumberSetting("delay", 100, 0, 2000, 10, "ms"));
    private final ModeSetting rotation = add(new ModeSetting("rotation", "silent", "silent", "follow"));
    private final BoolSetting returnSlot = add(new BoolSetting("returnSlot", true));
    private final BoolSetting preview = add(new BoolSetting("preview", true));
    private final AssistedInteractions interaction = new AssistedInteractions();
    private final Map<BlockPos, Long> refused = new HashMap<>();
    private Object world, player;
    private Crop work, selected;
    private Stage stage = Stage.IDLE;
    private long startedAt, nextAction;
    private int harvested, planted;
    private enum Stage { IDLE, WAIT_HARVEST, PLANT, WAIT_PLANT }
    private record Crop(BlockPos position, BlockState state, Item seed) { }

    public FarmAssist() { super("farmassist", Category.WORLD); }
    @Override public String suffix() { return Lang.t("farm.harvested") + " " + harvested + " · " + Lang.t("farm.planted") + " " + planted; }
    @Override public void onEnable() { reset(); harvested = planted = 0; }
    @Override public void onDisable() { reset(); }
    private void reset() {
        if (world == mc.level && player == mc.player && stage == Stage.WAIT_HARVEST && mc.gameMode != null
                && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        interaction.reset(); world = player = null; work = selected = null; stage = Stage.IDLE;
        nextAction = startedAt = 0; refused.clear();
    }

    /** Inventory cleaner and combat keep policy must consult this before their junk exclusions. */
    public static boolean keepsSeed(ItemStack stack) {
        FarmAssist farm = ModuleManager.get(FarmAssist.class);
        if (farm == null || !farm.isEnabled() || !farm.keepSeeds.on() || stack.isEmpty()) return false;
        return farm.wheat.on() && stack.is(Items.WHEAT_SEEDS) || farm.carrots.on() && stack.is(Items.CARROT)
                || farm.potatoes.on() && stack.is(Items.POTATO) || farm.beetroot.on() && stack.is(Items.BEETROOT_SEEDS)
                || farm.netherWart.on() && stack.is(Items.NETHER_WART);
    }

    /** Called at the existing ClientPacketListener block-update tail, on the client thread. */
    public static void serverBlockUpdate(BlockPos position, BlockState state) {
        FarmAssist farm = ModuleManager.get(FarmAssist.class);
        if (farm == null || !farm.isEnabled() || farm.world != mc.level || farm.player != mc.player || farm.work == null
                || !farm.work.position().equals(position)) return;
        if (farm.stage == Stage.WAIT_HARVEST) {
            if (state.isAir() || state.canBeReplaced() && !state.is(farm.work.state().getBlock())) {
                farm.harvested++;
                farm.stage = farm.replant.on() ? Stage.PLANT : Stage.IDLE;
                if (farm.stage == Stage.IDLE) farm.work = null;
                farm.startedAt = System.currentTimeMillis();
            } else if (state.equals(farm.work.state())) farm.refuse();
            else farm.cancelWork();
        } else if (farm.stage == Stage.WAIT_PLANT) {
            if (state.is(farm.work.state().getBlock())) { farm.planted++; farm.cancelWork(); }
            else if (!state.canBeReplaced()) farm.refuse();
        }
    }

    @Override public void onTick() {
        if (!inGame()) { reset(); return; }
        if (world != mc.level || player != mc.player) { reset(); world = mc.level; player = mc.player; }
        long now = System.currentTimeMillis();
        refused.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (work != null && (!mc.level.hasChunkAt(work.position()) || now - startedAt > 2500)) refuse();
        if (stage == Stage.WAIT_HARVEST || stage == Stage.WAIT_PLANT) return;
        if (!interaction.ready() || AuraRotations.active() || now < nextAction) return;
        if (stage == Stage.PLANT) {
            if (!mc.level.getBlockState(work.position()).canBeReplaced()) { refuse(); return; }
            var hit = AssistedInteractions.placement(work.position(), false);
            if (hit == null || !AssistedInteractions.contains(work.seed())) return;
            if (!AssistedInteractions.followReady(hit.getLocation(), rotation.get())) return;
            stage = Stage.WAIT_PLANT; startedAt = now;
            if (!interaction.use(this, work.seed(), hit, rotation.get(), returnSlot.on(), false).consumesAction()) stage = Stage.PLANT;
            nextAction = now + Math.max(50, delay.intVal()); return;
        }
        selected = findCrop();
        if (selected == null) return;
        // Keep at least one real planting item before harvesting; drops are never invented.
        if (replant.on() && !AssistedInteractions.contains(selected.seed())) return;
        var hit = cropHit(selected.position());
        if (hit == null || !AssistedInteractions.followReady(hit.getLocation(), rotation.get())
                || !InventoryAutomation.claim(mc.player.inventoryMenu, InvUtil.menuSlot(mc.player.getInventory().selected))) return;
        work = selected; stage = Stage.WAIT_HARVEST; startedAt = now;
        boolean begun = InteractionLook.interact(hit.getLocation(), "silent", () -> mc.gameMode.startDestroyBlock(hit.getBlockPos(), hit.getDirection()));
        if (!begun) { refuse(); return; }
        // Crops have zero hardness, so vanilla startDestroyBlock normally completes immediately.
        // A custom server may refuse it; only its later block update advances this workflow.
        InventoryPoses.operated(this, rotation.get(), hit.getLocation(), true);
        nextAction = now + Math.max(50, delay.intVal());
    }
    private void cancelWork() { work = null; stage = Stage.IDLE; nextAction = System.currentTimeMillis() + delay.intVal(); }
    private void refuse() {
        if (stage == Stage.WAIT_HARVEST && mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        if (work != null) {
            if (refused.size() >= 256) refused.clear();
            refused.put(work.position(), System.currentTimeMillis() + 3000);
        }
        cancelWork();
    }
    private Crop findCrop() {
        Crop best = null; double distance = range.val() * range.val();
        int radius = (int) Math.ceil(range.val()); BlockPos center = mc.player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 2, radius))) {
            if (refused.containsKey(pos) || !mc.level.hasChunkAt(pos)) continue;
            BlockState state = mc.level.getBlockState(pos); Item seed = seed(state);
            if (seed == null) continue;
            boolean mature = state.getBlock() instanceof CropBlock crop ? crop.isMaxAge(state)
                    : state.is(Blocks.NETHER_WART) && state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
            double d = mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
            if (mature && d < distance && cropHit(pos) != null) {
                best = new Crop(pos.immutable(), state, seed); distance = d;
            }
        }
        return best;
    }
    private Item seed(BlockState state) {
        if (wheat.on() && state.is(Blocks.WHEAT)) return Items.WHEAT_SEEDS;
        if (carrots.on() && state.is(Blocks.CARROTS)) return Items.CARROT;
        if (potatoes.on() && state.is(Blocks.POTATOES)) return Items.POTATO;
        if (beetroot.on() && state.is(Blocks.BEETROOTS)) return Items.BEETROOT_SEEDS;
        if (netherWart.on() && state.is(Blocks.NETHER_WART)) return Items.NETHER_WART;
        return null;
    }
    private BlockHitResult cropHit(BlockPos position) {
        Vec3 eye = mc.player.getEyePosition(), point = Vec3.atCenterOf(position);
        var hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position) && AssistedInteractions.reachable(hit) ? hit : null;
    }
    @Override public void onRender3D(Render3D renderer) {
        Crop crop = work != null ? work : selected;
        if (!preview.on() || crop == null || !inGame() || world != mc.level) return;
        renderer.begin();
        try { renderer.box(new AABB(crop.position()).deflate(.03), 0x187CFFAA, 0xC0A9FFD0, 1.3f); }
        finally { renderer.end(); }
    }
}
