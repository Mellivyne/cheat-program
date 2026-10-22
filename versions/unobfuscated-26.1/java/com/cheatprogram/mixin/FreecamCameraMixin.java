package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.module.legit.Zoom;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Camera.class)
public abstract class FreecamCameraMixin {
    @Shadow private boolean detached;
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yaw,float pitch);
    // Native update() builds culling/projection from this position afterwards.
    @Inject(method="alignWithEntity",at=@At("TAIL"))
    private void cheatprogram$freeCamera(float partial,CallbackInfo ci){
        if(!Freecam.isActive())return;
        detached=true;setPosition(Freecam.cameraPosition(partial));setRotation(Freecam.cameraYaw(partial),Freecam.cameraPitch(partial));
    }
    @ModifyReturnValue(method="calculateFov",at=@At("RETURN"))
    private float cheatprogram$zoom(float original,float partial){return Zoom.apply(original);}
}
