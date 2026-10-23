package com.cheatprogram.dev;
/** Optional local preview entry point; inactive in release use. */
public final class DevTools {
 private DevTools() { }
 public static boolean isCapturingScreenshot(){return "open-check".equals(System.getProperty("cheatprogram.dev",""));}
 public static void tick(){if(!isCapturingScreenshot())return;try{Class.forName("com.cheatprogram.dev.OpenPreviewCheck").getMethod("tick").invoke(null);}catch(Exception failure){throw new IllegalStateException("Preview check failed",failure);}}
}
