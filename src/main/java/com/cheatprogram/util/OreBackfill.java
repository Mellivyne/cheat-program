package com.cheatprogram.util;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.render.Render3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real, opt-in tunnel backfill. Only server block-update hooks confirm a placed block. */
public final class OreBackfill {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Set<Block> TERRAIN = Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.COBBLESTONE,
            Blocks.COBBLED_DEEPSLATE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.TUFF,
            Blocks.NETHERRACK, Blocks.END_STONE, Blocks.BASALT, Blocks.BLACKSTONE);
    private static OreBackfill instance;
    private final Module owner;
    private final AssistedInteractions interactions = new AssistedInteractions();
    private final Deque<Vec3> trail = new ArrayDeque<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final Map<BlockPos, Pending> pending = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> retryAfter = new HashMap<>();
    private ClientLevel world;
    private LocalPlayer body;
    private Vec3 previous, heading = Vec3.ZERO;
    private int tick, nextUse, confirmed, rejected, timedOut;
    private boolean active, invalidId, missingMaterial;
    private record Candidate(BlockPos pos, Block block, BlockHitResult hit) { }
    private record Pending(Block block, int deadline) { }
    public record Options(boolean enabled, boolean matching, String blockId, double distance,
                          int radius, int height, int interval, int budget, String rotation, boolean preview) { }

    public OreBackfill(Module owner) { this.owner = owner; instance = this; }

    /** Invoke only for a received authoritative block/section update, never Level.setBlock. */
    public static void onServerBlockUpdate(BlockPos pos, BlockState state) {
        OreBackfill current = instance;
        if (current == null || !current.active || !current.owner.isEnabled()
                || current.world != MC.level || current.body != MC.player) return;
        Pending attempt = current.pending.remove(pos);
        if (attempt == null) return;
        if (state.is(attempt.block)) current.confirmed++;
        else current.rejected++;
        current.retryAfter.put(pos.immutable(), current.tick + 100);
    }

    public void clear() {
        interactions.reset(); trail.clear(); candidates.clear(); pending.clear(); retryAfter.clear();
        world = null; body = null; previous = null; heading = Vec3.ZERO; active = false;
        tick = nextUse = confirmed = rejected = timedOut = 0; invalidId = missingMaterial = false;
    }
    public void checkLifecycle() {
        if (!owner.isEnabled() || body != null && (body != MC.player || world != MC.level || !body.isAlive())) clear();
    }
    public String status() {
        if (!active) return "";
        String key = invalidId ? "invalidId" : missingMaterial ? "noMaterial" : "counts";
        return " · " + Lang.t("ui.orebackfill." + key, confirmed, pending.size(), candidates.size(), rejected + timedOut);
    }

    public void tick(Options options) {
        checkLifecycle();
        if (!options.enabled || MC.player == null || MC.level == null) { if (active) clear(); return; }
        if (world != MC.level || body != MC.player) { clear(); world = MC.level; body = MC.player; }
        active = true; tick++; invalidId = missingMaterial = false; candidates.clear();
        pending.entrySet().removeIf(entry -> {
            if (tick < entry.getValue().deadline) return false;
            timedOut++; retryAfter.put(entry.getKey(), tick + 100); return true;
        });
        retryAfter.entrySet().removeIf(entry -> tick >= entry.getValue());
        Vec3 now = body.position();
        if (previous == null) { previous = now; trail.addLast(now); return; }
        Vec3 movement = now.subtract(previous);
        // A correction, dimension transfer or another movement experiment is not a tunnel trail.
        if (movement.lengthSqr() > 16) {
            trail.clear(); trail.addLast(now); previous = now; heading = Vec3.ZERO; return;
        }
        Vec3 horizontal = new Vec3(movement.x, 0, movement.z);
        if (horizontal.lengthSqr() > .0064) heading = horizontal.normalize();
        previous = now;
        if (trail.isEmpty() || trail.peekLast().distanceToSqr(now) >= .09) trail.addLast(now);
        while (trail.size() > 128) trail.removeFirst();
        trail.removeIf(point -> point.distanceToSqr(now) > 144);
        if (heading.lengthSqr() < .5 || !interactions.ready()) return;
        Block specified = options.matching ? null : parse(options.blockId);
        if (!options.matching && specified == null) { invalidId = true; return; }
        int scanned = 0;
        Set<BlockPos> seen = new java.util.HashSet<>();
        for (Vec3 visited : trail) {
            Vec3 behind = visited.subtract(now);
            double horizontalDistance = Math.sqrt(behind.x * behind.x + behind.z * behind.z);
            if (horizontalDistance < options.distance || horizontalDistance > options.distance + 2 + options.radius
                    || behind.dot(heading) > -options.distance * .65 || Math.abs(behind.y) > 2) continue;
            BlockPos base = BlockPos.containing(visited);
            for (int y = 0; y < options.height; y++) for (int x = -options.radius; x <= options.radius; x++)
                for (int z = -options.radius; z <= options.radius; z++) {
                    if (++scanned > 384) break;
                    BlockPos pos = base.offset(x, y, z);
                    if (!seen.add(pos) || pending.containsKey(pos) || retryAfter.containsKey(pos) || !safeAir(pos, options.distance)) continue;
                    Block block = options.matching ? match(pos) : specified;
                    if (block == null || !(block.asItem() instanceof BlockItem) || AssistedInteractions.find(block.asItem()) < 0) {
                        missingMaterial = true; continue;
                    }
                    BlockHitResult hit = AssistedInteractions.placement(pos, false);
                    if (hit != null) candidates.add(new Candidate(pos.immutable(), block, hit));
                }
            if (scanned > 384 || candidates.size() >= 64) break;
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.pos.distToCenterSqr(body.position())));
        if (tick < nextUse || pending.size() >= 4) return;
        int attempts = 0;
        for (Candidate candidate : List.copyOf(candidates)) {
            if (attempts >= options.budget || pending.size() >= 4) break;
            if (!safeAir(candidate.pos, options.distance) || !AssistedInteractions.reachable(candidate.hit)) continue;
            if (!AssistedInteractions.followReady(candidate.hit.getLocation(), options.rotation)) break;
            attempts++;
            var result = interactions.use(owner, candidate.block.asItem(), candidate.hit, options.rotation, true, false);
            nextUse = tick + Math.max(1, options.interval);
            if (result.consumesAction()) pending.put(candidate.pos, new Pending(candidate.block, tick + 60));
            else retryAfter.put(candidate.pos, tick + 20);
        }
    }

    private Block parse(String text) {
        ResourceLocation id = ResourceLocation.tryParse(text.trim());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        // A full, stationary material prevents water, powder snow, gravity and plant traps.
        return block.asItem() instanceof BlockItem && !(block instanceof FallingBlock)
                && block.defaultBlockState().isCollisionShapeFullBlock(world, body.blockPosition())
                && block.defaultBlockState().getFluidState().isEmpty()
                && block != Blocks.MAGMA_BLOCK && block != Blocks.TNT ? block : null;
    }
    private Block match(BlockPos pos) {
        Map<Block, Integer> counts = new HashMap<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!loaded(neighbor)) continue;
            Block block = matchingMaterial(world.getBlockState(neighbor).getBlock());
            if (block != null) counts.merge(block, 3, Integer::sum);
        }
        // Also inspect the terrain immediately beside the real player, not the ore camera.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = body.blockPosition().relative(direction);
            if (!loaded(neighbor)) continue;
            Block block = matchingMaterial(world.getBlockState(neighbor).getBlock());
            if (block != null) counts.merge(block, 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
    private Block matchingMaterial(Block terrain) {
        if (!TERRAIN.contains(terrain)) return null;
        if (AssistedInteractions.find(terrain.asItem()) >= 0) return terrain;
        Block fallback = terrain == Blocks.STONE ? Blocks.COBBLESTONE
                : terrain == Blocks.DEEPSLATE ? Blocks.COBBLED_DEEPSLATE : null;
        return fallback != null && AssistedInteractions.find(fallback.asItem()) >= 0 ? fallback : null;
    }
    private boolean loaded(BlockPos pos) {
        return pos.getY() >= world.getMinY() && pos.getY() <= world.getMaxY()
                && world.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null;
    }
    private boolean safeAir(BlockPos pos, double distance) {
        if (!loaded(pos) || !world.getWorldBorder().isWithinBounds(pos) || !world.getBlockState(pos).isAir()) return false;
        Vec3 delta = Vec3.atCenterOf(pos).subtract(body.position());
        if (delta.x * delta.x + delta.z * delta.z < distance * distance || delta.dot(heading) > -.65) return false;
        AABB block = new AABB(pos);
        // Keep breathing, standing and one forward escape step free, even while crouched.
        AABB breathing = body.getBoundingBox().inflate(.8, .35, .8);
        AABB escape = breathing.expandTowards(heading.scale(1.2));
        if (block.intersects(breathing) || block.intersects(escape)
                || !world.getEntities((net.minecraft.world.entity.Entity)null, block.inflate(.02), entity -> !entity.isRemoved()).isEmpty()) return false;
        // Never seal the retreat when a wall already blocks the immediate forward exit.
        AABB forwardExit = body.getBoundingBox().move(heading.scale(1.2));
        for (BlockPos exit : BlockPos.betweenClosed(forwardExit)) if (!loaded(exit)) return false;
        if (!world.noCollision(body, forwardExit)) return false;
        return true;
    }
    public void render(Render3D render, boolean preview) {
        if (!active || !preview || world != MC.level || body != MC.player) return;
        for (Candidate candidate : candidates) render.box(new AABB(candidate.pos).deflate(.015), 0x1839D8D0, 0xBB39D8D0, 1);
        for (BlockPos pos : pending.keySet()) render.box(new AABB(pos).deflate(.01), 0x26FFB74D, 0xFFFFB74D, 1.5f);
    }
}
