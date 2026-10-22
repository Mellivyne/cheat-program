package com.cheatprogram.mixin;

import com.cheatprogram.ai.AiPlayers;
import com.cheatprogram.ai.BotPlayers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class AiPlayerNameMixin {
	@ModifyReturnValue(method = "getName", at = @At("RETURN"))
	private Component cheatprogram$aiPlayerName(Component original) {
		var id=((Player)(Object)this).getUUID();
		String name=AiPlayers.name(id);
		if(name==null) name=BotPlayers.name(id);
		return name==null?original:Component.literal(name);
	}
}
