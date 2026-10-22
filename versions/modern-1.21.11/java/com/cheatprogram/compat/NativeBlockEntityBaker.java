package com.cheatprogram.compat;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Converts real native block-entity submissions into the blueprint's cached mesh builders. */
public final class NativeBlockEntityBaker {
    private static Pipeline pipeline;
    private NativeBlockEntityBaker() { }

    public static <T extends BlockEntity, S extends BlockEntityRenderState> void bake(
            BlockEntityRenderer<T, S> renderer, T entity, PoseStack pose, MultiBufferSource destination) {
        if (pipeline == null) pipeline = new Pipeline();
        S state = renderer.createRenderState();
        renderer.extractRenderState(entity, state, 0, Vec3.ZERO, null);
        state.lightCoords = 15728880;
        CameraRenderState camera = new CameraRenderState();
        camera.initialized = true;
        pipeline.forward.destination = destination;
        try {
            renderer.submit(state, pose, pipeline.submits, camera);
            pipeline.features.renderAllFeatures();
        } finally {
            pipeline.submits.clear();
            pipeline.forward.destination = null;
        }
    }

    private static final class Pipeline {
        final SubmitNodeStorage submits = new SubmitNodeStorage();
        final ForwardingBufferSource forward = new ForwardingBufferSource();
        final OutlineBufferSource outlines = new OutlineBufferSource();
        final FeatureRenderDispatcher features;
        Pipeline() {
            Minecraft mc = Minecraft.getInstance();
            features = new FeatureRenderDispatcher(submits, mc.getBlockRenderer(), forward, mc.getAtlasManager(), outlines, forward, mc.font);
        }
    }

    private static final class ForwardingBufferSource extends MultiBufferSource.BufferSource {
        MultiBufferSource destination;
        ForwardingBufferSource() { super(new ByteBufferBuilder(1536), new LinkedHashMap<>()); }
        @Override public VertexConsumer getBuffer(RenderType type) {
            if (destination == null) throw new IllegalStateException("Blueprint bake outside its native rendering scope");
            return destination.getBuffer(type);
        }
    }
}
