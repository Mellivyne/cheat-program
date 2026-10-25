package com.cheatprogram.gui;

import com.cheatprogram.ai.AiConfig;
import com.cheatprogram.ai.AiPlayerModels;
import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Icon;
import java.util.Locale;
import java.util.UUID;

/** Independent, explicitly refreshed catalogue for a designer's connection. */
final class AiConnectionPicker implements AutoCloseable {
    private final UUID id=UUID.randomUUID();
    private final TextField search=new TextField(128);
    private final GuiBase.Scroller scroll=new GuiBase.Scroller();
    private final AiConfig config;
    private boolean open;
    AiConnectionPicker(AiConfig config){this.config=config;search.onChange=value->{scroll.target=0;scroll.value.snap(0);};}
    void invalidate(){AiPlayerModels.clear(id);open=false;}
    void refresh(){AiPlayerModels.refresh(id,config.endpoint(),config.apiKey());}
    void providers(GuiBase gui,float x,float y,float width,TextField endpoint,TextField key,Runnable changed){
        String provider="custom";
        try{String host=java.net.URI.create(endpoint.text.strip()).getHost();
            if("api.openai.com".equalsIgnoreCase(host))provider="openai";
            else if("api.deepseek.com".equalsIgnoreCase(host))provider="deepseek";
        }catch(IllegalArgumentException ignored){}
        float gap=4,buttonWidth=(width-gap*2)/3;
        String[] values={"openai","deepseek","custom"};
        for(int i=0;i<values.length;i++){
            String value=values[i],label=i==0?"OpenAI":i==1?"DeepSeek":Lang.t("ui.ai.custom");
            gui.button("designer.provider."+value,label,null,x+i*(buttonWidth+gap),y-7,buttonWidth,25,value.equals(provider),()->{
                if(value.equals("custom")){gui.focused=endpoint;endpoint.allSelected=true;return;}
                changed.run();invalidate();
                config.preset(value);config.save();
                endpoint.setText(config.endpoint());key.setText("");gui.focused=key;
            });
        }
    }
    void draw(GuiBase gui,float x,float y,float width,Runnable save){
        gui.c.textC(GuiBase.REG,Lang.t("ui.ai.model"),x,y,10,Theme.TEXT_2);
        gui.button("designer.models.refresh",Lang.t("ui.ai.refresh"),Icon.REFRESH,x+width-72,y-11,72,22,false,()->{save.run();refresh();open=true;gui.focused=null;});
        gui.button("designer.models.choose",gui.c.ellipsize(GuiBase.REG,config.model().isBlank()?Lang.t("ui.ai.chooseModel"):config.model(),10,width-30),Icon.CHEVRON_DOWN,x,y+13,width,29,false,()->{save.run();open=true;gui.focused=search;});
    }
    void overlay(GuiBase gui,float width,float height){
        if(!open)return;
        float w=Math.min(480,width-40),h=Math.min(354,height-40),x=(width-w)/2,y=(height-h)/2;
        gui.c.rrect(0,0,width,height,0,0x60000000);
        gui.hit(0,0,width,height).click(button->dismiss(gui)).scroll(amount->{});
        gui.c.lens(x,y,w,h,18,Theme.lift(.12f),Theme.lensGlass(gui.c.scale));
        gui.hit(x,y,w,h).click(button->{}).scroll(amount->{});
        gui.c.textC(GuiBase.BOLD,Lang.t("ui.ai.modelsTitle"),x+16,y+22,12,Theme.TEXT);
        gui.iconButton("designer.models.close",Icon.CLOSE,x+w-20,y+22,12,Theme.TEXT_2,()->dismiss(gui));
        gui.textField(search,Lang.t("ui.ai.modelSearch"),Icon.SEARCH,x+12,y+39,w-24,28);
        var state=AiPlayerModels.state(id);
        gui.c.textC(GuiBase.REG,gui.c.ellipsize(GuiBase.REG,Lang.t(state.status()),8.5f,w-28),x+14,y+82,8.5f,Theme.TEXT_3);
        String query=search.text.strip().toLowerCase(Locale.ROOT);
        var entries=state.models().stream().filter(m->m.id().toLowerCase(Locale.ROOT).contains(query)||m.name().toLowerCase(Locale.ROOT).contains(query)).toList();
        float top=y+96,available=h-110;
        scroll.max=Math.max(0,entries.size()*40-available);scroll.update(gui.dt);
        gui.hit(x+10,top,w-20,available).scroll(scroll::scroll);
        gui.c.clip(x+10,top,w-20,available);gui.pushHitClip(x+10,top,w-20,available);
        try{
            if(entries.isEmpty())gui.c.textC(GuiBase.REG,Lang.t(state.loading()?"ui.ai.modelsLoading":"ui.ai.modelsEmpty"),x+20,top+22,10,Theme.TEXT_3);
            float row=top-scroll.get();
            for(var entry:entries){
                if(row+40>=top&&row<top+available){
                    boolean selected=entry.id().equals(config.model());
                    gui.c.textC(GuiBase.MED,gui.c.ellipsize(GuiBase.MED,entry.name(),10.5f,w-50),x+22,row+13,10.5f,entry.usable()?Theme.TEXT:Theme.TEXT_4);
                    gui.c.textC(GuiBase.REG,gui.c.ellipsize(GuiBase.REG,entry.id(),8,w-50),x+22,row+28,8,Theme.TEXT_3);
                    if(selected)gui.c.textC(GuiBase.REG,"✓",x+w-28,row+18,11,Theme.TEXT);
                    if(entry.usable())gui.hit(x+12,row,w-24,37).left(()->{config.model(entry.id());config.save();dismiss(gui);});
                }
                row+=40;
            }
        }finally{gui.popHitClip();gui.c.unclip();}
    }
    boolean dismiss(GuiBase gui){if(!open)return false;open=false;gui.focused=null;return true;}
    @Override public void close(){open=false;AiPlayerModels.clear(id);}
}
