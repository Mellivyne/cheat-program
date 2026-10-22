package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Anim;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

public class StorageEsp extends Module {
	private final BoolSetting chests = add(new BoolSetting("chests", true));
	private final BoolSetting enderChests = add(new BoolSetting("enderChests", true));
	private final BoolSetting shulkers = add(new BoolSetting("shulkers", true));
	private final BoolSetting others = add(new BoolSetting("others", false));

	public StorageEsp() {
		super("storageesp", Category.VISUAL);
	}

	private boolean matches(BlockEntity be) {
		if (be instanceof ChestBlockEntity) return chests.on();
		if (be instanceof EnderChestBlockEntity) return enderChests.on();
		if (be instanceof ShulkerBoxBlockEntity) return shulkers.on();
		return others.on() && (be instanceof BarrelBlockEntity || be instanceof AbstractFurnaceBlockEntity
				|| be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity);
	}

	@Override
	public void onRender3D(Render3D r) {
		r.begin();
		ChunkPos center = mc.player.chunkPosition();
		int radius = Math.min(mc.options.getEffectiveRenderDistance(), 16);
		for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
			for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
				LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (!matches(be)) continue;
					AABB box = new AABB(be.getBlockPos());
					if (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity) box = box.deflate(1 / 16.0, 0, 1 / 16.0).contract(0, 2 / 16.0, 0);
					boolean special = be instanceof EnderChestBlockEntity || be instanceof ShulkerBoxBlockEntity;
					r.box(box, Anim.white(special ? 0.10f : 0.06f), Anim.white(special ? 0.95f : 0.7f), 1.6f);
				}
			}
		}
		r.end();
	}
}
