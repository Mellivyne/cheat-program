package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.module.visual.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/** Singleplayer can synchronize the real pose; multiplayer has an explicit local appearance. */
public final class SwimState extends Module {
    public final ModeSetting mode = add(new ModeSetting("mode", "swim_authorized", "swim_authorized", "swim_local"));
    private static volatile java.util.UUID owner;
    private static volatile boolean authoritative, preview;
    public SwimState() { super("swimstate", Category.MOVEMENT); }
    @Override public void onTick() {
        owner = mc.player.getUUID(); preview = mode.is("swim_local") || mc.getSingleplayerServer()==null;
        authoritative = !preview && mc.getSingleplayerServer() != null;
        if ((preview || authoritative) && !Freecam.isActive()) applyPose(mc.player);
    }
    @Override public void onDisable() {
        owner = null; preview = authoritative = false;
        if (mc.player != null) { mc.player.setSwimming(false); mc.player.setPose(mc.player.isShiftKeyDown() ? Pose.CROUCHING : Pose.STANDING); }
    }
    public static void tickLifecycle() {
        Minecraft client = Minecraft.getInstance();
        SwimState module = ModuleManager.get(SwimState.class);
        if (module == null || !module.isEnabled() || client.player == null || client.level == null) {
            owner = null; preview = authoritative = false;
        }
    }
    /** Called at Player.updateSwimming tail on both logical sides. */
    public static void applyPose(Player player) {
        if (owner == null || !owner.equals(player.getUUID()) || player.isPassenger() || player.isSleeping()
                || player.isFallFlying() || player.isSpectator() || !player.isAlive()) return;
        if (player.level().isClientSide() ? !(preview || authoritative) : !authoritative) return;
        if (!player.level().noCollision(player, player.getDimensions(Pose.SWIMMING).makeBoundingBox(player.position()))) return;
        player.setSwimming(true); player.setPose(Pose.SWIMMING);
    }
    @Override public String suffix() { return mc.getSingleplayerServer()!=null&&!mode.is("swim_local")?mode.display():com.cheatprogram.core.Lang.t("mode.swim_local"); }
}
