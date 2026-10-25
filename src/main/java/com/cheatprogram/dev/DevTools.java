package com.cheatprogram.dev;
/** Optional local preview entry point; inactive in release use. */
public final class DevTools {
 private DevTools() { }
 public static boolean isCapturingScreenshot(){return "open-check".equals(System.getProperty("cheatprogram.dev",""));}
 public static void tick(){String mode=System.getProperty("cheatprogram.dev","");if(!isCapturingScreenshot()&&!"gameplay-check".equals(mode))return;try{Class.forName("gameplay-check".equals(mode)?"com.cheatprogram.dev.GameplayCheck":"com.cheatprogram.dev.OpenPreviewCheck").getMethod("tick").invoke(null);}catch(Exception failure){throw new IllegalStateException("Development check failed",failure);}}
}
