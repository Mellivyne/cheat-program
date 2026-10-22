package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.capture.AppCastPointerState;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.R2D;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import java.util.HashSet;
import java.util.Set;

/** Holding the interaction key owns relative mouse input; the Minecraft camera never receives its delta. */
public final class AppCastInputScreen extends com.cheatprogram.compat.CompatScreen {
    private final AppCast module;private final AppCastPointerState pointer;private boolean restored,textMode,cursorOwned,controlTap,pointerDirty=true;private int previousCursor,previousRaw;
    private AppCastPointerState.Mapping coordinateMapping;
    private final Set<Integer> held=new HashSet<>(),sentKeys=new HashSet<>(),pointerButtons=new HashSet<>();
    private long lastDragMove;
    private static final long DRAG_INTERVAL_NANOS=8_000_000L;
    public AppCastInputScreen(AppCast module,double u,double v){super(Component.literal("Application screen input"));this.module=module;pointer=new AppCastPointerState(u,v);textMode=!module.moveFirst();}
    @Override protected void init(){
        long window=minecraft.getWindow().handle();if(!cursorOwned){previousCursor=GLFW.glfwGetInputMode(window,GLFW.GLFW_CURSOR);
        previousRaw=GLFW.glfwRawMouseMotionSupported()?GLFW.glfwGetInputMode(window,GLFW.GLFW_RAW_MOUSE_MOTION):GLFW.GLFW_FALSE;cursorOwned=true;}
        if(GLFW.glfwRawMouseMotionSupported())GLFW.glfwSetInputMode(window,GLFW.GLFW_RAW_MOUSE_MOTION,GLFW.GLFW_FALSE);
        if(GLFW.glfwGetInputMode(window,GLFW.GLFW_CURSOR)!=GLFW.GLFW_CURSOR_DISABLED)GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_DISABLED);
        // Baseline exactly once after mode/fullscreen/DPI setup. Render/click paths
        // never poll a newer position ahead of the ordered GLFW callbacks.
        restored=false;pointer.reset();coordinateMapping=readMapping();try(MemoryStack stack=MemoryStack.stackPush()){var x=stack.mallocDouble(1);var y=stack.mallocDouble(1);GLFW.glfwGetCursorPos(window,x,y);pointer.baseline(x.get(0),y.get(0),coordinateMapping);}
        pointerDirty=true;minecraft.mouseHandler.setIgnoreFirstMove();com.cheatprogram.capture.AppInputFocus.keepGameForeground();
    }
    /** Receives the same ordered GLFW stream as clicks; never polls a later cursor state. */
    public void rawMouseMoved(double currentX,double currentY){
        if(restored||minecraft==null||!minecraft.isWindowActive()){pointer.reset();return;}
        if(GLFW.glfwGetInputMode(minecraft.getWindow().handle(),GLFW.GLFW_CURSOR)!=GLFW.GLFW_CURSOR_DISABLED){pointer.reset();return;}
        // GLFW can deliver hundreds of callbacks per frame. Reuse the last dimensions;
        // resolving them here used to upload a pending video frame from this callback.
        pointerDirty=pointer.move(currentX,currentY,coordinateMapping,module.pointerSpeed())||pointerDirty;
        // A complete short drag can occur between two game frames. Preserve its
        // path and hover independently of video rendering; hover is coalesced at 125 Hz.
        flushPointer(false);
    }
    private boolean flushPointer(boolean force){
        if(!pointerDirty)return true;
        if(!com.cheatprogram.capture.AppInputFocus.keepGameForeground())return false;
        boolean dragging=!pointerButtons.isEmpty();long now=System.nanoTime();
        if(!force&&now-lastDragMove<DRAG_INTERVAL_NANOS)return false;
        boolean sent=dragging?module.inputDrag(u(),v()):module.inputMove(u(),v());
        if(sent){pointerDirty=false;lastDragMove=now;}return sent;
    }
    private AppCastPointerState.Mapping readMapping(){var window=minecraft.getWindow();return new AppCastPointerState.Mapping(window.getScreenWidth(),window.getScreenHeight(),window.getWidth(),window.getHeight(),module.clientWidth(),module.clientHeight());}
    public double u(){return pointer.u();}public double v(){return pointer.v();}
    private boolean typing(){return module.keyboardOnly()||textMode;}
    private int appModifiers(int mods){int key=module.interactionKey();if(key==GLFW.GLFW_KEY_LEFT_ALT||key==GLFW.GLFW_KEY_RIGHT_ALT)mods&=~GLFW.GLFW_MOD_ALT;if(key==GLFW.GLFW_KEY_LEFT_SHIFT||key==GLFW.GLFW_KEY_RIGHT_SHIFT)mods&=~GLFW.GLFW_MOD_SHIFT;return mods;}
    private KeyMapping[] movement(){return new KeyMapping[]{minecraft.options.keyUp,minecraft.options.keyDown,minecraft.options.keyLeft,minecraft.options.keyRight,minecraft.options.keyJump,minecraft.options.keyShift,minecraft.options.keySprint};}
    private boolean movementKey(int key,int scan){for(KeyMapping binding:movement())if(binding.matches(new net.minecraft.client.input.KeyEvent(key,scan,0)))return true;return false;}
    private void updateMovement(){for(KeyMapping binding:movement())binding.setDown(!module.keyboardOnly()&&!typing()&&com.cheatprogram.util.Keys.physical(binding));}
    @Override public void tick(){if(!module.inputStillValid()||!module.keyboardOnly()&&GLFW.glfwGetKey(minecraft.getWindow().handle(),module.interactionKey())!=GLFW.GLFW_PRESS){module.leaveInteraction();return;}var next=readMapping();if(coordinateMapping==null||next.clientWidth()!=coordinateMapping.clientWidth()||next.clientHeight()!=coordinateMapping.clientHeight())pointerDirty=true;coordinateMapping=next;updateMovement();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphicsExtractor graphics,int x,int y,float partial){ }
    @Override public void render(GuiGraphicsExtractor graphics,int x,int y,float partial){
        // Do not fight another cursor owner once per frame. Resize reinitializes this
        // screen; an unexpected mode/focus loss ends interaction and releases buttons.
        if(!module.inputStillValid()||GLFW.glfwGetInputMode(minecraft.getWindow().handle(),GLFW.GLFW_CURSOR)!=GLFW.GLFW_CURSOR_DISABLED){module.leaveInteraction();return;}
        flushPointer(false);R2D.begin(graphics);try{String text=Lang.t(module.keyboardOnly()?"ui.appcast.keyboardControls":typing()?"ui.appcast.typeControls":"ui.appcast.moveControls")+" · "+Lang.t(module.inputStatusKey());float size=Fonts.pixelSize(11);String caption=Fonts.ellipsize(Fonts.Face.REGULAR,text,size,Math.max(1,R2D.width()-24));
            R2D.text(Fonts.Face.REGULAR,caption,Math.max(8,(R2D.width()-R2D.textWidth(Fonts.Face.REGULAR,caption,size))/2),R2D.height()-16,size,0xe6ffffff);drawPointer();}finally{R2D.end();}
    }
    private void drawPointer(){double[] p=module.pointerPosition(u(),v());if(p!=null)com.cheatprogram.render.AppCastPointer.draw((float)p[0],(float)p[1]);}
    @Override public boolean mouseClicked(double x,double y,int button){
        flushPointer(true);boolean sent=com.cheatprogram.capture.AppInputFocus.keepGameForeground()&&module.inputButton(button,true,u(),v());
        if(sent){pointerButtons.add(button);lastDragMove=0;}pointerDirty=!sent;return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        flushPointer(true);boolean sent=module.inputButton(button,false,u(),v());pointerButtons.remove(button);pointerDirty|=!sent;
        if(!sent){module.releaseInput();pointerButtons.clear();}return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){return true;}
    /** Pointer interaction owns wheel input in both movement and typing modes. Keyboard capture is independent. */
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){flushPointer(true);boolean sent=com.cheatprogram.capture.AppInputFocus.keepGameForeground()&&module.inputScroll(horizontal,vertical,u(),v());pointerDirty|=!sent;return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){
        mods=appModifiers(mods);
        if(key==GLFW.GLFW_KEY_ESCAPE){module.leaveInteraction();return true;}if(key==module.interactionKey())return true;
        // A tap switches typing/movement. A chord remains a normal Ctrl shortcut.
        // Previously Ctrl was consumed immediately, so paste and selection could not work.
        if(!module.keyboardOnly()&&key==GLFW.GLFW_KEY_LEFT_CONTROL){if(held.add(key))controlTap=true;return true;}
        if(controlTap){controlTap=false;if(typing()&&module.inputKey(GLFW.GLFW_KEY_LEFT_CONTROL,true,false))sentKeys.add(GLFW.GLFW_KEY_LEFT_CONTROL);}
        boolean repeat=!held.add(key);updateMovement();if(!typing()&&movementKey(key,scan))return true;
        // Text is sent once as WM_CHAR; ordinary apps would TranslateMessage a posted printable keydown again.
        boolean control=(mods&GLFW.GLFW_MOD_CONTROL)!=0,shortcut=control||(mods&GLFW.GLFW_MOD_ALT)!=0;
        if(typing()&&control&&key==GLFW.GLFW_KEY_V){module.inputText(minecraft.keyboardHandler.getClipboard());return true;}
        if(typing()&&key>=GLFW.GLFW_KEY_SPACE&&key<=GLFW.GLFW_KEY_WORLD_2&&!shortcut)return true;
        if(module.inputKey(key,true,repeat))sentKeys.add(key);return true;
    }
    @Override public boolean keyReleased(int key,int scan,int mods){held.remove(key);if(key==GLFW.GLFW_KEY_LEFT_CONTROL&&controlTap){controlTap=false;textMode=!textMode;module.releaseInput();sentKeys.clear();pointerButtons.clear();}updateMovement();if(sentKeys.remove(key))module.inputKey(key,false,false);return true;}
    @Override public boolean charTyped(char character,int mods){if(typing()&&(appModifiers(mods)&(GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_ALT))==0)module.inputChar(character);return true;}
    @Override public void onClose(){module.leaveInteraction();}
    @Override public void removed(){
        module.releaseInput();held.clear();sentKeys.clear();pointerButtons.clear();KeyMapping.releaseAll();com.cheatprogram.capture.AppInputFocus.release();if(restored)return;restored=true;cursorOwned=false;long window=minecraft.getWindow().handle();
        pointer.reset();GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,previousCursor);if(GLFW.glfwRawMouseMotionSupported())GLFW.glfwSetInputMode(window,GLFW.GLFW_RAW_MOUSE_MOTION,previousRaw);minecraft.mouseHandler.setIgnoreFirstMove();
    }
}
