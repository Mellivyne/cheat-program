package com.cheatprogram.core;

import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.Setting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.util.DeviceLanguage;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Client-wide options that live outside of profiles (appearance, language, targets, GUI bind). */
public final class ClientSettings {
	public static ClientSettings INSTANCE;

	public final List<Setting<?>> all = new ArrayList<>();

	// General
	public final ModeSetting language = add(new ModeSetting("language", DeviceLanguage.system(), DeviceLanguage.SUPPORTED.toArray(String[]::new)) {
		@Override public void set(String value) { super.set(DeviceLanguage.resolve(value)); }
		@Override public void load(com.google.gson.JsonElement element) {
			// Missing fields retain the first-use device default; explicit unknown values use English.
			if(element==null)return;
			value=element.isJsonPrimitive()&&element.getAsJsonPrimitive().isString()
					?DeviceLanguage.resolve(element.getAsString()):"en";
		}
	});
	public final ModeSetting fontMode = add(new ModeSetting("fontMode", "smooth", "smooth", "vanilla", "custom"));
	public final TextSetting fontFamily = add(new TextSetting("fontFamily", "", 256));
	public final TextSetting fontPath = add(new TextSetting("fontPath", "", 4096));
	public final BoolSetting keybinds = add(new BoolSetting("keybinds", true));
	public final BoolSetting autoSave = add(new BoolSetting("autoSave", true));
	public final BoolSetting notifications = add(new BoolSetting("notifications", true));
	public final BoolSetting tooltips = add(new BoolSetting("tooltips", true));

	// Liquid glass appearance
	public final NumberSetting transparency = add(new NumberSetting("transparency", 90, 0, 100, 1, "%"));
	public final NumberSetting blur = add(new NumberSetting("blur", 8, 0, 100, 1, "%"));
	public final NumberSetting frost = add(new NumberSetting("frost", 0, 0, 100, 1, "%"));
	public final NumberSetting refraction = add(new NumberSetting("refraction", 85, 0, 100, 1, "%"));
	public final NumberSetting highlight = add(new NumberSetting("highlight", 50, 0, 100, 1, "%"));
	public final NumberSetting desaturate = add(new NumberSetting("desaturate", 10, 0, 100, 1, "%"));
	public final NumberSetting uiScale = add(new NumberSetting("uiScale", 100, 70, 150, 5, "%"));
	public final NumberSetting animSpeed = add(new NumberSetting("animSpeed", 100, 50, 200, 10, "%"));

	// Targets shared by combat modules
	public final BoolSetting targetPlayers = add(new BoolSetting("targetPlayers", true));
	public final BoolSetting targetHostiles = add(new BoolSetting("targetHostiles", true));
	public final BoolSetting targetPassives = add(new BoolSetting("targetPassives", false));
	public final BoolSetting targetInvisible = add(new BoolSetting("targetInvisible", true));
	public final BoolSetting ignoreFriends = add(new BoolSetting("ignoreFriends", true));

	public int guiKey = GLFW.GLFW_KEY_RIGHT_SHIFT;
	public String activeProfile = "default";

	private <S extends Setting<?>> S add(S setting) {
		all.add(setting);
		return setting;
	}

	public Setting<?> byId(String id) {
		for (Setting<?> s : all) if (s.id.equals(id)) return s;
		return null;
	}

	public void resetAppearance() {
		fontMode.reset();
		fontFamily.reset();
		fontPath.reset();
		transparency.reset();
		blur.reset();
		frost.reset();
		refraction.reset();
		highlight.reset();
		desaturate.reset();
		uiScale.reset();
		animSpeed.reset();
	}
}
