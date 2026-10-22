package com.cheatprogram.core.setting;

import com.cheatprogram.core.Lang;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.concurrent.ThreadLocalRandom;

/** A min-max pair, shown as a slider with two handles. */
public class RangeSetting extends Setting<double[]> {
	public final double min;
	public final double max;
	public final double step;
	public final String unit;

	public RangeSetting(String id, double low, double high, double min, double max, double step, String unit) {
		super(id, new double[]{low, high});
		this.value = new double[]{low, high};
		this.min = min;
		this.max = max;
		this.step = step;
		this.unit = unit == null ? "" : unit;
	}

	public double low() {
		return value[0];
	}

	public double high() {
		return value[1];
	}

	public void setLow(double v) {
		v = NumberSetting.clampStep(v, min, max, step);
		set(new double[]{Math.min(v, value[1]), value[1]});
	}

	public void setHigh(double v) {
		v = NumberSetting.clampStep(v, min, max, step);
		set(new double[]{value[0], Math.max(v, value[0])});
	}

	/** A uniformly random value inside the range. */
	public double random() {
		if (value[1] <= value[0]) return value[0];
		return ThreadLocalRandom.current().nextDouble(value[0], value[1]);
	}

	public double norm(double v) {
		return max == min ? 0 : (v - min) / (max - min);
	}

	public double denorm(double t) {
		return min + (max - min) * Math.max(0, Math.min(1, t));
	}

	public String format() {
		String a = NumberSetting.formatNumber(value[0], step);
		String b = NumberSetting.formatNumber(value[1], step);
		return (a.equals(b) ? a : a + "-" + b) + Lang.unit(unit);
	}

	@Override
	public void reset() {
		set(new double[]{defaultValue[0], defaultValue[1]});
	}

	@Override
	public String summary() {
		return Lang.summary(format(), name());
	}

	@Override
	public JsonElement save() {
		JsonArray a = new JsonArray();
		a.add(value[0]);
		a.add(value[1]);
		return a;
	}

	@Override
	public void load(JsonElement element) {
		if (element != null && element.isJsonArray() && element.getAsJsonArray().size() == 2) {
			double lo = NumberSetting.clampStep(element.getAsJsonArray().get(0).getAsDouble(), min, max, step);
			double hi = NumberSetting.clampStep(element.getAsJsonArray().get(1).getAsDouble(), min, max, step);
			value = new double[]{Math.min(lo, hi), Math.max(lo, hi)};
		}
	}
}
