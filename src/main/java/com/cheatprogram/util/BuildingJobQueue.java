package com.cheatprogram.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Coalesces ticks for one building job, never for an entire world session. */
public final class BuildingJobQueue {
    private final AtomicBoolean pending = new AtomicBoolean();

    public boolean submit(Consumer<Runnable> executor, Runnable action) {
        if (!pending.compareAndSet(false, true)) return false;
        try {
            executor.accept(() -> {
                try { action.run(); }
                finally { pending.set(false); }
            });
            return true;
        } catch (RuntimeException | Error failure) {
            pending.set(false);
            throw failure;
        }
    }
}
