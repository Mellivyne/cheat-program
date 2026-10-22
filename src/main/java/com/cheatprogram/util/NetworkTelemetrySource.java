package com.cheatprogram.util;

/** The packet callback writes only atomics; HUD sampling consumes them on the client thread. */
public interface NetworkTelemetrySource {
    record Arrival(long receivedCount, long lastArrivalNanos, long maxGapNanos) {}
    Arrival cheatprogram$takeNetworkArrival();
}
