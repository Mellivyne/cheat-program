package com.cheatprogram.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.world.entity.Entity;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface RearMirrorLevelRendererAccessor {
	@Accessor("cullingFrustum") Frustum cheatprogram$mirrorFrustum();
	@Accessor("cullingFrustum") void cheatprogram$mirrorFrustum(Frustum frustum);
	@Accessor("entityOutlineTarget") RenderTarget cheatprogram$mirrorOutlineTarget();
	@Accessor("entityOutlineTarget") void cheatprogram$mirrorOutlineTarget(RenderTarget target);
	@Accessor("targets") LevelTargetBundle cheatprogram$mirrorTargets();
	@Accessor("visibleEntities") List<Entity> cheatprogram$mirrorVisibleEntities();
}
