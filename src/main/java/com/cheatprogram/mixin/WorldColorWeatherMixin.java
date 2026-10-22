package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.WorldColors;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(Level.class)
public abstract class WorldColorWeatherMixin {
    @ModifyReturnValue(method="getRainLevel",at=@At("RETURN"))
    private float cheatprogram$rain(float original){WorldColors c=WorldColors.active();return c==null||(Object)this!=Minecraft.getInstance().level?original:c.weather(original,false);}
    @ModifyReturnValue(method="getThunderLevel",at=@At("RETURN"))
    private float cheatprogram$thunder(float original){WorldColors c=WorldColors.active();return c==null||(Object)this!=Minecraft.getInstance().level?original:c.weather(original,true);}
}
