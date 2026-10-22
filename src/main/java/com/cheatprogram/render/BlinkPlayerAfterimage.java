package com.cheatprogram.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.phys.Vec3;

/** Own baked vanilla skin model and pose snapshot; never mutates the live player or a shared renderer model. */
final class BlinkPlayerAfterimage {
	private static final Minecraft MC = Minecraft.getInstance();
	private final PlayerModel model;
	private final PlayerRenderState state;
	private final float yaw;
	private final int light;
	private BlinkPlayerAfterimage(PlayerModel model, PlayerRenderState state, float yaw, int light) {
		this.model = model; this.state = state; this.yaw = yaw; this.light = light;
	}
	static BlinkPlayerAfterimage capture(LocalPlayer player, Vec3 from, Vec3 to) {
		var renderer = MC.getEntityRenderDispatcher().getRenderer(player);
		if (!(renderer instanceof PlayerRenderer playerRenderer)) return null;
		PlayerRenderState state = playerRenderer.createRenderState();
		playerRenderer.extractRenderState(player, state, 1);
		boolean slim = state.skin.model() == PlayerSkin.Model.SLIM;
		PlayerModel model = new PlayerModel(MC.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
		Vec3 movement = to.subtract(from);
		float yaw = movement.horizontalDistanceSqr() > 1.0e-8 ? (float)Math.toDegrees(Math.atan2(-movement.x, movement.z)) : player.getYRot();
		state.bodyRot = yaw; state.yRot = 0; state.xRot = 0;
		return new BlinkPlayerAfterimage(model, state, yaw, playerRenderer.getPackedLightCoords(player, 1));
	}
	void draw(Render3D renderer, Vec3 position, float opacity, boolean throughWalls) {
		if (opacity <= .001f) return;
		PoseStack poses = new PoseStack();
		poses.translate(position.x - renderer.camera.x, position.y - renderer.camera.y, position.z - renderer.camera.z);
		poses.mulPose(Axis.YP.rotationDegrees(180 - yaw));
		float scale = state.scale * .9375f;
		poses.scale(-scale, -scale, scale); poses.translate(0, -1.501f, 0);
		model.setupAnim(state);
		RenderType type = RenderType.entityTranslucent(state.skin.texture(), false);
		type.setupRenderState();
		try {
			if (throughWalls) RenderSystem.disableDepthTest(); else RenderSystem.enableDepthTest();
			// Draw directly after setting the type, so its normal depth-write setup cannot overwrite this mask.
			RenderSystem.depthMask(false); RenderSystem.setShaderColor(1, 1, 1, 1);
			BufferBuilder buffer = Tesselator.getInstance().begin(type.mode(), type.format());
			int color = Math.max(0, Math.min(255, Math.round(opacity * 255))) << 24 | 0xFFFFFF;
			model.renderToBuffer(poses, buffer, light, OverlayTexture.NO_OVERLAY, color);
			BufferUploader.drawWithShader(buffer.buildOrThrow());
		} finally { type.clearRenderState(); }
	}
}
