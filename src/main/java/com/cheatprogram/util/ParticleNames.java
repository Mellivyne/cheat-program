package com.cheatprogram.util;

import com.cheatprogram.core.Lang;
import java.util.Locale;

/** Display names follow the mod's global locale; registry ids are always preserved separately. */
public final class ParticleNames {
    private ParticleNames() { }
    public static String display(String id) {
        String key="particle."+id.replace(':','.');
        return Lang.has(key)?Lang.t(key):id;
    }
    public static boolean matches(String id,String query) {
        String needle=query.strip().toLowerCase(Locale.ROOT);
        return needle.isEmpty()||id.toLowerCase(Locale.ROOT).contains(needle)||display(id).toLowerCase(Locale.ROOT).contains(needle);
    }
}
