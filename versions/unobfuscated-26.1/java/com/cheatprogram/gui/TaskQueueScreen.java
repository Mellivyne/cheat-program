package com.cheatprogram.gui;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.utility.TaskQueue;
import com.cheatprogram.render.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import java.util.*;
public final class TaskQueueScreen extends GuiBase {
    private final Screen parent;private final TaskQueue module;private final Scroller scroll=new Scroller();
    public TaskQueueScreen(Screen parent,TaskQueue module){super(Lang.t("module.taskqueue"));this.parent=parent;this.module=module;}
    private void add(String step){if(!module.running())module.plan.set(module.plan.get().isBlank()?step:module.plan.get()+", "+step);}
    private void remove(int index){if(module.running())return;var steps=new ArrayList<>(steps());if(index<steps.size()){steps.remove(index);module.plan.set(String.join(", ",steps));}}
    private List<String> steps(){return Arrays.stream(module.plan.get().split("[,;，；\\s]+")).filter(s->!s.isBlank()).toList();}
    @Override public void render(GuiGraphicsExtractor g,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(g,partial)){endFrame();return;}R2D.begin(g);
        try{
            float height=layoutPanel(680,560);
            if(height<300){height=300;float scale=Math.min(c.scale,(R2D.height()-32)/height);c.set(scale,Math.round((R2D.width()-680*scale)/2),16);updateMouse();}
            drawPanelSurface(g,680,height,24);
            c.textC(BOLD,Lang.t("module.taskqueue"),24,30,16,Theme.TEXT);
            c.textC(REG,c.ellipsize(REG,module.status(),10,630),24,56,10,Theme.TEXT_2);
            String[] kinds={"equip","clean","loot","refill","build","wait"};
            for(int i=0;i<kinds.length;i++){String step=kinds[i];button("queue.add."+step,Lang.t("queue.step."+step),Icon.PLUS,24+i*106,78,100,28,false,()->add(step));}
            float top=121,bottom=height-118;var steps=steps();scroll.max=Math.max(0,steps.size()*42-(bottom-top));scroll.update(dt);
            c.clip(20,top,640,bottom-top);pushHitClip(20,top,640,bottom-top);hit(20,top,640,bottom-top).scroll(scroll::scroll);
            try{for(int i=0;i<steps.size();i++){
                final int index=i;float y=top+i*42-scroll.get();if(y+36<top||y>bottom)continue;
                c.lens(24,y,630,36,12,Theme.lift(.05f),Theme.chipGlass(c.scale));
                c.textC(MED,(i+1)+". "+Lang.t("queue.step."+steps.get(i)),38,y+18,11,Theme.TEXT);
                button("queue.remove."+i,Lang.t("ui.delete"),Icon.CLOSE,550,y+5,90,26,false,()->remove(index));
            }}finally{popHitClip();c.unclip();}
            panelScrollBar("queue.scroll",scroll,660,top,bottom-top);
            c.textC(REG,Lang.t("ui.queue.hint"),24,height-94,8,Theme.TEXT_3);
            button("queue.back",Lang.t("ui.back"),Icon.BACK,24,height-53,100,28,false,this::onClose);
            button("queue.start",Lang.t("ui.queue.start"),Icon.POWER,138,height-53,160,28,true,module::start);
            button("queue.pause",Lang.t("ui.queue.pause"),Icon.REFRESH,310,height-53,160,28,false,module::togglePause);
            button("queue.stop",Lang.t("ui.queue.stop"),Icon.CLOSE,482,height-53,170,28,false,module::stop);
        }finally{R2D.end();endFrame();}
    }
    @Override public boolean mouseClicked(double x,double y,int button){dispatchClick(x,y,button);return true;}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){return dispatchScroll(x,y,v);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return false;}
    @Override public void onClose(){mc.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
