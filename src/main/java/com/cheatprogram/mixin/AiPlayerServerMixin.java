package com.cheatprogram.mixin;

import com.cheatprogram.ai.AiPlayers;
import com.cheatprogram.ai.BotPlayers;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class AiPlayerServerMixin {
	@Inject(method = "tickServer", at = @At("TAIL"))
	private void cheatprogram$aiPlayerTick(BooleanSupplier tickTime, CallbackInfo info) {
		AiPlayers.onServerTick((MinecraftServer) (Object) this);
		BotPlayers.onServerTick((MinecraftServer) (Object) this);
	}
	@Inject(method = "stopServer", at = @At("HEAD"))
	private void cheatprogram$removeAiPlayers(CallbackInfo info) {
		AiPlayers.onServerStopping((MinecraftServer) (Object) this);
		BotPlayers.onServerStopping((MinecraftServer) (Object) this);
	}
}
