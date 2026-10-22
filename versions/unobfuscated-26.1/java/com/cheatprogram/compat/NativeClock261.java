package com.cheatprogram.compat;
/** Exact native world-clock adapter for private dev scene time restoration. */
public final class NativeClock261 {
    private NativeClock261() { }
    public static void setDayTime(net.minecraft.server.level.ServerLevel level, long ticks) {
        var clock = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
            .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
        level.clockManager().setTotalTicks(clock, ticks);
    }
}
