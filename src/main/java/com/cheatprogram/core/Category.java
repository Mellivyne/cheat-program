package com.cheatprogram.core;

import com.cheatprogram.render.Icon;

public enum Category {
	FAVORITES("favorites", Icon.STAR),
	COMBAT("combat", Icon.SWORD),
	MOVEMENT("movement", Icon.NAVIGATION),
	VISUAL("visual", Icon.EYE),
	UTILITY("utility", Icon.WRENCH),
	WORLD("world", Icon.GLOBE),
	INVENTORY("inventory", Icon.BRIEFCASE),
	MACRO("macro", Icon.TERMINAL),
	LEGIT("legit", Icon.SHIELD);

	public final String id;
	public final Icon icon;

	Category(String id, Icon icon) {
		this.id = id;
		this.icon = icon;
	}

	public String displayName() {
		return Lang.t("category." + id);
	}
}
