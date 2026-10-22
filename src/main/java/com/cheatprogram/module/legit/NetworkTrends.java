package com.cheatprogram.module.legit;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudLayout;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.NetworkTelemetrySource;
import net.minecraft.client.multiplayer.ClientLevel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Passive trends: actual HUD frame intervals, tab-list latency, receive gaps, and estimated server TPS. */
public final class NetworkTrends extends Module {
    private final BoolSetting frames = add(new BoolSetting("frames", true));
    private final BoolSetting latency = add(new BoolSetting("latency", true));
    private final BoolSetting packetGaps = add(new BoolSetting("packetGaps", true));
    private final BoolSetting serverTps = add(new BoolSetting("serverTps", true));
    private final BoolSetting showSpikes = add(new BoolSetting("showSpikes", true));
    private final NumberSetting history = add(new NumberSetting("history", 30, 5, 180, 5, "s"));
    private final NumberSetting width = add(new NumberSetting("width", 254, 180, 480, 2));
    private final NumberSetting size = add(new NumberSetting("size", 100, 60, 180, 5, "%"));
    private final NumberSetting frameThreshold = add(new NumberSetting("frameThreshold", 50, 10, 500, 5, "ms"));
    private final NumberSetting pingThreshold = add(new NumberSetting("pingThreshold", 200, 20, 2000, 10, "ms"));
    private final NumberSetting packetThreshold = add(new NumberSetting("packetThreshold", 2500, 100, 10000, 100, "ms"));
    private final NumberSetting tpsThreshold = add(new NumberSetting("tpsThreshold", 17, 1, 20, .5));
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final ArrayDeque<Spike> spikes = new ArrayDeque<>();
    private final long[] lastSpike = new long[4];
    private ClientLevel world;
    private long frameAt, sampleAt, lastTimeSync, lastGameTime = Long.MIN_VALUE, lastPacketCount;
    private double frameTotal;
    private int frameCount;
    private float framePeak, estimatedTps = -1;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private record Sample(long time, float frame, float ping, float gap, float tps, float packets) {}
    private record Spike(int metric, float value, Instant time) {}
    public NetworkTrends() { super("networktrends", Category.LEGIT); }
    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    private void reset() {
        world = mc.level; samples.clear(); spikes.clear(); java.util.Arrays.fill(lastSpike, 0);
        frameAt = sampleAt = lastTimeSync = lastPacketCount = 0; lastGameTime = Long.MIN_VALUE;
        frameTotal = frameCount = 0; framePeak = 0; estimatedTps = -1;
        if (mc.getConnection() != null && (Object)mc.getConnection().getConnection() instanceof NetworkTelemetrySource source)
            lastPacketCount = source.cheatprogram$takeNetworkArrival().receivedCount();
    }
    public static void timeSync(long gameTime) {
        NetworkTrends module = ModuleManager.get(NetworkTrends.class);
        if (module != null && module.isEnabled()) module.acceptTimeSync(gameTime);
    }
    private void acceptTimeSync(long gameTime) {
        if (!inGame()) return;
        if (world != mc.level) reset();
        long now = System.nanoTime();
        if (lastTimeSync != 0 && lastGameTime != Long.MIN_VALUE) {
            double seconds = (now - lastTimeSync) / 1_000_000_000D;
            long ticks = gameTime - lastGameTime;
            if (seconds >= .1 && seconds <= 15 && ticks > 0 && ticks <= 400 && !mc.isPaused())
                estimatedTps = (float)Math.min(20, ticks / seconds);
            else estimatedTps = -1;
        }
        lastTimeSync = now; lastGameTime = gameTime;
    }
    private void collect(long now) {
        if (frameAt != 0) {
            float ms = (now - frameAt) / 1_000_000F;
            if (ms > 0 && ms < 10000) { frameTotal += ms; frameCount++; framePeak = Math.max(framePeak, ms); }
        }
        frameAt = now;
        if (sampleAt == 0) { sampleAt = now; return; }
        if (now - sampleAt < 500_000_000L) return;
        float frame = frameCount == 0 ? -1 : (float)(frameTotal / frameCount);
        var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        float ping = info == null ? -1 : info.getLatency();
        float gap = -1, rate = 0;
        if (mc.getConnection() != null && (Object)mc.getConnection().getConnection() instanceof NetworkTelemetrySource source) {
            var arrival = source.cheatprogram$takeNetworkArrival();
            if (arrival.lastArrivalNanos() != 0) gap = Math.max(arrival.maxGapNanos(), now - arrival.lastArrivalNanos()) / 1_000_000F;
            rate = Math.max(0, arrival.receivedCount() - lastPacketCount) * 1_000_000_000F / (now - sampleAt);
            lastPacketCount = arrival.receivedCount();
        }
        float tps = lastTimeSync == 0 || now - lastTimeSync > 6_000_000_000L || mc.isPaused() ? -1 : estimatedTps;
        samples.addLast(new Sample(now, frame, ping, gap, tps, rate));
        while (!samples.isEmpty() && now - samples.getFirst().time > history.val() * 1_000_000_000D) samples.removeFirst();
        while (samples.size() > 380) samples.removeFirst();
        if (framePeak > frameThreshold.val()) spike(0, framePeak, now);
        if (ping > pingThreshold.val()) spike(1, ping, now);
        if (gap > packetThreshold.val()) spike(2, gap, now);
        if (tps >= 0 && tps < tpsThreshold.val()) spike(3, tps, now);
        frameTotal = 0; frameCount = 0; framePeak = 0; sampleAt = now;
    }
    private void spike(int metric, float value, long now) {
        if (now - lastSpike[metric] < 3_000_000_000L) return;
        lastSpike[metric] = now; spikes.addFirst(new Spike(metric, value, Instant.now()));
        while (spikes.size() > 8) spikes.removeLast();
    }
    @Override public void onRender2D(HudContext ctx) {
        if (!inGame()) return;
        if (world != mc.level) reset();
        long now = System.nanoTime(); collect(now);
        List<Integer> metrics = new ArrayList<>();
        if (frames.on()) metrics.add(0); if (latency.on()) metrics.add(1); if (packetGaps.on()) metrics.add(2); if (serverTps.on()) metrics.add(3);
        int spikeRows = showSpikes.on() ? Math.min(3, spikes.size()) : 0;
        float scale = size.floatVal() / 100, w = width.floatVal(), h = 48 + metrics.size() * 45 + spikeRows * 12;
        float x = HudLayout.x(id, w * scale, ctx.width), y = HudLayout.y(id, h * scale, ctx.height);
        ctx.panel(x, y, w * scale, h * scale, 14 * scale);
        Canvas c = ctx.c; c.push(); c.translate(x, y); c.scaleAround(scale, 0, 0);
        try {
            c.textC(Fonts.Face.MEDIUM, name(), 12, 15, 10, Theme.TEXT);
            c.textC(Fonts.Face.REGULAR, history.format(), w - c.textWidth(Fonts.Face.REGULAR, history.format(), 8) - 12, 15, 8, Theme.TEXT_3);
            Sample last = samples.peekLast(); float row = 35;
            for (int metric : metrics) {
                float value = last == null ? -1 : value(last, metric);
                c.textC(Fonts.Face.REGULAR, label(metric), 12, row, 8.5f, Theme.TEXT_2);
                String formatted = value < 0 ? "--" : String.format(Locale.ROOT, metric == 3 ? "%.1f TPS" : "%.1f ms", value);
                c.textC(Fonts.Face.MEDIUM, formatted, w - 12 - c.textWidth(Fonts.Face.MEDIUM, formatted, 9), row, 9, Theme.TEXT);
                graph(c, metric, 12, row + 8, w - 24, 19, now); row += 45;
            }
            int index = 0;
            for (Spike spike : spikes) {
                if (index++ >= spikeRows) break;
                String timestamp = CLOCK.withZone(ZoneId.systemDefault()).format(spike.time);
                String detail = timestamp + "  " + label(spike.metric) + " " + String.format(Locale.ROOT, "%.1f", spike.value);
                c.textC(Fonts.Face.REGULAR, c.ellipsize(Fonts.Face.REGULAR, detail, 7.5f, w - 24), 12, row - 5, 7.5f, Theme.TEXT_2); row += 12;
            }
            c.textC(Fonts.Face.REGULAR, Lang.t("ui.networktrends.passive"), 12, h - 12, 7, Theme.TEXT_3);
        } finally { c.pop(); }
        HudLayout.report(id, x, y, w * scale, h * scale);
    }
    private static float value(Sample sample, int metric) { return switch (metric) { case 0 -> sample.frame; case 1 -> sample.ping; case 2 -> sample.gap; default -> sample.tps; }; }
    private String label(int metric) { return Lang.t("ui.networktrends." + new String[]{"frames", "ping", "gap", "tps"}[metric]); }
    private void graph(Canvas c, int metric, float x, float y, float w, float h, long now) {
        c.rrect(x, y, w, h, 3, Anim.white(.035f)); c.rect(x, y + h / 2, w, .5f, Anim.white(.08f));
        float maximum = metric == 3 ? 20 : metric == 0 ? 33 : metric == 1 ? 100 : 1000;
        for (Sample sample : samples) maximum = Math.max(maximum, value(sample, metric));
        // At most 72 chart columns per metric, independent of the chosen history length.
        // Keep the worst value inside each bucket so a short stall is not averaged away.
        int group = Math.max(1, (samples.size() + 71) / 72), count = 0;
        float worst = -1; long time = 0;
        for (Sample sample : samples) {
            float value = value(sample, metric);
            if (value >= 0) worst = worst < 0 ? value : metric == 3 ? Math.min(worst, value) : Math.max(worst, value);
            time = sample.time;
            if (++count >= group) {
                chartBar(c, metric, worst, maximum, time, now, x, y, w, h, group);
                count = 0; worst = -1;
            }
        }
        if (count > 0) chartBar(c, metric, worst, maximum, time, now, x, y, w, h, group);
    }
    private void chartBar(Canvas c, int metric, float value, float max, long time, long now,
                          float x, float y, float w, float h, int group) {
        if (value < 0) return;
        float px = x + w * (1 - (now - time) / (history.floatVal() * 1_000_000_000F));
        float bar = Math.max(.7f, Math.min(w, w * group * .5f / history.floatVal() - .5f));
        float height = Math.max(.8f, Math.min(1, value / max) * (h - 1));
        float left = Math.max(x, px - bar), right = Math.min(x + w, px);
        if (right > left) c.rect(left, y + h - height, right - left, height, metric == 3 ? 0xb399e5b5 : 0xb3d5eaff);
    }
}
