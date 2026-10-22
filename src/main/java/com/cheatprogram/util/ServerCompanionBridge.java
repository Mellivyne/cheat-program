package com.cheatprogram.util;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Explicit command handshake. No invented capabilities and no cancelled disconnect packets. */
public final class ServerCompanionBridge {
    private static final Minecraft MC = Minecraft.getInstance();
    private static ClientPacketListener connection;
    private static String nonce = "";
    private static long requestedAt;
    private static boolean confirmed, requested;
    private static final Map<String, String> STATES = new HashMap<>();
    private static final Map<String, String> SENT = new HashMap<>();
    private static final Map<String, Long> SENT_AT = new HashMap<>();
    private static final java.util.ArrayDeque<String> ACTIONS = new java.util.ArrayDeque<>();
    private ServerCompanionBridge() { }

    public static void tickLifecycle() {
        ClientPacketListener current = MC.getConnection();
        if (connection != current) {
            connection = current; nonce = ""; requested = confirmed = false; requestedAt = 0;
            STATES.clear(); SENT.clear(); SENT_AT.clear(); ACTIONS.clear();
        }
        if (confirmed && connection != null && !ACTIONS.isEmpty()) connection.sendCommand("cpassist " + nonce + " " + ACTIONS.removeFirst());
    }
    public static boolean ready() { tickLifecycle(); return confirmed && connection != null; }
    public static boolean enabled(String feature) { return ready() && "on".equals(STATES.get(feature)); }
    public static boolean ensure() {
        tickLifecycle();
        if (connection == null || MC.player == null) return false;
        if (!requested) {
            nonce = UUID.randomUUID().toString().replace("-", "");
            requested = true; requestedAt = System.nanoTime();
            connection.sendCommand("cpassist hello " + nonce);
        }
        return confirmed;
    }
    public static String status(String feature) {
        tickLifecycle();
        if (confirmed) {
            String state = STATES.getOrDefault(feature, "ready");
            return Lang.t("ui.companion." + switch (state) {
                case "on" -> "enabled"; case "off" -> "disabled"; case "denied" -> "denied";
                case "error" -> "error"; default -> "ready";
            });
        }
        return Lang.t(requested && System.nanoTime() - requestedAt < 3_000_000_000L
                ? "ui.companion.connecting" : "ui.companion.required");
    }
    public static void retry() { requested = confirmed = false; STATES.clear(); SENT.clear(); SENT_AT.clear(); ensure(); }
    public static void request(String feature, String arguments) {
        if (!ensure()) return;
        String command = feature + " " + arguments;
        long now = System.nanoTime();
        // Repeat only until the server confirms the intended state; never one command every client tick.
        String desired = arguments.startsWith("on") ? "on" : "off";
        if (command.equals(SENT.get(feature)) && (desired.equals(STATES.get(feature)) || "denied".equals(STATES.get(feature))
                || now - SENT_AT.getOrDefault(feature, 0L) < 2_000_000_000L)) return;
        SENT.put(feature, command); SENT_AT.put(feature, now);
        connection.sendCommand("cpassist " + nonce + " " + command);
    }
    public static boolean action(String action) {
        if (!ensure()) {
            if (connection == null) return false;
            if (!ACTIONS.contains(action) && ACTIONS.size() < 8) ACTIONS.addLast(action);
            return true;
        }
        connection.sendCommand("cpassist " + nonce + " " + action);
        return true;
    }
    public static void cancelActions(String prefix) { ACTIONS.removeIf(action -> action.startsWith(prefix)); }
    public static int vaultCount() {
        try { return Integer.parseInt(STATES.getOrDefault("vaultCount", "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }
    /** Called on the client thread before system chat is displayed. Nonce is per connection. */
    public static boolean accept(String message) {
        tickLifecycle();
        String prefix = "[CPASSIST|" + nonce + "|";
        if (!requested || nonce.isEmpty() || !message.startsWith(prefix) || !message.endsWith("]")) return false;
        String[] fields = message.substring(prefix.length(), message.length() - 1).split("\\|", 3);
        if (fields.length < 2) return true;
        if (fields[0].equals("hello") && fields[1].equals("ok")) confirmed = true;
        else {
            STATES.put(fields[0], fields[1]);
            if (fields[0].equals("vault") && fields.length == 3) STATES.put("vaultCount", fields[2]);
            if (fields[1].equals("denied") || fields[1].equals("error"))
                Notifications.push("Paper", Lang.t("ui.companion." + fields[1]), false);
        }
        return true;
    }
}
