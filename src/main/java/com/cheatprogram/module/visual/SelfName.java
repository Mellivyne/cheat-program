package com.cheatprogram.module.visual;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Local overhead name, anchored to the interpolated player rather than to a screen corner. */
public final class SelfName extends Module {
    private final TextSetting customName=add(new TextSetting("customName","",512));
    private final NumberSetting size=add(new NumberSetting("size",100,30,250,5,"%"));
    private final NumberSetting height=add(new NumberSetting("height",.32,0,2,.05));
    private final NumberSetting range=add(new NumberSetting("range",64,4,128,4));
    private final BoolSetting background=add(new BoolSetting("background",true));
    private final BoolSetting throughWalls=add(new BoolSetting("throughWalls",false));
    private final BoolSetting firstPerson=add(new BoolSetting("firstPerson",false));
    private HudWorldPanel.Projection projection;
    private Object world;
    public SelfName(){super("selfname",Category.VISUAL);}
    @Override public void onDisable(){projection=null;world=null;}
    @Override public void onRender3D(Render3D renderer){
        projection=null;world=mc.level;
        if(!inGame()||mc.player.isSpectator()||!mc.player.isAlive()||renderer.camera.distanceToSqr(mc.player.position())<.3
                ||!firstPerson.on()&&mc.options.getCameraType().isFirstPerson()&&!Freecam.isActive())return;
        Vec3 anchor=mc.player.getPosition(renderer.partialTick).add(0,mc.player.getBbHeight()+height.val(),0);
        if(!throughWalls.on()){
            var hit=mc.level.clip(new ClipContext(renderer.camera,anchor,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()!=HitResult.Type.MISS&&hit.getLocation().distanceToSqr(renderer.camera)+.01<anchor.distanceToSqr(renderer.camera))return;
        }
        projection=HudWorldPanel.project(renderer,anchor,range.val());
    }
    @Override public void onRender2D(HudContext hud){
        if(!inGame()||world!=mc.level||projection==null||System.nanoTime()-projection.time()>250_000_000)return;
        String source=customName.get().isBlank()?mc.player.getDisplayName().getString():customName.get();
        String name=com.cheatprogram.util.UnicodeText.displayName(source,128);if(name.isBlank())return;
        float font=Math.min(11*projection.scale()*hud.c.scale*(float)size.fraction(),Math.max(6,(R2D.height()-12)/1.3f));
        name=hud.c.ellipsize(Fonts.Face.REGULAR,name,font/hud.c.scale,Math.max(1,R2D.width()-14)/hud.c.scale);
        float width=R2D.textWidth(Fonts.Face.REGULAR,name,font);
        float x=Math.max(7,Math.min(R2D.width()-width-7,projection.x()-width/2));
        float y=Math.max(font*.62f+4,Math.min(R2D.height()-font*.62f-4,projection.y()));
        if(background.on())R2D.rect(x-3,y-font*.62f,width+6,font*1.22f,0x60000000);
        R2D.textCentered(Fonts.Face.REGULAR,name,x,y,font,0xffffffff);
    }
}
