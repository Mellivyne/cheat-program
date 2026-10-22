package com.cheatprogram.module.visual;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.PaletteText;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Text-only mark; every glyph goes through the same global font path as the control panel. */
public final class Watermark extends Module {
    private final TextSetting text=add(new TextSetting("text","Cheat Program",256));
    private final ModeSetting style=add(new ModeSetting("style","watermark_flat","watermark_flat","watermark_3d"));
    private final NumberSetting size=add(new NumberSetting("size",18,6,96,1));
    private final NumberSetting x=add(new NumberSetting("x",2,0,100,.5,"%"));
    private final NumberSetting y=add(new NumberSetting("y",8,0,100,.5,"%"));
    private final BoolSetting bold=add(new BoolSetting("bold",true));
    private final ModeSetting palette=add(new ModeSetting("palette","palette_custom","palette_custom","palette_white","palette_rainbow","palette_trans"));
    private final BoolSetting colorAnimation=add(new BoolSetting("colorAnimation",true)).visibleWhen(this::colorsVisible);
    private final NumberSetting colorPeriod=add(new NumberSetting("colorPeriod",6,.5,60,.1," s")).visibleWhen(()->colorsVisible()&&colorAnimation.on());
    private final NumberSetting colorStrength=add(new NumberSetting("colorStrength",100,0,100,5,"%")).visibleWhen(()->colorsVisible()&&colorAnimation.on());
    private final ModeSetting colorDirection=add(new ModeSetting("colorDirection","palette_flow_left","palette_flow_left","palette_flow_right")).visibleWhen(()->colorsVisible()&&colorAnimation.on());
    private final NumberSetting colorPhase=add(new NumberSetting("colorPhase",0,0,100,1,"%")).visibleWhen(this::colorsVisible);
    private final NumberSetting red=add(new NumberSetting("red",255,0,255,1)).visibleWhen(()->palette.is("palette_custom"));
    private final NumberSetting green=add(new NumberSetting("green",255,0,255,1)).visibleWhen(()->palette.is("palette_custom"));
    private final NumberSetting blue=add(new NumberSetting("blue",255,0,255,1)).visibleWhen(()->palette.is("palette_custom"));
    private final NumberSetting opacity=add(new NumberSetting("opacity",100,10,100,5,"%"));
    private final NumberSetting pitch=add(new NumberSetting("pitch",15,-80,80,1,"°")).visibleWhen(()->style.is("watermark_3d"));
    private final NumberSetting yaw=add(new NumberSetting("yaw",-20,-80,80,1,"°")).visibleWhen(()->style.is("watermark_3d"));
    private final NumberSetting roll=add(new NumberSetting("roll",0,-180,180,1,"°")).visibleWhen(()->style.is("watermark_3d"));
    private final NumberSetting depth=add(new NumberSetting("depth",3,0,16,1)).visibleWhen(()->style.is("watermark_3d"));
    private long paletteStartedAt;
    public Watermark(){super("watermark",Category.VISUAL);}
    private boolean colorsVisible(){return palette.is("palette_rainbow")||palette.is("palette_trans");}
    @Override public void onDisable(){paletteStartedAt=0;}
    @Override public void onRender2D(HudContext hud){
        String caption=singleLine(text.get(),256);if(caption.isBlank())return;
        Fonts.Face face=bold.on()?Fonts.Face.BOLD:Fonts.Face.REGULAR;
        float font=Fonts.pixelSize(size.floatVal()*hud.c.scale),width=R2D.textWidth(face,caption,font),height=font*1.2f;
        float extrusion=style.is("watermark_3d")?depth.intVal()*hud.c.scale*.6f:0;
        Matrix4f rotation=new Matrix4f();
        if(style.is("watermark_3d"))rotation.rotateZ((float)Math.toRadians(roll.val()))
                .rotateX((float)Math.toRadians(pitch.val())).rotateY((float)Math.toRadians(yaw.val()));
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        Vector3f corner=new Vector3f();
        for(int i=0;i<4;i++){
            corner.set((i&1)==0?0:width+extrusion,(i&2)==0?0:height+extrusion,0);rotation.transformPosition(corner);
            minX=Math.min(minX,corner.x);minY=Math.min(minY,corner.y);maxX=Math.max(maxX,corner.x);maxY=Math.max(maxY,corner.y);
        }
        float margin=3,availableW=Math.max(1,R2D.width()-margin*2),availableH=Math.max(1,R2D.height()-margin*2);
        float boundW=Math.max(1,maxX-minX),boundH=Math.max(1,maxY-minY);
        float fit=Math.min(1,Math.min(availableW/boundW,availableH/boundH));
        float px=margin+(availableW-boundW*fit)*(float)x.fraction()-minX*fit;
        float py=margin+(availableH-boundH*fit)*(float)y.fraction()-minY*fit;
        int alpha=Math.round(255*(float)opacity.fraction()),rgb=palette.is("palette_white")?0xFFFFFF:red.intVal()<<16|green.intVal()<<8|blue.intVal();
        int side=(Math.round(alpha*.65f)<<24)|((((rgb>>16)&255)/3)<<16)|((((rgb>>8)&255)/3)<<8)|(rgb&255)/3;
        if(colorsVisible()&&colorAnimation.on()){
            if(paletteStartedAt==0)paletteStartedAt=System.nanoTime();
        }else paletteStartedAt=0;
        double seconds=paletteStartedAt==0?0:(System.nanoTime()-paletteStartedAt)/1_000_000_000.0;
        PaletteText.Style colors=new PaletteText.Style(palette.get(),PaletteText.phase(seconds,colorPeriod.val(),colorStrength.fraction(),colorDirection.is("palette_flow_right"),colorPhase.fraction()));
        Matrix4f transform=new Matrix4f().translate(px,py,0).scale(fit).mul(rotation);
        R2D.withPixelTransform(transform,()->{
            if(style.is("watermark_3d"))for(int layer=depth.intVal();layer>0;layer--){
                float offset=layer*hud.c.scale*.6f;
                if(colors.colored())PaletteText.drawPixels(face,caption,offset,font+offset,font,side,colors,offset,width,1f/3,0);
                else R2D.text(face,caption,offset,font+offset,font,side);
            }
            if(colors.colored())PaletteText.drawPixels(face,caption,0,font,font,alpha<<24|rgb,colors,0,width,1,0);
            else R2D.text(face,caption,0,font,font,alpha<<24|rgb);
        });
    }
    private static String singleLine(String value,int limit){
        int end=0;for(int i=0;i<limit&&end<value.length();i++)end+=Character.charCount(value.codePointAt(end));
        return value.substring(0,end).replaceAll("[\\x00-\\x1f\\x7f]"," ");
    }
}
