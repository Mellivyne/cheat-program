package com.cheatprogram.util;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** Read-only Windows display language; never changes process or device language settings. */
final class WindowsSystemLanguage {
    private WindowsSystemLanguage() { }
    static String tag() { return Detected.TAG; }
    private static final class Detected { private static final String TAG=read(); }
    public interface Api extends StdCallLibrary {
        boolean GetUserPreferredUILanguages(int flags,IntByReference count,char[] names,IntByReference length);
        short GetUserDefaultUILanguage();
        int LCIDToLocaleName(int locale,char[] name,int length,int flags);
    }
    private static String read() {
        try {
            if(!Platform.isWindows())return null;
            Api api=Native.load("kernel32",Api.class,W32APIOptions.UNICODE_OPTIONS);
            IntByReference count=new IntByReference(),length=new IntByReference();
            api.GetUserPreferredUILanguages(8,count,null,length); // MUI_LANGUAGE_NAME
            int size=length.getValue();
            if(size>1&&size<=4096) {
                char[] names=new char[size];
                if(api.GetUserPreferredUILanguages(8,count,names,length)) {
                    String tag=first(names);if(!tag.isBlank())return tag;
                }
            }
            char[] name=new char[85];
            int language=Short.toUnsignedInt(api.GetUserDefaultUILanguage());
            if(language!=0&&api.LCIDToLocaleName(language,name,name.length,0)>1)return first(name);
        }catch(RuntimeException|LinkageError unavailable){/* JVM display locale is the portable fallback. */}
        return null;
    }
    private static String first(char[] buffer) { int length=0;while(length<buffer.length&&buffer[length]!=0)length++;return new String(buffer,0,length); }
}
