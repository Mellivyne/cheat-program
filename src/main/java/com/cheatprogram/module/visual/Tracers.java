package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Anim;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class Tracers extends Module {
	private final BoolSetting players = add(new BoolSetting("players", true));
	private final BoolSetting hostiles = add(new BoolSetting("hostiles", false));
	private final BoolSetting passives = add(new BoolSetting("passives", false));
	private final NumberSetting opacity = add(new NumberSetting("opacity", 70, 10, 100, 5, "%"));

	public Tracers() {
		super("tracers", Category.VISUAL);
	}

	private boolean matches(Entity e) {
		if (e == mc.player || !(e instanceof LivingEntity) || e instanceof ArmorStand || !e.isAlive()) return false;
		if (e instanceof Player) return players.on();
		if (e instanceof Enemy) return hostiles.on();
		return passives.on();
	}

	@Override
	public void onRender3D(Render3D r) {
		r.begin();
		Vec3 start = r.crosshair();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!matches(e)) continue;
			Vec3 end = e.getPosition(r.partialTick).add(0, e.getBbHeight() * 0.55, 0);
			double dist = end.distanceTo(r.camera);
			float fade = (float) Math.max(0.35, 1.0 - dist / 96.0);
			r.line(start, end, Anim.white((float) opacity.fraction() * fade), 1.6f);
		}
		r.end();
	}
}
