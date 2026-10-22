package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable local observations. Remote operator permissions are never inferred from display names. */
public final class PersonalInfo {
    public record Snapshot(boolean inWorld, boolean integrated, String address, int players, int maximum,
                           List<String> operators, String operatorStatusKey) {
        private static final Snapshot EMPTY = new Snapshot(false, false, "", 0, -1, List.of(), "ui.personal.notConnected");
        public Snapshot { operators = List.copyOf(operators); }
    }
    private static final Minecraft MC = Minecraft.getInstance();
    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    private static Object world, connection;
    private static long generation, nextRefresh;
    private static boolean pending;
    private PersonalInfo() { }

    /** Client-thread entry point; server-owned lists are copied on the integrated server thread. */
    public static Snapshot snapshot() { refresh(); return snapshot; }
    public static void refresh() {
        Object currentWorld = MC.level, currentConnection = MC.getConnection();
        if (world != currentWorld || connection != currentConnection) {
            world = currentWorld; connection = currentConnection; generation++; nextRefresh = 0; pending = false;
            snapshot = Snapshot.EMPTY;
        }
        if (currentWorld == null || MC.player == null || currentConnection == null) return;
        long now = System.nanoTime();
        if (pending || now < nextRefresh) return;
        nextRefresh = now + 1_000_000_000L;
        var server = MC.getSingleplayerServer();
        if (server == null) {
            var data = MC.getCurrentServer();
            String address = data == null || data.ip == null ? "" : data.ip;
            int maximum = data != null && data.players != null ? data.players.max() : -1;
            snapshot = new Snapshot(true, false, address, MC.getConnection().getOnlinePlayers().size(), maximum,
                    List.of(), "ui.personal.remoteOpsUnavailable");
            return;
        }
        if (!snapshot.inWorld()) snapshot = new Snapshot(true, true, "", MC.getConnection().getOnlinePlayers().size(),
                -1, List.of(), "ui.personal.serverLoading");
        long token = generation;
        pending = true;
        try { server.execute(() -> {
            Snapshot result;
            try {
                var players = server.getPlayerList();
                Set<String> names = new LinkedHashSet<>(Arrays.asList(players.getOpNames()));
                // A singleplayer owner with commands may have real permission without an ops.json entry.
                for (var player : players.getPlayers())
                    if (server.getProfilePermissions(player.nameAndId()) > 0) names.add(player.getGameProfile().name());
                names.removeIf(name -> name == null || name.isBlank());
                ArrayList<String> operators = new ArrayList<>(names); operators.sort(String.CASE_INSENSITIVE_ORDER);
                result = new Snapshot(true, true, "", players.getPlayerCount(), players.getMaxPlayers(), operators,
                        operators.isEmpty() ? "ui.personal.noOperators" : "");
            } catch (RuntimeException unavailable) {
                result = new Snapshot(true, true, "", 0, -1, List.of(), "ui.personal.serverUnavailable");
            }
            Snapshot ready = result;
            MC.execute(() -> {
                if (generation != token || MC.getSingleplayerServer() != server || MC.level != currentWorld) return;
                snapshot = ready; pending = false;
            });
        }); } catch (RuntimeException stopped) {
            pending = false;
            snapshot = new Snapshot(true, true, "", 0, -1, List.of(), "ui.personal.serverUnavailable");
        }
    }
    public static void tickLifecycle() { refresh(); }
    public static void forceRefresh() { nextRefresh = 0; refresh(); }
}
