package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.mixin.MinecraftAccessor;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Clock;
import net.minecraft.world.phys.HitResult;

public class AutoClicker extends Module {
	private final RangeSetting cps = add(new RangeSetting("cps", 8, 12, 1, 20, 1, ""));
	private final BoolSetting cooldown = add(new BoolSetting("cooldown", false));
	private final BoolSetting breakBlocks = add(new BoolSetting("breakBlocks", true));
	private final Clock clock = new Clock();
	private boolean wasDown;

	public AutoClicker() {
		super("autoclicker", Category.COMBAT);
	}

	@Override
	public void onRender3D(Render3D r3d) {
		boolean down = mc.gui.screen() == null && mc.options.keyAttack.isDown();
		if (!down) {
			wasDown = false;
			return;
		}
		if (!wasDown) {
			// vanilla already clicked on the press itself
			wasDown = true;
			clock.reset(Clock.cpsDelay(cps.random()));
			return;
		}
		if (breakBlocks.on() && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) return;
		if (cooldown.on() && mc.player.getAttackStrengthScale(0f) < 0.9f) return;
		if (mc.player.isUsingItem() || !clock.ready()) return;
		MinecraftAccessor accessor = (MinecraftAccessor) mc;
		accessor.cheatprogram$setMissTime(0);
		accessor.cheatprogram$startAttack();
		clock.reset(Clock.cpsDelay(cps.random()));
	}
}
