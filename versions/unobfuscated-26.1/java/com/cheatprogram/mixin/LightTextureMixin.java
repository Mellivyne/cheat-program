package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.Fullbright;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** The native extraction state owns night vision from 26.1, rather than an old GPU float ordinal. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightTextureMixin {
    @Shadow private boolean needsUpdate;
    @Unique private boolean cheatprogram$wasFullbright;
    @Inject(method="extract",at=@At("HEAD"))
    private void cheatprogram$refreshToggle(LightmapRenderState state,float partial,CallbackInfo ci){
        boolean active=Fullbright.active();
        if(active!=cheatprogram$wasFullbright){needsUpdate=true;cheatprogram$wasFullbright=active;}
    }
    @Inject(method="extract",at=@At("TAIL"))
    private void cheatprogram$fullbright(LightmapRenderState state,float partial,CallbackInfo ci){
        if(Fullbright.active()&&state.needsUpdate)state.nightVisionEffectIntensity=1;
    }
}
