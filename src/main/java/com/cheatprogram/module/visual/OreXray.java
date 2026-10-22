package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.render.OreXrayRenderer;
import com.cheatprogram.render.OreCandidateRenderer;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.OreSeedPredictor;
import com.cheatprogram.util.OreSeedSource;
import com.cheatprogram.util.OreVeinInference;
import com.cheatprogram.util.OreBackfill;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Observation-only ore overlay. Never requests a chunk or changes vanilla world meshes. */
public final class OreXray extends Module {
	private final ModeSetting oreMode = add(new ModeSetting("oreMode", "ore_client", "ore_client", "ore_seed", "ore_vein"));
	private final BoolSetting exposedOnly = add(new BoolSetting("exposedOnly", false)).visibleWhen(() -> !seedMode() && !veinMode());
	private final NumberSetting veinRadius = add(new NumberSetting("veinRadius", 3, 2, 4, 1)).visibleWhen(this::veinMode);
	private final NumberSetting veinAssociation = add(new NumberSetting("veinAssociation", 40, 10, 90, 5, "%")).visibleWhen(this::veinMode);
	private final NumberSetting veinLimit = add(new NumberSetting("veinLimit", 128, 16, 256, 16)).visibleWhen(this::veinMode);
	private final TextSetting seed = add(new TextSetting("seed", "", 40)).visibleWhen(this::seedMode);
	private final BoolSetting autoSeed = add(new BoolSetting("autoSeed", true)).visibleWhen(this::seedMode);
	private final BoolSetting collectSeed = add(new BoolSetting("collectSeed", true)).visibleWhen(() -> seedMode() && autoSeed.on());
	private final BoolSetting terrainCheck = add(new BoolSetting("terrainCheck", true)).visibleWhen(this::seedMode);
	private final BoolSetting showExposed = add(new BoolSetting("showExposed", true)).visibleWhen(this::seedMode);
	private final BoolSetting coal = add(new BoolSetting("coal", false));
	private final BoolSetting iron = add(new BoolSetting("iron", true));
	private final BoolSetting copper = add(new BoolSetting("copper", false));
	private final BoolSetting gold = add(new BoolSetting("gold", true));
	private final BoolSetting redstone = add(new BoolSetting("redstone", false));
	private final BoolSetting lapis = add(new BoolSetting("lapis", false));
	private final BoolSetting diamond = add(new BoolSetting("diamond", true));
	private final BoolSetting emerald = add(new BoolSetting("emerald", true));
	private final BoolSetting quartz = add(new BoolSetting("quartz", false));
	private final BoolSetting netherGold = add(new BoolSetting("netherGold", false));
	private final BoolSetting ancientDebris = add(new BoolSetting("ancientDebris", true));
	private final TextSetting customBlocks = add(new TextSetting("customBlocks", "", 2048));
	private final NumberSetting range = add(new NumberSetting("range", 48, 16, 128, 4));
	private final NumberSetting limit = add(new NumberSetting("limit", 1536, 128, 4096, 128));
	private final NumberSetting opacity = add(new NumberSetting("opacity", 95, 20, 100, 5, "%"));
	private final BoolSetting backfill = add(new BoolSetting("backfill", false));
	private final ModeSetting backfillMaterial = add(new ModeSetting("backfillMaterial", "backfill_match", "backfill_match", "backfill_id")).visibleWhen(backfill::on);
	private final TextSetting backfillBlock = add(new TextSetting("backfillBlock", "minecraft:cobblestone", 128)).visibleWhen(() -> backfill.on() && backfillMaterial.is("backfill_id"));
	private final NumberSetting backfillDistance = add(new NumberSetting("backfillDistance", 2.3, 1.5, 5, .1)).visibleWhen(backfill::on);
	private final NumberSetting backfillRadius = add(new NumberSetting("backfillRadius", 0, 0, 2, 1)).visibleWhen(backfill::on);
	private final NumberSetting backfillHeight = add(new NumberSetting("backfillHeight", 2, 1, 3, 1)).visibleWhen(backfill::on);
	private final NumberSetting backfillInterval = add(new NumberSetting("backfillInterval", 5, 1, 100, 1, "t")).visibleWhen(backfill::on);
	private final NumberSetting backfillBudget = add(new NumberSetting("backfillBudget", 1, 1, 4, 1)).visibleWhen(backfill::on);
	private final ModeSetting backfillRotation = add(new ModeSetting("backfillRotation", "silent", "silent", "follow")).visibleWhen(backfill::on);
	private final BoolSetting backfillPreview = add(new BoolSetting("backfillPreview", true)).visibleWhen(backfill::on);
	private final OreBackfill backfiller = new OreBackfill(this);
	private final OreXrayRenderer renderer = new OreXrayRenderer();
	private final OreXrayRenderer predictedRenderer = new OreXrayRenderer();
	private final OreSeedPredictor predictor = new OreSeedPredictor();
	private final OreSeedSource seedSource = new OreSeedSource();
	private final OreVeinInference veinInference = new OreVeinInference();
	private final Map<SectionKey, List<Ore>> predictedSections = new LinkedHashMap<>();
	private int predictedTotal;
	private final Map<SectionKey, List<Ore>> sections = new LinkedHashMap<>();
	private final Set<Block> selected = new HashSet<>();
	private final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
	private List<SectionKey> scan = List.of();
	private List<SectionKey> validation = List.of();
	private final List<Ore> pending = new ArrayList<>();
	private final List<Ore> validated = new ArrayList<>();
	private ClientLevel world;
	private Vec3 center = Vec3.ZERO;
	private BlockPos planCenter;
	private SectionKey scanning;
	private LevelChunkSection scanningSection;
	private SectionKey validating;
	private List<Ore> validationSource;
	private int validationCell;
	private boolean validationChanged;
	private String selectionKey = "";
	private int tick, scanCursor, cellCursor, validationCursor, total, invalidCustom;
	private long cycleFinished;

	public record SectionKey(int x, int y, int z) {
		public BlockPos origin() { return new BlockPos(x << 4, y << 4, z << 4); }
		public double distance(Vec3 point) {
			double dx = (x << 4) + 8 - point.x, dy = (y << 4) + 8 - point.y, dz = (z << 4) + 8 - point.z;
			return dx * dx + dy * dy + dz * dz;
		}
	}
	public record Ore(BlockPos position, BlockState state) { }

	public OreXray() { super("orexray", Category.VISUAL); }
	private boolean seedMode() { return oreMode.is("ore_seed"); }
	private boolean veinMode() { return oreMode.is("ore_vein"); }
	/** Contextual usage notes are rendered as small text by the settings panel. */
	public List<String> helpKeys() {
		List<String> notes = new ArrayList<>(veinMode()
				? List.of("ui.orexray.help.received", "ui.orexray.help.vein", "ui.orexray.help.veinLegend")
				: seedMode() ? List.of("ui.orexray.help.seed", "ui.orexray.help.seedLimits")
				: List.of("ui.orexray.help.received", "ui.orexray.help.exposed"));
		if (backfill.on()) { notes.add("ui.orebackfill.help"); notes.add("ui.orebackfill.matchHelp"); }
		return List.copyOf(notes);
	}
	@Override public String suffix() {
		String detail = seedMode() ? " · " + Lang.t(seedSource.statusKey()) + " · " + Lang.t("ui.orexray.predicted") + " " + predictedTotal
				+ (seedSource.seed() == null ? "" : " · " + Lang.t("ui.orexray.predictor." + predictor.status().name().toLowerCase(java.util.Locale.ROOT))) : "";
		if (veinMode()) detail = " · " + Lang.t("ui.orexray.anchors") + " " + veinInference.anchorCount()
				+ " · " + Lang.t("ui.orexray.candidates") + " " + veinInference.results().size();
		return oreMode.display() + " · " + range.format() + " · " + total + detail
				+ (invalidCustom == 0 ? "" : " · " + Lang.t("ui.orexray.invalidIds") + " " + invalidCustom) + backfiller.status();
	}
	@Override public void onEnable() { clear(); }
	@Override public void onDisable() { clear(); renderer.close(); predictedRenderer.close(); }
	private void clear() {
		backfiller.clear();
		world = null; sections.clear(); selected.clear(); scan = validation = List.of();
		pending.clear(); scanning = null; scanningSection = null; planCenter = null; selectionKey = ""; resetValidation();
		tick = scanCursor = cellCursor = validationCursor = total = invalidCustom = 0; cycleFinished = 0;
		renderer.clear();
		clearPrediction();
		veinInference.clear();
		seedSource.clear();
	}
	private void clearPrediction() {
		predictor.clear(); predictedSections.clear(); predictedTotal = 0; predictedRenderer.clear();
	}
	/** Called by the global client lifecycle even when no world is being rendered. */
	public void checkLifecycle() { backfiller.checkLifecycle(); if ((!isEnabled() || !inGame()) && world != null) { clear(); renderer.close(); predictedRenderer.close(); } }
	private String currentSelection() {
		return oreMode.get() + ":" + exposedOnly.on() + ":" + showExposed.on() + ":" + coal.on() + ":" + iron.on() + ":" + copper.on() + ":" + gold.on() + ":" + redstone.on() + ":" + lapis.on()
				+ ":" + diamond.on() + ":" + emerald.on() + ":" + quartz.on() + ":" + netherGold.on() + ":" + ancientDebris.on()
				+ ":" + customBlocks.get() + ":" + range.intVal() + ":" + limit.intVal();
	}
	private void choose(BoolSetting setting, Block... blocks) { if (setting.on()) for (Block block : blocks) selected.add(block); }
	private void readSelection(String key) {
		clearPrediction();
		veinInference.clear();
		sections.clear(); total = 0; renderer.clear(); scan = validation = List.of();
		pending.clear(); scanning = null; scanningSection = null; scanCursor = cellCursor = validationCursor = 0; planCenter = null; resetValidation();
		selected.clear(); invalidCustom = 0; selectionKey = key;
		choose(coal, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
		choose(iron, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
		choose(copper, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
		choose(gold, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
		choose(redstone, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
		choose(lapis, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
		choose(diamond, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
		choose(emerald, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
		choose(quartz, Blocks.NETHER_QUARTZ_ORE); choose(netherGold, Blocks.NETHER_GOLD_ORE); choose(ancientDebris, Blocks.ANCIENT_DEBRIS);
		for (String name : customBlocks.get().split("[,;，；\\s]+")) {
			if (name.isBlank()) continue;
			ResourceLocation id = ResourceLocation.tryParse(name.trim());
			if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) { invalidCustom++; continue; }
			Block block = BuiltInRegistries.BLOCK.getValue(id);
			if (block == Blocks.AIR || block.defaultBlockState().getRenderShape() != RenderShape.MODEL) { invalidCustom++; continue; }
			selected.add(block);
		}
	}
	private LevelChunk loadedChunk(int x, int z) { return world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false); }
	private LevelChunkSection section(SectionKey key) {
		LevelChunk chunk = loadedChunk(key.x, key.z);
		if (chunk == null) return null;
		int index = world.getSectionIndexFromSectionY(key.y);
		return index < 0 || index >= chunk.getSections().length ? null : chunk.getSections()[index];
	}
	private boolean target(BlockState state) { return selected.contains(state.getBlock()) && state.getRenderShape() == RenderShape.MODEL; }
	private boolean visibleOre(BlockPos position, BlockState state) {
		if (!target(state)) return false;
		if (!seedMode() && !veinMode() && !exposedOnly.on()) return true;
		if (seedMode() && !showExposed.on()) return false;
		for (Direction direction : Direction.values()) {
			BlockPos neighbor = position.relative(direction);
			LevelChunk chunk = loadedChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4);
			if (chunk != null && !chunk.getBlockState(neighbor).canOcclude()) return true;
		}
		return false;
	}
	private boolean withinRange(BlockPos position) { return position.distToCenterSqr(center) <= range.val() * range.val(); }
	private void plan() {
		planCenter = BlockPos.containing(center); pending.clear(); scanning = null; scanningSection = null; scanCursor = cellCursor = 0;
		int radius = range.intVal();
		int minX = Mth.floor(center.x - radius) >> 4, maxX = Mth.floor(center.x + radius) >> 4;
		int minZ = Mth.floor(center.z - radius) >> 4, maxZ = Mth.floor(center.z + radius) >> 4;
		int minY = Math.max(world.getMinSectionY(), Mth.floor(center.y - radius) >> 4);
		int maxY = Math.min(world.getMaxSectionY(), Mth.floor(center.y + radius) >> 4);
		List<SectionKey> candidates = new ArrayList<>();
		for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
			// FULL,false is the client chunk-cache lookup; an unknown chunk is skipped, never requested.
			if (loadedChunk(x, z) == null) continue;
			for (int y = minY; y <= maxY; y++) {
				SectionKey key = new SectionKey(x, y, z);
				if (intersectsRange(key, radius)) candidates.add(key);
			}
		}
		candidates.sort(Comparator.comparingDouble(key -> key.distance(center))); scan = candidates;
		Set<SectionKey> present = new HashSet<>(candidates);
		for (SectionKey key : List.copyOf(sections.keySet())) if (!present.contains(key)) replace(key, List.of());
		cycleFinished = 0;
	}
	private boolean intersectsRange(SectionKey key, int radius) {
		double dx = Math.max(0, Math.abs(center.x - ((key.x << 4) + 8)) - 8);
		double dy = Math.max(0, Math.abs(center.y - ((key.y << 4) + 8)) - 8);
		double dz = Math.max(0, Math.abs(center.z - ((key.z << 4) + 8)) - 8);
		return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
	}
	private void replace(SectionKey key, List<Ore> ores) {
		List<Ore> before = sections.get(key);
		if (ores.isEmpty()) {
			if (before != null) { sections.remove(key); total -= before.size(); renderer.replace(key, List.of()); }
			return;
		}
		if (ores.equals(before)) return;
		List<Ore> copy = List.copyOf(ores); sections.put(key, copy); total += copy.size() - (before == null ? 0 : before.size());
		renderer.replace(key, copy);
		// Keep the nearest sections when the user-selected model budget is full.
		while (total > limit.intVal() && !sections.isEmpty()) {
			SectionKey farthest = sections.keySet().stream().max(Comparator.comparingDouble(section -> section.distance(center))).orElseThrow();
			List<Ore> removed = sections.remove(farthest); total -= removed.size(); renderer.replace(farthest, List.of());
		}
		validation = List.copyOf(sections.keySet());
	}
	private void validateKnown() {
		// Touch at most 256 cached blocks or 8 sections per tick. Mining/removal is reflected
		// without waiting for the next potentially large radius scan to reach that section.
		int checked = 0, groups = 0;
		while (checked < 256 && groups < 8) {
			if (validating == null) {
				if (validationCursor >= validation.size()) { validationCursor = 0; break; }
				validating = validation.get(validationCursor++); validationSource = sections.get(validating); groups++;
				if (validationSource == null) { resetValidation(); continue; }
			}
			if (sections.get(validating) != validationSource) { resetValidation(); continue; }
			LevelChunkSection section = section(validating);
			if (section == null) { replace(validating, List.of()); resetValidation(); continue; }
			while (validationCell < validationSource.size() && checked++ < 256) {
				Ore ore = validationSource.get(validationCell++); BlockPos pos = ore.position;
				BlockState state = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
				if (visibleOre(pos, state) && withinRange(pos)) {
					validated.add(state == ore.state ? ore : new Ore(pos, state)); if (state != ore.state) validationChanged = true;
				} else validationChanged = true;
			}
			if (validationCell >= validationSource.size()) {
				if (validationChanged) replace(validating, validated);
				resetValidation();
			}
		}
	}
	private void resetValidation() { validating = null; validationSource = null; validationCell = 0; validationChanged = false; validated.clear(); }
	@Override public void onTick() {
		if (!inGame()) { checkLifecycle(); return; }
		if (world != mc.level) { clear(); world = mc.level; }
		backfiller.tick(new OreBackfill.Options(backfill.on(), backfillMaterial.is("backfill_match"), backfillBlock.get(),
				backfillDistance.val(), backfillRadius.intVal(), backfillHeight.intVal(), backfillInterval.intVal(),
				backfillBudget.intVal(), backfillRotation.get(), backfillPreview.on()));
		String key = currentSelection(); if (!key.equals(selectionKey)) readSelection(key);
		center = Freecam.isActive() ? mc.gameRenderer.getMainCamera().getPosition() : mc.player.getEyePosition();
		tick++; validateKnown();
		if (seedMode()) {
			seedSource.update(world, seed.get(), autoSeed.on(), autoSeed.on() && collectSeed.on());
			Long knownSeed = seedSource.seed();
			if (knownSeed != null && !selected.isEmpty()) {
				predictor.tick(world, knownSeed, center, range.intVal(), Set.copyOf(selected), Math.max(0, limit.intVal() - total), terrainCheck.on());
				syncPrediction(predictor.results());
			} else if (!predictedSections.isEmpty()) clearPrediction();
		} else {
			seedSource.clear();
			if (!predictedSections.isEmpty()) clearPrediction();
		}
		if (scanning != null && section(scanning) != scanningSection) { scanning = null; scanningSection = null; pending.clear(); }
		if (selected.isEmpty()) return;
		if (planCenter == null || planCenter.distToCenterSqr(center) > 64
				|| scanning == null && scanCursor >= scan.size() && cycleFinished != 0 && tick - cycleFinished >= 12) plan();
		long deadline = System.nanoTime() + 2_000_000L;
		int cells = 0, groups = 0;
		while (cells < 24576 && groups < 192 && System.nanoTime() < deadline) {
			if (scanning == null) {
				if (scanCursor >= scan.size()) { if (cycleFinished == 0) cycleFinished = tick; break; }
				scanning = scan.get(scanCursor++); scanningSection = section(scanning); cellCursor = 0; pending.clear(); groups++;
				if (scanningSection == null || scanningSection.hasOnlyAir() || !scanningSection.maybeHas(this::target)) {
					replace(scanning, List.of()); scanning = null; scanningSection = null; continue;
				}
			}
			int end = Math.min(4096, cellCursor + 256);
			for (; cellCursor < end; cellCursor++, cells++) {
				int x = cellCursor & 15, z = cellCursor >> 4 & 15, y = cellCursor >> 8;
				BlockState state = scanningSection.getBlockState(x, y, z);
				if (!target(state) || pending.size() >= limit.intVal()) continue;
				samplePos.set((scanning.x << 4) + x, (scanning.y << 4) + y, (scanning.z << 4) + z);
				if (withinRange(samplePos) && visibleOre(samplePos, state)) pending.add(new Ore(samplePos.immutable(), state));
			}
			if (cellCursor >= 4096) { replace(scanning, pending); scanning = null; scanningSection = null; pending.clear(); }
		}
		if (veinMode()) veinInference.tick(world, center, sections.values(), veinRadius.intVal(), veinAssociation.intVal(), veinLimit.intVal(), range.intVal());
	}
	private void syncPrediction(Map<SectionKey, List<Ore>> result) {
		for (SectionKey key : List.copyOf(predictedSections.keySet())) if (!result.containsKey(key)) {
			predictedSections.remove(key); predictedRenderer.replace(key, List.of());
		}
		for (var entry : result.entrySet()) if (!entry.getValue().equals(predictedSections.get(entry.getKey()))) {
			predictedSections.put(entry.getKey(), entry.getValue()); predictedRenderer.replace(entry.getKey(), entry.getValue());
		}
		predictedTotal = predictedSections.values().stream().mapToInt(List::size).sum();
	}
	@Override public void onRender3D(Render3D render) {
		if (!inGame() || world != mc.level) { checkLifecycle(); return; }
		if (seedMode()) predictedRenderer.draw(render, opacity.floatVal() / 100 * .70f);
		renderer.draw(render, opacity.floatVal() / 100);
		if (veinMode()) OreCandidateRenderer.draw(render, veinInference.results(), opacity.floatVal() / 100);
		backfiller.render(render, backfillPreview.on());
	}
}
