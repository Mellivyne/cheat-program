package com.cheatprogram.core;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.ai.AiController;
import com.cheatprogram.ai.AiPlayers;
import com.cheatprogram.ai.BotPlayers;
import com.cheatprogram.ai.voice.SpeechVoices;
import com.cheatprogram.gui.ClickGuiScreen;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.LoginWelcome;
import com.cheatprogram.hud.IslandEvents;
import com.cheatprogram.util.PersonalInfo;
import com.cheatprogram.render.PersonalAvatar;

import com.cheatprogram.module.inventory.*;
import com.cheatprogram.module.legit.*;

import com.cheatprogram.module.utility.*;
import com.cheatprogram.module.visual.*;
import com.cheatprogram.module.world.*;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.render.ControlPanelTextures;
import com.cheatprogram.util.KeyNames;
import com.cheatprogram.util.InventoryAutomation;
import com.cheatprogram.util.InventoryPoses;
import com.cheatprogram.util.CapeTextures;
import com.cheatprogram.util.ChatHistoryStore;
import com.cheatprogram.util.ContainerIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ModuleManager {
	private static final List<Module> MODULES = new ArrayList<>();
	private static final Map<Class<?>, Module> BY_CLASS = new HashMap<>();
	private static final Map<String, Module> BY_ID = new HashMap<>();

	private ModuleManager() {
	}

	public static void init() {
		// Visual
		register(new Personal(), new Hud(), new ControlPanel(), new PanelTransitions(), new AppCast(), new SpatialAudio(), new FloatingSubtitles(), new ParticleEffects(), new WeaponAnimations(), new WearableWeapons(), new ModelWear(), new Watermark(), new SelfName(), new MotionTrails(), new WorldColors(), new DroppedItems3D(), new CustomCape(), new BlueArchiveHalo(), new TargetOrbit(), new ProjectileTrajectory(), new DamageEffects(), new Minimap(), new Esp(), new Tracers(), new EntityLines(), new Freecam(), new StorageEsp(), new OreXray(), new Fullbright(), new NoHurtCam(), new TargetHud());
		register(new BuildingBlueprints(), new RobotPlayers(), new TrainingRobots(), new ChatRepeat(), new ChatTracker());
		register(new AutoArmor(), new InvCleaner(), new ChestStealer());
		register(new Keystrokes(), new InfoPanel(), new RearViewMirror(), new NetworkTrends(), new TimeChanger(), new Zoom(), new SoundDirections(), new DeathRecovery());
	}

	private static void register(Module... modules) {
		for (Module m : modules) {
			MODULES.add(m);
			BY_ID.put(m.id, m);
			BY_CLASS.put(m.getClass(), m);
		}
	}

	/** Modules switched on for a brand new install. */
	static void applyFirstRunDefaults() {
		((Module) get(Hud.class)).setEnabledSilently(true);
	}

	public static List<Module> all() {
		return Collections.unmodifiableList(MODULES);
	}

	@SuppressWarnings("unchecked")
	public static <T extends Module> T get(Class<T> type) {
		return (T) BY_CLASS.get(type);
	}

	public static boolean on(Class<? extends Module> type) {
		Module m = BY_CLASS.get(type);
		return m != null && m.isEnabled();
	}

	public static Module byId(String id) {
		return BY_ID.get(id);
	}

	public static List<Module> byCategory(Category category) {
		List<Module> out = new ArrayList<>();
		for (Module m : MODULES) {
			if (category == Category.FAVORITES ? m.isFavorite() : true) out.add(m);
		}
		return out;
	}

	public static int enabledCount(Category category) {
		int n = 0;
		for (Module m : MODULES) if ((category != Category.FAVORITES || m.isFavorite()) && m.isEnabled() && !m.isOneShot()) n++;
		return n;
	}

	// ---------------------------------------------------------------- hooks

	private static void safely(Module m, String what, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			CheatProgram.LOGGER.error("Module {} failed during {}", m.id, what, t);
		}
	}

	public static void onTick() {
		if(Config.restartRequired())return;
		Minecraft mc = Minecraft.getInstance();
		InventoryAutomation.beginTick();
		if (Config.firstRun) {
			// ClientSettings has already selected the device language before config loading.
			// Persist that default without replacing a choice made before the first tick.
			Config.firstRun = false;
			Config.markDirty();
		}
		Config.tick();
		try{AppCast.checkLifecycleAll();SpatialAudio.lifecycle();FloatingSubtitles captions=get(FloatingSubtitles.class);if(captions!=null)captions.checkLifecycle();ParticleEffects particles=get(ParticleEffects.class);if(particles!=null)particles.checkLifecycle();}
		catch(Throwable error){CheatProgram.LOGGER.error("Projection / particle lifecycle failed",error);}
		try{com.cheatprogram.render.WorldControlPanel.lifecycle();}
		catch(Throwable error){CheatProgram.LOGGER.error("World control panel lifecycle failed",error);}
		try { PersonalAvatar.tickLifecycle(); PersonalInfo.tickLifecycle(); LoginWelcome.tickLifecycle(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("Personal profile lifecycle failed", t); }
		try { IslandEvents.tickLifecycle(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("Island event lifecycle failed", t); }
		try{com.cheatprogram.hud.TitleIntro.tickLifecycle();}
		catch(Throwable t){CheatProgram.LOGGER.error("Title intro lifecycle failed",t);}
		OreXray oreXray=get(OreXray.class);
		if(oreXray!=null)safely(oreXray,"lifecycle",oreXray::checkLifecycle);
		try { ControlPanelTextures.onTick(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("Panel background failed during tick", t); }
		try { ChatHistoryStore.tick(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("Chat history failed during tick", t); }
		try { ContainerIndex.tick(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("Container index failed during tick", t); }
		Freecam.tickLifecycle();

		try { InventoryPoses.tickLifecycle(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("Inventory pose lifecycle failed", t); }

		DeathRecovery recovery=get(DeathRecovery.class);if(recovery!=null)safely(recovery,"lifecycle",recovery::checkLifecycle);
		MotionTrails trails=get(MotionTrails.class);if(trails!=null)safely(trails,"lifecycle",trails::checkLifecycle);
		if (mc.player != null && mc.level != null) {
			for (Module m : MODULES) if (m.isEnabled()) safely(m, "tick", m::onTick);
		}
		try { AiController.onTick(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("AI failed during tick", t); }
		try { AiPlayers.onTick(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("AI players failed during tick", t); }
		try { SpeechVoices.tickLifecycle(); }
		catch(Throwable t) { CheatProgram.LOGGER.error("Local voice lifecycle failed",t); }
		try { BotPlayers.onTick(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("Robot players failed during tick", t); }
		try { CapeTextures.onTick(); }
		catch (Throwable t) { CheatProgram.LOGGER.error("Cape media failed during tick", t); }

		BuildingBlueprints buildings = get(BuildingBlueprints.class);
		if (buildings != null) safely(buildings, "lifecycle", buildings::checkLifecycle);

		ChatRepeat chatRepeat = get(ChatRepeat.class);
		if (chatRepeat != null) safely(chatRepeat, "lifecycle", chatRepeat::checkLifecycle);

		ChestStealer chestStealer = get(ChestStealer.class);
		if (chestStealer != null) safely(chestStealer, "lifecycle", ChestStealer::checkLifecycle);
		DamageEffects damageEffects=get(DamageEffects.class);
		if(damageEffects!=null)safely(damageEffects,"lifecycle",damageEffects::checkLifecycle);
		DroppedItems3D droppedItems=get(DroppedItems3D.class);
		if(droppedItems!=null)safely(droppedItems,"lifecycle",droppedItems::checkLifecycle);
		Minimap minimap=get(Minimap.class);
		if(minimap!=null)safely(minimap,"lifecycle",minimap::checkLifecycle);

	}

	public static void onPostTick() {
		if(Config.restartRequired())return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		for (Module m : MODULES) if (m.isEnabled()) safely(m, "post tick", m::onPostTick);
		InventoryAutomation.finishTick();
	}

	public static void onRender2D(HudContext hud) {
		if(Config.restartRequired())return;
		for (Module m : MODULES) if (m.isEnabled()) safely(m, "render2d", () -> m.onRender2D(hud));
	}

	public static void onRender3D(Render3D r3d) {
		if(Config.restartRequired())return;
		for (Module m : MODULES) if (m.isEnabled()) safely(m, "render3d", () -> m.onRender3D(r3d));

		try{com.cheatprogram.render.WorldControlPanel.render(r3d);}
		catch(Throwable error){CheatProgram.LOGGER.error("World control panel rendering failed",error);}
	}

	public static boolean onPacketSend(Packet<?> packet) {
		boolean cancel = false;
		for (Module m : MODULES) {
			if (!m.isEnabled()) continue;
			try {
				cancel |= m.onPacketSend(packet);
			} catch (Throwable t) {
				CheatProgram.LOGGER.error("Module {} failed handling a packet", m.id, t);
			}
		}
		return cancel;
	}

	public static boolean onAttack(Entity target) {
		boolean cancel = false;
		for (Module m : MODULES) {
			if (!m.isEnabled()) continue;
			try {
				cancel |= m.onAttack(target);
			} catch (Throwable t) {
				CheatProgram.LOGGER.error("Module {} failed handling an attack", m.id, t);
			}
		}
		if (!cancel) IslandEvents.attacked(target);
		return cancel;
	}

	public static void onPostAttack(Entity target) {
		for (Module m : MODULES) if (m.isEnabled()) safely(m, "post attack", () -> m.onPostAttack(target));
	}

	/** Key or mouse button input while no screen is open. */
	public static void onInput(int code, int action) {
		if(Config.restartRequired())return;
		Minecraft mc = Minecraft.getInstance();
		if (action == GLFW.GLFW_REPEAT) return;
		boolean press = action == GLFW.GLFW_PRESS;
		if (press && code == ClientSettings.INSTANCE.guiKey && !KeyNames.isMouse(code)) {
			if(com.cheatprogram.render.WorldControlPanel.ordinaryOpen())return;
			mc.gui.setScreen(new ClickGuiScreen());
			return;
		}
		if (!ClientSettings.INSTANCE.keybinds.on() || code < 0) return;
		for (Module m : MODULES) {
			if (m.getKey() != code) continue;
			if (m.isHoldBind()) {
				if (press != m.isEnabled()) {m.setEnabled(press);if(!m.isOneShot())Notifications.moduleToggled(m);}
			} else if (press) {
				m.toggle();
				if (!m.isOneShot()) Notifications.moduleToggled(m);
			}
		}
	}

	/** Turns every module off (used by Panic). */
	public static void disableAll() {
		for (Module m : MODULES) if (m.isEnabled() && !(m instanceof Hud)) m.setEnabled(false);
	}
}
