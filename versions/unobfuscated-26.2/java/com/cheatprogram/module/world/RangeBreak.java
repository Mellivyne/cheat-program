package com.cheatprogram.module.world;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Render3D;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit bounded operations; a remote connection uses normal, reachable vanilla mining. */
public final class RangeBreak extends Module {
    private final NumberSetting radiusX = add(new NumberSetting("radiusX", 2, 0, 16, 1));
    private final NumberSetting radiusY = add(new NumberSetting("radiusY", 2, 0, 16, 1));
    private final NumberSetting radiusZ = add(new NumberSetting("radiusZ", 2, 0, 16, 1));
    private final BoolSetting drops = add(new BoolSetting("drops", false));
    private final BoolSetting unbreakable = add(new BoolSetting("unbreakable", false));
    private final BoolSetting keepBreaking = add(new BoolSetting("keepBreaking", false));
    private final NumberSetting repeatDelay = add(new NumberSetting("repeatDelay", 100, 50, 2000, 10, "ms")
            .visibleWhen(keepBreaking::on));
    private final AtomicLong generation = new AtomicLong();
    private volatile Job job;
    private volatile boolean executionAllowed;
    private long nextPassAt;
    private int selectedSlot = -1;
    private long manualUntil;

    public RangeBreak() { super("rangebreak", Category.WORLD); }
    // Continuous destruction also requires a deliberate switch-on in each world/session.
    @Override public boolean isOneShot() { return true; }
    @Override public String suffix() {
        Job active = job;
        return active == null ? dimensions() : active.progress + " / " + active.total;
    }
    private String dimensions() { return (radiusX.intVal() * 2 + 1) + " × " + (radiusY.intVal() * 2 + 1) + " × " + (radiusZ.intVal() * 2 + 1); }

    @Override public void onEnable() {
        cancel();
        if (!inGame() || mc.gameMode == null || !mc.player.isAlive() || mc.player.isSpectator()) {
            Notifications.push(name(), Lang.t("ui.rangebreak.unavailable"), false);
            setEnabled(false);
            return;
        }
        selectedSlot = mc.player.getInventory().getSelectedSlot();
        createPass();
    }

    private void createPass() {
        BlockPos center = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos().immutable() : mc.player.blockPosition().immutable();
        int rx = radiusX.intVal(), ry = radiusY.intVal(), rz = radiusZ.intVal();
        job = new Job(mc.getSingleplayerServer(), mc.level, mc.level.dimension(), mc.player.getUUID(), generation.get(),
                center.offset(-rx, -ry, -rz), rx * 2 + 1, ry * 2 + 1, rz * 2 + 1, drops.on(), unbreakable.on());
        nextPassAt = 0;
    }

    @Override public void onDisable() { cancel(); }
    private void cancel() {
        executionAllowed = false;
        generation.incrementAndGet();
        Job active = job;
        if (active != null) active.stopMining();
        job = null;
        selectedSlot = -1;
        manualUntil = nextPassAt = 0;
    }

    /** Root calls this outside worlds too, so a queued operation never crosses a world change. */
    public static void tickLifecycle() {
        RangeBreak module = ModuleManager.get(RangeBreak.class);
        if (module != null) module.checkLifecycle();
    }

    public void checkLifecycle() {
        Job active = job;
        if (active == null && isEnabled()) { setEnabled(false); return; }
        if (active != null && (mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()
                || mc.player != active.playerInstance || !active.player.equals(mc.player.getUUID()) || mc.level != active.clientLevel
                || mc.gameMode != active.gameMode || mc.getSingleplayerServer() != active.server)) setEnabled(false);
    }

    private static boolean buildingBusy() {
        BuildingBlueprints buildings = ModuleManager.get(BuildingBlueprints.class);
        return buildings != null && buildings.busy();
    }

    private static boolean canOperate() {
        return inGame() && mc.gameMode != null && mc.getConnection() != null && mc.gui.screen() == null
                && mc.gui.overlay() == null && mc.isWindowActive() && !mc.isPaused()
                && mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.isPassenger()
                && !mc.player.isUsingItem() && !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown()
                && !AiController.isRunning();
    }

    /** Called only around vanilla's no-button stop, not around the module's own explicit abort. */
    public static boolean preserveMiningProgress() {
        RangeBreak module = ModuleManager.get(RangeBreak.class);
        if (module == null || !module.isEnabled() || !module.executionAllowed || !canOperate() || buildingBusy()) return false;
        Job active = module.job;
        return active != null && active.server == null && active.mining && active.valid();
    }

    @Override public void onTick() {
        checkLifecycle();
        Job active = job;
        if (active == null) return;
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        if (selectedSlot != currentSlot) {
            selectedSlot = currentSlot;
            manualUntil = System.currentTimeMillis() + 350;
        }
        executionAllowed = canOperate() && System.currentTimeMillis() >= manualUntil && !buildingBusy();
        active.waiting = buildingBusy();
        if (!executionAllowed) {
            active.stopMining();
            return;
        }
        if (active.finished) {
            if (!keepBreaking.on()) { setEnabled(false); return; }
            if (System.currentTimeMillis() < nextPassAt) return;
            createPass();
            active = job;
        }
        if (active.server == null) {
            active.stepRemote();
            return;
        }
        if (!active.queued.compareAndSet(false, true)) return;
        Job scheduled = active;
        scheduled.server.execute(() -> {
            try {
                if (scheduled.valid() && executionAllowed) scheduled.step();
            } catch (Exception failure) {
                CheatProgram.LOGGER.warn("Range destruction failed", failure);
                scheduled.finish("ui.rangebreak.failed", false);
            } finally { scheduled.queued.set(false); }
        });
    }

    @Override public void onRender3D(Render3D renderer) {
        Job active = job;
        if (active == null || mc.level != active.clientLevel) return;
        BlockPos p = active.minimum;
        renderer.begin();
        try {
            renderer.box(new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + active.width,
                    p.getY() + active.height, p.getZ() + active.depth), 0x06FFFFFF, 0xEDFFFFFF, 2);
        } finally { renderer.end(); }
    }

    @Override public void onRender2D(HudContext hud) {
        Job active = job;
        if (active == null || mc.level != active.clientLevel || mc.gui.screen() != null) return;
        String text = active.waiting ? Lang.t("ui.rangebreak.waiting")
                : active.finished ? Lang.t("ui.rangebreak.repeating")
                : Lang.t("ui.rangebreak.progress", active.progress, active.total);
        if (active.server == null) text += " · " + Lang.t("ui.rangebreak.serverRules");
        float width = Math.min(hud.width - 24, hud.c.textWidth(Fonts.Face.REGULAR, text, 10) + 24);
        hud.panel((hud.width - width) / 2, hud.height - 66, width, 28, 12);
        hud.c.textMid(Fonts.Face.REGULAR, text, hud.width / 2, hud.height - 52, 10, 0xEFFFFFFF);
    }

    private final class Job {
        final IntegratedServer server;
        final ClientLevel clientLevel;
        final MultiPlayerGameMode gameMode;
        final Object playerInstance;
        final ResourceKey<Level> dimension;
        final UUID player;
        final long token;
        final BlockPos minimum;
        final int width, height, depth, total;
        final boolean dropItems, breakUnbreakable;
        final AtomicBoolean queued = new AtomicBoolean();
        volatile int progress, broken, skipped;
        volatile boolean waiting, finished;
        ServerLevel level;
        int lastServerTick = Integer.MIN_VALUE;
        int lastClientTick = Integer.MIN_VALUE;
        int nextMiningTick;
        boolean mining;
        BlockPos miningPos;

        Job(IntegratedServer server, ClientLevel clientLevel, ResourceKey<Level> dimension, UUID player,
                long token, BlockPos minimum, int width, int height, int depth, boolean dropItems, boolean breakUnbreakable) {
            this.server = server; this.clientLevel = clientLevel; this.gameMode = mc.gameMode;
            this.playerInstance = mc.player;
            this.dimension = dimension; this.player = player;
            this.token = token; this.minimum = minimum; this.width = width; this.height = height; this.depth = depth;
            this.total = width * height * depth; this.dropItems = dropItems; this.breakUnbreakable = breakUnbreakable;
        }
        boolean valid() {
            return token == generation.get() && job == this && !finished && isEnabled()
                    && mc.player == playerInstance && mc.level == clientLevel && mc.gameMode == gameMode
                    && mc.getSingleplayerServer() == server;
        }
        BlockPos at(int index) {
            return minimum.offset(index % width, index / (width * depth), index / width % depth);
        }
        void stopMining() {
            // Only ever abort a target this job started, never a user's unrelated manual action.
            if (mining && mc.level == clientLevel && mc.gameMode == gameMode && mc.player == playerInstance
                    && player.equals(mc.player.getUUID())) gameMode.stopDestroyBlock();
            mining = false;
            miningPos = null;
        }
        void step() {
            if (!valid() || !executionAllowed || mc.gui.screen() != null || mc.isPaused() || buildingBusy()) return;
            int tick = server.getTickCount();
            if (tick == lastServerTick) return;
            lastServerTick = tick;
            if (level == null) level = server.getLevel(dimension);
            if (level == null) { finish("ui.rangebreak.failed", false); return; }
            long stop = System.nanoTime() + 4_000_000L;
            int visited = 0;
            while (progress < total && visited++ < 4096 && System.nanoTime() < stop && valid() && executionAllowed) {
                if (buildingBusy()) { waiting = true; return; }
                BlockPos position = at(progress);
                // Never request or generate an unloaded chunk for a destructive operation.
                if (!level.isInWorldBounds(position) || !level.getWorldBorder().isWithinBounds(position)
                        || !level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4)) {
                    skipped++; progress++; continue;
                }
                BlockState state = level.getBlockState(position);
                if (state.isAir() || !breakUnbreakable && state.getDestroySpeed(level, position) < 0) {
                    skipped++; progress++; continue;
                }
                if (!valid() || !executionAllowed || mc.gui.screen() != null || mc.isPaused()) return;
                // Vanilla container removal can spill its contents even with block drops disabled.
                if (!dropItems && state.hasBlockEntity()) com.cheatprogram.compat.WorldBridge.clear(level.getBlockEntity(position));
                if (level.destroyBlock(position, dropItems, server.getPlayerList().getPlayer(player), 512)) broken++;
                else skipped++;
                progress++;
            }
            if (progress == total) finish("ui.rangebreak.completed", true);
        }

        /** One ordinary mining advancement per client tick; no client-side fake block removal. */
        void stepRemote() {
            if (!valid() || !executionAllowed || mc.player.tickCount == lastClientTick) return;
            lastClientTick = mc.player.tickCount;
            if (mc.player.tickCount < nextMiningTick) return;
            if (!mining && gameMode.isDestroying()) return;
            // A scan can skip empty/blocked cells, but never acts on two blocks in one tick.
            int scanned = 0;
            long until = System.nanoTime() + 2_000_000L;
            while (progress < total && scanned++ < 256 && System.nanoTime() < until) {
                BlockPos pos = at(progress);
                if (!clientLevel.isInWorldBounds(pos) || !clientLevel.hasChunkAt(pos)
                        || !clientLevel.getWorldBorder().isWithinBounds(pos)) {
                    stopMining(); skipped++; progress++; continue;
                }
                BlockState state = clientLevel.getBlockState(pos);
                if (state.isAir()) {
                    if (mining && pos.equals(miningPos)) { stopMining(); broken++; }
                    else skipped++;
                    progress++;
                    continue;
                }
                if (state.getDestroySpeed(clientLevel, pos) < 0
                        || state.getDestroyProgress(mc.player, clientLevel, pos) <= 0) {
                    stopMining(); skipped++; progress++; continue;
                }
                Vec3 eye = mc.player.getEyePosition();
                BlockHitResult hit = clientLevel.clip(new ClipContext(eye, Vec3.atCenterOf(pos),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)
                        || !WorldInteractSupport.reachable(hit)) {
                    stopMining(); skipped++; progress++; continue;
                }
                boolean accepted;
                if (mining) accepted = gameMode.continueDestroyBlock(pos, hit.getDirection());
                else {
                    accepted = gameMode.startDestroyBlock(pos, hit.getDirection());
                    mining = accepted && gameMode.isDestroying();
                    miningPos = mining ? pos.immutable() : null;
                }
                if (!accepted) {
                    stopMining(); skipped++; progress++;
                } else {
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    if (clientLevel.getBlockState(pos).isAir()) {
                        stopMining(); broken++; progress++;
                        // Preserve vanilla's between-block cadence, including creative mode.
                        nextMiningTick = mc.player.tickCount + 5;
                    } else if (!gameMode.isDestroying()) {
                        // A sword in creative mode, disabled items, or a denied local action
                        // can return true without starting a break. Do not retry it forever.
                        stopMining(); skipped++; progress++;
                        nextMiningTick = mc.player.tickCount + 5;
                    }
                }
                if (progress == total) finish("ui.rangebreak.completed", true);
                return;
            }
            if (progress == total) finish("ui.rangebreak.completed", true);
        }

        void finish(String key, boolean success) {
            if (!valid()) return;
            // The server thread's completion must be acknowledged on the client before a
            // new pass is allowed; otherwise a fast tick could bypass the repeat delay.
            nextPassAt = Long.MAX_VALUE;
            finished = true;
            executionAllowed = false;
            mc.execute(() -> {
                if (token != generation.get() || job != this) return;
                stopMining();
                if (success && keepBreaking.on()) {
                    nextPassAt = System.currentTimeMillis() + repeatDelay.intVal();
                } else {
                    String message = success && server == null ? "ui.rangebreak.remoteCompleted" : key;
                    Notifications.push(name(), Lang.t(message, broken, skipped), success);
                    setEnabled(false);
                }
            });
        }
    }
}
