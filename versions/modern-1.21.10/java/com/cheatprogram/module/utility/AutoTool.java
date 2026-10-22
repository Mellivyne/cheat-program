package com.cheatprogram.module.utility;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.util.InvUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class AutoTool extends Module {
	private final BoolSetting switchBack = add(new BoolSetting("switchBack", true));
	private int previous = -1;

	public AutoTool() {
		super("autotool", Category.UTILITY);
	}

	@Override
	public void onTick() {
		boolean mining = mc.screen == null && mc.options.keyAttack.isDown()
				&& mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
		if (mining) {
			BlockState state = mc.level.getBlockState(((BlockHitResult) mc.hitResult).getBlockPos());
			if (state.isAir()) return;
			int current = mc.player.getInventory().getSelectedSlot();
			int best = -1;
			float bestSpeed = InvUtil.stack(current).getDestroySpeed(state);
			for (int i = 0; i < 9; i++) {
				float speed = InvUtil.stack(i).getDestroySpeed(state);
				if (speed > bestSpeed + 0.01f) {
					bestSpeed = speed;
					best = i;
				}
			}
			if (best >= 0) {
				if (previous < 0) previous = current;
				InvUtil.select(best);
			}
		} else if (previous >= 0) {
			if (switchBack.on()) InvUtil.select(previous);
			previous = -1;
		}
	}
}
