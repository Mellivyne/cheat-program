package com.cheatprogram.media;

import com.google.gson.JsonObject;

/** Updates the former default once; saved user offsets and later choices remain intact. */
public final class SubtitlePresets {
    private SubtitlePresets() { }

    public static JsonObject migrate(JsonObject original) {
        if (original.has("layoutDefaultsVersion")) return original;
        JsonObject result = original.deepCopy();
        try {
            if (result.has("distance") && Math.abs(result.get("distance").getAsDouble() - 8) < .000001)
                result.addProperty("distance", 3.5);
        } catch (RuntimeException invalid) { }
        result.addProperty("layoutDefaultsVersion", 1);
        return result;
    }
}
