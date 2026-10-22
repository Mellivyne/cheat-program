package com.cheatprogram.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Invoker("startAttack")
	boolean cheatprogram$startAttack();

	@Invoker("startUseItem")
	void cheatprogram$startUseItem();

	@Accessor("rightClickDelay")
	int cheatprogram$getRightClickDelay();

	@Accessor("rightClickDelay")
	void cheatprogram$setRightClickDelay(int delay);

	@Accessor("missTime")
	void cheatprogram$setMissTime(int ticks);
}
