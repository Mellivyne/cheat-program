package com.cheatprogram.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Friends {
	private static final List<String> NAMES = new ArrayList<>();

	private Friends() {
	}

	public static List<String> all() {
		return NAMES;
	}

	public static boolean add(String name) {
		name = name.trim();
		if (name.isEmpty() || contains(name)) return false;
		NAMES.add(name);
		Config.markDirty();
		return true;
	}

	public static void remove(String name) {
		NAMES.removeIf(n -> n.equalsIgnoreCase(name));
		Config.markDirty();
	}

	public static boolean contains(String name) {
		for (String n : NAMES) if (n.equalsIgnoreCase(name)) return true;
		return false;
	}

	public static boolean isFriend(Entity entity) {
		return entity instanceof Player p && contains(p.getGameProfile().getName());
	}

	static void set(List<String> names) {
		NAMES.clear();
		for (String n : names) if (!n.isBlank() && !contains(n)) NAMES.add(n.trim());
		NAMES.sort((a, b) -> a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT)));
	}
}
