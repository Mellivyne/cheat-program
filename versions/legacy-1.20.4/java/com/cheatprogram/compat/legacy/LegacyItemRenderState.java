package com.cheatprogram.compat.legacy;
import net.minecraft.client.resources.model.BakedModel;import net.minecraft.world.item.ItemDisplayContext;
public final class LegacyItemRenderState {
 public float ageInTicks,bobOffset;public int count;public Item item;
 public record Item(BakedModel model){public boolean isGui3d(){return model.isGui3d();}public net.minecraft.client.renderer.block.model.ItemTransform transform(){return model.getTransforms().getTransform(ItemDisplayContext.GROUND);}}
}
