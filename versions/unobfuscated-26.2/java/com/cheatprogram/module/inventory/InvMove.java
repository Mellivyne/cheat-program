package com.cheatprogram.module.inventory;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.gui.GuiBase;
import com.cheatprogram.util.Keys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

public class InvMove extends Module {
	public InvMove() {
		super("invmove", Category.INVENTORY);
	}

	@Override
	public void onTick() {
		boolean allowed = mc.gui.screen() instanceof AbstractContainerScreen<?> || mc.gui.screen() instanceof GuiBase gui && !gui.isTyping();
		if (!allowed) return;
		for (KeyMapping key : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
				mc.options.keyJump, mc.options.keySprint}) {
			Keys.restore(key);
		}
	}
}
