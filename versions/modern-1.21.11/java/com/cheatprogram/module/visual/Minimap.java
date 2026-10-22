package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** A small surface-map snapshot of known client chunks; no chunk requests or extra world render. */
public final class Minimap extends Module {
	private static final int RESOLUTION = 64;
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("cheatprogram", "dynamic/minimap");
	private final NumberSetting mapSize = add(new NumberSetting("mapSize", 164, 96, 320, 1, "px"));
	private final NumberSetting radius = add(new NumberSetting("radius", 64, 16, 192, 4));
	private final NumberSetting positionX = add(new NumberSetting("positionX", 0, 0, 100, .1, "%"));
	private final NumberSetting positionY = add(new NumberSetting("positionY", 20, 0, 100, .1, "%"));
	private final NumberSetting opacity = add(new NumberSetting("opacity", 75, 15, 100, 5, "%"));
	private final NumberSetting refreshRate = add(new NumberSetting("refreshRate", 5, 1, 10, 1, "fps"));
	private final ModeSetting orientation = add(new ModeSetting("orientation", "map_north", "map_north", "map_follow"));
	private final BoolSetting players = add(new BoolSetting("players", true));
	private final BoolSetting hostiles = add(new BoolSetting("hostiles", true));
	private final BoolSetting passives = add(new BoolSetting("passives", false));
	private final BoolSetting names = add(new BoolSetting("names", false));
	private final BoolSetting coordinates = add(new BoolSetting("coordinates", true));
	private final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
	private int worldIdentity, tick, nextUpdate, cursor;
	private int[] working, ready;
	private Frame sampling, displayed;
	private DynamicTexture texture;
	private boolean textureDirty;
	private record Frame(double x, double z, double radius, double cos, double sin) { }

	public Minimap() { super("minimap", Category.VISUAL); }
	@Override public String suffix() { return radius.format() + ", " + orientation.display(); }
	@Override public void onEnable() { clear(); }
	@Override public void onDisable() { clear(); }
	private void clear() {
		worldIdentity = tick = nextUpdate = cursor = 0; working = ready = null; sampling = displayed = null; textureDirty = false;
		if (texture != null) { mc.getTextureManager().release(TEXTURE); texture = null; }
	}
	public void checkLifecycle() {
		if (!inGame() || !isEnabled() || worldIdentity != 0 && worldIdentity != System.identityHashCode(mc.level)) clear();
	}
	public float[] bounds(float screenWidth, float screenHeight) {
		float size = Math.max(48, Math.min(mapSize.floatVal(), Math.min(screenWidth - 16, screenHeight - 60)));
		float height = size + (coordinates.on() ? 36 : 20);
		return new float[]{6 + Math.max(0, screenWidth - size - 12) * positionX.floatVal() / 100,
				6 + Math.max(0, screenHeight - height - 12) * positionY.floatVal() / 100, size, height};
	}
	public void move(float x, float y, float screenWidth, float screenHeight) {
		float[] b = bounds(screenWidth, screenHeight);
		positionX.set((double) ((x - 6) / Math.max(1, screenWidth - b[2] - 12) * 100));
		positionY.set((double) ((y - 6) / Math.max(1, screenHeight - b[3] - 12) * 100));
	}
	public void resize(float size) { mapSize.set((double) size); }
	public void resetLayout() { mapSize.reset(); positionX.reset(); positionY.reset(); }
	public void zoom(double scroll) { radius.set(radius.val() - scroll * 4); }
	public void toggleOrientation() { orientation.set(orientation.is("map_north") ? "map_follow" : "map_north"); }
	public String orientationLabel() { return orientation.display(); }

	@Override public void onTick() {
		checkLifecycle(); if (!inGame() || mc.isPaused()) return;
		worldIdentity = System.identityHashCode(mc.level); tick++;
		if (sampling == null) {
			if (tick < nextUpdate) return;
			double angle = orientation.is("map_follow") ? Math.toRadians(mc.gameRenderer.getMainCamera().yRot() + 180) : 0;
			sampling = new Frame(mc.player.getX(), mc.player.getZ(), radius.val(), Math.cos(angle), Math.sin(angle));
			working = new int[RESOLUTION * RESOLUTION]; cursor = 0;
		}
		long deadline = System.nanoTime() + 1_500_000L;
		for (int count = 0; count < 1536 && cursor < working.length; count++, cursor++) {
			int x = cursor % RESOLUTION, y = cursor / RESOLUTION;
			double u = ((x + .5) / RESOLUTION * 2 - 1) * sampling.radius;
			double v = ((y + .5) / RESOLUTION * 2 - 1) * sampling.radius;
			int worldX = Mth.floor(sampling.x + u * sampling.cos - v * sampling.sin);
			int worldZ = Mth.floor(sampling.z + u * sampling.sin + v * sampling.cos);
			int color = surfaceColor(worldX, worldZ);
			// Alpha mask prevents square terrain corners from crossing the rounded glass inset.
			double cornerX = Math.max(0, Math.abs(x + .5 - RESOLUTION / 2d) - (RESOLUTION / 2d - 3));
			double cornerY = Math.max(0, Math.abs(y + .5 - RESOLUTION / 2d) - (RESOLUTION / 2d - 3));
			if (cornerX * cornerX + cornerY * cornerY > 9) color = 0;
			working[cursor] = color;
			if ((count & 63) == 63 && System.nanoTime() > deadline) { cursor++; break; }
		}
		if (cursor >= working.length) {
			ready = working; displayed = sampling; working = null; sampling = null; textureDirty = true;
			nextUpdate = tick + Math.max(0, 20 / refreshRate.intVal() - 3);
		}
	}
	private int surfaceColor(int x, int z) {
		var chunk = mc.level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
		if (chunk == null) return 0x46272b32;
		int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
		if (height < mc.level.getMinY()) return 0x46272b32;
		// The surface heightmap points to the top occupied block. Transparent plants are walked down.
		for (int i = 0; i < 12 && height >= mc.level.getMinY(); i++, height--) {
			samplePos.set(x, height, z);
			var state = chunk.getBlockState(samplePos);
			MapColor color = state.getMapColor(mc.level, samplePos);
			if (color != MapColor.NONE) return color.calculateARGBColor(MapColor.Brightness.NORMAL);
		}
		return 0x46272b32;
	}
	private void upload() {
		if (!textureDirty || ready == null) return;
		if (texture == null) {
			texture = new DynamicTexture(() -> "Cheat Program texture", new NativeImage(RESOLUTION, RESOLUTION, true));
			com.cheatprogram.compat.gl.RenderSystem.setTextureFilter(texture, false, false); mc.getTextureManager().register(TEXTURE, texture);
		}
		var pixels = texture.getPixels();
		if (pixels == null) return;
		for (int y = 0; y < RESOLUTION; y++) for (int x = 0; x < RESOLUTION; x++) pixels.setPixel(x, y, ready[y * RESOLUTION + x]);
		texture.upload(); textureDirty = false;
	}
	@Override public void onRender2D(HudContext hud) {
		if (!inGame()) return;
		if (worldIdentity != 0 && worldIdentity != System.identityHashCode(mc.level)) { clear(); return; }
		upload();
		float[] b = bounds(hud.width, hud.height); float x = b[0], y = b[1], w = b[2];
		Canvas c = hud.c; hud.panel(x, y, w, b[3], 12);
		c.textC(Fonts.Face.MEDIUM, name(), x + 9, y + 10, 9, Anim.white(.9f));
		c.textRight(Fonts.Face.REGULAR, Integer.toString(radius.intVal()), x + w - 9, y + 10, 8, Anim.white(.6f));
		float mapX = x + 5, mapY = y + 19, mapW = w - 10;
		c.rrect(mapX, mapY, mapW, mapW, 7, Anim.black(.16f));
		if (texture != null && displayed != null) {
			R2D.texture(TEXTURE, c.px(mapX), c.py(mapY), mapW * c.scale, mapW * c.scale, 0, 0, 1, 1, Math.round(opacity.floatVal() * 2.55f) << 24 | 0xffffff);
			c.clip(mapX, mapY, mapW, mapW);
			try { drawRadar(c, mapX, mapY, mapW); } finally { c.unclip(); }
		}
		c.textC(Fonts.Face.BOLD, northLabel(), mapX + 5, mapY + 7, 8, Anim.white(.8f));
		if (coordinates.on()) c.textMid(Fonts.Face.REGULAR, mc.player.getBlockX() + "  " + mc.player.getBlockY() + "  " + mc.player.getBlockZ(), x + w / 2, y + b[3] - 8, 8, Anim.white(.72f));
	}
	private String northLabel() { return orientation.is("map_north") ? "N" : "↑"; }
	private void drawRadar(Canvas c, float x, float y, float size) {
		Frame frame = displayed;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || entity == mc.player || entity.isRemoved() || !living.isAlive()
					|| entity instanceof ArmorStand || !mc.level.hasChunkAt(entity.blockPosition())) continue;
			int color;
			if (entity instanceof Player player) { if (!players.on() || player.isSpectator()) continue; color = 0xffffffff; }
			else if (entity instanceof Enemy) { if (!hostiles.on()) continue; color = 0xffff9c9c; }
			else { if (!passives.on()) continue; color = 0xffb8efcc; }
			double dx = entity.getX() - frame.x, dz = entity.getZ() - frame.z;
			double u = (dx * frame.cos + dz * frame.sin) / frame.radius, v = (-dx * frame.sin + dz * frame.cos) / frame.radius;
			if (Math.abs(u) > .94 || Math.abs(v) > .94) continue;
			float px = x + (float) (u + 1) * size / 2, py = y + (float) (v + 1) * size / 2;
			c.circle(px, py, 2.6f, Anim.black(.65f)); c.circle(px, py, 1.7f, color);
			if (names.on()) c.textMid(Fonts.Face.REGULAR, c.ellipsize(Fonts.Face.REGULAR, entity.getName().getString(), 7, 55), px, py - 5, 7, color);
		}
		double dx = mc.player.getX() - frame.x, dz = mc.player.getZ() - frame.z;
		float cx = x + (float) ((dx * frame.cos + dz * frame.sin) / frame.radius + 1) * size / 2;
		float cy = y + (float) ((-dx * frame.sin + dz * frame.cos) / frame.radius + 1) * size / 2;
		double yaw = Math.toRadians(mc.gameRenderer.getMainCamera().yRot());
		double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
		float dirX = (float) (forwardX * frame.cos + forwardZ * frame.sin), dirY = (float) (-forwardX * frame.sin + forwardZ * frame.cos);
		for (int i = 1; i <= 4; i++) c.circle(cx + dirX * i, cy + dirY * i, 1.7f - i * .15f, Anim.white(1 - i * .1f));
		c.circle(cx, cy, 3.4f, Anim.black(.6f)); c.circle(cx, cy, 2.3f, 0xffffffff);
	}
}
