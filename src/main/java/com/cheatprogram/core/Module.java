package com.cheatprogram.core;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.setting.Setting;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.Render3D;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

public abstract class Module {
	protected static final Minecraft mc = Minecraft.getInstance();

	public final String id;
	public final Category category;
	public final int defaultKey;
	private final List<Setting<?>> settings = new ArrayList<>();
	private boolean enabled;
	private int key;
	private boolean holdBind;
	private boolean favorite;
	private boolean drawn = true;

	/** Slide-in progress of this module's entry in the HUD array list. */
	public float listAnim;

	protected Module(String id, Category category) {
		this(id, category, -1);
	}

	protected Module(String id, Category category, int defaultKey) {
		this.id = id;
		this.category = category;
		this.defaultKey = defaultKey;
		this.key = defaultKey;
	}

	protected <S extends Setting<?>> S add(S setting) {
		setting.owner = this;
		settings.add(setting);
		return setting;
	}

	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	public String name() {
		return Lang.t("module." + id);
	}

	public String description() {
		return Lang.t("module." + id + ".desc");
	}

	/** Text appended in the HUD array list (e.g. the active mode). */
	public String suffix() {
		return "";
	}

	/** Comma separated summary of the visible settings, shown in the module row like "40% Chance, 3.0-3.1 Range". */
	public String summary() {
		StringJoiner joiner = new StringJoiner(", ");
		for (Setting<?> s : settings) {
			if (!s.isVisible()) continue;
			String part = s.summary();
			if (part != null && !part.isEmpty()) joiner.add(part);
		}
		return joiner.toString();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public void setEnabled(boolean value) {
		if (value && Config.restartRequired()) return;
		if (enabled == value) return;
		enabled = value;
		Config.markDirty();
		try {
			if (value) onEnable();
			else onDisable();
		} catch (Throwable t) {
			CheatProgram.LOGGER.error("Module {} failed to {}", id, value ? "enable" : "disable", t);
		}
	}

	/** Sets the flag without running enable/disable hooks; used while loading a profile at startup. */
	void setEnabledSilently(boolean value) {
		enabled = value;
	}

	/** One-shot modules run an action when switched on and immediately turn themselves off again. */
	public boolean isOneShot() {
		return false;
	}

	public int getKey() {
		return key;
	}

	public void setKey(int key) {
		this.key = key;
		Config.markDirty();
	}

	public boolean isHoldBind() {
		return holdBind;
	}

	public void setHoldBind(boolean holdBind) {
		this.holdBind = holdBind;
		Config.markDirty();
	}

	public boolean isFavorite() {
		return favorite;
	}

	public void setFavorite(boolean favorite) {
		this.favorite = favorite;
		Config.markDirty();
	}

	public boolean isDrawn() {
		return drawn;
	}

	public void setDrawn(boolean drawn) {
		this.drawn = drawn;
		Config.markDirty();
	}

	protected static boolean inGame() {
		return mc.player != null && mc.level != null;
	}

	public void onEnable() {
	}

	public void onDisable() {
	}

	/** Called at the start of every client tick while enabled and in a world. */
	public void onTick() {
	}

	/** Called at the end of every client tick while enabled and in a world. */
	public void onPostTick() {
	}

	public void onRender2D(HudContext hud) {
	}

	public void onRender3D(Render3D r3d) {
	}

	/** @return true to cancel the outgoing packet */
	public boolean onPacketSend(Packet<?> packet) {
		return false;
	}

	/** Called before the local player attacks an entity. @return true to cancel the attack */
	public boolean onAttack(Entity target) {
		return false;
	}

	public void onPostAttack(Entity target) {
	}
}
