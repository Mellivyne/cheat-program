package com.cheatprogram.module.movement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** LandingRescue-only arithmetic; no world mutation or client fall-distance reset. */
final class LandingRescueTrajectory {
    record Point(double x, double y, double z) { }
    record Cell(int x, int y, int z, int tick) { }
    private record Key(int x, int y, int z) { }
    private double previousY = Double.NaN, descended;

    /** A local Flight ability lease alone cannot establish server fall protection. */
    static boolean flightProtected(boolean flying, boolean serverAllowsFlight) {
        return flying && serverAllowsFlight;
    }

    void reset() { previousY = Double.NaN; descended = 0; }

    double observe(double y, boolean safe) {
        if (!Double.isFinite(y)) { reset(); return 0; }
        if (safe) descended = 0;
        else if (Double.isFinite(previousY)) descended += Math.max(0, previousY - y);
        previousY = y;
        return descended;
    }

    boolean dangerous(double clientDistance, double remainingDrop, double minimum) {
        return Math.max(descended, Math.max(0, clientDistance)) + Math.max(0, remainingDrop) >= minimum;
    }

    /** Every cell crossed by the feet, including cells skipped by a fast single-tick fall. */
    static List<Cell> cells(List<Point> path, int maxTicks) {
        var found = new LinkedHashMap<Key, Cell>();
        for (int tick = 1; tick < path.size() && tick <= maxTicks; tick++) {
            Point from = path.get(tick - 1), to = path.get(tick);
            double sx = from.x, sy = from.y + 1e-6, sz = from.z;
            double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
            int x = floor(sx), y = floor(sy), z = floor(sz);
            int endX = floor(to.x), endY = floor(to.y + 1e-6), endZ = floor(to.z);
            int stepX = Double.compare(dx, 0), stepY = Double.compare(dy, 0), stepZ = Double.compare(dz, 0);
            double tx = boundary(sx, x, dx), ty = boundary(sy, y, dy), tz = boundary(sz, z, dz);
            double dtx = delta(dx), dty = delta(dy), dtz = delta(dz);
            // Client motion is bounded, but malformed coordinates must not create an unbounded loop.
            for (int guard = 0; guard < 512; guard++) {
                Key key = new Key(x, y, z);
                found.putIfAbsent(key, new Cell(x, y, z, tick));
                if (x == endX && y == endY && z == endZ) break;
                double next = Math.min(tx, Math.min(ty, tz));
                if (!Double.isFinite(next) || next > 1 + 1e-9) break;
                if (tx <= next + 1e-10) { x += stepX; tx += dtx; }
                if (ty <= next + 1e-10) { y += stepY; ty += dty; }
                if (tz <= next + 1e-10) { z += stepZ; tz += dtz; }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static int floor(double value) { return (int) Math.floor(value); }
    private static double delta(double direction) { return direction == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction); }
    private static double boundary(double start, int cell, double direction) {
        return direction == 0 ? Double.POSITIVE_INFINITY : ((direction > 0 ? cell + 1 : cell) - start) / direction;
    }
}
