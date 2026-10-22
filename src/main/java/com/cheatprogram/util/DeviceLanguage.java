package com.cheatprogram.util;

import java.util.List;
import java.util.Locale;

/** Shared first-use language policy, independent of game language and saved user preferences. */
public final class DeviceLanguage {
    public static final List<String> SUPPORTED=List.of("zh","en","ja","ko","zh_tw","es","fr","de");
    private DeviceLanguage() { }
    public static String system() {
        try {
            String windows=WindowsSystemLanguage.tag();
            return windows==null||windows.isBlank()?resolve(Locale.getDefault(Locale.Category.DISPLAY)):resolve(windows);
        }
        catch(RuntimeException unavailable) { return "en"; }
    }
    public static String resolve(String tag) {
        return tag==null||tag.isBlank()?"en":resolve(Locale.forLanguageTag(tag.strip().replace('_','-')));
    }
    public static String resolve(Locale locale) {
        if(locale==null)return "en";
        String language=locale.getLanguage().toLowerCase(Locale.ROOT);
        if("zh".equals(language)) {
            if("Hant".equalsIgnoreCase(locale.getScript()))return "zh_tw";
            if("Hans".equalsIgnoreCase(locale.getScript()))return "zh";
            return switch(locale.getCountry().toUpperCase(Locale.ROOT)) { case "TW","HK","MO" -> "zh_tw";default -> "zh"; };
        }
        return SUPPORTED.contains(language)?language:"en";
    }
}
