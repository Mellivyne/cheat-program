package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.media.player.MusicModels.Provider;
import com.cheatprogram.media.player.MusicPlayer;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Explicit local account setup. No secret is copied into module settings or a share code. */
public final class MusicAccountScreen extends GuiBase {
    private final Screen parent;
    private final MusicPlayer player=MusicPlayer.INSTANCE;
    private final Provider provider;
    private final TextField value=new TextField(4096);
    public MusicAccountScreen(Screen parent,Provider provider){
        super(Lang.t("ui.music.settings"));this.parent=parent;this.provider=provider;
        value.masked=provider==Provider.KUWO;
        if(provider==Provider.SPOTIFY)value.setText(player.accountConfiguration());
        value.onEnter=this::save;
    }
    @Override protected void init(){focused=value;}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{
            float h=layoutPanel(680,370);drawPanelSurface(graphics,680,h,22);
            c.textC(BOLD,provider.label+" · "+Lang.t("ui.music.settings"),26,31,16,Theme.TEXT);
            iconButton("music.account.close",Icon.CLOSE,651,31,14,Theme.TEXT_2,this::onClose);
            float y=62;
            String hint=Lang.t(provider==Provider.SPOTIFY?"ui.music.spotifySetup":"ui.music.kuwoSetup");
            for(String line:c.wrap(REG,hint,9,624)){c.textC(REG,line,28,y,9,Theme.TEXT_3);y+=17;}
            if(provider==Provider.SPOTIFY){
                c.textC(REG,Lang.t("ui.music.redirect")+" http://127.0.0.1/callback",28,y+7,9,Theme.TEXT_2);y+=29;
            }
            String label=Lang.t(provider==Provider.SPOTIFY?"ui.music.clientId":"ui.music.cookie");
            c.textC(MED,label,28,y+12,10,Theme.TEXT_2);
            textField(value,label,Icon.USER,26,y+29,628,38);
            y+=89;
            for(String line:c.wrap(REG,Lang.t("ui.music.privacyDetails"),8.5f,624)){c.textC(REG,line,28,y,8.5f,Theme.TEXT_3);y+=15;}
            button("music.account.cancel",Lang.t("ui.music.cancel"),Icon.BACK,26,h-49,135,30,false,this::onClose);
            button("music.account.save",Lang.t("ui.music.save"),Icon.CHECK,519,h-49,135,30,true,this::save);
        }finally{R2D.end();endFrame();}
    }
    private void save(){String input=value.text.strip();if(input.isEmpty())return;player.configureAccount(provider,input);value.setText("");mc.setScreen(parent);}
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return focused!=null&&focused.keyPressed(key,mods);}
    @Override public boolean charTyped(char character,int mods){return focused!=null&&focused.charTyped(character);}
    @Override public void onClose(){value.setText("");mc.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
