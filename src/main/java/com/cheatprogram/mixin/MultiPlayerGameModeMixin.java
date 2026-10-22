package com.cheatprogram.mixin;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.world.FastBreak;
import com.cheatprogram.util.InventoryProtection;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$protectAutomaticInventory(int containerId,int slotId,int button,ClickType type,Player player,CallbackInfo ci) {
		if(!InventoryProtection.isAutomatic() || player!=Minecraft.getInstance().player) return;
		var menu=player.containerMenu;
		if(menu!=null && menu.containerId==containerId && !InventoryProtection.allowClick(menu,slotId,button,type)) ci.cancel();
	}
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$attack(Player player, Entity target, CallbackInfo ci) {
		if (player == Minecraft.getInstance().player && ModuleManager.onAttack(target)) ci.cancel();
	}

	@Inject(method = "attack", at = @At("TAIL"))
	private void cheatprogram$postAttack(Player player, Entity target, CallbackInfo ci) {
		if (player == Minecraft.getInstance().player) ModuleManager.onPostAttack(target);
	}

	@WrapOperation(method = "continueDestroyBlock", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
	private float cheatprogram$breakSpeed(BlockState state, Player player, BlockGetter level, BlockPos pos, Operation<Float> original) {
		return FastBreak.scale(original.call(state, player, level, pos));
	}
}
