package com.cheatprogram.core.setting;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
	public final String id;
	public final T defaultValue;
	protected T value;
	/** Owning module, or null for client-wide settings. */
	public Module owner;
	private BooleanSupplier visibility = () -> true;

	protected Setting(String id, T defaultValue) {
		this.id = id;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
		Config.markDirty();
	}

	public void reset() {
		set(defaultValue);
	}

	/** Only show this setting while the condition holds (e.g. a mode-specific option). */
	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
		this.visibility = condition;
		return (S) this;
	}

	public boolean isVisible() {
		return visibility.getAsBoolean();
	}

	public String name() {
		return owner == null ? Lang.t("ui." + id) : Lang.setting(owner.id, id);
	}

	/** Short text shown in the module row, e.g. "40% Chance". Empty when nothing should be shown. */
	public abstract String summary();

	public abstract JsonElement save();

	public abstract void load(JsonElement element);
}
