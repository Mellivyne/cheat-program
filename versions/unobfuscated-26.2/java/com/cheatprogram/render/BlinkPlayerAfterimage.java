package com.cheatprogram.render;

import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;

import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;

/** Own baked vanilla skin model and pose snapshot; never mutates the live player or a shared renderer model. */
final class BlinkPlayerAfterimage {
	private static final Minecraft MC = Minecraft.getInstance();
	private final PlayerModel model;
	private final AvatarRenderState state;
	private final float yaw;
	private final int light;
	private BlinkPlayerAfterimage(PlayerModel model, AvatarRenderState state, float yaw, int light) {
		this.model = model; this.state = state; this.yaw = yaw; this.light = light;
	}
	static BlinkPlayerAfterimage capture(LocalPlayer player, Vec3 from, Vec3 to) {
		var renderer = MC.getEntityRenderDispatcher().getRenderer(player);
		if (!(renderer instanceof AvatarRenderer playerRenderer)) return null;
		AvatarRenderState state = playerRenderer.createRenderState();
		playerRenderer.extractRenderState(player, state, 1);
		boolean slim = state.skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
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
		RenderType type = net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(state.skin.body().texturePath(), false);
        BufferBuilder buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(type.primitiveTopology(),type.format());
        model.renderToBuffer(poses,buffer,light,OverlayTexture.NO_OVERLAY,0xFFFFFFFF);
        com.cheatprogram.compat.NativeCachedMesh.immediate(type,buffer.buildOrThrow(),renderer.viewMatrix(),renderer.projectionMatrix(),opacity,throughWalls);
	}
}
