package com.cheatprogram.gui;

import com.cheatprogram.core.*;import com.cheatprogram.core.setting.*;import com.cheatprogram.module.visual.ModelWear;import com.cheatprogram.render.*;import com.cheatprogram.util.*;import net.minecraft.util.Util;import net.minecraft.client.gui.GuiGraphicsExtractor;import net.minecraft.client.gui.screens.Screen;import org.lwjgl.glfw.GLFW;import org.lwjgl.system.MemoryStack;import org.lwjgl.util.tinyfd.TinyFileDialogs;import java.nio.file.*;import java.util.*;

/** File import and transforms only; this is not a mesh modelling editor. */

public final class ModelWearScreen extends GuiBase {

 private final Screen parent;private final ModelWear module;private final WearModelPreview preview=new WearModelPreview();private final TextField path=new TextField(4096);private final Scroller settings=new Scroller();private String status="";private boolean importing,closed;private long generation;

 public ModelWearScreen(Screen parent,ModelWear module){super(Lang.t("module.modelwear"));this.parent=parent;this.module=module;path.onEnter=()->importPath(path.text);}

 @Override protected void init(){closed=false;}

 @Override public void render(GuiGraphicsExtractor g,int x,int y,float partial){module.move(module.offsetX.val(),module.offsetY.val(),module.offsetZ.val());beginFrame();if(!preparePanelBackground(g,partial)){endFrame();return;}R2D.begin(g);try{float h=layoutPanel(1000,720);drawPanelSurface(g,1000,h,22);c.textC(BOLD,Lang.t("module.modelwear"),24,30,16,Theme.TEXT);iconButton("model.close",Icon.CLOSE,972,29,14,Theme.TEXT_2,this::onClose);

  textField(path,Lang.t("ui.modelWear.fileHint"),Icon.FOLDER,24,58,638,31);button("model.choose",Lang.t("ui.modelWear.choose"),Icon.FOLDER,674,58,142,31,false,this::choose);button("model.import",Lang.t("ui.modelWear.import"),Icon.DOWNLOAD,828,58,148,31,true,()->importPath(path.text));

  float top=107,bottom=h-87,pane=Math.max(100,bottom-top);c.rrect(24,top,440,pane,13,0x18000000);preview.draw(g,c.px(24),c.py(top),440*c.scale,pane*c.scale,module);

  if(module.model()==null)c.textMid(REG,Lang.t(WearModelStore.status()),244,top+pane-16,10,Theme.TEXT_3);

  hit(24,top,440,pane).scroll(amount->{if(GLFW.glfwGetKey(mc.getWindow().handle(),GLFW.GLFW_KEY_LEFT_SHIFT)==GLFW.GLFW_PRESS)module.size.set(module.size.val()+amount*.05);else preview.zoom=Math.max(.02f,Math.min(12,preview.zoom*(float)Math.pow(1.12,amount)));}).click(this::dragPreview);

  settings.update(dt);hit(484,top,492,pane).scroll(settings::scroll);c.clip(484,top,492,pane);pushHitClip(484,top,492,pane);try{float cy=top-settings.get();for(Setting<?> setting:module.settings()){if(!setting.isVisible())continue;if(setting instanceof NumberSetting n){c.textC(REG,c.ellipsize(REG,n.name(),10,330),492,cy+12,10,Theme.TEXT_2);c.textRight(REG,n.format(),966,cy+12,10,Theme.TEXT);slider(n,498,cy+34,462);cy+=59;}else if(setting instanceof BoolSetting b){button("model.option."+b.id,b.name(),b.on()?Icon.CHECK:Icon.CLOSE,490,cy,480,30,b.on(),b::toggle);cy+=40;}else if(setting instanceof ModeSetting m){button("model.option."+m.id,m.name()+" · "+m.display(),Icon.CHEVRON_RIGHT,490,cy,480,30,false,()->m.set(m.modes.get((m.modes.indexOf(m.get())+1)%m.modes.size())));cy+=40;}}settings.max=Math.max(0,cy+settings.get()-top-pane+8);}finally{popHitClip();c.unclip();}panelScrollBar("model.scroll",settings,982,top,pane);

  c.textC(REG,c.ellipsize(REG,Lang.t(status.isBlank()?"ui.modelWear.previewHint":status),8.5f,950),25,h-66,8.5f,Theme.TEXT_3);button("model.back",Lang.t("ui.back"),Icon.BACK,24,h-44,100,28,false,this::onClose);button("model.on",Lang.t(module.isEnabled()?"ui.modelWear.disable":"ui.modelWear.enable"),Icon.POWER,136,h-44,140,28,module.isEnabled(),module::toggle);button("model.share",Lang.t("ui.scopedShare.open"),Icon.LINK,288,h-44,190,28,false,()->mc.gui.setScreen(new ScopedShareScreen(this,ScopedPresetCodec.Scope.MODEL)));button("model.resetView",Lang.t("ui.modelWear.resetView"),Icon.REFRESH,490,h-44,140,28,false,preview::reset);button("model.clear",Lang.t("ui.modelWear.clear"),Icon.CLOSE,642,h-44,140,28,false,()->module.asset(""));

 }finally{R2D.end();endFrame();}}

 private void dragPreview(int button){if(button!=0&&button!=1&&button!=2)return;float x=mx,y=my,angle=preview.yaw,tilt=preview.pitch;double ox=module.offsetX.val(),oy=module.offsetY.val(),oz=module.offsetZ.val(),yaw=module.yaw.val(),pitch=module.pitch.val();drag=(u,v)->{if(button==0){preview.yaw=angle+(u-x)*.7f;preview.pitch=Math.max(-80,Math.min(80,tilt+(v-y)*.7f));}else if(button==1)module.move(ox+(u-x)*.012/preview.zoom,oy-(v-y)*.012/preview.zoom,oz);else{module.yaw.set(yaw+(u-x)*.7);module.pitch.set(pitch+(v-y)*.7);}};}

 private void choose(){if(importing)return;WearModelStore.IO.execute(()->{String chosen=null;try(MemoryStack stack=MemoryStack.stackPush()){var filters=stack.mallocPointer(4);for(String type:List.of("*.obj","*.glb","*.gltf","*.cpmodel"))filters.put(stack.UTF8(type));filters.flip();chosen=TinyFileDialogs.tinyfd_openFileDialog(Lang.t("ui.modelWear.choose"),"",filters,"OBJ / static glTF 2 / GLB",false);}catch(Throwable ignored){}String file=chosen;mc.execute(()->{if(!closed&&file!=null){path.setText(file);importPath(file);}});});}

 private void importPath(String file){if(importing||file==null||file.isBlank())return;importing=true;status="ui.modelWear.loading";long token=++generation;WearModelStore.IO.execute(()->{String id="",error="";try{if(file.startsWith("\\\\")||file.contains("://"))throw WearModelData.bad("referenceBlocked");id=WearModelStore.install(WearModelData.encode(WearModelData.importFile(Path.of(file))));}catch(Exception failure){error=failure.getMessage()!=null&&failure.getMessage().startsWith("ui.modelWear.")?failure.getMessage():"ui.modelWear.invalid";}String result=id,message=error;mc.execute(()->{if(closed||token!=generation)return;importing=false;if(!result.isBlank()){module.asset(result);status="ui.modelWear.ready";}else status=message;});});}

 @Override public void onFilesDrop(List<Path> files){if(!files.isEmpty()){path.setText(files.getFirst().toString());importPath(path.text);}}

 @Override public boolean mouseClicked(double x,double y,int b){if(!dispatchClick(x,y,b))focused=null;return true;}@Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}@Override public boolean keyPressed(int k,int scan,int mods){if(k==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return focused!=null&&focused.keyPressed(k,mods);}@Override public boolean charTyped(char c,int mods){return focused!=null&&focused.charTyped(c);}@Override public void onClose(){mc.gui.setScreen(parent);}@Override public void removed(){closed=true;generation++;importing=false;drag=null;preview.close();super.removed();}

}

