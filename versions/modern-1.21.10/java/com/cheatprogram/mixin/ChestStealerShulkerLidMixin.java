package com.cheatprogram.mixin;

import com.cheatprogram.render.ChestContainerVisuals;
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Change only the rendered lid, because getProgress is also used by vanilla shulker collision boxes. */
@Mixin(ShulkerBoxRenderer.class)
public abstract class ChestStealerShulkerLidMixin {
	@Redirect(method = "extractRenderState(Lnet/minecraft/world/level/block/entity/ShulkerBoxBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/ShulkerBoxRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/ShulkerBoxBlockEntity;getProgress(F)F"))
	private float cheatprogram$confirmedAutomaticLid(ShulkerBoxBlockEntity box, float partialTick) {
		return ChestContainerVisuals.openness(box, partialTick, box.getProgress(partialTick));
	}
}
