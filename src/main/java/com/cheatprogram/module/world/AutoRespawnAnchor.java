package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Render3D;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Charge with actual glowstone, then use the full anchor through vanilla's main-hand fallback. */
public final class AutoRespawnAnchor extends Module {
    private final NumberSetting delay = add(new NumberSetting("delay", 50, 0, 2000, 10, "ms"));
    private final BoolSetting fastCharge=add(new BoolSetting("fastCharge",true));
    private final ModeSetting rotation = add(new ModeSetting("rotation", "silent", "silent", "follow"));
    private final WorldInteractSupport interaction = new WorldInteractSupport();
    private final SmartExplosions smart = new SmartExplosions();
    private Object world, player;
    private long nextAction;
    private int actionTick = -1;
    private BlockPos fullAnchor;
    private long fullUsedAt;

    public AutoRespawnAnchor() {
        super("autorespawnanchor", Category.WORLD);
        add(smart.enabled); add(smart.range.visibleWhen(smart.enabled::on));
        add(smart.minDamage.visibleWhen(smart.enabled::on)); add(smart.maxSelf.visibleWhen(smart.enabled::on));
        add(smart.marker.visibleWhen(smart.enabled::on));
    }
    @Override public void onRender3D(Render3D r) { smart.render(r); }
    @Override public void onRender2D(com.cheatprogram.hud.HudContext hud) { smart.hud(hud,true); }
    @Override public String suffix() { return delay.format() + ", " + rotation.display(); }
    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    private void reset() {
        interaction.reset(); world = player = null; nextAction = 0; actionTick = -1;
        fullAnchor = null; fullUsedAt = 0;
        smart.reset();
    }

    @Override public void onTick() {
        if (!inGame()) { reset(); return; }
        if (world != mc.level || player != mc.player) { reset(); world = mc.level; player = mc.player; }
        if (fullAnchor != null && mc.level.hasChunkAt(fullAnchor)) {
            BlockState pending = mc.level.getBlockState(fullAnchor);
            if (!pending.is(Blocks.RESPAWN_ANCHOR)
                    || pending.getValue(RespawnAnchorBlock.CHARGE) != RespawnAnchorBlock.MAX_CHARGES) {
                fullAnchor = null; fullUsedAt = 0;
            }
        }
        long now = System.currentTimeMillis();
        if (!interaction.ready() || mc.player.isSecondaryUseActive() || actionTick == mc.player.tickCount
                || now < nextAction) return;
        if (smart.enabled.on() && RespawnAnchorBlock.canSetSpawn(mc.level)) return;
        SmartExplosions.Choice selected = smart.enabled.on() ? smart.find(true,
                p -> !p.equals(fullAnchor) || now-fullUsedAt >= Math.max(1000,delay.intVal())) : null;
        BlockHitResult hit = smart.enabled.on() ? (selected == null ? null : selected.hit())
                : mc.hitResult instanceof BlockHitResult looked ? looked : null;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos position = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(position);
        if (!state.is(Blocks.RESPAWN_ANCHOR) || state.getValue(RespawnAnchorBlock.CHARGE)<RespawnAnchorBlock.MAX_CHARGES
                && !WorldInteractSupport.contains(Items.GLOWSTONE) || !WorldInteractSupport.reachable(hit)
                || smart.enabled.on() && !smart.safe(net.minecraft.world.phys.Vec3.atCenterOf(position),true,position)
                || !WorldInteractSupport.aimReady(hit.getLocation(), rotation.get())) return;
        if (position.equals(fullAnchor)) {
            // Vanilla leaves a Nether anchor in place after setting spawn. Do not spam that use.
            // In other dimensions wait for the server's block update before retrying a lost use.
            if (RespawnAnchorBlock.canSetSpawn(mc.level) || now - fullUsedAt < Math.max(1000, delay.intVal())) return;
        }
        // Recheck immediately before use: charges 0–3 consume glowstone. At charge 4, vanilla
        // tries useWithoutItem only from MAIN_HAND and either explodes or sets the Nether spawn.
        state = mc.level.getBlockState(position);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) return;
        boolean full = state.getValue(RespawnAnchorBlock.CHARGE) == RespawnAnchorBlock.MAX_CHARGES;
        if(full) {
            if(!WorldInteractSupport.useFullAnchor(hit).consumesAction())return;
        }else if(fastCharge.on()) {
            if(WorldInteractSupport.fillAnchorFast(hit)==0)return;
        }else if (!WorldInteractSupport.useMainHand(Items.GLOWSTONE, hit).consumesAction()) return;
        mc.player.swing(InteractionHand.MAIN_HAND);
        actionTick = mc.player.tickCount;
        nextAction = now + delay.intVal();
        if (full) { fullAnchor = position.immutable(); fullUsedAt = now; }
    }
}
