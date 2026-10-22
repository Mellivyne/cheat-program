package com.cheatprogram.module.world;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.visual.Freecam;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/** Real idle activity plus an optional, explicitly authorized Paper AFK/floating exemption. */
public final class AntiKick extends Module {
    public final BoolSetting idleActivity = add(new BoolSetting("idleActivity", true));
    public final NumberSetting idleInterval = add(new NumberSetting("idleInterval", 30, 5, 300, 1, "s")).visibleWhen(idleActivity::on);
    public final BoolSetting downwardPulse = add(new BoolSetting("downwardPulse", false));
    public final NumberSetting pulseInterval = add(new NumberSetting("pulseInterval", 30, 5, 120, 1, "tick")).visibleWhen(downwardPulse::on);
    public final NumberSetting pulseDepth = add(new NumberSetting("pulseDepth", 0.04, 0.01, 0.25, 0.01)).visibleWhen(downwardPulse::on);
    private Vec3 observed;
    private float yaw, pitch;
    private long idleSince;
    public AntiKick() { super("antikick", Category.WORLD); }
    @Override public void onEnable() { observed = null; idleSince = 0; }
    @Override public void onDisable() { observed=null; }
    @Override public void onTick() {
        if (mc.gui.screen() != null || mc.isPaused() || Freecam.isActive() || AiController.isRunning()
                || mc.player.isPassenger() || !mc.player.isAlive()) { observed = null; return; }
        long now = System.nanoTime();
        if (observed == null || observed.distanceToSqr(mc.player.position()) > 0.0025
                || Math.abs(yaw - mc.player.getYRot()) > 1 || Math.abs(pitch - mc.player.getXRot()) > 1) {
            idleSince = now; observed = mc.player.position(); yaw = mc.player.getYRot(); pitch = mc.player.getXRot();
        }
        if (idleActivity.on() && now - idleSince >= (long)(idleInterval.val() * 1_000_000_000L)
                && mc.player.onGround() && !mc.player.isUsingItem()) {
            // A real jump and ordinary arm swing, with no fake ground bit or chat spam.
            mc.player.jumpFromGround(); mc.player.swing(InteractionHand.MAIN_HAND); idleSince = now;
        }
        if (downwardPulse.on() && !mc.player.onGround() && !mc.player.isInWater() && !mc.player.isFallFlying()
                && mc.player.tickCount % pulseInterval.intVal() == 0) {
            Vec3 delta = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(delta.x, Math.min(delta.y, -pulseDepth.val()), delta.z);
        }
    }
    @Override public String suffix() { return com.cheatprogram.core.Lang.t("ui.antikick.client"); }
}
