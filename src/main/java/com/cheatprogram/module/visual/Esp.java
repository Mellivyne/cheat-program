package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Friends;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Anim;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class Esp extends Module {
	private final ModeSetting mode = add(new ModeSetting("mode", "glow", "glow", "box"));
	private final BoolSetting players = add(new BoolSetting("players", true));
	private final BoolSetting hostiles = add(new BoolSetting("hostiles", true));
	private final BoolSetting passives = add(new BoolSetting("passives", false));
	private final BoolSetting items = add(new BoolSetting("items", false));

	public Esp() {
		super("esp", Category.VISUAL);
	}

	@Override
	public String suffix() {
		return mode.display();
	}

	public boolean matches(Entity e) {
		if (e == mc.player || e.isRemoved()) return false;
		if (e instanceof Player) return players.on();
		if (e instanceof ItemEntity) return items.on();
		if (e instanceof Enemy) return hostiles.on();
		return e instanceof LivingEntity && !(e instanceof ArmorStand) && passives.on();
	}

	/** Used by the glowing-outline mixins. */
	public static boolean glowing(Entity e) {
		Esp esp = ModuleManager.get(Esp.class);
		return esp != null && esp.isEnabled() && esp.mode.is("glow") && esp.matches(e);
	}

	@Override
	public void onRender3D(Render3D r) {
		if (!mode.is("box")) return;
		r.begin();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!matches(e)) continue;
			Vec3 lerped = e.getPosition(r.partialTick);
			AABB box = e.getBoundingBox().move(lerped.subtract(e.position())).inflate(0.05);
			boolean friend = Friends.isFriend(e);
			r.box(box, Anim.white(friend ? 0.03f : 0.07f), Anim.white(friend ? 0.45f : 0.9f), 2f);
		}
		r.end();
	}
}
