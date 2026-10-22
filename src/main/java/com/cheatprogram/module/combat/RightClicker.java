package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.mixin.MinecraftAccessor;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Clock;
import net.minecraft.world.item.BlockItem;

public class RightClicker extends Module {
	private final RangeSetting cps = add(new RangeSetting("cps", 7, 13, 1, 20, 1, ""));
	private final NumberSetting startDelay = add(new NumberSetting("startDelay", 0, 0, 500, 10, "ms"));
	private final BoolSetting blocksOnly = add(new BoolSetting("blocksOnly", true));
	private final Clock clock = new Clock();
	private long pressedSince = -1;

	public RightClicker() {
		super("rightclicker", Category.COMBAT);
	}

	@Override
	public void onRender3D(Render3D r3d) {
		if (mc.screen != null || !mc.options.keyUse.isDown()) {
			pressedSince = -1;
			return;
		}
		long now = System.currentTimeMillis();
		if (pressedSince < 0) pressedSince = now;
		if (now - pressedSince < startDelay.val()) return;
		if (blocksOnly.on() && !(mc.player.getMainHandItem().getItem() instanceof BlockItem)) return;
		if (mc.player.isUsingItem() || !clock.ready()) return;
		MinecraftAccessor accessor = (MinecraftAccessor) mc;
		accessor.cheatprogram$setRightClickDelay(0);
		accessor.cheatprogram$startUseItem();
		clock.reset(Clock.cpsDelay(cps.random()));
	}
}
