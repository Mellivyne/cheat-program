package com.cheatprogram.core;

import com.cheatprogram.core.setting.*;
import com.cheatprogram.util.KeyNames;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Self-contained, offline configuration codes. No backend, executable content or local resources. */
public final class ProfileShareCodec {
    public static final int MAX_CODE = 180_000;
    private static final int MAX_PACKED = 128 * 1024, MAX_PLAIN = 512 * 1024;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> TEXT_ALLOW = Set.of(
            "cheststealer.weaponIds", "cheststealer.itemQuotas", "invcleaner.keepItems", "invcleaner.junk",
            "itemprotection.lockedSlots", "itemprotection.items", "orexray.customBlocks", "orexray.backfillBlock", "watermark.text");
    private static final Set<String> LOCAL_MODULES = Set.of("personal", "chattracker", "privatechat", "chatrepeat", "taskqueue");
    private static final Set<String> LOCAL_ACTIVATION = Set.of("appcast", "spatialaudio", "floatingsubtitles", "particleeffects", "controlpanel", "customcape");
    private ProfileShareCodec() {}

    public static final class InvalidCode extends Exception {
        public final String key;
        public InvalidCode(String key) { super(key); this.key = key; }
    }
    public record Decoded(String identity, JsonObject modules, int enabledCount) {}

    public static boolean portable(Module module) {
        return module.category != Category.MACRO && !LOCAL_MODULES.contains(module.id);
    }
    public static boolean portable(Setting<?> setting) {
        // Permanent privacy rule: a future whitelist/type change must never make credentials or local paths portable.
        if (privateSettingName(setting.id)) return false;
        return setting instanceof BoolSetting || setting instanceof NumberSetting || setting instanceof RangeSetting
                || setting instanceof ModeSetting || setting instanceof TextSetting && TEXT_ALLOW.contains(setting.owner.id + "." + setting.id);
    }
    private static boolean privateSettingName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("apikey") || normalized.contains("secret") || normalized.contains("credential")
                || normalized.contains("password") || normalized.contains("token") || normalized.contains("authorization")
                || normalized.contains("endpoint") || normalized.contains("path") || normalized.contains("directory")
                || normalized.equals("key") || normalized.equals("bearer") || normalized.equals("auth")
                || normalized.equals("url") || normalized.equals("uri") || normalized.equals("serveraddress");
    }

    /** Builds a fresh, sorted whitelist rather than copying arbitrary keys from a local JSON file. */
    public static String encode(JsonObject profile, String identity) throws InvalidCode {
        JsonObject root = new JsonObject(); root.addProperty("v", 1); root.addProperty("id", identity);
        JsonObject modules = new JsonObject(), source = object(profile.get("modules"));
        for (Module module : ModuleManager.all().stream().sorted(Comparator.comparing(m -> m.id)).toList()) {
            if (!portable(module)) continue;
            JsonObject raw = source.has(module.id) ? object(source.get(module.id)) : new JsonObject();
            JsonObject out = new JsonObject();
            boolean enabled = raw.has("enabled") && bool(raw.get("enabled"));
            out.addProperty("enabled", enabled && !module.isOneShot() && !LOCAL_ACTIVATION.contains(module.id));
            out.addProperty("key", raw.has("key") ? key(raw.get("key")) : module.defaultKey);
            for (String flag : List.of("hold", "favorite", "drawn")) out.addProperty(flag, raw.has(flag) ? bool(raw.get(flag)) : flag.equals("drawn"));
            JsonObject settings = new JsonObject(), rawSettings = raw.has("settings") ? object(raw.get("settings")) : new JsonObject();
            for (Setting<?> setting : module.settings().stream().sorted(Comparator.comparing(s -> s.id)).toList()) {
                if (!portable(setting)) continue;
                JsonElement value = rawSettings.has(setting.id) ? rawSettings.get(setting.id) : defaultValue(setting);
                // Media/independent plans and in-world mouse capture never activate on someone else's device.
                if (module.id.equals("hud") && setting.id.equals("worldScreen")) value = new JsonPrimitive(false);
                // Ordinary watermark text is portable, but credentials, URLs and local paths are not.
                if (setting instanceof TextSetting && !safeText(value.getAsString())) value = defaultValue(setting);
                settings.add(setting.id, validate(setting, value));
            }
            out.add("settings", settings); modules.add(module.id, out);
        }
        root.add("modules", modules);
        byte[] plain = JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_PLAIN) throw new InvalidCode("ui.profileCode.tooLarge");
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(buffer)) { zip.write(plain); }
            byte[] packed = buffer.toByteArray();
            if (packed.length > MAX_PACKED) throw new InvalidCode("ui.profileCode.tooLarge");
            String code = "CP1." + Base64.getUrlEncoder().withoutPadding().encodeToString(packed) + "." + digest(packed);
            if (code.length() > MAX_CODE) throw new InvalidCode("ui.profileCode.tooLarge");
            return code;
        } catch (IOException impossible) { throw new InvalidCode("ui.profileCode.invalid"); }
    }

    /** All checks finish before Config is allowed to create a file or change a live module. */
    public static Decoded decode(String supplied) throws InvalidCode {
        if (supplied == null || supplied.length() > MAX_CODE) throw new InvalidCode("ui.profileCode.tooLarge");
        String code = supplied.strip();
        String[] pieces = code.split("\\.", -1);
        if (pieces.length != 3 || !pieces[0].equals("CP1")) throw new InvalidCode("ui.profileCode.version");
        if (!pieces[1].matches("[A-Za-z0-9_-]+") || !pieces[2].matches("[0-9a-f]{64}")) throw new InvalidCode("ui.profileCode.invalid");
        try {
            byte[] packed = Base64.getUrlDecoder().decode(pieces[1]);
            if (packed.length > MAX_PACKED) throw new InvalidCode("ui.profileCode.tooLarge");
            if (!MessageDigest.isEqual(pieces[2].getBytes(StandardCharsets.US_ASCII), digest(packed).getBytes(StandardCharsets.US_ASCII)))
                throw new InvalidCode("ui.profileCode.checksum");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
                byte[] block = new byte[4096]; int read;
                while ((read = zip.read(block)) != -1) {
                    if (buffer.size() + read > MAX_PLAIN) throw new InvalidCode("ui.profileCode.tooLarge");
                    buffer.write(block, 0, read);
                }
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
            JsonObject root;
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                root = object(readJson(reader, 0, new int[]{0}));
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new InvalidCode("ui.profileCode.invalid");
            }
            only(root, Set.of("v", "id", "modules"));
            if (integer(root.get("v")) != 1) throw new InvalidCode("ui.profileCode.version");
            String identity = string(root.get("id"));
            if (!UUID.fromString(identity).toString().equals(identity)) throw new InvalidCode("ui.profileCode.invalid");
            JsonObject modules = object(root.get("modules"));
            if (modules.size() > ModuleManager.all().size()) throw new InvalidCode("ui.profileCode.incompatible");
            int enabledCount = 0;
            for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
                Module module = ModuleManager.byId(entry.getKey());
                if (module == null || !portable(module)) throw new InvalidCode("ui.profileCode.incompatible");
                JsonObject data = object(entry.getValue());
                only(data, Set.of("enabled", "key", "hold", "favorite", "drawn", "settings"));
                boolean enabled = bool(data.get("enabled")); key(data.get("key"));
                for (String flag : List.of("hold", "favorite", "drawn")) bool(data.get(flag));
                if (enabled && (module.isOneShot() || LOCAL_ACTIVATION.contains(module.id))) throw new InvalidCode("ui.profileCode.privateContent");
                if (enabled) enabledCount++;
                JsonObject settings = object(data.get("settings"));
                Map<String, Setting<?>> known = new HashMap<>(); for (Setting<?> setting : module.settings()) known.put(setting.id, setting);
                for (Map.Entry<String, JsonElement> option : settings.entrySet()) {
                    Setting<?> setting = known.get(option.getKey());
                    if (setting == null || !portable(setting)) throw new InvalidCode("ui.profileCode.incompatible");
                    JsonElement checked = validate(setting, option.getValue());
                    if (module.id.equals("hud") && setting.id.equals("worldScreen") && checked.getAsBoolean())
                        throw new InvalidCode("ui.profileCode.privateContent");
                    option.setValue(checked);
                }
            }
            if (modules.size() == 0) throw new InvalidCode("ui.profileCode.invalid");
            return new Decoded(identity, modules, enabledCount);
        } catch (InvalidCode invalid) { throw invalid; }
        catch (Exception invalid) { throw new InvalidCode("ui.profileCode.invalid"); }
    }

    private static JsonElement defaultValue(Setting<?> setting) {
        Object value = setting.defaultValue;
        if (value instanceof Boolean flag) return new JsonPrimitive(flag);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof double[] range) { JsonArray array = new JsonArray(); for (double v : range) array.add(v); return array; }
        return JsonNull.INSTANCE;
    }
    private static JsonElement validate(Setting<?> setting, JsonElement raw) throws InvalidCode {
        if (setting instanceof BoolSetting) return new JsonPrimitive(bool(raw));
        if (setting instanceof NumberSetting number) return new JsonPrimitive(number(raw, number.min, number.max));
        if (setting instanceof RangeSetting range) {
            if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 2) throw new InvalidCode("ui.profileCode.invalid");
            double low = number(raw.getAsJsonArray().get(0), range.min, range.max), high = number(raw.getAsJsonArray().get(1), range.min, range.max);
            if (low > high) throw new InvalidCode("ui.profileCode.invalid");
            JsonArray result = new JsonArray(); result.add(low); result.add(high); return result;
        }
        if (setting instanceof ModeSetting mode) {
            String value = string(raw); if (!mode.modes.contains(value)) throw new InvalidCode("ui.profileCode.incompatible"); return new JsonPrimitive(value);
        }
        if (setting instanceof TextSetting text) {
            String value = string(raw); if (value.length() > Math.min(text.maxLength, 4096) || !safeText(value)) throw new InvalidCode("ui.profileCode.privateContent");
            return new JsonPrimitive(value);
        }
        throw new InvalidCode("ui.profileCode.incompatible");
    }
    private static boolean safeText(String value) {
        if (value.codePoints().anyMatch(cp -> Character.isISOControl(cp))) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return !value.matches("(?s).*[A-Za-z]:[\\\\/].*") && !value.startsWith("\\\\") && !value.startsWith("/")
                && !lower.contains("://") && !lower.contains("api_key") && !lower.contains("apikey")
                && !lower.contains("bearer ") && !value.matches("(?s).*\\bsk-[A-Za-z0-9_-]{12,}.*");
    }
    private static JsonObject object(JsonElement value) throws InvalidCode {
        if (value == null || !value.isJsonObject()) throw new InvalidCode("ui.profileCode.invalid"); return value.getAsJsonObject();
    }
    private static String string(JsonElement value) throws InvalidCode {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new InvalidCode("ui.profileCode.invalid"); return value.getAsString();
    }
    private static boolean bool(JsonElement value) throws InvalidCode {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new InvalidCode("ui.profileCode.invalid"); return value.getAsBoolean();
    }
    private static double number(JsonElement value, double min, double max) throws InvalidCode {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new InvalidCode("ui.profileCode.invalid");
        double number = value.getAsDouble(); if (!Double.isFinite(number) || number < min || number > max) throw new InvalidCode("ui.profileCode.invalid"); return number;
    }
    private static int integer(JsonElement value) throws InvalidCode {
        double number = number(value, Integer.MIN_VALUE, Integer.MAX_VALUE); if (number != Math.rint(number)) throw new InvalidCode("ui.profileCode.invalid"); return (int) number;
    }
    private static int key(JsonElement value) throws InvalidCode {
        int key = integer(value); if (key != -1 && !(key >= 0 && key <= 348) && !(key >= KeyNames.MOUSE_OFFSET && key < KeyNames.MOUSE_OFFSET + 8)) throw new InvalidCode("ui.profileCode.invalid"); return key;
    }
    private static void only(JsonObject object, Set<String> allowed) throws InvalidCode {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new InvalidCode("ui.profileCode.incompatible");
    }
    private static String digest(byte[] packed) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packed)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Strict bounded parser also rejects duplicate fields instead of accepting the last value. */
    private static JsonElement readJson(JsonReader reader, int depth, int[] nodes) throws IOException, InvalidCode {
        if (depth > 12 || ++nodes[0] > 20_000) throw new InvalidCode("ui.profileCode.tooLarge");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object = new JsonObject();
                while (reader.hasNext()) { String name = reader.nextName(); if (name.length() > 64 || object.has(name)) throw new InvalidCode("ui.profileCode.invalid"); object.add(name, readJson(reader, depth + 1, nodes)); }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array = new JsonArray();
                while (reader.hasNext()) { if (array.size() >= 2) throw new InvalidCode("ui.profileCode.invalid"); array.add(readJson(reader, depth + 1, nodes)); }
                reader.endArray(); yield array;
            }
            case STRING -> { String value = reader.nextString(); if (value.length() > 4096) throw new InvalidCode("ui.profileCode.tooLarge"); yield new JsonPrimitive(value); }
            case NUMBER -> { String value = reader.nextString(); if (value.length() > 48) throw new InvalidCode("ui.profileCode.invalid"); yield new JsonPrimitive(new BigDecimal(value)); }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            default -> throw new InvalidCode("ui.profileCode.invalid");
        };
    }
}
