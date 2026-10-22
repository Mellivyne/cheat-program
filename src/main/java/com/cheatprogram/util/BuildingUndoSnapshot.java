package com.cheatprogram.util;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Session-bound original blocks; capture is immediately before mutation, exclusively on the server thread. */
public final class BuildingUndoSnapshot {
	public final IntegratedServer server;
	public final ServerLevel level;
	public final BlockPos origin;
	public final int width, height, depth, count;
	public final String name;
	private final int[] original;
	private final List<BlockState> palette = new ArrayList<>();
	private final Map<BlockState, Integer> indices = new HashMap<>();
	private final Map<Integer, CompoundTag> entities = new HashMap<>();
	private final BitSet touched;
	private volatile int changed;
	private volatile int cursor;
	public BuildingUndoSnapshot(IntegratedServer server, ServerLevel level, BlockPos origin, int width, int height, int depth, String name) {
		long volume = (long) width * height * depth;
		if (volume < 1 || volume > BuildingFiles.MAX_BLOCKS) throw new IllegalArgumentException("Invalid undo dimensions");
		this.server = server; this.level = level; this.origin = origin.immutable(); this.width = width; this.height = height; this.depth = depth; this.name = name;
		count = (int) volume; original = new int[count]; Arrays.fill(original, -1); touched = new BitSet(count);
	}
	public boolean belongsTo(IntegratedServer currentServer, ServerLevel currentLevel) { return server == currentServer && level == currentLevel; }
	public int changed() { return changed; }
	public int cursor() { return cursor; }
	public void advance() { cursor++; }
	public boolean finished() { return cursor >= count * 2; }
	public int nextTouched(int start) { return touched.nextSetBit(start); }
	public BlockPos position(int index) { return origin.offset(index % width, index / (width * depth), index / width % depth); }
	public void capture(int index) {
		if (touched.get(index)) return;
		BlockPos position = position(index); BlockState state = level.getBlockState(position);
		BlockEntity entity = level.getBlockEntity(position);
		// Construct all NBT before marking this cell touched. A failed capture must never start modifying it.
		CompoundTag data = entity == null ? null : entity.saveWithFullMetadata(level.registryAccess());
		Integer paletteIndex = indices.get(state);
		if (paletteIndex == null) { paletteIndex = palette.size(); palette.add(state); indices.put(state, paletteIndex); }
		if (data != null) entities.put(index, data);
		original[index] = paletteIndex; touched.set(index); changed++;
	}
	public BlockState state(int index) { return palette.get(original[index]); }
	public CompoundTag entityData(int index) { CompoundTag value = entities.get(index); return value == null ? null : value.copy(); }
	/** Only touched cells need work; retain this cursor when the player cancels an undo. */
	public int skipUntouched() {
		int phase = cursor / count, index = cursor % count;
		int next = nextTouched(index);
		cursor = next < 0 ? (phase + 1) * count : phase * count + next;
		return cursor;
	}
}
