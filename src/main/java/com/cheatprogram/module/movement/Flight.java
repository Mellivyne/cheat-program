package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import org.lwjgl.glfw.GLFW;

/** Client flight physics; the server's original permissions remain separately tracked. */
public final class Flight extends Module {
	private final NumberSetting speed = add(new NumberSetting("speed", 1.0, 0.1, 5.0, 0.1, "x"));
	private final BoolSetting antiFall = add(new BoolSetting("antiFall", true));
	private ClientLevel world;
	private LocalPlayer body;
	private AbilityLease abilities;

	/** No Minecraft instance is needed to exercise permission and speed ownership. */
	static final class AbilityLease {
		private final Abilities target;
		private boolean originalMayfly, originalFlying;
		private float originalSpeed, appliedSpeed;

		AbilityLease(Abilities target) {
			this.target = target;
			acceptServerState();
		}

		void acceptServerState() {
			originalMayfly = target.mayfly;
			originalFlying = target.flying;
			originalSpeed = appliedSpeed = target.getFlyingSpeed();
		}

		boolean drive(float multiplier) {
			boolean synchronize = originalMayfly && !target.flying;
			// These are local physics flags, not a grant of server-side flight permission.
			target.mayfly = target.flying = true;
			appliedSpeed = originalSpeed * multiplier;
			target.setFlyingSpeed(appliedSpeed);
			return synchronize;
		}

		boolean release() {
			boolean synchronize = originalMayfly && target.flying != originalFlying;
			if (Float.compare(target.getFlyingSpeed(), appliedSpeed) == 0) target.setFlyingSpeed(originalSpeed);
			target.mayfly = originalMayfly;
			target.flying = originalFlying;
			return synchronize;
		}

		boolean serverAllowsFlight() { return originalMayfly; }
	}

	public Flight() {
		super("flight", Category.MOVEMENT, GLFW.GLFW_KEY_G);
	}

	@Override
	public void onEnable() {
		FlatFlight flat = ModuleManager.get(FlatFlight.class);
		if (flat != null && flat.isEnabled()) flat.setEnabled(false);
		release();
	}

	private static boolean ready() {
		return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isRemoved()
				&& !mc.player.isPassenger() && !TeleportAura.ownsMotion();
	}

	/** Runs without a world tick so a lease cannot leak into a replacement player or world. */
	public static void tickLifecycle() {
		Flight module = ModuleManager.get(Flight.class);
		if (module == null) return;
		if (!module.isEnabled() || !ready() || module.body != null
				&& (module.body != mc.player || module.world != mc.level)) module.release();
	}

	@Override
	public void onTick() {
		if (!ready()) { release(); return; }
		if (body != mc.player || world != mc.level) {
			release();
			body = mc.player;
			world = mc.level;
			abilities = new AbilityLease(body.getAbilities());
		}
		if (abilities.drive(speed.floatVal())) body.onUpdateAbilities();
	}

	private void release() {
		// The lease owns its original Abilities reference, never a replacement player's flags.
		if (body != null && abilities != null) {
			boolean synchronize = abilities.release();
			if (synchronize && body == mc.player && world == mc.level) body.onUpdateAbilities();
		}
		body = null;
		world = null;
		abilities = null;
	}

	@Override
	public void onDisable() { release(); }

	/** Vanilla has already applied these flags and speeds; they replace the previous lease. */
	public static void onAbilitiesChanged() {
		Flight module = ModuleManager.get(Flight.class);
		if (module == null || module.body != mc.player || module.world != mc.level || mc.player == null) return;
		// A disable before the next drive must preserve this authoritative packet unchanged.
		module.abilities.acceptServerState();
	}

	/** A local mayfly lease must never be mistaken for a server permission by fall protection. */
	public static boolean serverAllowsFlight() {
		Flight module = ModuleManager.get(Flight.class);
		if (mc.player == null) return false;
		return module != null && module.body == mc.player && module.world == mc.level
				? module.abilities.serverAllowsFlight() : mc.player.getAbilities().mayfly;
	}

	/** Shared fall protection covers experimental client flight as well as native flight. */
	public static boolean requestsFallProtection() {
		Flight module = ModuleManager.get(Flight.class);
		return module != null && module.isEnabled() && module.antiFall.on();
	}

	@Override
	public String summary() {
		return super.summary() + (mc.player != null && !serverAllowsFlight() ? ", " + Lang.t("ui.flight.local") : "");
	}
}
