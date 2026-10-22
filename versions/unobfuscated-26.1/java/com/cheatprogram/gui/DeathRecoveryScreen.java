package com.cheatprogram.gui;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.utility.DeathRecovery;
import com.cheatprogram.render.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

public final class DeathRecoveryScreen extends GuiBase {
    private final Screen parent;private final DeathRecovery module;private final Scroller scroll=new Scroller();
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    public DeathRecoveryScreen(Screen parent,DeathRecovery module){super(Lang.t("module.deathrecovery"));this.parent=parent;this.module=module;}
    @Override public void render(GuiGraphicsExtractor g,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(g,partial)){endFrame();return;}R2D.begin(g);
        try{
            float height=layoutPanel(720,580);
            if(height<280){height=280;float scale=Math.min(c.scale,(R2D.height()-32)/height);c.set(scale,Math.round((R2D.width()-720*scale)/2),16);updateMouse();}
            drawPanelSurface(g,720,height,24);
            c.textC(BOLD,Lang.t("module.deathrecovery"),24,30,16,Theme.TEXT);
            c.textC(REG,Lang.t("ui.recovery.scope"),24,54,8.5f,Theme.TEXT_3);
            float top=78,bottom=height-75;var entries=module.history();
            scroll.max=Math.max(0,entries.size()*118-(bottom-top));scroll.update(dt);
            c.clip(20,top,680,bottom-top);pushHitClip(20,top,680,bottom-top);hit(20,top,680,bottom-top).scroll(scroll::scroll);
            try{
                if(entries.isEmpty())c.textMid(REG,Lang.t("ui.recovery.empty"),360,top+70,11,Theme.TEXT_3);
                for(int i=0;i<entries.size();i++){
                    var entry=entries.get(i);float y=top+i*118-scroll.get();if(y+110<top||y>bottom)continue;
                    c.lens(24,y,672,108,14,Theme.lift(entry==module.selected()?.12f:.05f),Theme.chipGlass(c.scale));
                    c.textC(MED,"X "+entry.x()+"  Y "+entry.y()+"  Z "+entry.z(),38,y+20,12,Theme.TEXT);
                    c.textC(REG,DATE.format(Instant.ofEpochMilli(entry.time()))+" · "+entry.dimension(),38,y+40,9,Theme.TEXT_3);
                    String items=entry.items().stream().map(item->item.name()+" ×"+item.count()).collect(Collectors.joining(" · "));
                    c.textC(REG,c.ellipsize(REG,items,9.5f,635),38,y+65,9.5f,Theme.TEXT_2);
                    button("recover.select."+i,Lang.t("ui.containersearch.locate"),Icon.TARGET,482,y+10,105,25,false,()->module.select(entry));
                    button("recover.delete."+i,Lang.t("ui.delete"),Icon.CLOSE,595,y+10,83,25,false,()->module.delete(entry));
                    c.textC(REG,c.ellipsize(REG,entry.context(),8,635),38,y+88,8,Theme.TEXT_3);
                }
            }finally{popHitClip();c.unclip();}
            panelScrollBar("recovery.scroll",scroll,701,top,bottom-top);
            button("recovery.back",Lang.t("ui.back"),Icon.BACK,24,height-47,110,27,false,this::onClose);
            c.textC(REG,Lang.t(module.routeStatus()),155,height-33,9.5f,Theme.TEXT_2);
        }finally{R2D.end();endFrame();}
    }
    @Override public boolean mouseClicked(double x,double y,int button){dispatchClick(x,y,button);return true;}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){return dispatchScroll(x,y,v);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return false;}
    @Override public void onClose(){mc.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
