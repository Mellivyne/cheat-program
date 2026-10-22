package com.cheatprogram.core.setting;

import com.cheatprogram.core.Lang;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public class NumberSetting extends Setting<Double> {
	public final double min;
	public final double max;
	public final double step;
	public final String unit;

	public NumberSetting(String id, double defaultValue, double min, double max, double step, String unit) {
		super(id, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
		this.unit = unit == null ? "" : unit;
	}

	public NumberSetting(String id, double defaultValue, double min, double max, double step) {
		this(id, defaultValue, min, max, step, "");
	}

	public double val() {
		return value;
	}

	public float floatVal() {
		return value.floatValue();
	}

	public int intVal() {
		return (int) Math.round(value);
	}

	/** Value mapped to 0..1 for percentages. */
	public double fraction() {
		return value / 100.0;
	}

	@Override
	public void set(Double v) {
		super.set(clampStep(v, min, max, step));
	}

	public void setNormalized(double t) {
		set(min + (max - min) * Math.max(0, Math.min(1, t)));
	}

	public double normalized() {
		return max == min ? 0 : (value - min) / (max - min);
	}

	public String format() {
		return formatNumber(value, step) + Lang.unit(unit);
	}

	@Override
	public String summary() {
		return Lang.summary(format(), name());
	}

	@Override
	public JsonElement save() {
		return new JsonPrimitive(value);
	}

	@Override
	public void load(JsonElement element) {
		if (element != null && element.isJsonPrimitive()) value = clampStep(element.getAsDouble(), min, max, step);
	}

	static double clampStep(double v, double min, double max, double step) {
		v = Math.max(min, Math.min(max, v));
		if (step > 0) {
			v = min + Math.round((v - min) / step) * step;
			v = Math.max(min, Math.min(max, v));
			v = Math.round(v * 1e6) / 1e6;
		}
		return v;
	}

	public static String formatNumber(double v, double step) {
		if (step >= 1 || Math.abs(v - Math.round(v)) < 1e-6 && step >= 0.5) return Long.toString(Math.round(v));
		int decimals = step >= 0.1 ? 1 : 2;
		return String.format(Locale.ROOT, "%." + decimals + "f", v);
	}
}
