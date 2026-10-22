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
	@Redirect(method = "render(Lnet/minecraft/world/level/block/entity/ShulkerBoxBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/ShulkerBoxBlockEntity;getProgress(F)F"))
	private float cheatprogram$confirmedAutomaticLid(ShulkerBoxBlockEntity box, float partialTick) {
		return ChestContainerVisuals.openness(box, partialTick, box.getProgress(partialTick));
	}
}
