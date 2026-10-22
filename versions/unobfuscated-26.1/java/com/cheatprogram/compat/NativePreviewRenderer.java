package com.cheatprogram.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.cheatprogram.render.RenderStateSnapshot;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ElderGuardianParticleGroup;
import net.minecraft.client.particle.ItemPickupParticleGroup;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ParticleFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

/** Native queued model/particle rendering into a private target, independent of the live frame. */
public final class NativePreviewRenderer implements AutoCloseable {
    private final Minecraft mc = Minecraft.getInstance();
    private final ByteBufferBuilder storage = new ByteBufferBuilder(256 * 1024);
    private final ByteBufferBuilder crumbleStorage = new ByteBufferBuilder(64 * 1024);
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(storage);
    private final MultiBufferSource.BufferSource crumbling = MultiBufferSource.immediate(crumbleStorage);
    // OutlineBufferSource has no close API. Reuse this render-thread-owned source
    // rather than leak its native allocator on every preview frame.
    private static final OutlineBufferSource outlines = new OutlineBufferSource();
    private final SubmitNodeStorage submits = new SubmitNodeStorage();
    private final FeatureRenderDispatcher features = new FeatureRenderDispatcher(submits, mc.getModelManager(), buffers,
            mc.getAtlasManager(), outlines, crumbling, mc.font, mc.gameRenderer.getGameRenderState());
    private final QuadParticleRenderState quads = new QuadParticleRenderState();
    private final ParticleFeatureRenderer.ParticleBufferCache quadBuffers = new ParticleFeatureRenderer.ParticleBufferCache();
    private int quadCount;

    public void avatar(AvatarRenderer renderer, AvatarRenderState state, PoseStack pose, int light) {
        state.lightCoords = light;
        CameraRenderState camera = new CameraRenderState();
        camera.initialized = true;
        renderer.submit(state, pose, submits, camera);
    }

    public void particle(Particle particle, Camera camera, float partial) {
        // Some providers have a real simulation but intentionally no visual
        // geometry (e.g. emitters). Do not accidentally render a quad subclass
        // whose provider deliberately assigned it to the invisible group.
        if (particle.getGroup() == ParticleRenderType.NO_RENDER) return;
        if (particle instanceof SingleQuadParticle single) {
            // Uses the provider's actual frame, rotation, sprite atlas, light and material layer.
            single.extract(quads, camera, partial);
            quadCount++;
        } else if (particle.getGroup() == ParticleRenderType.ITEM_PICKUP) {
            var group = new ItemPickupParticleGroup(mc.particleEngine);
            group.add(particle);
            group.extractRenderState(null, camera, partial).submit(submits, cameraState(camera));
        } else if (particle.getGroup() == ParticleRenderType.ELDER_GUARDIANS) {
            var group = new ElderGuardianParticleGroup(mc.particleEngine);
            group.add(particle);
            group.extractRenderState(null, camera, partial).submit(submits, cameraState(camera));
        } else if (particle.getGroup() != ParticleRenderType.NO_RENDER) {
            throw new IllegalArgumentException("Unsupported native particle group: " + particle.getGroup());
        }
    }

    private static CameraRenderState cameraState(Camera camera) {
        CameraRenderState state = new CameraRenderState();
        state.initialized = true;
        state.pos = camera.position();
        state.orientation.set(camera.rotation());
        return state;
    }

    public void draw(RenderTarget target) {
        RenderSystem.assertOnRenderThread();
        // The surrounding editor also draws with custom GL shaders. Native
        // submission must restore both that state and vanilla's texture views;
        // restoring only raw GL ids leaves later native sampler bindings stale.
        RenderStateSnapshot saved = new RenderStateSnapshot();
        var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
        boolean wasScissored = scissor.enabled();
        int scissorX = scissor.x(), scissorY = scissor.y(), scissorWidth = scissor.width(), scissorHeight = scissor.height();
        var previousColor = RenderSystem.outputColorTextureOverride;
        var previousDepth = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = target.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
        try {
            // The editor's outer clip belongs to the final compositing quad,
            // not to the coordinates of this smaller private framebuffer.
            RenderSystem.disableScissorForRenderTypeDraws();
            com.cheatprogram.compat.gl.RenderSystem.invalidateNativeState();
            features.renderAllFeatures();
            buffers.endBatch();
            crumbling.endBatch();
            outlines.endOutlineBatch();
            if(quadCount!=0)for(boolean translucent:new boolean[]{false,true}){
                var prepared=quads.prepare(quadBuffers,translucent);
                if(prepared==null)continue;
                try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        ()->"Cheat Program private native particles",target.getColorTextureView(),OptionalInt.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
                    pass.setUniform("Projection",RenderSystem.getProjectionMatrixBuffer());
                    pass.setUniform("Fog",RenderSystem.getShaderFog());
                    pass.bindTexture("Sampler2",mc.gameRenderer.lightmap(),RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR));
                    quads.render(prepared,quadBuffers,pass,mc.getTextureManager());
                }
            }
        } finally {
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
            if (wasScissored) RenderSystem.enableScissorForRenderTypeDraws(scissorX, scissorY, scissorWidth, scissorHeight);
            else RenderSystem.disableScissorForRenderTypeDraws();
            quads.clear();
            quadCount = 0;
            submits.clear();
            saved.restore();
        }
    }

    @Override public void close() {
        features.endFrame();
        features.close();
        quadBuffers.close();
        storage.close();
        crumbleStorage.close();
    }
}
