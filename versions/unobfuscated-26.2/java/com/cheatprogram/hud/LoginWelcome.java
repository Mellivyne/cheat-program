package com.cheatprogram.hud;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.HudEditorScreen;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.module.visual.Personal;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.PersonalAvatar;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.Minecraft;

/** One local welcome per play connection. DynamicIsland remains the sole animated outer shell. */
public final class LoginWelcome {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final float NARROW_END=.26f,EXPAND_END=.86f;
    // Adjust the installed version's visible holds, leaving the shape/flip transitions at their original pace.
    private static final float VERIFY_HOLD=1.24f*1.20f*.85f,SUCCESS_HOLD=1.30f*1.20f+.30f;
    private static final float LOAD_END=EXPAND_END+VERIFY_HOLD,FLIP_END=LOAD_END+.34f,
            FOLD_END=FLIP_END+.41f,SUCCESS_END=FOLD_END+SUCCESS_HOLD,END=SUCCESS_END+.60f;
    private static final float CARD_W=176,CARD_H=142,SUCCESS_W=250,SUCCESS_H=28;
    private static Object connection;
    private static boolean played;
    private static float time=-1;
    private LoginWelcome(){}

    /** Called globally, including menus. Dimension changes and respawn keep the same connection identity. */
    public static void tickLifecycle(){
        Object current=MC.getConnection();
        if(current==null){connection=null;played=false;time=-1;return;}
        if(current!=connection){connection=current;played=false;time=-1;}
        Personal personal=ModuleManager.get(Personal.class);
        if(personal!=null&&!personal.welcomeEnabled()){played=true;time=-1;return;}
        // Disabling or hiding the HUD consumes this connection's animation rather than queuing a surprise replay.
        if(played){if(active()&&!hudAvailable())time=-1;return;}
        if(MC.player==null||MC.level==null||MC.gui.overlay()!=null||MC.gui.screen()!=null||MC.isPaused()
                ||!MC.isWindowActive()||!MC.player.isAlive())return;
        if(personal!=null)PersonalAvatar.warm(personal.avatarPath());
        played=true;
        if(hudAvailable())time=0;
    }
    private static boolean hudAvailable(){
        if(MC.gui.hud.isHidden()||MC.gui.screen() instanceof HudEditorScreen)return false;
        Hud hud=ModuleManager.get(Hud.class);
        return hud!=null&&hud.isEnabled()&&hud.settings().stream().anyMatch(setting->setting.id.equals("island")&&Boolean.TRUE.equals(setting.get()));
    }
    /** Pass false while editing the HUD. A suppressed run is consumed, never replayed on leaving the editor. */
    public static void update(float dt,boolean available){
        if(!active())return;
        Personal personal=ModuleManager.get(Personal.class);
        if(!available||connection!=MC.getConnection()||personal!=null&&!personal.welcomeEnabled()){time=-1;return;}
        if(MC.player==null||MC.level==null||MC.gui.overlay()!=null||MC.isPaused()||!MC.isWindowActive())return;
        time+=Math.max(0,Math.min(.1f,Float.isFinite(dt)?dt:0));
        if(time>=END)time=-1;
    }
    public static boolean active(){return time>=0;}
    public static float expandedWidth(float normalWidth){
        if(!active())return normalWidth;
        if(time<NARROW_END)return mix(normalWidth,104,span(0,NARROW_END));
        if(time<EXPAND_END)return mix(104,CARD_W,span(NARROW_END,EXPAND_END));
        if(time<FLIP_END)return CARD_W;
        if(time<FOLD_END)return mix(CARD_W,SUCCESS_W,span(FLIP_END,FOLD_END));
        if(time<SUCCESS_END)return SUCCESS_W;
        return mix(SUCCESS_W,normalWidth,span(SUCCESS_END,END));
    }
    public static float expandedHeight(float normalHeight){
        if(!active())return normalHeight;
        if(time<NARROW_END)return mix(normalHeight,26,span(0,NARROW_END));
        if(time<EXPAND_END)return mix(26,CARD_H,span(NARROW_END,EXPAND_END));
        if(time<FLIP_END)return CARD_H;
        if(time<FOLD_END)return mix(CARD_H,SUCCESS_H,span(FLIP_END,FOLD_END));
        if(time<SUCCESS_END)return SUCCESS_H;
        return mix(SUCCESS_H,normalHeight,span(SUCCESS_END,END));
    }
    public static float normalVisibility(){
        if(!active())return 1;
        if(time<NARROW_END)return 1-Anim.easeOutCubic(time/.18f);
        if(time<SUCCESS_END)return 0;
        return Anim.easeOutCubic((time-SUCCESS_END-.17f)/(END-SUCCESS_END-.17f));
    }
    /** Clamp this to the live spring height / 2 when drawing the sole outer surface. */
    public static float radius(float normalRadius){
        if(!active())return normalRadius;
        if(time<EXPAND_END)return mix(normalRadius,28,span(NARROW_END,EXPAND_END));
        if(time<FLIP_END)return 28;
        if(time<FOLD_END)return mix(28,14,span(FLIP_END,FOLD_END));
        if(time<SUCCESS_END)return 14;
        return mix(14,normalRadius,span(SUCCESS_END,END));
    }
    public static R2D.GlassStyle glassStyle(float scale){
        R2D.GlassStyle base=Theme.windowGlass(scale);
        R2D.GlassStyle idle=Theme.chipGlass(scale);float t=1-normalVisibility();
        return new R2D.GlassStyle(Anim.lerp(idle.refraction(),Math.max(22*scale,base.refraction()*1.35f),t),
                Anim.lerp(idle.bevel(),Math.max(18*scale,base.bevel()),t),0,
                Anim.lerp(idle.feather(),Math.max(2*scale,base.feather()*.5f),t),Anim.lerp(idle.haze(),Math.min(.025f,base.haze()),t),1);
    }
    public static float lift(){return Theme.lift(Anim.lerp(.07f,.105f,1-normalVisibility()));}

    /** Contents only: no panel, shadow or backdrop capture is created here. */
    public static void draw(HudContext context,float x,float y,float width,float height){
        if(!active()||context.editing||width<20||height<10)return;
        Canvas c=context.c;
        float outgoing=time<SUCCESS_END?1:1-Anim.easeOutCubic((time-SUCCESS_END)/.34f);
        if(outgoing<.001f)return;
        c.clip(x+1,y+1,Math.max(1,width-2),Math.max(1,height-2));
        R2D.pushAlpha(outgoing);
        try{
            if(time>=NARROW_END&&time<FLIP_END)drawCard(c,x,y,width,height);
            else if(time>=FLIP_END)drawSuccess(c,x,y,width,height);
        }finally{R2D.popAlpha();c.unclip();}
    }
    private static void drawCard(Canvas c,float x,float y,float width,float height){
        float appear=Anim.easeOutCubic((time-NARROW_END-.10f)/.28f);
        float imageWidth=Math.min(112,Math.max(0,width-28)),imageHeight=Math.min(82,Math.max(0,height-44));
        if(imageWidth>2&&imageHeight>2){
            float angle=time<LOAD_END?0:90*span(LOAD_END,FLIP_END);
            float imageX=x+(width-imageWidth)/2,imageY=y+12;
            float visibleWidth=Math.max(1.3f,imageWidth*(float)Math.cos(Math.toRadians(angle)));
            c.shadow(x+(width-visibleWidth)/2,imageY+2,visibleWidth,imageHeight,Math.min(8,visibleWidth/2),5,Anim.black(.17f*appear));
            PersonalAvatar.drawFlipped(c,imageX,imageY,imageWidth,imageHeight,8,appear,angle);
            if(angle>82)c.rrect(x+width/2-.65f,imageY+1,1.3f,Math.max(1,imageHeight-2),.6f,Anim.white(.5f*appear));
        }
        float loadingAlpha=appear*(1-Anim.easeOutCubic((time-LOAD_END)/.25f));
        if(loadingAlpha>.01f&&height>68)spinner(c,x+width/2,y+height-19,6,loadingAlpha);
    }
    private static void drawSuccess(Canvas c,float x,float y,float width,float height){
        float fold=span(FLIP_END,FOLD_END);
        float imageWidth=mix(112,22,fold),imageHeight=Math.min(Math.max(2,height-9),mix(82,18,fold));
        float imageX=Anim.lerp(x+(width-imageWidth)/2,x+11,fold);
        float imageY=Anim.lerp(y+12,y+(height-imageHeight)/2,fold);
        PersonalAvatar.drawFlipped(c,imageX,imageY,imageWidth,imageHeight,mix(8,4,fold),1,90*(1-fold));
        float success=Anim.easeOutCubic((time-FLIP_END-.16f)/.32f);
        if(success>.001f){
            R2D.pushAlpha(success);
            try{
                String label=Lang.t("ui.welcome.success");
                float textRight=x+width-34,cy=y+height/2;
                c.textRight(Fonts.Face.MEDIUM,label,textRight,cy,10.5f,Theme.TEXT);
                c.shadow(x+width-24,cy-6,12,12,6,4,Anim.white(.11f));
                c.icon(Icon.CHECK,x+width-18,cy,13,Theme.TEXT);
            }finally{R2D.popAlpha();}
        }
    }
    private static void spinner(Canvas c,float x,float y,float radius,float alpha){
        double rotation=time*Math.PI*2*1.12;
        int count=30;
        for(int index=0;index<count;index++){
            float fraction=index/(float)(count-1);
            double angle=rotation+fraction*Math.PI*1.5;
            c.circle(x+(float)Math.cos(angle)*radius,y+(float)Math.sin(angle)*radius,.78f,
                    Anim.white(alpha*(.10f+.90f*fraction)));
        }
    }
    private static float span(float start,float end){return Anim.easeOutCubic((time-start)/(end-start));}
    private static float mix(float first,float second,float fraction){return Anim.lerp(first,second,Anim.clamp01(fraction));}
}
