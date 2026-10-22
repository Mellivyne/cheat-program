package com.cheatprogram.core.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class TextSetting extends Setting<String> {
	public final int maxLength;

	public TextSetting(String id, String defaultValue, int maxLength) {
		super(id, defaultValue);
		this.maxLength = maxLength;
	}

	@Override
	public void set(String v) {
		super.set(v.length() > maxLength ? v.substring(0, maxLength) : v);
	}

	@Override
	public String summary() {
		return value;
	}

	@Override
	public JsonElement save() {
		return new JsonPrimitive(value);
	}

	@Override
	public void load(JsonElement element) {
		if (element != null && element.isJsonPrimitive()) value = element.getAsString();
	}
}
