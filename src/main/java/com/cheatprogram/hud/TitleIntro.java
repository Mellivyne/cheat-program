package com.cheatprogram.hud;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.HudEditorScreen;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.render.HandwrittenTitle;
import com.cheatprogram.util.MotionCurve;
import net.minecraft.client.Minecraft;

/** One handwritten title per world connection, independent of the existing island login card. */
public final class TitleIntro {
    private static final Minecraft MC=Minecraft.getInstance();
    private static Object connection;
    private static boolean played;
    private static float age=-1;
    private static long frame;
    private TitleIntro(){ }
    public static void tickLifecycle(){
        Object next=MC.getConnection();if(next!=connection){connection=next;played=false;age=-1;frame=0;}
        if(next==null)return;
        Hud hud=ModuleManager.get(Hud.class);
        if(hud==null||!hud.titleIntroEnabled()||MC.options.hideGui||MC.screen instanceof HudEditorScreen){played=true;age=-1;return;}
        if(MC.player==null||MC.level==null||MC.screen!=null||MC.getOverlay()!=null||MC.isPaused()||!MC.isWindowActive())return;
        if(!MC.player.isAlive()){age=-1;played=true;return;}
        if(!played){played=true;age=0;frame=0;}
    }
    public static void preview(){
        Hud hud=ModuleManager.get(Hud.class);if(MC.player==null||MC.level==null||hud==null||!hud.titleIntroEnabled())return;
        connection=MC.getConnection();played=true;age=0;frame=0;
    }
    public static void render(HudContext context){
        if(age<0)return;
        Hud hud=ModuleManager.get(Hud.class);if(hud==null||!hud.titleIntroEnabled()||context.editing){age=-1;return;}
        long now=System.nanoTime();float dt=frame==0?0:Math.min(.1f,(now-frame)/1_000_000_000f);frame=now;
        if(MC.screen!=null||MC.getOverlay()!=null||MC.isPaused()||!MC.isWindowActive())return;
        age+=Math.max(0,dt);
        float writing=hud.titleIntroWriteSeconds(),hold=hud.titleIntroHoldSeconds(),fade=hud.titleIntroFadeSeconds();
        if(age>=writing+hold+fade){age=-1;return;}
        float progress=MotionCurve.smootherStep(age/writing),opacity=1-MotionCurve.smootherStep((age-writing-hold)/fade);
        float width=Math.min(Math.min(context.width*.82f,context.height*3.8f)*hud.titleIntroSize(),context.width*.9f);
        float halfHeight=HandwrittenTitle.halfHeight(width,hud.titleIntroThickness(),hud.titleIntroGlow());
        if(halfHeight>context.height*.46f){width*=context.height*.46f/halfHeight;halfHeight=context.height*.46f;}
        float centerY=Math.max(halfHeight+4,Math.min(context.height-halfHeight-4,context.height*hud.titleIntroY()));
        HandwrittenTitle.draw(context.c.px(context.width/2),context.c.py(centerY),width*context.c.scale,
                progress,opacity,hud.titleIntroPalette(),age*1.1f,hud.titleIntroThickness(),hud.titleIntroGlow());
    }
}
