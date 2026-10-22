package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.AssistantTargets;
import com.cheatprogram.util.AssistedInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/** Plans around a real target and places actual inventory items through vanilla interactions. */
public final class AutoTrap extends Module {
    private final ModeSetting mode = add(new ModeSetting("mode", "trap_web", "trap_web", "trap_feet", "trap_full"));
    private final BoolSetting players = add(new BoolSetting("players", true));
    private final BoolSetting friendly = add(new BoolSetting("friendly", false));
    private final BoolSetting hostile = add(new BoolSetting("hostile", false));
    private final NumberSetting range = add(new NumberSetting("range", 4, 1, 8, .1));
    private final NumberSetting delay = add(new NumberSetting("delay", 100, 0, 1500, 10, "ms"));
    private final ModeSetting rotation = add(new ModeSetting("rotation", "silent", "silent", "follow"));
    private final BoolSetting preview = add(new BoolSetting("preview", true));
    private final BoolSetting returnSlot = add(new BoolSetting("returnSlot", true));
    private final AssistedInteractions interaction = new AssistedInteractions();
    private final List<BlockPos> plan = new ArrayList<>();
    private LivingEntity target;
    private Object world;
    private BlockPos pending;
    private long nextAction, pendingAt;

    public AutoTrap() { super("autotrap", Category.COMBAT); }
    @Override public String suffix() { return mode.display() + (target == null ? "" : " · " + target.getName().getString()); }
    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    private void reset() { interaction.reset(); plan.clear(); target = null; pending = null; world = null; nextAction = pendingAt = 0; }

    @Override public void onTick() {
        if (!inGame()) { reset(); return; }
        if (world != mc.level) { reset(); world = mc.level; }
        target = select(); plan.clear();
        if (target == null) { pending = null; return; }
        BlockPos feet = BlockPos.containing(target.position());
        if (mode.is("trap_web")) {
            plan.add(feet); if (target.getBbHeight() > 1.2) plan.add(feet.above());
        } else {
            for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) plan.add(feet.relative(direction));
            if (mode.is("trap_full")) {
                for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) plan.add(feet.relative(direction).above());
                plan.add(feet.above(2));
            }
        }
        plan.removeIf(pos -> !mc.level.hasChunkAt(pos) || !mc.level.getWorldBorder().isWithinBounds(pos)
                || !mc.level.getBlockState(pos).canBeReplaced());
        long now = System.currentTimeMillis();
        if (pending != null) {
            // Client prediction may be rolled back. Wait briefly before the next placement;
            // an unchanged/refused block is retried only after a bounded backoff.
            if (now - pendingAt < 100) return;
            if (mc.level.getBlockState(pending).canBeReplaced() && now - pendingAt < 600) return;
            pending = null;
        }
        if (!interaction.ready() || now < nextAction) return;
        Item material = mode.is("trap_web") ? Items.COBWEB : material();
        if (material == null || !AssistedInteractions.contains(material)) return;
        for (BlockPos destination : plan) {
            if (!mode.is("trap_web") && !mc.level.getEntities((Entity) null, new AABB(destination),
                    entity -> !entity.isSpectator() && entity.isPickable()).isEmpty()) continue;
            var hit = AssistedInteractions.placement(destination, false);
            if (hit == null) continue;
            if (!AssistedInteractions.followReady(hit.getLocation(), rotation.get())) return;
            if (interaction.use(this, material, hit, rotation.get(), returnSlot.on(), false).consumesAction()) {
                pending = destination.immutable(); pendingAt = now;
                nextAction = now + delay.intVal();
            } else nextAction = now + Math.max(100, delay.intVal());
            return;
        }
    }
    private Item material() {
        for (Item item : new Item[]{Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.COBBLED_DEEPSLATE,
                Items.COBBLESTONE, Items.STONE, Items.DIRT}) if (AssistedInteractions.contains(item)) return item;
        return null;
    }
    private LivingEntity select() {
        LivingEntity best = null; double distance = range.val() * range.val();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!AssistantTargets.accepts(entity, players.on(), friendly.on(), hostile.on())) continue;
            double d = entity.distanceToSqr(mc.player);
            if (d < distance && mc.player.hasLineOfSight(entity)) { best = (LivingEntity) entity; distance = d; }
        }
        return best;
    }
    @Override public void onRender3D(Render3D renderer) {
        if (!preview.on() || !inGame() || world != mc.level || plan.isEmpty()) return;
        renderer.begin();
        try {
            for (BlockPos pos : plan) {
                boolean available = AssistedInteractions.placement(pos, false) != null;
                renderer.box(new AABB(pos).inflate(.002), available ? 0x197DE6FF : 0x16FFBA72,
                        available ? 0xD07DE6FF : 0xA0FFBA72, 1.3f);
            }
        } finally { renderer.end(); }
    }
}
