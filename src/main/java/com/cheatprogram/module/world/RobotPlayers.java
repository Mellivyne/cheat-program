package com.cheatprogram.module.world;

import com.cheatprogram.ai.BotPlayers;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;

/** A manager for explicitly requested, non-AI actors in the current local world. */
public final class RobotPlayers extends Module {
	public final TextSetting count = add(new TextSetting("count", "5", 256));
	public final TextSetting prefix = add(new TextSetting("prefix", "Robot", 64));
	public final BoolSetting randomId = add(new BoolSetting("randomId", false));
	public final ModeSetting mode = add(new ModeSetting("mode", "bot_survival", "bot_survival", "bot_creative", "bot_adventure", "bot_spectator"));
	public final ModeSetting behavior = add(new ModeSetting("behavior", "bot_stand", "bot_stand", "bot_follow"));
	public final NumberSetting spacing = add(new NumberSetting("spacing", 2, 1, 8, .5));
	public final NumberSetting spawnPerTick = add(new NumberSetting("spawnPerTick", 2, 1, 4, 1));
	public final NumberSetting followDistance = add(new NumberSetting("followDistance", 3, 1.5, 12, .5));
	public final BoolSetting autoRespawn = add(new BoolSetting("autoRespawn", false));

	public RobotPlayers() { super("robotplayers", Category.WORLD); prefix.visibleWhen(() -> !randomId.on()); }
	@Override public boolean isOneShot() { return true; }
	@Override public String suffix() { return Integer.toString(BotPlayers.activeCount()); }
	@Override public void onDisable() { BotPlayers.removeAll(); }
	public void spawnBatch() {
		if (!isEnabled()) setEnabled(true);
		BotPlayers.spawnBatch(count.get(), prefix.get(), mode.get(), behavior.get(), spacing.val(), spawnPerTick.intVal(), followDistance.val(), autoRespawn.on(), randomId.on());
	}
	public void applySettings() { BotPlayers.applyOrdinary(behavior.get(), followDistance.val()); }
}
