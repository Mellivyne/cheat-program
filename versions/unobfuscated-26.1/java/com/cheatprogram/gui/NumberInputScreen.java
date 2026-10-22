package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** A draft is separate from the live setting until explicit confirmation. */
public final class NumberInputScreen extends GuiBase {
    private final Screen parent;private final NumberSetting setting;
    private final TextField input=new TextField(128);
    private String error="";private boolean finished;
    public NumberInputScreen(Screen parent,NumberSetting setting){
        super(Lang.t("ui.number.title"));this.parent=parent;this.setting=setting;
        input.setText(NumberInputValues.format(setting.val()));input.onEnter=this::apply;input.onChange=value->error="";
    }
    @Override protected void init(){focused=input;input.allSelected=true;}
    @Override public void render(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{
            float h=layoutPanel(560,300);drawPanelSurface(graphics,560,h,20);
            c.textC(BOLD,Lang.t("ui.number.title"),24,28,15,Theme.TEXT);
            iconButton("number.close",Icon.CLOSE,531,28,13,Theme.TEXT_2,this::onClose);
            c.textC(MED,c.ellipsize(MED,setting.name(),12,512),24,61,12,Theme.TEXT_2);
            textField(input,NumberInputValues.format(setting.val()),null,24,82,512,38);
            String unit=Lang.unit(setting.unit);
            c.textC(REG,c.ellipsize(REG,Lang.t("ui.number.range")+"  "+NumberInputValues.format(setting.min)+" … "+NumberInputValues.format(setting.max)+unit,9,512),25,141,9,Theme.TEXT_3);
            if(setting.step>0)c.textC(REG,Lang.t("ui.number.step")+"  "+NumberInputValues.format(setting.step)+unit,25,162,9,Theme.TEXT_3);
            NumberInputValues.Result result=NumberInputValues.parse(input.text,setting.min,setting.max,setting.step);
            String message=error.isEmpty()?(result.valid()?Lang.t("ui.number.result")+"  "+NumberInputValues.format(result.value())+unit:Lang.t(result.problem())):Lang.t(error);
            c.textC(REG,c.ellipsize(REG,message,9,512),25,191,9,result.valid()&&error.isEmpty()?Theme.TEXT_2:0xffffb8aa);
            button("number.cancel",Lang.t("ui.number.cancel"),Icon.BACK,24,h-51,142,31,false,this::onClose);
            button("number.apply",Lang.t("ui.number.apply"),Icon.CHECK,394,h-51,142,31,true,this::apply);
        }finally{R2D.end();endFrame();}
    }
    private void apply(){
        if(finished)return;NumberInputValues.Result result=NumberInputValues.apply(input.text,setting.min,setting.max,setting.step,setting::set);
        if(!result.valid()){error=result.problem();focused=input;return;}finished=true;mc.setScreen(parent);
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=input;return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){apply();return true;}return focused!=null&&focused.keyPressed(key,mods);}
    @Override public boolean charTyped(char character,int mods){return focused!=null&&focused.charTyped(character);}
    @Override public void onClose(){if(!finished){finished=true;mc.setScreen(parent);}}
    @Override public boolean isPauseScreen(){return false;}
}
