package com.cheatprogram.hud;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.ai.voice.AiVoiceControl;
import com.cheatprogram.ai.voice.VoiceHudText;
import com.cheatprogram.core.Lang;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.render.*;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.Spring;
import net.minecraft.client.Minecraft;
import java.util.List;

/** The saved island anchor becomes one flattened glass volume; only actual results contain text. */
public final class AiVoiceHud {
    private static final Anim presence=new Anim(0);
    private static final VoiceLiquidMotion motion=new VoiceLiquidMotion(),previewMotion=new VoiceLiquidMotion();
    private static final Spring fallbackWidth=new Spring(72),fallbackHeight=new Spring(64);
    private static long frame;
    private static double previewAt;
    private static Object world;
    private static boolean islandRendered,showResult;
    private static float delta,resultWidth=200,resultHeight=48;
    private static String result="",layoutText="";
    private static float layoutAvailable=-1,layoutHeight=-1;
    private static List<String> lines=List.of();
    private AiVoiceHud() { }

    public static void beginFrame(HudContext hud) {
        islandRendered=false;long now=System.nanoTime();
        delta=frame==0?1f/60:Math.max(0,Math.min(.1f,(now-frame)/1_000_000_000f));frame=now;
        Minecraft mc=Minecraft.getInstance();
        boolean allowed=AiVoiceControl.available()&&!com.cheatprogram.core.Config.restartRequired()&&AiVoiceControl.config().showHud&&!hud.editing
                &&(mc.screen==null||mc.screen instanceof com.cheatprogram.gui.WorldPanelInputScreen||mc.screen instanceof com.cheatprogram.gui.AppCastInputScreen);
        if(world!=mc.level){world=mc.level;presence.snap(0);motion.reset();result="";lines=List.of();layoutText="";layoutAvailable=-1;}
        if(!allowed){presence.snap(0);motion.update(delta,0,false,false,false);return;}
        var voice=AiVoiceControl.state();var reply=AiController.voiceReply();long millis=System.currentTimeMillis();
        boolean listening=voice.stage()==AiVoiceControl.Stage.LISTENING;
        boolean working=voice.stage()==AiVoiceControl.Stage.TRANSCRIBING||AiController.isVoiceRequest();
        String next="";
        if(!listening&&!working){
            if(!reply.text().isBlank()&&millis-reply.changedAt()<10_000)next=reply.text();
            else if(voice.stage()==AiVoiceControl.Stage.ERROR&&millis-voice.changedAt()<6500)next=Lang.t(voice.statusKey());
            else if(voice.stage()==AiVoiceControl.Stage.READY&&!voice.statusKey().equals("ui.voiceInput.sent")&&millis-voice.changedAt()<6500)next=voice.transcript();
        }
        result=VoiceHudText.display(next);showResult=!result.isBlank();
        presence.to(listening||working||showResult?1:0,delta,listening||working||showResult?10:9);
        motion.update(delta,voice.level(),listening,working,showResult);
        if(showResult)layout(hud);
    }
    private static void layout(HudContext hud){
        float available=Math.max(24,Math.min(336,Math.min(hud.width-12,hud.width*.72f)));
        if(result.equals(layoutText)&&layoutAvailable==available&&layoutHeight==hud.height)return;
        layoutText=result;layoutAvailable=available;layoutHeight=hud.height;
        float measured=hud.c.textWidth(Fonts.Face.MEDIUM,result.replace('\n',' '),11);
        resultWidth=Math.min(available,Math.max(128,Math.min(336,measured+34)));
        int count=Math.max(1,Math.min(VoiceHudText.MAX_LINES,(int)((hud.height*.26f-24)/12.5f)));
        lines=VoiceHudText.wrap(result,Math.max(1,resultWidth-32),count,t->hud.c.textWidth(Fonts.Face.MEDIUM,t,11));
        resultHeight=Math.min(Math.max(30,hud.height-8),Math.max(40,24+lines.size()*12.5f));
    }
    static float presence(){return presence.value;}
    static void islandRendered(){islandRendered=true;}
    static float width(HudContext hud){return Math.max(24,Math.min(hud.width-12,showResult?resultWidth:72*motion.stretch()));}
    static float height(){return showResult?resultHeight:64/(float)Math.sqrt(motion.stretch());}
    static float damping(){return showResult?17:21;}
    static R2D.GlassStyle glassStyle(float scale,R2D.GlassStyle ignored){return new R2D.GlassStyle(19*scale,13*scale,0,1.2f*scale,0,1);}

    static void draw(HudContext hud,float x,float y,float width,float height){
        if(presence.value<.002f)return;
        R2D.pushAlpha(presence.value);
        try{
            Canvas c=hud.c;c.shadow(x,y+3,width,height,height/2,10,Anim.black(.22f));
            VoiceLiquid.draw(c,x,y,width,height,motion.phase(),motion.amplitude(),motion.result());
            // No captions, waveform glyphs, transcripts or status labels while listening/thinking.
            float textAlpha=showResult?Anim.clamp01((motion.result()-.55f)/.45f)*Anim.clamp01((width-72)/Math.max(1,resultWidth-72)):0;
            if(textAlpha>.002f){
                R2D.pushAlpha(textAlpha);c.clip(x+12,y+8,Math.max(1,width-24),Math.max(1,height-16));
                try{float ty=y+(height-lines.size()*12.5f)/2+6.25f;for(String line:lines){c.textC(Fonts.Face.MEDIUM,line,x+16,ty,11,0xFFF8F8F8);ty+=12.5f;}}
                finally{c.unclip();R2D.popAlpha();}
            }
        }finally{R2D.popAlpha();}
    }
    public static void render(HudContext hud){
        if(islandRendered||presence.value<.002f)return;
        fallbackWidth.target=width(hud);fallbackHeight.target=height();
        float w=Math.max(24,Math.min(hud.width-12,fallbackWidth.update(delta,230,damping())));
        float h=Math.max(22,Math.min(hud.height-8,fallbackHeight.update(delta,230,damping())));
        float x=HudLayout.x("island",w,hud.width),y=HudLayout.y("island",h,hud.height);
        // Ensure this frame has a backdrop; the shader then owns the sole visible voice surface.
        R2D.pushAlpha(0);try{hud.liquidPanel(x,y,w,h,h/2,glassStyle(hud.c.scale,Theme.chipGlass(hud.c.scale)),0);}finally{R2D.popAlpha();}
        draw(hud,x,y,w,h);HudLayout.report("island",x,y,w,h);
    }
    /** Existing settings/AI-page previews use the same monochrome material and finite phase integral. */
    public static void orb(Canvas c,float cx,float cy,float radius,String phase,float audio,double seconds){
        double dt=previewAt==0?1.0/60:Math.max(0,Math.min(.1,seconds-previewAt));previewAt=seconds;
        boolean listen=phase.equals("listening");previewMotion.update(dt,audio,listen,!listen,false);
        float stretch=previewMotion.stretch();VoiceLiquid.draw(c,cx-radius*stretch,cy-radius*.89f,radius*2*stretch,radius*1.78f,
                previewMotion.phase(),previewMotion.amplitude(),0);
    }
}
