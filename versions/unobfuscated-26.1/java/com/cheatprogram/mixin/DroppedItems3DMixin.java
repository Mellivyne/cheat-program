package com.cheatprogram.mixin;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.DroppedItems3D;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.WeakHashMap;

@Mixin(ItemEntityRenderer.class)
public abstract class DroppedItems3DMixin {
	@Unique private final Map<ItemEntityRenderState, DroppedItems3D.Frame> cheatprogram$droppedFrames = new WeakHashMap<>();

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V", at = @At("TAIL"))
	private void cheatprogram$extractDroppedPose(ItemEntity entity, ItemEntityRenderState state, float partialTick, CallbackInfo ci) {
		DroppedItems3D module = ModuleManager.get(DroppedItems3D.class);
		if (module == null || !module.isEnabled() || state.item.isEmpty()) {
			cheatprogram$droppedFrames.remove(state);
			return;
		}
		DroppedItems3D.Frame frame = module.extract(entity, state, partialTick);
		if (frame == null) cheatprogram$droppedFrames.remove(state);
		else cheatprogram$droppedFrames.put(state, frame);
	}

	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
	private void cheatprogram$renderDroppedModels(PoseStack pose, SubmitNodeCollector buffers, int light,
			ItemClusterRenderState cluster, RandomSource random, AABB bounds, Operation<Void> original,
			ItemEntityRenderState state, PoseStack entityPose, SubmitNodeCollector entityBuffers, CameraRenderState cameraState) {
		DroppedItems3D module = ModuleManager.get(DroppedItems3D.class);
		DroppedItems3D.Frame frame = cheatprogram$droppedFrames.get(state);
		if (module == null || !module.isEnabled() || frame == null) {
			original.call(pose, buffers, light, cluster, random, bounds);
			return;
		}
		module.render(pose, state, frame, () -> original.call(pose, buffers, light, cluster, random, bounds));
	}
}
