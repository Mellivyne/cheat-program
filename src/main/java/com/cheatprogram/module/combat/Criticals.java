package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.util.ServerActionGate;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class Criticals extends Module {
	private final ModeSetting mode = add(new ModeSetting("mode", "packet", "packet", "jump"));
	private final ServerActionGate serverActions = new ServerActionGate();

	public Criticals() {
		super("criticals", Category.COMBAT);
	}

	@Override
	public String suffix() {
		return mode.display();
	}

	@Override public void onEnable() { serverActions.reset(); }

	public static void onServerCorrection() {
		Criticals module = ModuleManager.get(Criticals.class);
		if (module != null && module.isEnabled()) module.serverActions.corrected(System.nanoTime());
	}

	@Override
	public boolean onAttack(Entity target) {
		var p = mc.player;
		if (p == null || !serverActions.ready(System.nanoTime()) || !(target instanceof LivingEntity)
				|| !p.onGround() || p.isInWater() || p.isInLava() || p.onClimbable() || p.isPassenger()) return false;
		if (mode.is("packet")) {
			// A remote server owns position/fall state. Fabricated up/down steps contradict actual movement.
			if (!mc.hasSingleplayerServer()) return false;
			double x = p.getX(), y = p.getY(), z = p.getZ();
			p.connection.send(new ServerboundMovePlayerPacket.Pos(x, y + 0.0625, z, false, p.horizontalCollision));
			p.connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, p.horizontalCollision));
		} else {
			p.jumpFromGround();
		}
		return false;
	}
}
