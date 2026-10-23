package com.cheatprogram.mixin;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.dev.DevTools;
import com.cheatprogram.module.visual.Esp;
import com.cheatprogram.util.ChatHistoryStore;
import com.cheatprogram.util.ContainerIndex;
import com.cheatprogram.ai.voice.SpeechVoices;
import com.cheatprogram.render.ControlPanelTextures;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @org.spongepowered.asm.mixin.Unique private boolean cheatprogram$stopped;

	@Inject(method = "tick", at = @At("HEAD"))
	private void cheatprogram$preTick(CallbackInfo ci) {
		ModuleManager.onTick();
		DevTools.tick();
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void cheatprogram$postTick(CallbackInfo ci) {
		ModuleManager.onPostTick();
	}

	@ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
	private boolean cheatprogram$glow(boolean original, Entity entity) {
		return original || Esp.glowing(entity);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void cheatprogram$save(CallbackInfo ci) {
        if(cheatprogram$stopped)return;cheatprogram$stopped=true;
		com.cheatprogram.ai.voice.AiVoiceControl.shutdown();
		com.cheatprogram.render.WorldControlPanel.shutdown();
		com.cheatprogram.compat.NativeCachedMesh.shutdown();
		com.cheatprogram.compat.gl.RenderSystem.shutdown();
		com.cheatprogram.compat.GuiBridge.shutdown();
		com.cheatprogram.module.visual.AppCast.shutdown();
		com.cheatprogram.module.visual.SpatialAudio.shutdown();
		com.cheatprogram.module.visual.FloatingSubtitles.shutdown();
		com.cheatprogram.render.WorldSubtitleRenderer.release();
		com.cheatprogram.render.WearModelRenderer.release();
		com.cheatprogram.render.VoiceLiquid.shutdown();
		com.cheatprogram.util.ParticleStudio.flush(true);
		ControlPanelTextures.release();
		ChatHistoryStore.flush();
		ContainerIndex.shutdown();
		SpeechVoices.shutdown();
		Config.saveAll();
	}
}
