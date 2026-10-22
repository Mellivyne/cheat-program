package com.cheatprogram.core.setting;

import com.cheatprogram.core.Lang;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.List;

/** One choice out of a fixed list. Mode ids are translated through Lang as "mode.id". */
public class ModeSetting extends Setting<String> {
	public final List<String> modes;

	public ModeSetting(String id, String defaultValue, String... modes) {
		super(id, defaultValue);
		this.modes = Arrays.asList(modes);
	}

	public boolean is(String mode) {
		return value.equals(mode);
	}

	public String display() {
		return Lang.mode(value);
	}

	@Override
	public String summary() {
		return display();
	}

	@Override
	public JsonElement save() {
		return new JsonPrimitive(value);
	}

	@Override
	public void load(JsonElement element) {
		if (element != null && element.isJsonPrimitive() && modes.contains(element.getAsString())) value = element.getAsString();
	}
}
