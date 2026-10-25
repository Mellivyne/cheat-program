package com.cheatprogram.util;

import com.cheatprogram.module.visual.OreXray.Ore;
import com.cheatprogram.module.visual.OreXray.SectionKey;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.material.FluidState;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Seed-derived vanilla ore candidates, never a source of authoritative server blocks.
 * Only tick() touches ClientLevel. The worker owns copied palettes and every write made
 * by vanilla placement goes into private ProtoChunks. No chunk requests or packets.
 */
public final class OreSeedPredictor implements AutoCloseable {
    public enum Status { BOOTSTRAPPING, SCANNING, READY, UNSUPPORTED, FAILED, WAITING_CHUNKS }
    private static final int CACHE_LIMIT = 512;
    private static volatile Bootstrap sharedBootstrap;
    @FunctionalInterface private interface Ref0<R> extends Serializable { R call(WorldGenLevel level); }
    @FunctionalInterface private interface Ref1<A, R> extends Serializable { R call(WorldGenLevel level, A argument); }
    @FunctionalInterface private interface Ref2<A, B, R> extends Serializable { R call(WorldGenLevel level, A a, B b); }
    @FunctionalInterface private interface Ref3<A, B, C, R> extends Serializable { R call(WorldGenLevel level, A a, B b, C c); }
    @FunctionalInterface private interface Ref4<A, B, C, D, R> extends Serializable { R call(WorldGenLevel level, A a, B b, C c, D d); }
    @FunctionalInterface private interface Void1<A> extends Serializable { void call(WorldGenLevel level, A a); }
    private static final Map<String, String> METHODS = methodNames();

    /** Method references carry remapped method handles in the production JAR; literal Mojmap names do not. */
    private static Map<String, String> methodNames() {
        Map<String, String> names = new HashMap<>();
        bind(names, "getSeed", (Ref0<Long>)WorldGenLevel::getSeed);
        bind(names, "getMinY", (Ref0<Integer>)WorldGenLevel::getMinBuildHeight);
        bind(names, "getHeight", (Ref0<Integer>)WorldGenLevel::getHeight);
        bind(names, "getHeight", (Ref3<Heightmap.Types, Integer, Integer, Integer>)WorldGenLevel::getHeight);
        bind(names, "getBlockState", (Ref1<BlockPos, BlockState>)WorldGenLevel::getBlockState);
        bind(names, "getFluidState", (Ref1<BlockPos, FluidState>)WorldGenLevel::getFluidState);
        bind(names, "isEmptyBlock", (Ref1<BlockPos, Boolean>)WorldGenLevel::isEmptyBlock);
        bind(names, "getBiome", (Ref1<BlockPos, Holder<Biome>>)WorldGenLevel::getBiome);
        bind(names, "getNoiseBiome", (Ref3<Integer, Integer, Integer, Holder<Biome>>)WorldGenLevel::getNoiseBiome);
        bind(names, "getUncachedNoiseBiome", (Ref3<Integer, Integer, Integer, Holder<Biome>>)WorldGenLevel::getUncachedNoiseBiome);
        bind(names, "ensureCanWrite", (Ref1<BlockPos, Boolean>)WorldGenLevel::ensureCanWrite);
        bind(names, "getChunk", (Ref1<BlockPos, ChunkAccess>)WorldGenLevel::getChunk);
        bind(names, "getChunk", (Ref2<Integer, Integer, ChunkAccess>)WorldGenLevel::getChunk);
        bind(names, "getChunk", (Ref3<Integer, Integer, ChunkStatus, ChunkAccess>)WorldGenLevel::getChunk);
        bind(names, "getChunk", (Ref4<Integer, Integer, ChunkStatus, Boolean, ChunkAccess>)WorldGenLevel::getChunk);
        bind(names, "setBlock", (Ref3<BlockPos, BlockState, Integer, Boolean>)WorldGenLevel::setBlock);
        bind(names, "setBlock", (Ref4<BlockPos, BlockState, Integer, Integer, Boolean>)WorldGenLevel::setBlock);
        bind(names, "setCurrentlyGenerating", (Void1<Supplier<String>>)WorldGenLevel::setCurrentlyGenerating);
        return Map.copyOf(names);
    }
    private static void bind(Map<String, String> names, String logical, Serializable reference) {
        try {
            Method replace = reference.getClass().getDeclaredMethod("writeReplace"); replace.setAccessible(true);
            names.put(((SerializedLambda)replace.invoke(reference)).getImplMethodName(), logical);
        } catch (ReflectiveOperationException exception) { throw new ExceptionInInitializerError(exception); }
    }
    private record GeneratorData(ChunkGenerator generator, List<FeatureSorter.StepFeatureData> steps) { }
    private record Bootstrap(HolderLookup.Provider lookup, Map<ResourceKey<Level>, GeneratorData> dimensions) { }
    private record ChunkCopy(int x, int z, LevelChunkSection[] sections) { }
    private record Snapshot(int x, int z, int minY, int height, long seed, ResourceKey<Level> dimension,
                            Registry<Biome> biomes, List<ChunkCopy> chunks, Set<Block> selected, boolean terrain) { }
    private record ChunkResult(long key, List<Ore> ores) { }

    private final Map<Long, List<Ore>> cache = new LinkedHashMap<>(64, .75F, true);
    private Map<SectionKey, List<Ore>> results = Map.of();
    private ThreadPoolExecutor worker;
    private Future<Bootstrap> bootFuture;
    private Future<ChunkResult> job;
    private Bootstrap bootstrap;
    private ClientLevel world;
    private long seed;
    private Set<Block> selected = Set.of();
    private boolean terrain;
    private Status status = Status.WAITING_CHUNKS;
    private int ticks;
    private String failure = "";
    private Vec3 publishedCenter;
    private int publishedRadius, publishedLimit;
    private boolean dirty = true;
    private ChunkPos plannedChunk;
    private int plannedRadius;
    private List<ChunkPos> plannedCandidates = List.of();
    private List<Ore> validation = List.of();
    private int validationCursor;
    private final Set<Long> removed = new LinkedHashSet<>();

    public Status status() { return status; }
    /** An error class only; seed, server address and registry contents are never logged. */
    public String failure() { return failure; }
    public Map<SectionKey, List<Ore>> results() { return results; }

    private ThreadPoolExecutor executor() {
        if (worker == null || worker.isShutdown()) {
            worker = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "CheatProgram-OrePrediction"); thread.setDaemon(true); return thread;
            });
        }
        return worker;
    }

    /** Main/client thread only. Captures at most one already-loaded 3x3 region per tick. */
    public void tick(ClientLevel level, long worldSeed, Vec3 center, int radius, Set<Block> targets,
                     int maxModels, boolean terrainCheck) {
        if (level == null || targets.isEmpty()) { clear(); return; }
        Set<Block> selection = Set.copyOf(targets);
        if (world != level || seed != worldSeed || !selected.equals(selection) || terrain != terrainCheck) {
            clear(); world = level; seed = worldSeed; selected = selection; terrain = terrainCheck;
        }
        if (!level.dimension().equals(Level.OVERWORLD) && !level.dimension().equals(Level.NETHER)) {
            status = Status.UNSUPPORTED; results = Map.of(); return;
        }
        if (status == Status.FAILED) return;
        ticks++;
        try {
            if (bootstrap == null) {
                if (sharedBootstrap != null) bootstrap = sharedBootstrap;
                else {
                    if (bootFuture == null) bootFuture = executor().submit(OreSeedPredictor::bootstrap);
                    if (!bootFuture.isDone()) { status = Status.BOOTSTRAPPING; return; }
                    bootstrap = bootFuture.get(); bootFuture = null;
                }
            }
            GeneratorData data = bootstrap.dimensions.get(level.dimension());
            int expectedMin = level.dimension().equals(Level.NETHER) ? 0 : -64;
            int expectedHeight = level.dimension().equals(Level.NETHER) ? 256 : 384;
            if (data == null || level.getMinBuildHeight() != expectedMin || level.getHeight() != expectedHeight) {
                status = Status.UNSUPPORTED; results = Map.of(); return;
            }
            if (job != null && job.isDone()) {
                ChunkResult completed = job.get(); job = null; cache.put(completed.key, completed.ores);
                while (cache.size() > CACHE_LIMIT) cache.remove(cache.keySet().iterator().next());
                dirty = true;
            }
            validatePublished(level);
            int visibleRadius = Math.min(128, Math.max(16, radius)), visibleLimit = Math.min(4096, Math.max(0, maxModels));
            if (dirty || publishedCenter == null || publishedCenter.distanceToSqr(center) >= 1
                    || publishedRadius != visibleRadius || publishedLimit != visibleLimit) {
                publish(center, visibleRadius, visibleLimit); dirty = false;
                publishedCenter = center; publishedRadius = visibleRadius; publishedLimit = visibleLimit;
            }
            if (job != null) { status = Status.SCANNING; return; }
            ChunkPos currentChunk = new ChunkPos(BlockPos.containing(center));
            if (!currentChunk.equals(plannedChunk) || plannedRadius != visibleRadius) {
                plannedCandidates = candidates(center, visibleRadius); plannedChunk = currentChunk; plannedRadius = visibleRadius;
            }
            boolean missing = false;
            for (ChunkPos candidate : plannedCandidates) {
                if (cache.containsKey(candidate.toLong())) continue;
                Snapshot snapshot = snapshot(level, candidate);
                if (snapshot == null) { missing = true; continue; }
                // Worker captures this isolated value and immutable bootstrap only, never the live level.
                Bootstrap registry = bootstrap;
                job = executor().submit(() -> predict(snapshot, registry, data));
                status = Status.SCANNING; return;
            }
            status = missing ? Status.WAITING_CHUNKS : Status.READY;
        } catch (CancellationException ignored) { job = null; }
        catch (Exception failure) {
            this.failure = failure.getCause() == null ? failure.getClass().getSimpleName() : failure.getCause().getClass().getSimpleName();
            status = Status.FAILED; job = null;
        }
    }

    private static synchronized Bootstrap bootstrap() {
        if (sharedBootstrap != null) return sharedBootstrap;
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        var preset = lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value();
        Registry<LevelStem> stems = preset.createWorldDimensions().dimensions();
        Map<ResourceKey<Level>, GeneratorData> dimensions = new HashMap<>();
        for (var pair : Map.of(Level.OVERWORLD, LevelStem.OVERWORLD, Level.NETHER, LevelStem.NETHER).entrySet()) {
            ChunkGenerator generator = stems.get(pair.getValue()).generator();
            List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(
                    List.copyOf(generator.getBiomeSource().possibleBiomes()), holder -> generator.getBiomeGenerationSettings(holder).features(), true);
            dimensions.put(pair.getKey(), new GeneratorData(generator, steps));
        }
        return sharedBootstrap = new Bootstrap(lookup, Map.copyOf(dimensions));
    }

    private static List<ChunkPos> candidates(Vec3 center, int radius) {
        radius = Math.min(128, Math.max(16, radius)) + 16; // Neighbor-origin veins can enter the visible sphere.
        int minX = ((int)Math.floor(center.x - radius)) >> 4, maxX = ((int)Math.floor(center.x + radius)) >> 4;
        int minZ = ((int)Math.floor(center.z - radius)) >> 4, maxZ = ((int)Math.floor(center.z + radius)) >> 4;
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) chunks.add(new ChunkPos(x, z));
        chunks.sort(Comparator.comparingDouble(pos -> {
            double dx = pos.getMiddleBlockX() - center.x, dz = pos.getMiddleBlockZ() - center.z; return dx * dx + dz * dz;
        }));
        return chunks;
    }

    private Snapshot snapshot(ClientLevel level, ChunkPos origin) {
        List<LevelChunk> loaded = new ArrayList<>(9);
        for (int x = origin.x - 1; x <= origin.x + 1; x++) for (int z = origin.z - 1; z <= origin.z + 1; z++) {
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) return null;
            loaded.add(chunk);
        }
        List<ChunkCopy> copies = new ArrayList<>(9);
        for (LevelChunk chunk : loaded) {
            LevelChunkSection[] original = chunk.getSections(), copied = new LevelChunkSection[original.length];
            for (int i = 0; i < original.length; i++) copied[i] = new LevelChunkSection(original[i].getStates().copy(),((net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>)original[i].getBiomes()).copy());
            copies.add(new ChunkCopy(chunk.getPos().x, chunk.getPos().z, copied));
        }
        return new Snapshot(origin.x, origin.z, level.getMinBuildHeight(), level.getHeight(), seed, level.dimension(),
                level.registryAccess().registryOrThrow(Registries.BIOME), copies, selected, terrain);
    }

    private static boolean ore(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE || block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE || block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE
                || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE
                || block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE
                || block == Blocks.NETHER_QUARTZ_ORE || block == Blocks.NETHER_GOLD_ORE || block == Blocks.ANCIENT_DEBRIS;
    }

    private static ChunkResult predict(Snapshot snapshot, Bootstrap bootstrap, GeneratorData generator) {
        Sandbox sandbox = new Sandbox(snapshot, bootstrap);
        WorldGenLevel level = (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, sandbox);
        Set<Holder<Biome>> nearbyBiomes = new HashSet<>();
        for (ChunkCopy copy : snapshot.chunks) for (LevelChunkSection section : copy.sections) {
            section.getBiomes().getAll(holder -> holder.unwrapKey().flatMap(key -> bootstrap.lookup.lookupOrThrow(Registries.BIOME).get(key)).ifPresent(nearbyBiomes::add));
        }
        nearbyBiomes.retainAll(generator.generator.getBiomeSource().possibleBiomes());
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long decorationSeed = random.setDecorationSeed(snapshot.seed, snapshot.x << 4, snapshot.z << 4);
        BlockPos origin = new BlockPos(snapshot.x << 4, snapshot.minY, snapshot.z << 4);
        for (int step = 0; step < generator.steps.size(); step++) {
            interrupted();
            FeatureSorter.StepFeatureData stepData = generator.steps.get(step);
            Set<Integer> indices = new TreeSet<>();
            for (Holder<Biome> biome : nearbyBiomes) {
                List<HolderSet<PlacedFeature>> features = generator.generator.getBiomeGenerationSettings(biome).features();
                if (step < features.size()) features.get(step).stream().map(Holder::value).forEach(feature -> indices.add(stepData.indexMapping().applyAsInt(feature)));
            }
            for (int index : indices) {
                PlacedFeature feature = stepData.features().get(index);
                var configured = feature.feature().value();
                // Every selected ore feature is seeded by its ORIGINAL index, including skipped non-ore entries.
                // Other vanilla OreConfigurations (dirt/gravel/stone variants) also participate in replacement order.
                if (!(configured.config() instanceof OreConfiguration) || (configured.feature() != Feature.ORE && configured.feature() != Feature.SCATTERED_ORE)) continue;
                random.setFeatureSeed(decorationSeed, index, step);
                feature.placeWithBiomeCheck(level, generator.generator, random, origin);
                interrupted();
            }
        }
        List<Ore> ores = new ArrayList<>();
        for (ProtoChunk chunk : sandbox.chunks.values()) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                interrupted(); LevelChunkSection section = sections[index];
                if (!section.maybeHas(state -> snapshot.selected.contains(state.getBlock()) && ore(state))) continue;
                int yBase = snapshot.minY + (index << 4);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (snapshot.selected.contains(state.getBlock()) && ore(state)) ores.add(new Ore(new BlockPos((chunk.getPos().x << 4) + x, yBase + y, (chunk.getPos().z << 4) + z), state));
                }
            }
        }
        return new ChunkResult(ChunkPos.asLong(snapshot.x, snapshot.z), List.copyOf(ores));
    }

    private static void interrupted() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }

    private static final class Sandbox implements InvocationHandler {
        final Snapshot snapshot;
        final Bootstrap bootstrap;
        final Map<Long, ProtoChunk> chunks = new HashMap<>();
        final LevelHeightAccessor heights;
        final BiomeManager biomeManager;
        final Map<Heightmap.Types, Map<Long, Integer>> heightCache = new HashMap<>();
        Sandbox(Snapshot snapshot, Bootstrap bootstrap) {
            this.snapshot = snapshot; this.bootstrap = bootstrap;
            heights = LevelHeightAccessor.create(snapshot.minY, snapshot.height);
            biomeManager = new BiomeManager((x, y, z) -> biome(x << 2, y << 2, z << 2), BiomeManager.obfuscateSeed(snapshot.seed));
            boolean nether = snapshot.dimension.equals(Level.NETHER);
            for (ChunkCopy copy : snapshot.chunks) {
                for (int i = 0; i < copy.sections.length; i++) {
                    interrupted(); LevelChunkSection section = copy.sections[i];
                    if (snapshot.terrain && !section.maybeHas(OreSeedPredictor::ore)) continue;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!snapshot.terrain || ore(state)) {
                            int worldY = snapshot.minY + (i << 4) + y;
                            BlockState host = (nether ? Blocks.NETHERRACK : worldY < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
                            section.setBlockState(x, y, z, host, false);
                        }
                    }
                }
                ProtoChunk chunk = new ProtoChunk(new ChunkPos(copy.x, copy.z), UpgradeData.EMPTY, copy.sections,
                        new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), heights, snapshot.biomes, null);
                chunks.put(chunk.getPos().toLong(), chunk);
            }
        }
        BlockState state(BlockPos pos) {
            if (heights.isOutsideBuildHeight(pos)) return Blocks.VOID_AIR.defaultBlockState();
            ProtoChunk chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            return chunk == null ? Blocks.VOID_AIR.defaultBlockState() : chunk.getBlockState(pos);
        }
        Holder<Biome> biome(int x, int y, int z) {
            ProtoChunk chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (chunk == null) throw new IllegalStateException("Prediction crossed captured biome region");
            int section = Math.max(0, Math.min(chunk.getSections().length - 1, heights.getSectionIndex(y)));
            Holder<Biome> holder = chunk.getSections()[section].getNoiseBiome((x >> 2) & 3, (y >> 2) & 3, (z >> 2) & 3);
            return holder.unwrapKey().map(key -> bootstrap.lookup.lookupOrThrow(Registries.BIOME).getOrThrow(key))
                    .orElseThrow(() -> new IllegalStateException("Unregistered biome"));
        }
        int height(Heightmap.Types type, int x, int z) {
            long key = ((long)x << 32) ^ (z & 0xffffffffL);
            return heightCache.computeIfAbsent(type, ignored -> new HashMap<>()).computeIfAbsent(key, ignored -> {
                BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
                for (int y = snapshot.minY + snapshot.height - 1; y >= snapshot.minY; y--) {
                    position.set(x, y, z); BlockState block = state(position);
                    if (type.isOpaque().test(block)) return y + 1;
                }
                return snapshot.minY;
            });
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            interrupted(); String name = METHODS.getOrDefault(method.getName(), method.getName());
            switch (name) {
                case "getSeed": return snapshot.seed;
                case "getMinY": return snapshot.minY;
                case "getHeight": return args == null || args.length == 0 ? snapshot.height : height((Heightmap.Types)args[0], (Integer)args[1], (Integer)args[2]);
                case "getBlockState": return state((BlockPos)args[0]);
                case "getFluidState": return state((BlockPos)args[0]).getFluidState();
                case "isEmptyBlock": return state((BlockPos)args[0]).isAir();
                case "getBiome": return biomeManager.getBiome((BlockPos)args[0]);
                case "getNoiseBiome", "getUncachedNoiseBiome": return biome((Integer)args[0] << 2, (Integer)args[1] << 2, (Integer)args[2] << 2);
                case "ensureCanWrite": { BlockPos pos = (BlockPos)args[0]; return !heights.isOutsideBuildHeight(pos) && chunks.containsKey(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)); }
                case "getChunk": {
                    int x, z; if (args[0] instanceof BlockPos pos) { x = pos.getX() >> 4; z = pos.getZ() >> 4; } else { x = (Integer)args[0]; z = (Integer)args[1]; }
                    ProtoChunk chunk = chunks.get(ChunkPos.asLong(x, z));
                    if (chunk == null) throw new IllegalStateException("Prediction requested uncaptured chunk"); return chunk;
                }
                case "setBlock": {
                    BlockPos pos = (BlockPos)args[0]; ProtoChunk chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                    if (chunk == null || heights.isOutsideBuildHeight(pos)) return false;
                    chunk.setBlockState(pos, (BlockState)args[1], false); return true;
                }
                case "setCurrentlyGenerating": return null;
                case "toString": return "IsolatedOrePredictionRegion";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default:
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    throw new UnsupportedOperationException("Unsupported prediction method: " + name);
            }
        }
    }

    private void publish(Vec3 center, int radius, int limit) {
        if (limit == 0) { results = Map.of(); validation = List.of(); return; }
        Map<Long, Ore> unique = new HashMap<>(); double squared = (double)radius * radius;
        for (List<Ore> chunk : cache.values()) for (Ore ore : chunk) if (ore.position().distToCenterSqr(center) <= squared) unique.put(ore.position().asLong(), ore);
        List<Ore> nearest = new ArrayList<>(unique.values());
        nearest.sort(Comparator.comparingDouble(ore -> ore.position().distToCenterSqr(center)));
        validation = List.copyOf(nearest.subList(0, Math.min(limit, nearest.size()))); validationCursor = 0;
        Map<SectionKey, List<Ore>> groups = new LinkedHashMap<>();
        for (Ore ore : validation) {
            BlockPos pos = ore.position(); if (removed.contains(pos.asLong())) continue;
            SectionKey key = new SectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ore);
        }
        Map<SectionKey, List<Ore>> immutable = new LinkedHashMap<>(); groups.forEach((key, list) -> immutable.put(key, List.copyOf(list)));
        results = Map.copyOf(immutable);
    }

    /** Bounded main-thread pruning. Never interpret a received ore ID as proof of a predicted ore. */
    private void validatePublished(ClientLevel level) {
        if (validation.isEmpty()) return;
        boolean changed = false;
        for (int checked = 0; checked < Math.min(256, validation.size()); checked++) {
            if (validationCursor >= validation.size()) validationCursor = 0;
            BlockPos position = validation.get(validationCursor++).position();
            LevelChunk chunk = level.getChunkSource().getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false);
            if (chunk == null || level.isOutsideBuildHeight(position)) continue;
            BlockState actual = chunk.getBlockState(position);
            if (actual.isAir() || (terrain && !actual.canOcclude())) changed |= removed.add(position.asLong());
        }
        if (changed) {
            while (removed.size() > 65536) removed.remove(removed.iterator().next());
            // Filter immediately, without restarting the round-robin cursor or sorting all candidates each tick.
            Map<SectionKey, List<Ore>> pruned = new LinkedHashMap<>();
            results.forEach((key, ores) -> {
                List<Ore> keep = ores.stream().filter(ore -> !removed.contains(ore.position().asLong())).toList();
                if (!keep.isEmpty()) pruned.put(key, keep);
            });
            results = Map.copyOf(pruned);
        }
    }

    public void clear() {
        if (job != null) job.cancel(true); job = null;
        if (bootFuture != null) bootFuture.cancel(true); bootFuture = null;
        cache.clear(); results = Map.of(); world = null; selected = Set.of(); status = Status.WAITING_CHUNKS; failure = ""; ticks = 0;
        publishedCenter = null; dirty = true; plannedChunk = null; plannedCandidates = List.of();
        validation = List.of(); validationCursor = 0; removed.clear();
    }
    @Override public void close() { clear(); if (worker != null) worker.shutdownNow(); worker = null; }
}
