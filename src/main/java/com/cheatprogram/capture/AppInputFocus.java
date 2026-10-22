package com.cheatprogram.capture;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** A foreground lock owned by Minecraft, only for explicit in-game application interaction. */
public final class AppInputFocus {
    private interface Windows extends StdCallLibrary {
        Windows API=Native.load("user32",Windows.class,W32APIOptions.DEFAULT_OPTIONS);
        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer window,IntByReference process);
        boolean LockSetForegroundWindow(int mode);
        boolean IsWindow(Pointer window);
        boolean SetForegroundWindow(Pointer window);
        boolean IsIconic(Pointer window);
        boolean ShowWindow(Pointer window,int command);
        Pointer GetWindowLongPtrW(Pointer window,int index);
        boolean SetWindowPos(Pointer window,Pointer after,int x,int y,int width,int height,int flags);
    }
    private static boolean locked;
    private static Pointer gameWindow;
    private static Pointer protectedWindow;
    private static boolean addedTopmost;
    private static long lastLock;
    private AppInputFocus(){ }
    /** Never restores focus after Alt+Tab; it only protects an already foreground game. */
    public static boolean keepGameForeground(){
        if(!Platform.isWindows())return false;
        try{Pointer foreground=Windows.API.GetForegroundWindow();
            if(foreground==null){release();return false;}
            if(locked&&foreground.equals(gameWindow)&&System.nanoTime()-lastLock<250_000_000L)return true;
            IntByReference process=new IntByReference();Windows.API.GetWindowThreadProcessId(foreground,process);
            if(process.getValue()!=ProcessHandle.current().pid()){release();return false;}
            // Preserve the game's original Z order. Promoting it to topmost on
            // every interaction interferes with the shell when a video toggles
            // fullscreen. The native source guard keeps app windows below it.
            locked=Windows.API.LockSetForegroundWindow(1);gameWindow=locked?foreground:null;lastLock=System.nanoTime();return true;
        }catch(LinkageError|RuntimeException ignored){return false;}
    }
    /** Only the separately selected system-input mode may activate the original app. */
    public static boolean activateSelected(AppCaptureEngine.Window window){
        release();if(!Platform.isWindows()||window==null||window.desktop()||window.pid()<=0)return false;
        try{Pointer target=new Pointer(window.handle());if(!Windows.API.IsWindow(target))return false;
            IntByReference process=new IntByReference();Windows.API.GetWindowThreadProcessId(target,process);if(process.getValue()!=window.pid()||process.getValue()==ProcessHandle.current().pid())return false;
            if(Windows.API.IsIconic(target))Windows.API.ShowWindow(target,9);
            return Windows.API.SetForegroundWindow(target);
        }catch(LinkageError|RuntimeException ignored){return false;}
    }
    /** One-shot recovery only for a new view correlated with an explicit projected click.
     * The capture helper supplies both handles after its owner/intent checks. A user Alt+Tab
     * or any different foreground window invalidates recovery; there is no focus retry loop. */
    public static boolean restoreAfterProjectedView(long projectedHwnd,long gameHwnd){
        if(!Platform.isWindows()||projectedHwnd==0||gameHwnd==0||projectedHwnd==gameHwnd)return false;
        try{Pointer projected=new Pointer(projectedHwnd),game=new Pointer(gameHwnd);
            if(!projected.equals(Windows.API.GetForegroundWindow())||!Windows.API.IsWindow(projected)||!Windows.API.IsWindow(game))return false;
            IntByReference process=new IntByReference();
            Windows.API.GetWindowThreadProcessId(game,process);if(process.getValue()!=ProcessHandle.current().pid())return false;
            Windows.API.GetWindowThreadProcessId(projected,process);if(process.getValue()<=0||process.getValue()==ProcessHandle.current().pid())return false;
            // Recheck immediately before activation so a different app chosen meanwhile wins.
            if(!projected.equals(Windows.API.GetForegroundWindow())||!Windows.API.SetForegroundWindow(game))return false;
            if(!game.equals(Windows.API.GetForegroundWindow()))return false;
            locked=false;gameWindow=null;keepGameForeground();return true;
        }catch(LinkageError|RuntimeException ignored){return false;}
    }
    private static void restoreWindowOrder(){
        Pointer previous=protectedWindow;protectedWindow=null;boolean restore=addedTopmost;addedTopmost=false;
        if(previous!=null&&restore&&Platform.isWindows())try{
            if(Windows.API.IsWindow(previous))Windows.API.SetWindowPos(previous,new Pointer(-2),0,0,0,0,0x213);
        }catch(LinkageError|RuntimeException ignored){ }
    }
    public static void release(){restoreWindowOrder();gameWindow=null;if(!locked)return;locked=false;if(Platform.isWindows())try{Windows.API.LockSetForegroundWindow(2);}catch(LinkageError|RuntimeException ignored){ }}
}
