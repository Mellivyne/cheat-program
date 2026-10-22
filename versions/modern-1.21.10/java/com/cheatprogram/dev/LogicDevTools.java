package com.cheatprogram.dev;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.Setting;
import com.cheatprogram.gui.HudEditorScreen;
import com.cheatprogram.module.combat.KillAura;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.module.world.Scaffold;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Real input, placement packets and server damage checks. Only entered by -Dcheatprogram.dev=logic. */
final class LogicDevTools {
	private static final String WORLD = "cp_logic_dev";
	private static final boolean SCAFFOLD_SMOKE = "logic-scaffold-smoke".equals(System.getProperty("cheatprogram.dev", ""));
	private static final int FLOOR = 80;
	private static final int WALK_TICKS = 120;
	private static final Map<Setting<?>, JsonElement> SAVED_SETTINGS = new LinkedHashMap<>();
	private static final Map<Module, ModuleState> SAVED_MODULES = new LinkedHashMap<>();
	private static final List<String> FAILURES = new ArrayList<>();
	private static final List<UUID> TEST_ENTITIES = new ArrayList<>();
	private static int stage;
	private static int wait;
	private static int elapsed;
	private static int scaffoldCase;
	private static int auraCase;
	private static int hundredCase;
	private static int passed;
	private static int screenInterruptions;
	private static long started;
	private static double startX;
	private static double startZ;
	private static double minY;
	private static double maxY;
	private static double maxVerticalSpeed;
	private static boolean openedContainer;
	private static boolean selectedRejectedTarget;
	private static boolean changedLockedTarget;
	private static boolean screenTargetCleared;
	private static UUID alternateId;
	private static CompletableFuture<Probe> guardBaseline;
	private static double refillStartZ;
	private static UUID targetId;
	private static CompletableFuture<UUID> preparation;
	private static CompletableFuture<Probe> probe;
	private static JsonElement savedLayout;
	private static String savedProfile;
	private static TutorialSteps savedTutorial;
	private static boolean savedPause;
	private static boolean savedHideGui;
	private static boolean savedAutoJump;
	private static final List<Boolean> SAVED_KEYS = new ArrayList<>();

	private record ModuleState(boolean enabled, int key, boolean hold) { }
	private record Probe(double x, double y, double z, int bridge, int blocks, boolean supported,
			float health, boolean dead, float alternateHealth, float selfHealth) { }

	/** A server-only NPC: it has real player damage semantics, without a login or connection. */
	private static final class TestPlayer extends Player {
		private TestPlayer(ServerLevel level, String name, double z) {
			super(level, new GameProfile(UUID.randomUUID(), name));
			setNoGravity(true);
			getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
			snapTo(0.5, FLOOR + 1, z, 180, 0);
			addTag("cp_logic_fixture");
		}

		@Override public net.minecraft.world.level.GameType gameMode() { return net.minecraft.world.level.GameType.SURVIVAL; }
		@Override public boolean isSpectator() { return false; }
		@Override public boolean isCreative() { return false; }
		@Override public boolean broadcastToPlayer(ServerPlayer player) { return false; }
	}

	private LogicDevTools() { }

	static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (stage == 99) return;
		if (started == 0) started = System.currentTimeMillis();
		try {
			if (System.currentTimeMillis() - started > 300_000) {
				throw new IllegalStateException("Timed out at stage " + stage + ", Scaffold case " + scaffoldCase + ", aura case " + auraCase);
			}
			if (wait > 0) { wait--; return; }
			step(mc);
		} catch (Throwable error) {
			FAILURES.add("Automation exception at stage " + stage + ": " + error);
			CheatProgram.LOGGER.error("DEV LOGIC exception at stage {}", stage, error);
			finish(mc);
		}
	}

	private static void step(Minecraft mc) {
		switch (stage) {
			case 0 -> {
				if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
				snapshot(mc);
				mc.options.tutorialStep = TutorialSteps.NONE;
				mc.options.pauseOnLostFocus = false;
				mc.options.hideGui = false;
				mc.options.autoJump().set(false);
				// Switch off existing gameplay hooks before loading the isolated development world.
				for (Module module : ModuleManager.all()) module.setEnabled(false);
				ClientSettings.INSTANCE.activeProfile = "logic_dev";
				ClientSettings.INSTANCE.language.set("zh");
				ClientSettings.INSTANCE.autoSave.set(false);
				setMovement(mc, false, false);
				CheatProgram.LOGGER.info("DEV LOGIC starting {} checks in isolated flat world {}", SCAFFOLD_SMOKE ? "Scaffold smoke" : "full real-input", WORLD);
				stage = 1;
				wait = 20;
			}
			case 1 -> {
				if (mc.getLevelSource().levelExists(WORLD)) {
					mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
				} else {
					LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL,
							true, new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261012L, false, false),
							WorldPresets::createFlatWorldDimensions, new TitleScreen());
				}
				stage = 2;
			}
			case 2 -> {
				if (mc.player == null || mc.level == null || mc.screen != null) return;
				requireWorld(mc);
				ModuleManager.get(Hud.class).setEnabled(true);
				configureScaffold();
				if (SCAFFOLD_SMOKE) checkLabel("module scaffold", ModuleManager.get(Scaffold.class).name());
				else checkChineseLabels();
				stage = 3;
				wait = 40;
			}
			case 3 -> {
				ModuleManager.get(Scaffold.class).setEnabled(false);
				setMovement(mc, false, false);
				if (scaffoldCase >= 6) { stage = 20; return; }
				preparation = prepareScaffold(mc, scaffoldCase);
				stage = 4;
			}
			case 4 -> {
				if (!preparation.isDone()) return;
				preparation.join();
				stage = 5;
				wait = 20; // Wait for teleport, inventory and chunk/block update packets to arrive.
			}
			case 5 -> {
				check("Scaffold scenario " + scaffoldCase + " starts at synchronized platform", Math.abs(mc.player.getY() - (FLOOR + 1)) < 0.15
						&& mc.level.getBlockState(new BlockPos(0, FLOOR, -1)).is(Blocks.STONE));
				mc.player.setYRot(0);
				mc.player.setXRot(0); // Test the old look-down gate with the ordinary forwards view.
				mc.options.autoJump().set(scaffoldCase == 3);
				mc.player.setSprinting(false);
				startX = mc.player.getX();
				startZ = mc.player.getZ();
				minY = mc.player.getY();
				maxY = minY;
				maxVerticalSpeed = 0;
				openedContainer = false;
				elapsed = 0;
				ModuleManager.get(Scaffold.class).setEnabled(true);
				set(ModuleManager.get(Scaffold.class), "tower", scaffoldCase == 5);
				setMovement(mc, scaffoldCase != 5, scaffoldCase == 1);
				mc.options.keyJump.setDown(scaffoldCase == 5);
				stage = 6;
			}
			case 6 -> {
				observeWalk(mc);
				if (++elapsed < (scaffoldCase == 5 ? 80 : WALK_TICKS) && !openedContainer) return;
				setMovement(mc, false, false);
				elapsed = 0;
				stage = 12;
			}
			case 7 -> {
				if (!probe.isDone()) return;
				Probe result = probe.join();
				logProbe("Scaffold " + scaffoldCase, result);
				check("Scaffold " + scaffoldCase + " never drops below the bridge", minY >= FLOOR + 0.8 && result.y >= FLOOR + 0.8);
				if (SCAFFOLD_SMOKE) check("Scaffold " + scaffoldCase + " rests on real server support", result.supported);
				check("Scaffold " + scaffoldCase + " does not open an interactive support", !openedContainer);
				if (scaffoldCase == 2) {
					check("empty inventory stays on real server support", result.blocks == 0 && result.supported);
					check("small stock really created a bridge before exhaustion", result.bridge >= 1);
					refillStartZ = result.z;
					preparation = supplyBlocks(mc, 64);
					stage = 8;
				} else if (scaffoldCase == 5) {
					check("Tower input creates a real vertical pillar", result.y > FLOOR + 3 && result.bridge > 1 && result.blocks < 64);
					check("Tower finishes on server-confirmed solid support", result.supported);
					check("Tower uses bounded vanilla vertical movement", maxVerticalSpeed <= 0.65 && maxY > FLOOR + 3);
					CheatProgram.LOGGER.info("DEV LOGIC TOWER: peakY={} maxVerticalSpeed={}", maxY, maxVerticalSpeed);
					scaffoldCase++;
					stage = 3;
				} else {
					check("Scaffold " + scaffoldCase + " continuously advances with blocks", result.z - startZ > 8 && result.bridge >= 8 && result.blocks < 64);
					if (scaffoldCase == 1) check("diagonal bridging advances on both axes", result.x - startX > 5 && result.z - startZ > 5);
					if (scaffoldCase == 4) check("container-first scene has a real bridge beyond the container", result.bridge >= 8);
					if (SCAFFOLD_SMOKE) {
						if (scaffoldCase == 0) { scaffoldCase = 4; stage = 3; }
						else stage = 30;
					} else {
						scaffoldCase++;
						stage = 3;
					}
				}
			}
			case 8 -> {
				if (!preparation.isDone()) return;
				preparation.join();
				stage = 9;
				wait = 20;
			}
			case 9 -> {
				ModuleManager.get(Scaffold.class).setEnabled(true);
				elapsed = 0;
				setMovement(mc, true, false);
				stage = 10;
			}
			case 10 -> {
				observeWalk(mc);
				if (++elapsed < WALK_TICKS && !openedContainer) return;
				setMovement(mc, false, false);
				elapsed = 0;
				stage = 13;
			}
			case 11 -> {
				if (!probe.isDone()) return;
				Probe result = probe.join();
				logProbe("Scaffold refill", result);
				check("replenishing blocks resumes real movement and placement", result.z - refillStartZ > 8 && result.blocks < 64 && result.bridge >= 8);
				check("refill does not release edge protection too early", minY >= FLOOR + 0.8 && result.y >= FLOOR + 0.8);
				scaffoldCase++;
				stage = 3;
			}
			case 12, 13 -> {
				// Keep Scaffold's edge guard while real movement inertia settles and is sampled.
				minY = Math.min(minY, mc.player.getY());
				if (++elapsed < (scaffoldCase == 5 ? 15 : 10)) return;
				probe = sampleServer(mc);
				stage = stage == 12 ? 7 : 11;
			}
			case 20 -> {
				if (auraCase >= 7) { stage = 40; return; }
				setMovement(mc, false, false);
				ModuleManager.get(KillAura.class).setEnabled(false);
				configureAura();
				if (auraCase == 6) set(ModuleManager.get(KillAura.class), "rotate", true);
				preparation = prepareAura(mc, auraCase);
				stage = 21;
			}
			case 21 -> {
				if (!preparation.isDone()) return;
				targetId = preparation.join();
				stage = 22;
				wait = 25;
			}
			case 22 -> {
				check("aura scenario " + auraCase + " has synchronized server entity", clientEntityVisible(mc, targetId));
				mc.player.setYRot(0);
				if (auraCase == 6) mc.player.setYRot(179);
				mc.player.setXRot(0);
				mc.player.getInventory().setSelectedSlot(0);
				elapsed = 0;
				selectedRejectedTarget = false;
				changedLockedTarget = false;
				screenTargetCleared = false;
				guardBaseline = null;
				ModuleManager.get(KillAura.class).setEnabled(true);
				stage = 23;
			}
			case 23 -> {
				if ((auraCase == 1 || auraCase == 2) && KillAura.target != null && KillAura.target.getUUID().equals(targetId)) selectedRejectedTarget = true;
				if (auraCase == 4) {
					if (elapsed == 10) moveAlternateCloser(mc);
					if (elapsed >= 15 && (KillAura.target == null || !KillAura.target.getUUID().equals(targetId))) changedLockedTarget = true;
				}
				if (auraCase == 5) {
					if (elapsed == 12) mc.setScreen(new HudEditorScreen(null));
					if (elapsed == 15) {
						screenTargetCleared = KillAura.target == null;
						check("opening a screen clears the aura target", screenTargetCleared);
					}
					if (elapsed == 20) guardBaseline = sampleServer(mc);
				}
				if (auraCase == 6 && elapsed == 1) {
					float yawChange = Mth.wrapDegrees(mc.player.getYRot() - 179);
					check("render rotation crosses the yaw boundary on the short path", yawChange > 0.01f && yawChange < 12);
					check("render rotation changes pitch gradually", mc.player.getXRot() > 0 && mc.player.getXRot() < 20);
					CheatProgram.LOGGER.info("DEV LOGIC ROTATION first observed tick: yawChange={} pitch={}", yawChange, mc.player.getXRot());
				}
				if (++elapsed < 70) return;
				probe = sampleServer(mc);
				stage = 24;
			}
			case 24 -> {
				if (!probe.isDone()) return;
				Probe result = probe.join();
				logProbe("KillAura " + auraCase, result);
				if (auraCase == 0) {
					check("KillAura damages an attackable mob on the server", result.health < 20);
				} else if (auraCase == 1) {
					check("wall-blocked target is never selected or damaged", !selectedRejectedTarget && result.health == 20);
				} else if (auraCase == 2) {
					check("out-of-range target is never selected or damaged", !selectedRejectedTarget && result.health == 20);
				} else if (auraCase == 3) {
					check("attacks can kill a real server target", result.dead);
					check("a dead target is released", KillAura.target == null || !KillAura.target.getUUID().equals(targetId));
				} else if (auraCase == 4) {
					check("a valid locked target stays selected when another moves closer", !changedLockedTarget);
					check("locked target still receives real server damage", result.health < 200);
				} else if (auraCase == 5) {
					check("an open screen keeps the target cleared", screenTargetCleared && KillAura.target == null);
					check("an open screen stops real server attacks", guardBaseline != null && guardBaseline.isDone()
							&& result.health == guardBaseline.join().health);
					mc.setScreen(null);
				} else {
					check("smooth rotation settles near the target across +/-180", Math.abs(Mth.wrapDegrees(mc.player.getYRot() - -170f)) < 4);
				}
				ModuleManager.get(KillAura.class).setEnabled(false);
				auraCase++;
				stage = 20;
			}
			case 30 -> finish(mc);
			case 40 -> {
				if (hundredCase >= 7) { stage = 30; return; }
				Module module = hundredAura();
				module.setEnabled(false);
				set(module, "mode", hundredCase == 0 ? "aura_hostiles" : hundredCase == 3 || hundredCase == 5 ? "aura_players"
						: hundredCase == 4 || hundredCase == 6 ? "aura_named" : "aura_living");
				set(module, "range", 100);
				set(module, "cooldown", true);
				set(module, "players", "NotAPlayer_Logic");
				preparation = prepareHundredAura(mc, hundredCase);
				stage = 41;
			}
			case 41 -> {
				if (!preparation.isDone()) return;
				targetId = preparation.join();
				stage = 42;
				wait = 20;
			}
			case 42 -> {
				hundredAura().setEnabled(true);
				check("hundred-metre aura keeps the ordinary aura disabled", !ModuleManager.on(KillAura.class));
				elapsed = 0;
				stage = 43;
			}
			case 43 -> {
				if (++elapsed < 70) return;
				probe = sampleServer(mc);
				stage = 44;
			}
			case 44 -> {
				if (!probe.isDone()) return;
				Probe result = probe.join();
				logProbe("HundredMeterAura " + hundredCase, result);
				check("hundred-metre mode " + hundredCase + " never attacks the actual local player", result.selfHealth == 20);
				if (hundredCase == 0) {
					check("hostile mode damages a real hostile mob 95 metres away", result.health < 20);
					check("hostile mode leaves the nearer passive mob unharmed", result.alternateHealth == 20);
				} else if (hundredCase == 1) {
					check("living mode damages a passive mob 95 metres away", result.health < 20);
				} else if (hundredCase == 2) {
					check("a mob beyond 100 metres stays unharmed", result.health == 20);
				} else if (hundredCase == 3) {
					check("players mode does not attack non-player living entities", result.health == 20);
				} else if (hundredCase == 4) {
					check("a missing player name does not match an identically named mob", result.health == 20);
				} else if (hundredCase == 5) {
					check("players mode damages a real Player NPC 95 metres away", result.health < 20);
				} else {
					check("named mode damages the matching Player NPC", result.health < 20);
					check("named mode leaves a nearer nonmatching Player NPC unharmed", result.alternateHealth == 20);
				}
				hundredAura().setEnabled(false);
				hundredCase++;
				stage = 40;
			}
			default -> { }
		}
	}

	private static void snapshot(Minecraft mc) {
		savedProfile = ClientSettings.INSTANCE.activeProfile;
		savedLayout = com.cheatprogram.hud.HudLayout.save().deepCopy();
		for (Setting<?> setting : ClientSettings.INSTANCE.all) SAVED_SETTINGS.put(setting, setting.save().deepCopy());
		for (Module module : ModuleManager.all()) {
			SAVED_MODULES.put(module, new ModuleState(module.isEnabled(), module.getKey(), module.isHoldBind()));
			for (Setting<?> setting : module.settings()) SAVED_SETTINGS.put(setting, setting.save().deepCopy());
		}
		savedTutorial = mc.options.tutorialStep;
		savedPause = mc.options.pauseOnLostFocus;
		savedHideGui = mc.options.hideGui;
		savedAutoJump = mc.options.autoJump().get();
		for (var key : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
				mc.options.keyJump, mc.options.keyShift, mc.options.keySprint)) SAVED_KEYS.add(key.isDown());
	}

	private static void configureScaffold() {
		Scaffold module = ModuleManager.get(Scaffold.class);
		set(module, "mode", "legit");
		set(module, "autoSwitch", true);
		set(module, "safeWalk", true);
		set(module, "tower", false);
	}

	private static void configureAura() {
		KillAura module = ModuleManager.get(KillAura.class);
		set(module, "range", 3.6);
		set(module, "cooldown", true);
		set(module, "fov", 360);
		set(module, "throughWalls", false);
		set(module, "rotate", false);
		ClientSettings.INSTANCE.targetPlayers.set(false);
		ClientSettings.INSTANCE.targetHostiles.set(false);
		ClientSettings.INSTANCE.targetPassives.set(true);
	}

	private static void set(Module module, String id, Object value) {
		for (Setting<?> setting : module.settings()) {
			if (!setting.id.equals(id)) continue;
			JsonElement json = value instanceof Boolean b ? new JsonPrimitive(b)
					: value instanceof Number n ? new JsonPrimitive(n) : new JsonPrimitive(value.toString());
			setting.load(json);
			return;
		}
		throw new IllegalArgumentException("Development scenario cannot find " + module.id + "." + id);
	}

	private static CompletableFuture<UUID> prepareScaffold(Minecraft mc, int scenario) {
		requireWorld(mc);
		UUID playerId = mc.player.getUUID();
		var server = mc.getSingleplayerServer();
		return server.submit(() -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("Development player is missing");
			clearScene(level);
			for (int x = -2; x <= 2; x++) for (int z = -3; z <= 0; z++) {
				level.setBlock(new BlockPos(x, FLOOR, z), Blocks.STONE.defaultBlockState(), 3);
			}
			if (scenario == 3) {
				for (int x = -2; x <= 2; x++) for (int z = -3; z <= 32; z++) {
					level.setBlock(new BlockPos(x, FLOOR + 3, z), Blocks.STONE.defaultBlockState(), 3);
				}
			}
			if (scenario == 4) {
				// The candidate below the first gap opens a chest; the north face is valid stone.
				level.setBlock(new BlockPos(0, FLOOR - 1, 1), Blocks.CHEST.defaultBlockState(), 3);
			}
			player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
			player.getFoodData().setFoodLevel(20);
			player.setHealth(player.getMaxHealth());
			player.setDeltaMovement(Vec3.ZERO);
			player.teleportTo(0.5, FLOOR + 1, -1.5);
			writeBlocks(player, scenario == 2 ? 2 : 64);
			return playerId;
		});
	}

	private static CompletableFuture<UUID> supplyBlocks(Minecraft mc, int blocks) {
		requireWorld(mc);
		UUID playerId = mc.player.getUUID();
		var server = mc.getSingleplayerServer();
		return server.submit(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("Development player is missing");
			writeBlocks(player, blocks);
			return playerId;
		});
	}

	private static void writeBlocks(ServerPlayer player, int blocks) {
		for (int slot = 0; slot < 9; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
		player.getInventory().setItem(40, ItemStack.EMPTY);
		player.getInventory().setSelectedSlot(0);
		player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, blocks));
		player.inventoryMenu.broadcastChanges();
	}

	private static CompletableFuture<UUID> prepareAura(Minecraft mc, int scenario) {
		requireWorld(mc);
		UUID playerId = mc.player.getUUID();
		var server = mc.getSingleplayerServer();
		return server.submit(() -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("Development player is missing");
			clearScene(level);
			for (int x = -4; x <= 4; x++) for (int z = -4; z <= 12; z++) {
				level.setBlock(new BlockPos(x, FLOOR, z), Blocks.STONE.defaultBlockState(), 3);
			}
			if (scenario == 1) for (int x = -3; x <= 3; x++) for (int y = FLOOR + 1; y <= FLOOR + 4; y++) {
				level.setBlock(new BlockPos(x, y, 1), Blocks.STONE.defaultBlockState(), 3);
			}
			player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
			player.setHealth(20);
			player.setDeltaMovement(Vec3.ZERO);
			player.teleportTo(0.5, FLOOR + 1, 0.5);
			writeBlocks(player, 1);
			player.getInventory().setItem(0, new ItemStack(Items.WOODEN_SWORD));
			player.inventoryMenu.broadcastChanges();
			var cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
			if (cow == null) throw new IllegalStateException("Could not create the attackable development mob");
			cow.setNoAi(true);
			cow.setNoGravity(true);
			cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(scenario == 4 || scenario == 6 ? 200 : 20);
			cow.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
			cow.setHealth(cow.getMaxHealth());
			cow.snapTo(scenario == 6 ? 0.88 : 0.5, FLOOR + 1, scenario == 6 ? -1.66 : scenario == 2 ? 8.5 : 2.7, 180, 0);
			if (scenario == 3) cow.setHealth(3);
			if (!level.addFreshEntity(cow)) throw new IllegalStateException("Could not spawn the development mob");
			TEST_ENTITIES.add(cow.getUUID());
			cow.addTag("cp_logic_fixture");
			alternateId = null;
			if (scenario == 4) {
				var alternate = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
				if (alternate == null) throw new IllegalStateException("Could not create the alternate target");
				alternate.setNoAi(true);
				alternate.setNoGravity(true);
				alternate.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
				alternate.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
				alternate.setHealth(200);
				alternate.snapTo(1.7, FLOOR + 1, 2.8, 180, 0);
				if (!level.addFreshEntity(alternate)) throw new IllegalStateException("Could not spawn the alternate target");
				alternateId = alternate.getUUID();
				TEST_ENTITIES.add(alternateId);
				alternate.addTag("cp_logic_fixture");
			}
			return cow.getUUID();
		});
	}

	private static void moveAlternateCloser(Minecraft mc) {
		requireWorld(mc);
		UUID id = alternateId;
		var server = mc.getSingleplayerServer();
		server.execute(() -> {
			var alternate = server.overworld().getEntity(id);
			if (alternate != null) alternate.teleportTo(0.5, FLOOR + 1, 1.8);
		});
	}

	private static Module hundredAura() {
		Module module = ModuleManager.byId("hundredmeteraura");
		if (module == null) throw new IllegalStateException("HundredMeterAura must be registered before running the logic suite");
		return module;
	}

	private static CompletableFuture<UUID> prepareHundredAura(Minecraft mc, int scenario) {
		requireWorld(mc);
		UUID playerId = mc.player.getUUID();
		var server = mc.getSingleplayerServer();
		return server.submit(() -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("Development player is missing");
			clearScene(level);
			server.setDifficulty(Difficulty.NORMAL, true);
			level.setDayTime(18000); // Hostiles must survive both peaceful removal and sunlight.
			for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
				level.setBlock(new BlockPos(x, FLOOR, z), Blocks.STONE.defaultBlockState(), 3);
			}
			player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
			player.setHealth(20);
			player.setDeltaMovement(Vec3.ZERO);
			player.teleportTo(0.5, FLOOR + 1, 0.5);
			writeBlocks(player, 1);
			player.getInventory().setItem(0, new ItemStack(Items.WOODEN_SWORD));
			player.inventoryMenu.broadcastChanges();
			double z = scenario == 2 ? 105.5 : 95.5;
			level.getChunk(0, ((int) z) >> 4); // Loading a test chunk creates no fake players or authentication state.
			if (scenario == 5 || scenario == 6) {
				var npc = new TestPlayer(level, "NotAPlayer_Logic", z);
				if (!level.addFreshEntity(npc)) throw new IllegalStateException("Could not register the server-only Player NPC");
				TEST_ENTITIES.add(npc.getUUID());
				alternateId = null;
				if (scenario == 6) {
					level.getChunk(0, 3);
					var other = new TestPlayer(level, "OtherPlayer_Logic", 60.5);
					if (!level.addFreshEntity(other)) throw new IllegalStateException("Could not register the nonmatching Player NPC");
					alternateId = other.getUUID();
					TEST_ENTITIES.add(alternateId);
				}
				return npc.getUUID();
			}
			Mob victim = scenario == 0 ? EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND)
					: EntityType.COW.create(level, EntitySpawnReason.COMMAND);
			if (victim == null) throw new IllegalStateException("Could not create the distant development target");
			configureDistantMob(victim, z);
			if (scenario == 4) victim.setCustomName(Component.literal("NotAPlayer_Logic"));
			if (!level.addFreshEntity(victim)) throw new IllegalStateException("Could not spawn the distant target");
			TEST_ENTITIES.add(victim.getUUID());
			alternateId = null;
			if (scenario == 0) {
				level.getChunk(0, 3);
				var passive = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
				if (passive == null) throw new IllegalStateException("Could not create the filtered passive target");
				configureDistantMob(passive, 60.5);
				if (!level.addFreshEntity(passive)) throw new IllegalStateException("Could not spawn the filtered passive target");
				alternateId = passive.getUUID();
				TEST_ENTITIES.add(alternateId);
			}
			return victim.getUUID();
		});
	}

	private static void configureDistantMob(Mob mob, double z) {
		mob.setNoAi(true);
		mob.setNoGravity(true);
		mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
		mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
		mob.setHealth(20);
		mob.snapTo(0.5, FLOOR + 1, z, 180, 0);
		mob.addTag("cp_logic_fixture");
	}

	private static void clearScene(ServerLevel level) {
		// This small arena only exists in cp_logic_dev. Repeated runs erase their earlier bridges.
		for (int x = -4; x <= 26; x++) for (int z = -5; z <= 36; z++) {
			for (int y = FLOOR - 1; y <= FLOOR + 4; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
		}
		for (int y = FLOOR + 5; y <= FLOOR + 24; y++) level.setBlock(new BlockPos(0, y, -2), Blocks.AIR.defaultBlockState(), 3);
		for (UUID id : TEST_ENTITIES) {
			var entity = level.getEntity(id);
			if (entity != null) entity.discard();
		}
		TEST_ENTITIES.clear();
		// Also remove fixtures saved by an earlier process, so repeated runs do not gain extra targets.
		var oldFixtures = new ArrayList<net.minecraft.world.entity.Entity>();
		for (var entity : level.getAllEntities()) if (entity.getTags().contains("cp_logic_fixture")) oldFixtures.add(entity);
		for (var entity : oldFixtures) entity.discard();
		level.setDayTime(1000);
		level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
	}

	private static CompletableFuture<Probe> sampleServer(Minecraft mc) {
		requireWorld(mc);
		UUID playerId = mc.player.getUUID();
		UUID entityId = targetId;
		UUID secondId = alternateId;
		boolean towerScenario = stage < 20 && scaffoldCase == 5;
		var server = mc.getSingleplayerServer();
		return server.submit(() -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("Development player is missing");
			int placed = 0;
			for (int x = -4; x <= 26; x++) for (int z = 1; z <= 36; z++) {
				if (level.getBlockState(new BlockPos(x, FLOOR, z)).is(Blocks.COBBLESTONE)) placed++;
			}
			if (towerScenario) for (int y = FLOOR + 1; y <= FLOOR + 24; y++) {
				if (level.getBlockState(new BlockPos(0, y, -2)).is(Blocks.COBBLESTONE)) placed++;
			}
			int blocks = 0;
			for (int slot = 0; slot < 9; slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (stack.is(Items.COBBLESTONE)) blocks += stack.getCount();
			}
			boolean support = !level.noCollision(player, player.getBoundingBox().move(0, -0.1, 0));
			var entity = entityId == null ? null : level.getEntity(entityId);
			float health = entity instanceof LivingEntity living ? living.getHealth() : 0;
			boolean dead = entity == null || !entity.isAlive();
			var second = secondId == null ? null : level.getEntity(secondId);
			float secondHealth = second instanceof LivingEntity living ? living.getHealth() : 0;
			return new Probe(player.getX(), player.getY(), player.getZ(), placed, blocks, support, health, dead, secondHealth, player.getHealth());
		});
	}

	private static void observeWalk(Minecraft mc) {
		minY = Math.min(minY, mc.player.getY());
		maxY = Math.max(maxY, mc.player.getY());
		maxVerticalSpeed = Math.max(maxVerticalSpeed, Math.abs(mc.player.getDeltaMovement().y));
		boolean interactiveMenu = mc.player.containerMenu != mc.player.inventoryMenu;
		if (mc.screen != null || interactiveMenu) {
			var screen = mc.screen;
			CheatProgram.LOGGER.warn("DEV LOGIC {} UI event: stage={} scenario={} elapsed={} screen={} title={} pauseScreen={} containerScreen={} menu={} id={} position={},{},{}",
					interactiveMenu ? "CONTAINER" : "INTERRUPTION", stage, scaffoldCase, elapsed,
					screen == null ? "<none>" : screen.getClass().getName(), screen == null ? "" : screen.getTitle().getString(),
					screen != null && screen.isPauseScreen(), screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>,
					mc.player.containerMenu.getClass().getName(), mc.player.containerMenu.containerId,
					mc.player.getX(), mc.player.getY(), mc.player.getZ());
			if (interactiveMenu) {
				openedContainer = true;
				mc.player.closeContainer();
			} else {
				screenInterruptions++;
			}
			mc.setScreen(null);
		}
		setMovement(mc, scaffoldCase != 5, scaffoldCase == 1);
		mc.options.keyJump.setDown(scaffoldCase == 5);
	}

	private static boolean clientEntityVisible(Minecraft mc, UUID id) {
		for (var entity : mc.level.entitiesForRendering()) if (entity.getUUID().equals(id)) return true;
		return false;
	}

	private static void setMovement(Minecraft mc, boolean forwards, boolean left) {
		mc.options.keyUp.setDown(forwards);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(left);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keyShift.setDown(false);
		mc.options.keySprint.setDown(false);
	}

	private static void checkChineseLabels() {
		ClientSettings.INSTANCE.language.set("zh");
		for (Module module : ModuleManager.all()) {
			checkLabel("module " + module.id, module.name());
			for (Setting<?> setting : module.settings()) checkSetting(module.id + "." + setting.id, setting);
		}
		for (Setting<?> setting : ClientSettings.INSTANCE.all) checkSetting("client." + setting.id, setting);
	}

	private static void checkSetting(String id, Setting<?> setting) {
		checkLabel("setting " + id, setting.name());
		if (setting instanceof ModeSetting mode) {
			String original = mode.get();
			for (String value : mode.modes) {
				mode.load(new JsonPrimitive(value));
				checkLabel("mode " + id + "=" + value, mode.display());
			}
			mode.load(new JsonPrimitive(original));
		}
	}

	private static void checkLabel(String id, String label) {
		boolean missingKey = label.matches("(?:module|setting|mode|ui|category)\\..*");
		boolean translated = label.codePoints().anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN);
		// Technical abbreviations and the English-language selector retain their conventional spelling.
		boolean abbreviation = label.matches("CPS|FPS|TPS|RGB|WASD|NBT|HUD|ESP|English|简体中文");
		check("Chinese " + id + " = " + label, !missingKey && (translated || abbreviation));
	}

	private static void requireWorld(Minecraft mc) {
		if (mc.getSingleplayerServer() == null || !WORLD.equals(mc.getSingleplayerServer().getWorldData().getLevelName())) {
			throw new IllegalStateException("Logic development mutations require the cp_logic_dev singleplayer world");
		}
	}

	private static void check(String message, boolean ok) {
		if (ok) passed++;
		else FAILURES.add(message);
		CheatProgram.LOGGER.info("DEV LOGIC CHECK {}: {}", ok ? "OK" : "FAIL", message);
	}

	private static void logProbe(String test, Probe sample) {
		CheatProgram.LOGGER.info("DEV LOGIC SERVER {}: position={},{},{} bridge={} remaining={} supported={} health={} dead={} alternateHealth={} selfHealth={}",
				test, sample.x, sample.y, sample.z, sample.bridge, sample.blocks, sample.supported, sample.health, sample.dead, sample.alternateHealth, sample.selfHealth);
	}

	private static void finish(Minecraft mc) {
		stage = 99;
		setMovement(mc, false, false);
		try {
			for (Module module : ModuleManager.all()) module.setEnabled(false);
			for (var entry : SAVED_SETTINGS.entrySet()) entry.getKey().load(entry.getValue());
			for (var entry : SAVED_MODULES.entrySet()) {
				Module module = entry.getKey();
				ModuleState state = entry.getValue();
				module.setKey(state.key);
				module.setHoldBind(state.hold);
				module.setEnabled(state.enabled);
			}
			if (savedProfile != null) {
				ClientSettings.INSTANCE.activeProfile = savedProfile;
				com.cheatprogram.hud.HudLayout.load(savedLayout.getAsJsonObject());
				Config.saveAll();
				mc.options.tutorialStep = savedTutorial;
				mc.options.pauseOnLostFocus = savedPause;
				mc.options.hideGui = savedHideGui;
				mc.options.autoJump().set(savedAutoJump);
				var keys = List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
						mc.options.keyJump, mc.options.keyShift, mc.options.keySprint);
				for (int i = 0; i < keys.size(); i++) keys.get(i).setDown(SAVED_KEYS.get(i));
			}
			Notifications.entries().clear();
		} catch (Throwable error) {
			FAILURES.add("Restoring configuration failed: " + error);
			CheatProgram.LOGGER.error("DEV LOGIC configuration restore failed", error);
		} finally {
			CheatProgram.LOGGER.info("DEV LOGIC SUMMARY: {} passed, {} failed, {} unrelated screen interruptions, elapsed={}ms", passed, FAILURES.size(), screenInterruptions, System.currentTimeMillis() - started);
			for (String failure : FAILURES) CheatProgram.LOGGER.error("DEV LOGIC FAILURE: {}", failure);
			mc.stop();
		}
	}
}
