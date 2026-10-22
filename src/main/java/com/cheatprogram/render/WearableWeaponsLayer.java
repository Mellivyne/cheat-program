package com.cheatprogram.render;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.WearableWeapons;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;

/** Runs inside vanilla body/leg transforms, so swimming, crouching and silent body poses stay aligned. */
public final class WearableWeaponsLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
    private final ItemStackRenderState[] items = {
            new ItemStackRenderState(), new ItemStackRenderState(), new ItemStackRenderState(), new ItemStackRenderState()};
    private static boolean previewDrawing;
    public WearableWeaponsLayer(RenderLayerParent<PlayerRenderState, PlayerModel> parent) { super(parent); }
    public static boolean previewDrawing() { return previewDrawing; }
    public static void setPreviewDrawing(boolean value) { previewDrawing = value; }

    @Override public void render(PoseStack pose, MultiBufferSource buffers, int light,
                                 PlayerRenderState state, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        WearableWeapons module = ModuleManager.get(WearableWeapons.class);
        if (module == null || (!module.isEnabled() && !previewDrawing) || mc.player == null
                || state.id != mc.player.getId() || state.isSpectator || state.isInvisible) return;
        for (WearableWeapons.Resolved resolved : module.resolved()) {
            WearableWeapons.Attachment a = resolved.attachment();
            ItemStackRenderState item = items[a.index];
            mc.getItemModelResolver().updateForLiving(item, resolved.stack(), ItemDisplayContext.FIXED, false, mc.player);
            if (item.isEmpty()) continue;
            pose.pushPose();
            try {
                if (a.index < 2) getParentModel().body.translateAndRotate(pose);
                else if (a.index == 2) getParentModel().leftLeg.translateAndRotate(pose);
                else getParentModel().rightLeg.translateAndRotate(pose);
                pose.translate(a.x.val(), a.y.val(), a.z.val());
                pose.mulPose(Axis.XP.rotationDegrees(a.angleX.floatVal()));
                pose.mulPose(Axis.YP.rotationDegrees(a.angleY.floatVal()));
                pose.mulPose(Axis.ZP.rotationDegrees(a.angleZ.floatVal()));
                float size = a.size.floatVal() / 100F;
                // Vanilla sword/axe sprites lie diagonally. Normalize that diagonal before applying
                // the two default +/-45 degree mount angles, giving an X instead of an upright plus.
                boolean diagonal = a.index < 2 && (resolved.stack().is(ItemTags.SWORDS)
                        || resolved.stack().is(ItemTags.AXES) || resolved.stack().is(Items.MACE));
                pose.mulPose(Axis.ZP.rotationDegrees(diagonal ? 225 : 180));
                pose.scale(size, size, size);
                item.render(pose, buffers, light, OverlayTexture.NO_OVERLAY);
            } finally { pose.popPose(); }
        }
    }
}
