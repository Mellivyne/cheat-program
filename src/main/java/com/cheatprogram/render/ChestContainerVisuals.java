package com.cheatprogram.render;

import com.cheatprogram.core.Lang;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.util.Anim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Render-only supplement for a confirmed automatic container menu; never changes opener counts or collisions. */
public final class ChestContainerVisuals {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Map<BlockPos, Lid> LIDS = new HashMap<>();
	private static ClientLevel level;
	private static Set<BlockPos> active = Set.of();
	private static long lastTick = Long.MIN_VALUE;
	private static final class Lid { float old, value; }
	private ChestContainerVisuals() { }

	public static void open(ClientLevel clientLevel, List<BlockPos> positions) {
		if (level != clientLevel) clear();
		level = clientLevel;
		active = Set.copyOf(positions);
		for (BlockPos position : active) LIDS.computeIfAbsent(position.immutable(), ignored -> new Lid());
	}
	public static void close() { active = Set.of(); }
	public static void clear() { level = null; active = Set.of(); LIDS.clear(); lastTick = Long.MIN_VALUE; }

	/** Called even after the module is disabled so every supplemented lid can close normally. */
	public static void tickLifecycle(ClientLevel clientLevel) {
		if (level == null) return;
		if (level != clientLevel) { clear(); return; }
		long tick = clientLevel.getGameTime();
		if (tick == lastTick) return;
		lastTick = tick;
		LIDS.entrySet().removeIf(entry -> {
			Lid lid = entry.getValue();
			lid.old = lid.value;
			boolean opened = active.contains(entry.getKey()) && clientLevel.hasChunkAt(entry.getKey());
			// Match ChestLidController's ten-tick vanilla opening/closing interpolation.
			lid.value = Mth.clamp(lid.value + (opened ? .1f : -.1f), 0, 1);
			return !opened && lid.old <= 0 && lid.value <= 0;
		});
		if (LIDS.isEmpty() && active.isEmpty()) clear();
	}

	public static float openness(BlockEntity blockEntity, float partialTick, float vanilla) {
		if (level == null || blockEntity.getLevel() != level || level != MC.level) return vanilla;
		Lid lid = LIDS.get(blockEntity.getBlockPos());
		return lid == null ? vanilla : Math.max(vanilla, Mth.lerp(Mth.clamp(partialTick, 0, 1), lid.old, lid.value));
	}

	public static void outline(Render3D renderer) {
		if (level == null || level != MC.level || active.isEmpty()) return;
		AABB box = null;
		for (BlockPos position : active) {
			if (!level.hasChunkAt(position)) continue;
			var shape = level.getBlockState(position).getShape(level, position);
			AABB part = shape.isEmpty() ? new AABB(position) : shape.bounds().move(position);
			box = box == null ? part : box.minmax(part);
		}
		if (box != null) {
			renderer.begin();
			try { renderer.box(box.inflate(.004), 0, 0xD8FFFFFF, 1.4f); }
			finally { renderer.end(); }
		}
	}

	/** No placeholder inventory is created before the first real contents packet arrives. */
	public static void drawWaiting(HudWorldPanel.Projection projection, String title) {
		if (!R2D.isActive() || System.nanoTime() - projection.time() > 250_000_000L) return;
		float width = 190, height = 48, scale = projection.scale();
		float x = projection.x() - width * scale / 2, y = projection.y() - height * scale;
		Canvas canvas = new Canvas(); canvas.set(scale, x, y);
		Backdrop.capture(Theme.frost()); R2D.applyState();
		canvas.shadow(0, 2, width, height, 8, 7, Anim.black(.18f));
		canvas.lens(0, 0, width, height, 8, Theme.lift(.07f), Theme.chipGlass(scale));
		canvas.textC(Fonts.Face.REGULAR, canvas.ellipsize(Fonts.Face.REGULAR, title, 10, 170), 9, 13, 10, Theme.TEXT);
		canvas.textMid(Fonts.Face.REGULAR, Lang.t("cheststealer.waitingContents"), width / 2, 32, 10, Theme.TEXT_3);
	}
}
