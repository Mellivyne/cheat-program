package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.render.AppCastPlane;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Cursor manipulates only the local world plane, never the captured application. */
public final class AppCastEditorScreen extends GuiBase {
    private final Screen parent;private final AppCast module;
    private AppCastPlane dragPlane;private Vec3 dragPoint,dragCenter;private double previousX,previousY;private int dragging=-1;
    public AppCastEditorScreen(Screen parent,AppCast module){super(Lang.t("ui.appcast.edit"));this.parent=parent;this.module=module;}
    @Override public void render(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){
        beginFrame();R2D.begin(graphics);
        try{
            c.set(1,0,0);updateMouse();float screenW=R2D.width(),screenH=R2D.height();
            c.rrect(16,16,Math.min(screenW-32,720),72,10,0xb0101216);
            c.textC(BOLD,Lang.t("ui.appcast.edit"),30,36,15,Theme.TEXT);
            c.textC(REG,c.ellipsize(REG,Lang.t("ui.appcast.editHint"),11,Math.max(100,Math.min(screenW-64,680))),30,62,11,Theme.TEXT_2);
            c.rrect(16,screenH-66,Math.min(screenW-32,720),50,10,0xb0101216);
            button("cast.editor.back",Lang.t("ui.back"),Icon.BACK,26,screenH-55,100,28,false,this::onClose);
            button("cast.editor.place",Lang.t("ui.appcast.place"),Icon.TARGET,138,screenH-55,160,28,false,module::placeInFront);
            button("cast.editor.follow",Lang.t(module.follows()?"ui.appcast.follow":"ui.appcast.fixed"),Icon.NAVIGATION,310,screenH-55,178,28,module.follows(),()->module.follow(!module.follows()));
            c.textC(REG,c.ellipsize(REG,Lang.t(module.statusKey()),9,Math.max(60,screenW-530)),502,screenH-41,9,Theme.TEXT_3);
            AppCastPlane p=module.plane(partial);String transform=String.format(java.util.Locale.ROOT,"%.2f × %.2f m   X %.2f  Y %.2f  Z %.2f   ·   %.0f° / %.0f° / %.0f°",p.width(),p.height(),p.center().x,p.center().y,p.center().z,p.yaw(),p.pitch(),p.roll());
            c.textC(REG,c.ellipsize(REG,transform,10,screenW-40),20,screenH-80,10,Theme.TEXT_2);
        }finally{R2D.end();endFrame();}
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(dispatchClick(x,y,button))return true;Vec3 ray=module.screenRay(x,y),camera=module.renderCamera();if(ray==null||camera==null)return true;
        AppCastPlane plane=module.plane(1);AppCastPlane.Hit hit=plane.pick(camera,ray,true);if(hit==null)return true;
        if(button==0||button==1){dragging=button;dragPlane=plane;dragPoint=plane.point(hit.u(),hit.v());dragCenter=plane.center();previousX=x;previousY=y;}return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(dragging==0&&dragPlane!=null){Vec3 ray=module.screenRay(x,y),camera=module.renderCamera();if(ray!=null&&camera!=null){AppCastPlane.Hit hit=dragPlane.pick(camera,ray,false);if(hit!=null)module.setCenter(dragCenter.add(dragPlane.point(hit.u(),hit.v()).subtract(dragPoint)));}return true;}
        if(dragging==1){module.rotate((x-previousX)*.45,(y-previousY)*.45,hasControlDown());previousX=x;previousY=y;return true;}return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){dragging=-1;dragPlane=null;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        Vec3 ray=module.screenRay(x,y),camera=module.renderCamera();if(ray!=null&&camera!=null&&module.plane(1).pick(camera,ray,true)!=null){if(hasShiftDown())module.moveDepth(vertical*.2);else module.resize(Math.pow(1.1,vertical));}return true;
    }
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}if(key==GLFW.GLFW_KEY_F){module.placeInFront();return true;}return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){mc.gui.setScreen(parent);}
}
