package com.cheatprogram.module.utility;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.mixin.FishingHookAccessor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

public class AutoFish extends Module {
	private final RangeSetting recastDelay = add(new RangeSetting("recastDelay", 450, 750, 100, 2000, 50, "ms"));
	private long recastAt = -1;
	private long reeledAt;

	public AutoFish() {
		super("autofish", Category.UTILITY);
	}

	@Override
	public void onTick() {
		var p = mc.player;
		if (!p.getMainHandItem().is(Items.FISHING_ROD)) {
			recastAt = -1;
			return;
		}
		long now = System.currentTimeMillis();
		if (p.fishing != null && ((FishingHookAccessor) p.fishing).cheatprogram$isBiting() && now - reeledAt > 500) {
			use();
			reeledAt = now;
			recastAt = now + (long) recastDelay.random();
		} else if (recastAt > 0 && now >= recastAt) {
			if (p.fishing == null) use();
			recastAt = -1;
		}
	}

	private void use() {
		mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}
}
