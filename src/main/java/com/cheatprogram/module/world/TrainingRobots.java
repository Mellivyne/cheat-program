package com.cheatprogram.module.world;

import com.cheatprogram.ai.BotPlayers;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;

/** Own settings and cohort: no operation here modifies ordinary robots. */
public final class TrainingRobots extends Module {
	public final TextSetting count = add(new TextSetting("count", "2", 256));
	public final TextSetting prefix = add(new TextSetting("prefix", "Training", 64));
	public final BoolSetting randomId = add(new BoolSetting("randomId", false));
	public final ModeSetting mode = add(new ModeSetting("mode", "bot_survival", "bot_survival", "bot_adventure"));
	public final BoolSetting trainingEnabled = add(new BoolSetting("trainingEnabled", true));
	public final ModeSetting behavior = add(new ModeSetting("behavior", "bot_duel", "bot_duel", "bot_strafe", "bot_guard"));
	public final NumberSetting spacing = add(new NumberSetting("spacing", 2, 1, 8, .5));
	public final NumberSetting spawnPerTick = add(new NumberSetting("spawnPerTick", 2, 1, 4, 1));
	public final BoolSetting autoRespawn = add(new BoolSetting("autoRespawn", true));
	public final ModeSetting trainingTarget = add(new ModeSetting("trainingTarget", "bot_target_all", "bot_target_all", "bot_target_owner", "bot_target_robots"));
	private final NumberSetting targetBehaviorVersion=add(new NumberSetting("targetBehaviorVersion",2,2,2,0)).visibleWhen(()->false);
	public final ModeSetting trainingKit = add(new ModeSetting("trainingKit", "bot_kit_iron", "bot_kit_none", "bot_kit_wood", "bot_kit_iron", "bot_kit_diamond", "bot_kit_netherite"));
	public final NumberSetting trainingRadius = add(new NumberSetting("trainingRadius", 2.3, 1, 3, .1));
	public final NumberSetting trainingSpeed = add(new NumberSetting("trainingSpeed", 75, 10, 100, 5, "%"));
	public final NumberSetting attackInterval = add(new NumberSetting("attackInterval", 12, 2, 100, 1, "ticks"));
	public final BoolSetting attackCooldown = add(new BoolSetting("attackCooldown", true));
	public final BoolSetting trainingShield = add(new BoolSetting("trainingShield", true));
	public final ModeSetting shieldMode = add(new ModeSetting("shieldMode", "bot_shield_smart", "bot_shield_smart", "bot_shield_rhythm", "bot_shield_only")).visibleWhen(trainingShield::on);
	public final NumberSetting shieldUptime = add(new NumberSetting("shieldUptime", 35, 10, 80, 5, "%")).visibleWhen(() -> trainingShield.on() && !shieldMode.is("bot_shield_only"));
	private BotPlayers.Training appliedTraining;
	public TrainingRobots() { super("trainingrobots", Category.WORLD); prefix.visibleWhen(() -> !randomId.on()); }
	@Override public boolean isOneShot() { return true; }
	@Override public String suffix() { return Integer.toString(BotPlayers.activeCount(BotPlayers.Group.TRAINING)); }
	@Override public void onEnable() { appliedTraining = null; }
	@Override public void onDisable() { BotPlayers.removeAll(BotPlayers.Group.TRAINING); }
	@Override public void onTick() {
		// Target/behavior controls affect existing actors too, rather than looking changed in the
		// page while the live actor silently keeps its previous immutable options snapshot.
		if (!training().equals(appliedTraining)) applyTraining();
	}
	public void spawnBatch() {
		if (!isEnabled()) setEnabled(true);
		BotPlayers.spawnTrainingBatch(count.get(), prefix.get(), mode.get(), spacing.val(), spawnPerTick.intVal(), autoRespawn.on(), randomId.on(), training());
	}
	/** Explicit manager button only; keep the user's configured batch size unchanged. */
	public void spawnOne() {
		if (!isEnabled()) setEnabled(true);
		BotPlayers.spawnTrainingBatch("1", prefix.get(), mode.get(), spacing.val(), spawnPerTick.intVal(), autoRespawn.on(), randomId.on(), training());
	}
	public void chooseTarget(String target) {
		trainingTarget.set(target); trainingEnabled.set(true); applyTraining();
	}
	public BotPlayers.Training training() {
		return new BotPlayers.Training(trainingEnabled.on(), behavior.get(), trainingTarget.get(), trainingKit.get(), trainingRadius.val(), trainingSpeed.fraction(),
				attackInterval.intVal(), attackCooldown.on(), trainingShield.on(), shieldUptime.fraction(), shieldMode.get());
	}
	public void applyTraining() { appliedTraining = training(); BotPlayers.applyTraining(appliedTraining); }
}
