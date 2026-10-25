package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Loads the selected original character only; bounded image sampling and archive I/O stay off the render thread. */
public final class HaloModelLibrary {
	private static final AtomicLong GENERATION = new AtomicLong();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Cheat Program halo resources"); thread.setDaemon(true); return thread;
	});
	private static final int MAX_TEXTURE_EDGE = 2048;
	private static final long MAX_CHARACTER_PIXELS = 16L * 1024 * 1024;
	private static volatile Loaded current;
	private static String requested = "";
	private static volatile String status = "ui.halo.notLoaded";
	private record Loaded(String character, Model model, Map<String, ResourceLocation> textures, Map<String, HaloVolumeMesh.Sample> silhouettes) { }
	private static final Map<String,String> CHARACTERS = Map.of(
			"hoshino", "abydos", "shiroko", "abydos", "yuuka", "millennium", "hina", "gehenna", "azusa", "trinity",
			"arisu", "millennium", "wakamo", "hyakkiyako", "mika", "trinity", "hihumi", "trinity", "serika", "abydos");
	public record Model(Vector3f offset, Vector3f scale, Motion animation, List<Node> layers) { }
	public record Node(Vector3f position, Vector3f rotation, Vector3f scale, Motion animation, List<Primitive> primitives, List<Node> children) { }
	public record Primitive(String type, String texture, String innerTexture, float width, float height, int segments, boolean faceCamera) { }
	public record Function(boolean sinusoidal, double start, double speed, double amplitude, double omega, double phase) {
		public float at(double time) { return (float)(sinusoidal ? amplitude * Math.sin(omega * time + phase) : start + speed * time); }
	}
	public record Motion(List<Function> x, List<Function> y, List<Function> z, List<Function> pitch, List<Function> yaw, List<Function> roll,
			List<Function> glow) {
		public static float sum(List<Function> functions, double time) { float v = 0; for (var f : functions) v += f.at(time); return v; }
		public float brightness(double time) { return glow.isEmpty() ? 1 : Math.max(0, Math.min(2, sum(glow, time))); }
	}
	private HaloModelLibrary() { }
	public static Path archive() { return FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/halo/blue-archive-models.zip"); }
	public static String statusKey() { return status; }
	public static Model model(String character) {
		request(character, false);
		Loaded loaded = current;
		return loaded != null && loaded.character().equals(character) ? loaded.model() : null;
	}
	public static ResourceLocation texture(String name) { Loaded loaded = current; return loaded == null ? null : loaded.textures().get(name); }
	static HaloVolumeMesh.Sample silhouette(String name) { Loaded loaded = current; return loaded == null ? null : loaded.silhouettes().get(name); }
	public static void initialize() { request("hoshino", false); }
	/** A failed character retries only when explicitly enabled again, never once per rendered frame. */
	public static void retry(String character) { request(character, true); }
	private static synchronized void request(String character, boolean retry) {
		if (!CHARACTERS.containsKey(character) || !retry && requested.equals(character)) return;
		requested = character;
		long token = GENERATION.incrementAndGet();
		if (current != null && current.character().equals(character)) { status = "ui.halo.ready"; return; }
		status = "ui.halo.loading";
		IO.execute(() -> loadCharacter(character, token));
	}
	private static void check(long token) { if (GENERATION.get() != token) throw new CancellationException(); }
	private static void loadCharacter(String character, long token) {
		Map<String, NativeImage> decoded = new LinkedHashMap<>();
		Map<String, HaloVolumeMesh.Sample> silhouettes = new LinkedHashMap<>();
		try {
			check(token);
			Path archive = archive();
			if (!Files.isRegularFile(archive)) throw new IOException("External halo archive is missing");
			Model model;
			try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
				Map<String, Boolean> needed = new LinkedHashMap<>();
				String path = "assets/" + CHARACTERS.get(character) + "/halo_definitions/" + character + ".json";
				var entry = zip.getEntry(path);
				if (entry == null || entry.getSize() > 8 * 1024 * 1024) throw new IOException("Halo definition unavailable: " + character);
				JsonObject definition;
				try (var input = zip.getInputStream(entry)) {
					byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
					if (bytes.length > 8 * 1024 * 1024) throw new IOException("Halo definition too large");
					definition = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
				}
				JsonObject positioning = object(definition, "positioning");
				List<Node> nodes = new ArrayList<>();
				for (var element : array(definition, "layers")) if (element.isJsonObject()) nodes.add(node(element.getAsJsonObject(), needed, 0));
				model = new Model(vector(positioning.get("offset"), 0), vector(positioning.get("scale"), 1),
						motion(object(definition, "animation")), List.copyOf(nodes));
				Map<String, ZipEntry> entries = new LinkedHashMap<>();
				Map<String, Integer> sampling = new LinkedHashMap<>();
				long pixels = 0;
				for (String name : needed.keySet()) {
					check(token);
					ResourceLocation source = ResourceLocation.tryParse(name);
					if (source == null || source.getPath().contains("..")) throw new IOException("Invalid halo texture");
					ZipEntry texture = zip.getEntry("assets/" + source.getNamespace() + "/" + source.getPath());
					if (texture == null) throw new IOException("Halo texture unavailable: " + name);
					var size = HaloTextureDecoder.inspect(zip, texture);
					int step = Math.max(1, (Math.max(size.width(), size.height()) + MAX_TEXTURE_EDGE - 1) / MAX_TEXTURE_EDGE);
					entries.put(name, texture); sampling.put(name, step);
					pixels += (long)((size.width() + step - 1) / step) * ((size.height() + step - 1) / step);
				}
				// Size constraints choose sampling quality; they must not reject the bundled high-resolution assets.
				int budgetStep = Math.max(1, (int)Math.ceil(Math.sqrt((double)pixels / MAX_CHARACTER_PIXELS)));
				for (var texture : entries.entrySet()) {
					check(token);
					NativeImage image = HaloTextureDecoder.decode(zip, texture.getValue(), sampling.get(texture.getKey()) * budgetStep);
					decoded.put(texture.getKey(), image);
					silhouettes.put(texture.getKey(), HaloVolumeMesh.sample(image));
				}
			}
			check(token);
			Minecraft.getInstance().execute(() -> publish(character, model, decoded, silhouettes, token));
		} catch (CancellationException ignored) { closeDecoded(decoded); }
		catch (Throwable error) {
			closeDecoded(decoded);
			if (GENERATION.get() == token) {
				status = "ui.halo.failed";
				CheatProgram.LOGGER.error("Unable to load Blue Archive halo character {}", character, error);
			}
		}
	}
	private static void closeDecoded(Map<String, NativeImage> decoded) { for (NativeImage image : decoded.values()) if (image != null) image.close(); }
	private static void publish(String character, Model model, Map<String, NativeImage> decoded, Map<String, HaloVolumeMesh.Sample> silhouettes, long token) {
		Map<String, ResourceLocation> registered = new LinkedHashMap<>();
		try {
			check(token);
			int index = 0;
			for (var entry : decoded.entrySet()) {
				ResourceLocation location = new ResourceLocation("cheatprogram", "dynamic/blue_archive_halo/" + token + "/" + index++);
				DynamicTexture texture = new DynamicTexture(entry.getValue());
				entry.setValue(null); // DynamicTexture owns this NativeImage from this point onward.
				try {
					texture.setFilter(true, false);
					Minecraft.getInstance().getTextureManager().register(location, texture);
				} catch (Throwable error) { texture.close(); throw error; }
				registered.put(entry.getKey(), location);
			}
			Loaded previous = current;
			current = new Loaded(character, model, Map.copyOf(registered), Map.copyOf(silhouettes)); status = "ui.halo.ready";
			if (previous != null) for (var location : previous.textures().values()) Minecraft.getInstance().getTextureManager().release(location);
		} catch (Throwable error) {
			closeDecoded(decoded);
			for (var location : registered.values()) Minecraft.getInstance().getTextureManager().release(location);
			if (GENERATION.get() == token) {
				status = "ui.halo.failed";
				CheatProgram.LOGGER.error("Unable to upload Blue Archive halo character {}", character, error);
			}
		}
	}
	private static Node node(JsonObject json, Map<String, Boolean> needed, int depth) throws IOException {
		if (depth > 32) throw new IOException("Halo definition nested too deeply");
		List<Primitive> primitives = new ArrayList<>();
		if (json.has("primitive") && json.get("primitive").isJsonObject()) primitives.add(primitive(json.getAsJsonObject("primitive"), needed));
		for (var element : array(json, "primitives")) if (element.isJsonObject()) primitives.add(primitive(element.getAsJsonObject(), needed));
		List<Node> children = new ArrayList<>();
		for (var element : array(json, "children")) if (element.isJsonObject()) children.add(node(element.getAsJsonObject(), needed, depth + 1));
		return new Node(vector(json.get("position"), 0), vector(json.get("rotation"), 0), vector(json.get("scale"), 1),
				motion(object(json, "animation")), List.copyOf(primitives), List.copyOf(children));
	}
	private static Primitive primitive(JsonObject json, Map<String, Boolean> needed) throws IOException {
		String type = text(json, "type", "billboard");
		if (!type.equals("billboard") && !type.equals("ring")) throw new IOException("Unsupported halo primitive: " + type);
		String texture = text(json, "texture", "");
		if (texture.isEmpty()) throw new IOException("Halo primitive has no texture");
		String inner = text(json, "inner_texture", texture);
		needed.put(texture, true); if (type.equals("ring")) needed.put(inner, true);
		Vector3f size = vector(json.get("size"), 1);
		return new Primitive(type, texture, inner, Math.abs(size.x), Math.abs(size.y),
				Math.max(16, Math.min(512, (int)number(json.get("segments"), 64))), json.has("face_camera") && json.get("face_camera").getAsBoolean());
	}
	private static Motion motion(JsonObject json) {
		JsonObject offset = object(json, "offset"), rotation = object(json, "rotation");
		return new Motion(functions(offset.get("x")), functions(offset.get("y")), functions(offset.get("z")),
				functions(rotation.get("pitch")), functions(rotation.get("yaw")), functions(rotation.get("roll")), functions(json.get("glow")));
	}
	private static List<Function> functions(JsonElement value) {
		if (value == null || value.isJsonNull()) return List.of();
		List<Function> out = new ArrayList<>();
		JsonArray functions = new JsonArray();
		if (value.isJsonArray()) functions = value.getAsJsonArray(); else functions.add(value);
		for (var element : functions) {
			if (element.isJsonPrimitive()) out.add(new Function(false, number(element, 0), 0, 0, 0, 0));
			else if (element.isJsonObject()) {
				JsonObject f = element.getAsJsonObject();
				out.add(new Function(text(f, "function", "linear").equals("sin"), number(f.get("start"), 0), number(f.get("speed"), 0),
						number(f.get("A"), 0), number(f.get("omega"), 1), number(f.get("phi"), 0)));
			}
		}
		return List.copyOf(out);
	}
	private static Vector3f vector(JsonElement element, float fallback) {
		if (element == null || element.isJsonNull()) return new Vector3f(fallback);
		if (element.isJsonArray()) {
			JsonArray v = element.getAsJsonArray();
			return new Vector3f((float)(v.size() > 0 ? number(v.get(0), fallback) : fallback),
					(float)(v.size() > 1 ? number(v.get(1), fallback) : fallback), (float)(v.size() > 2 ? number(v.get(2), fallback) : fallback));
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			String text = element.getAsString().replace('[', ' ').replace(']', ' ').trim();
			String[] parts = text.split("[,\\s]+");
			if (parts.length >= 2) return new Vector3f(parse(parts[0], fallback), parse(parts[1], fallback), parts.length > 2 ? parse(parts[2], fallback) : fallback);
		}
		return new Vector3f((float)number(element, fallback));
	}
	private static float parse(String value, float fallback) { try { float number = Float.parseFloat(value); return Float.isFinite(number) ? number : fallback; } catch (RuntimeException ignored) { return fallback; } }
	private static double number(JsonElement value, double fallback) { try { double number = value.getAsDouble(); return Double.isFinite(number) ? number : fallback; } catch (RuntimeException ignored) { return fallback; } }
	private static String text(JsonObject json, String key, String fallback) { return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback; }
	private static JsonObject object(JsonObject json, String key) { return json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : new JsonObject(); }
	private static JsonArray array(JsonObject json, String key) { return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray(); }
}
