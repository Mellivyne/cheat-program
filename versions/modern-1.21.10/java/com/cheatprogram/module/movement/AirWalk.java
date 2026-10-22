package com.cheatprogram.module.movement;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.render.Render3D;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A player-only virtual walking surface. Vanilla collision still handles every real wall,
 * ceiling, step and floor; no world blocks, flying ability or gravity flag are changed.
 */
public final class AirWalk extends Module {
    private final ModeSetting heightMode = add(new ModeSetting("heightMode", "airwalk_current", "airwalk_current", "airwalk_fixed"));
    private final NumberSetting height = add(new NumberSetting("height", 80, -64, 512, 0.5)).visibleWhen(() -> heightMode.is("airwalk_fixed"));
    private final NumberSetting speed = add(new NumberSetting("speed", 1, 1, 10, 0.1, "x"));
    private final NumberSetting riseSpeed = add(new NumberSetting("riseSpeed", 4, 0.5, 20, 0.5));
    private final NumberSetting descendSpeed = add(new NumberSetting("descendSpeed", 4, 0.5, 20, 0.5));
    private final BoolSetting keyHeight = add(new BoolSetting("keyHeight", true));
    private final BoolSetting showPlatform = add(new BoolSetting("showPlatform", true));
    private final NumberSetting markerSize = add(new NumberSetting("markerSize", 1.6, 0.6, 8, 0.1)).visibleWhen(showPlatform::on);

    /** One immutable publication is read by the integrated server without accessing client state. */
    private record Surface(LocalPlayer local, UUID uuid, ResourceKey<Level> dimension, MinecraftServer server, double y) {
        boolean belongsTo(Entity entity) {
            if (entity == null || !entity.isAlive() || entity.isRemoved() || entity.noPhysics
                    || entity.level().dimension() != dimension) return false;
            if (entity.level().isClientSide()) return entity == local;
            return server != null && entity instanceof ServerPlayer player && player.level().getServer() == server
                    && uuid.equals(player.getUUID()) && !player.isSpectator() && !player.getAbilities().flying;
        }
    }
    private static volatile Surface surface;
    private ClientLevel world;
    private LocalPlayer body;
    private double platformY, targetY, manualOffset, lastHeight, requestedStep, moveStartY;
    private boolean heightDrive;
    private String lastMode;

    public AirWalk() { super("airwalk", Category.MOVEMENT); }

    @Override public void onEnable() {
        FlatFlight flat = ModuleManager.get(FlatFlight.class);
        if (flat != null && flat.isEnabled()) flat.setEnabled(false);
        reset(); if (canOwnMotion()) attach();
    }
    @Override public void onDisable() { reset(); }

    private void reset() {
        surface = null;
        world = null;
        body = null;
        manualOffset = 0;
        lastMode = null;
        heightDrive = false;
    }

    private static boolean canOwnMotion() {
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isRemoved()
                && !mc.player.isSpectator() && !mc.player.isPassenger() && !mc.player.noPhysics
                && !mc.player.getAbilities().flying && !mc.player.isFallFlying()
                && !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.onClimbable()
                && !AiController.isRunning() && !Freecam.isActive() && !ModuleManager.on(Flight.class) && !FlatFlight.ownsMotion();
    }

    private static boolean controlsReady() {
        return canOwnMotion() && mc.screen == null && mc.getOverlay() == null && !mc.isPaused() && mc.isWindowActive();
    }

    private void attach() {
        world = mc.level;
        body = mc.player;
        platformY = body.getY();
        targetY = platformY;
        manualOffset = 0;
        lastMode = heightMode.get();
        lastHeight = height.val();
        publish();
    }

    private void publish() {
        surface = new Surface(body, body.getUUID(), world.dimension(), mc.getSingleplayerServer(), platformY);
    }

    /** Runs even without a world, so reconnects, death and dimension changes cannot retain a floor. */
    public static void tickLifecycle() {
        AirWalk module = ModuleManager.get(AirWalk.class);
        if (module == null) { surface = null; return; }
        if (!module.isEnabled() || !canOwnMotion() || module.body != null
                && (module.body != mc.player || module.world != mc.level)) module.reset();
    }

    @Override public void onTick() {
        heightDrive = false;
        if (!canOwnMotion()) { reset(); return; }
        if (body != mc.player || world != mc.level || surface == null) attach();
        // Keep supporting the body when a screen opens, but never drive its input or height there.
        if (!controlsReady()) return;

        if (!heightMode.get().equals(lastMode) || height.val() != lastHeight) {
            lastMode = heightMode.get();
            lastHeight = height.val();
            manualOffset = 0;
            targetY = body.getY();
        }
        if (heightMode.is("airwalk_fixed")) targetY = height.val() + manualOffset;
        double vertical = 0;
        if (keyHeight.on()) {
            if (mc.options.keyJump.isDown()) vertical += riseSpeed.val() / 20;
            if (mc.options.keyShift.isDown()) vertical -= descendSpeed.val() / 20;
        }
        targetY += vertical;
        targetY = Math.max(world.getMinY(), Math.min(world.getMaxY() - body.getBbHeight() - 0.05, targetY));
        if (heightMode.is("airwalk_fixed")) manualOffset = targetY - height.val();
        double delta = targetY - body.getY();
        double step = Math.max(-descendSpeed.val() / 20, Math.min(riseSpeed.val() / 20, delta));

        if (Math.abs(step) > 1.0e-5) {
            // Lower the surface first; raising it first could place a collider through the body.
            if (step < 0) { platformY = body.getY() + step; publish(); }
            moveStartY = body.getY();
            requestedStep = step;
            heightDrive = true;
            Vec3 movement = body.getDeltaMovement();
            // Vanilla travel moves and interpolates the body once; a separate early move here
            // would be lost when level ticking updates old position and would visibly stutter.
            body.setDeltaMovement(movement.x, step, movement.z);
        } else {
            platformY = Math.min(platformY, body.getY());
        }
        publish();
        body.resetFallDistance();
        if (keyHeight.on()) clearVerticalKeys(body.input);
    }

    @Override public void onPostTick() {
        if (body != mc.player || world != mc.level || surface == null) return;
        if (!canOwnMotion()) { reset(); return; }
        if (heightDrive) {
            platformY = body.getY();
            if (Math.abs(body.getY() - moveStartY - requestedStep) > 1.0e-4 && !heightMode.is("airwalk_fixed")) {
                targetY = platformY; // A low ceiling must not accumulate a delayed upward jump.
            }
            body.resetFallDistance();
            publish();
            heightDrive = false;
        }
        // Vanilla stepping up onto a real block advances the walking surface with the player.
        if (body.onGround() && body.getY() > platformY + 1.0e-4) {
            platformY = body.getY();
            if (heightMode.is("airwalk_current")) targetY = Math.max(targetY, platformY);
            publish();
        }
    }

    public static boolean ownsMotion() {
        AirWalk module = ModuleManager.get(AirWalk.class);
        return module != null && module.isEnabled() && canOwnMotion();
    }

    /** Root merges this with Speed/Scaffold using max(), rather than multiplying accelerators. */
    public static float speedMultiplier() {
        AirWalk module = ModuleManager.get(AirWalk.class);
        return ownsMotion() && controlsReady() ? module.speed.floatVal() : 1;
    }

    /** Call after scaffold's input hook and before the exclusive AI movement driver. */
    public static void applyInput(KeyboardInput input) {
        if (capturesVerticalInput()) clearVerticalKeys(input);
    }

    public static boolean capturesVerticalInput() {
        AirWalk module = ModuleManager.get(AirWalk.class);
        return module != null && module.keyHeight.on() && ownsMotion() && controlsReady();
    }

    private static void clearVerticalKeys(net.minecraft.client.player.ClientInput input) {
        Input original = input.keyPresses;
        input.keyPresses = new Input(original.forward(), original.backward(), original.left(), original.right(),
                false, false, original.sprint());
    }

    /** Add support to the actual collider list; the existing real block shapes are kept intact. */
    public static List<VoxelShape> appendSurface(Entity entity, AABB query, List<VoxelShape> original) {
        Surface support = surface;
        if (support == null || !support.belongsTo(entity)) return original;
        // A server packet can be one tick behind a rising local surface. Never put a floor
        // through its owner's feet; the movement itself catches the integrated twin up.
        double y = Math.min(support.y, entity.getBoundingBox().minY);
        if (query.minY > y + 1.0e-6 || query.maxY < y - 0.04) return original;
        List<VoxelShape> result = new ArrayList<>(original.size() + 1);
        result.addAll(original);
        result.add(Shapes.create(new AABB(query.minX - 1, y - 0.04, query.minZ - 1,
                query.maxX + 1, y, query.maxZ + 1)));
        return result;
    }

    /** Used only to recognize the same virtual floor in vanilla edge and fall handling. */
    public static boolean supported(Entity entity) {
        Surface support = surface;
        return support != null && support.belongsTo(entity)
                && Math.abs(entity.getBoundingBox().minY - support.y) <= 0.051;
    }

    /** The integrated server owns the same virtual floor; remote servers never use this hook. */
    public static boolean integratedSupport(Entity entity) {
        return entity instanceof ServerPlayer && supported(entity);
    }

    @Override public String suffix() {
        return heightMode.display() + " · " + speed.format();
    }

    @Override public String summary() {
        String elevation = surface != null ? String.format(Locale.ROOT, "Y %.1f", platformY) : heightMode.display();
        return elevation + ", " + speed.format() + (keyHeight.on() ? ", " + Lang.t("setting.airwalk.keyHeight") : "");
    }

    @Override public void onRender3D(Render3D renderer) {
        Surface support = surface;
        if (support == null || body != mc.player || world != mc.level || !showPlatform.on()) return;
        Vec3 position = body.getPosition(renderer.partialTick);
        double radius = markerSize.val() / 2;
        double y = support.y - 0.006;
        renderer.begin();
        try {
            renderer.box(new AABB(position.x - radius, y - 0.018, position.z - radius,
                    position.x + radius, y, position.z + radius), 0x10FFFFFF, 0xA6FFFFFF, 1.25f);
        } finally { renderer.end(); }
    }
}
