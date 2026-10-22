package com.cheatprogram.hud;

import com.cheatprogram.core.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Positions of draggable HUD elements, stored as fractions of the screen. An element whose
 * anchor lies near the horizontal centre is centred on it; otherwise an anchor in the right
 * (or bottom) half aligns the element by its right (or bottom) edge.
 */
public final class HudLayout {
	public static final List<String> IDS = List.of("island", "arraylist", "keystrokes", "info", "targethud", "sounddirections", "networktrends");
	/** Anchors within this distance of the screen centre centre the element. */
	public static final float CENTER_BAND = 0.12f;
	private static final Map<String, float[]> DEFAULTS = new LinkedHashMap<>();
	private static final Map<String, float[]> POSITIONS = new HashMap<>();
	private static final Map<String, float[]> BOUNDS = new HashMap<>();
	private static final Map<String, Long> SEEN = new HashMap<>();

	static {
		DEFAULTS.put("island", new float[]{0.5f, 0.010f});
		DEFAULTS.put("arraylist", new float[]{0.994f, 0.010f});
		DEFAULTS.put("keystrokes", new float[]{0.008f, 0.26f});
		DEFAULTS.put("info", new float[]{0.008f, 0.47f});
		DEFAULTS.put("targethud", new float[]{0.56f, 0.56f});
		DEFAULTS.put("sounddirections", new float[]{.01f,.64f});
		DEFAULTS.put("networktrends", new float[]{.99f,.72f});
	}

	private HudLayout() {
	}

	public static float[] fraction(String id) {
		float[] p = POSITIONS.get(id);
		return p != null ? p : DEFAULTS.get(id);
	}

	public static void set(String id, float fx, float fy) {
		POSITIONS.put(id, new float[]{Math.max(0, Math.min(1, fx)), Math.max(0, Math.min(1, fy))});
		Config.markDirty();
	}

	public static void reset() {
		POSITIONS.clear();
		Config.markDirty();
	}

	public static boolean centered(String id) {
		return Math.abs(fraction(id)[0] - 0.5f) < CENTER_BAND;
	}

	public static boolean alignRight(String id) {
		return fraction(id)[0] > 0.5f && !centered(id);
	}

	public static boolean alignBottom(String id) {
		return fraction(id)[1] > 0.5f;
	}

	/** Top-left x of an element of the given width. */
	public static float x(String id, float width, float screenW) {
		float fx = fraction(id)[0];
		float x;
		if (centered(id)) x = fx * screenW - width / 2f;
		else x = fx * screenW - (fx > 0.5f ? width : 0);
		return Math.max(2, Math.min(screenW - width - 2, x));
	}

	/** The list's side is explicit; legacy and dragged anchors retain their distance from that edge. */
	public static float arraylistX(float width, float screenW, boolean right) {
		float saved = fraction("arraylist")[0];
		float inset = Math.min(saved, 1 - saved) * screenW;
		float x = right ? screenW - inset - width : inset;
		return Math.max(2, Math.min(screenW - width - 2, x));
	}

	public static float y(String id, float height, float screenH) {
		float fy = fraction(id)[1];
		float y = fy * screenH - (fy > 0.5f ? height : 0);
		return Math.max(2, Math.min(screenH - height - 2, y));
	}

	/** Elements report where they were drawn so the editor can hit-test them. */
	public static void report(String id, float x, float y, float w, float h) {
		BOUNDS.put(id, new float[]{x, y, w, h});
		SEEN.put(id, System.currentTimeMillis());
	}

	/** Bounds drawn during the last frames, or null if the element is not visible. */
	public static float[] bounds(String id) {
		Long seen = SEEN.get(id);
		if (seen == null || System.currentTimeMillis() - seen > 250) return null;
		return BOUNDS.get(id);
	}

	public static JsonElement save() {
		JsonObject root = new JsonObject();
		for (Map.Entry<String, float[]> e : POSITIONS.entrySet()) {
			JsonArray a = new JsonArray();
			a.add(e.getValue()[0]);
			a.add(e.getValue()[1]);
			root.add(e.getKey(), a);
		}
		return root;
	}

	public static void load(JsonObject root) {
		POSITIONS.clear();
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			if (!DEFAULTS.containsKey(e.getKey()) || !e.getValue().isJsonArray()) continue;
			JsonArray a = e.getValue().getAsJsonArray();
			if (a.size() == 2) POSITIONS.put(e.getKey(), new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat()});
		}
	}
}
