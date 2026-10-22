package com.cheatprogram.core.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting<Boolean> {
	public BoolSetting(String id, boolean defaultValue) {
		super(id, defaultValue);
	}

	public boolean on() {
		return value;
	}

	public void toggle() {
		set(!value);
	}

	@Override
	public String summary() {
		return value ? name() : "";
	}

	@Override
	public JsonElement save() {
		return new JsonPrimitive(value);
	}

	@Override
	public void load(JsonElement element) {
		if (element != null && element.isJsonPrimitive()) value = element.getAsBoolean();
	}
}
