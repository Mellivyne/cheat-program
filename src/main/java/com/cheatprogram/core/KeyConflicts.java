package com.cheatprogram.core;

import java.util.List;
import java.util.stream.Collectors;

/** Uses stored bindings rather than a second stale key map. */
public final class KeyConflicts {
	private KeyConflicts() { }
	public static List<Module> conflicts(Module module) {
		if (module == null || module.getKey() < 0) return List.of();
		return ModuleManager.all().stream().filter(other -> other != module && other.getKey() == module.getKey()).toList();
	}
	public static String description(Module module) {
		if (module == null || module.getKey() < 0) return "";
		String names = conflicts(module).stream().map(Module::name).collect(Collectors.joining("、"));
		if (module.getKey() == ClientSettings.INSTANCE.guiKey) names = names.isEmpty() ? Lang.t("ui.keyconflicts.gui") : names + "、" + Lang.t("ui.keyconflicts.gui");
		return names.isEmpty() ? "" : Lang.t("ui.keyconflicts.shared", names);
	}
	public static String guiDescription() {
		String names = ModuleManager.all().stream().filter(module -> module.getKey() >= 0 && module.getKey() == ClientSettings.INSTANCE.guiKey)
				.map(Module::name).collect(Collectors.joining("、"));
		return names.isEmpty() ? "" : Lang.t("ui.keyconflicts.shared", names);
	}
}
