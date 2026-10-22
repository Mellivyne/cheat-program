package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.WeaponAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandRenderer.class)
public abstract class WeaponAnimationsMixin {
	@WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V"))
	private void cheatprogram$weaponSwing(ItemInHandRenderer renderer, float swing, PoseStack pose,
			int side, HumanoidArm arm, Operation<Void> original, AbstractClientPlayer player, float partialTick,
			float pitch, InteractionHand hand, float handSwing, ItemStack stack, float handEquipped,
			PoseStack handPose, SubmitNodeCollector buffers, int light) {
		WeaponAnimations animations = WeaponAnimations.forHand(player, hand, stack);
		if (animations != null) {
            // Vanilla now places the hand before swingArm; undo that root only
            // for the custom animation, whose stable root includes equip height.
            pose.translate(-side * .56F, .52F + handEquipped * .6F, .72F);
            animations.renderSwing(player, hand, stack, pose, arm, swing, handEquipped);
        }
		else original.call(renderer, swing, pose, side, arm);
	}
}
