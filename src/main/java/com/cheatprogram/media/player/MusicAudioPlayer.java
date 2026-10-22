package com.cheatprogram.media.player;

import com.cheatprogram.media.player.MusicModels.Media;
import com.cheatprogram.media.player.MusicModels.Provider;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.*;

/** Bounded streaming decoder. URLs and credentials never enter a subprocess command line. */
final class MusicAudioPlayer implements AutoCloseable {
    static final int RATE = 48_000, FRAME_BYTES = 4;
    record Sample(long token, long positionMs, boolean playing, String status) { }
    @FunctionalInterface interface Listener { void sample(Sample sample); }
    @FunctionalInterface interface StreamSource { InputStream open(Provider provider, URI uri) throws Exception; }
    @FunctionalInterface interface DecoderFactory { Decoder open(long seekMs) throws Exception; }
    @FunctionalInterface interface SinkFactory { Sink open() throws Exception; }
    interface Decoder extends AutoCloseable {
        InputStream pcm();
        OutputStream encoded();
        int finish() throws Exception;
        @Override void close();
    }
    interface Sink extends AutoCloseable {
        void start(); void stop(); void drain();
        int write(byte[] bytes, int offset, int length);
        long frames();
        @Override void close();
    }
    private final StreamSource source;
    private final DecoderFactory decoders;
    private final SinkFactory sinks;
    private final Listener listener;
    private final ThreadPoolExecutor output = pool("music-pcm", 2, 1);
    private final ThreadPoolExecutor input = pool("music-stream", 2, 2);
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor(daemon("music-close"));
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(daemon("music-clock"));
    private volatile Session current;
    private volatile double gain = .8;
    private volatile boolean closed;

    MusicAudioPlayer(Listener listener) {
        this((provider, uri) -> MusicNetwork.stream(provider, uri, Map.of("Referer", provider.home)),
                MusicAudioPlayer::ffmpeg, MusicAudioPlayer::soundLine, listener);
    }
    /** Test seam: synthetic PCM, an in-memory stream and a fake sink need no device or subprocess. */
    MusicAudioPlayer(StreamSource source, DecoderFactory decoders, SinkFactory sinks, Listener listener) {
        this.source = source; this.decoders = decoders; this.sinks = sinks; this.listener = listener;
        clock.scheduleAtFixedRate(this::tick, 0, 40, TimeUnit.MILLISECONDS);
    }
    void play(Provider provider, Media media, long positionMs, long token, boolean paused) {
        stop();
        if (closed) return;
        Session session = new Session(provider, media, Math.max(0, positionMs), token, paused);
        current = session;
        output.getQueue().removeIf(task -> task instanceof Session old && old.cancelled.get());
        try { output.execute(session); }
        catch (RejectedExecutionException full) { session.finish("failed"); }
    }
    void paused(boolean paused) {
        Session session = current;
        if (session != null) { session.paused = paused; synchronized (session) { session.notifyAll(); } }
    }
    void volume(double value) { if (Double.isFinite(value)) gain = Math.max(0, Math.min(1, value)); }
    void stop() {
        Session old = current; current = null;
        if (old != null) old.cancel();
    }
    @Override public void close() {
        closed = true; stop(); clock.shutdownNow(); output.shutdownNow(); input.shutdownNow(); cleanup.shutdown();
    }
    private void tick() {
        Session session = current;
        if (session == null || session.cancelled.get() || session.terminal) return;
        Sink sink = session.sink;
        if (sink == null) return;
        try {
            if (session.paused) sink.stop(); else if (session.lineStarted) sink.start();
            long frames = Math.max(0, sink.frames());
            boolean advanced = frames > session.lastFrames;
            session.lastFrames = frames;
            session.positionMs = session.offsetMs + frames * 1000 / RATE;
            emit(session, advanced && !session.paused,
                    session.paused ? "paused" : advanced ? "playing" : "buffering");
        } catch (RuntimeException unavailable) { session.finish("audioUnavailable"); session.cancel(); }
    }
    private void emit(Session session, boolean playing, String status) {
        if (current == session && !session.cancelled.get())
            listener.sample(new Sample(session.token, session.positionMs, playing, "ui.musicPlayer." + status));
    }
    private final class Session implements Runnable {
        final Provider provider; final Media media; final long offsetMs, token;
        final AtomicBoolean cancelled = new AtomicBoolean(), resourcesClosed = new AtomicBoolean();
        volatile boolean paused, terminal, lineStarted;
        volatile Decoder decoder; volatile Sink sink; volatile InputStream stream;
        volatile Thread outputThread, inputThread;
        volatile long positionMs, lastFrames;
        volatile boolean inputFailed;
        Session(Provider provider, Media media, long offsetMs, long token, boolean paused) {
            this.provider = provider; this.media = media; this.offsetMs = offsetMs; this.positionMs = offsetMs;
            this.token = token; this.paused = paused;
        }
        @Override public void run() {
            outputThread = Thread.currentThread();
            try {
                if (cancelled.get()) return;
                Decoder openedDecoder;
                try { openedDecoder = decoders.open(offsetMs); }
                catch (FileNotFoundException missing) { finish("decoderRequired"); return; }
                boolean abandoned;
                synchronized (this) { abandoned = cancelled.get(); if (!abandoned) decoder = openedDecoder; }
                if (abandoned) { openedDecoder.close(); return; }
                Sink openedSink;
                try { openedSink = sinks.open(); }
                catch (Exception unavailable) { finish("audioUnavailable"); return; }
                synchronized (this) { abandoned = cancelled.get(); if (!abandoned) sink = openedSink; }
                if (abandoned) { openedSink.close(); return; }
                try { input.execute(this::feed); }
                catch (RejectedExecutionException full) { finish("failed"); return; }
                byte[] bytes = new byte[3840 + FRAME_BYTES]; int tail = 0; boolean started = false;
                InputStream pcm = openedDecoder.pcm();
                while (!cancelled.get()) {
                    synchronized (this) {
                        while (paused && !cancelled.get()) {
                            if (started) { openedSink.stop(); started = false; }
                            wait(40);
                        }
                    }
                    if (cancelled.get()) return;
                    int count = pcm.read(bytes, tail, 3840);
                    if (count < 0) break;
                    if (count == 0) continue;
                    count += tail; int length = count - count % FRAME_BYTES;
                    if (!started && length > 0) { openedSink.start(); started = true; lineStarted = true; }
                    scale(bytes, length, gain);
                    for (int at = 0; at < length && !cancelled.get();) {
                        if (paused) {
                            openedSink.stop(); started = false;
                            synchronized (this) { while (paused && !cancelled.get()) wait(40); }
                            if (cancelled.get()) return;
                            openedSink.start(); started = true;
                        }
                        int written = openedSink.write(bytes, at, length - at);
                        if (written <= 0) throw new IOException("PCM output stopped");
                        at += written;
                        if (at < length && paused) {
                            openedSink.stop(); started = false;
                            synchronized (this) { while (paused && !cancelled.get()) wait(40); }
                            if (!cancelled.get()) { openedSink.start(); started = true; }
                        }
                    }
                    tail = count - length;
                    if (tail > 0) System.arraycopy(bytes, length, bytes, 0, tail);
                }
                if (!cancelled.get()) {
                    openedSink.drain();
                    positionMs = offsetMs + Math.max(0, openedSink.frames()) * 1000 / RATE;
                    int exit = openedDecoder.finish();
                    finish(exit == 0 && !inputFailed ? "ended" : "failed");
                }
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            catch (Exception failure) { finish("failed"); }
            finally { cancelled.set(true); closeResources(); outputThread = null; }
        }
        void feed() {
            inputThread = Thread.currentThread();
            try {
                if (cancelled.get()) return;
                InputStream opened = source.open(provider, URI.create(media.url()));
                boolean abandoned;
                synchronized (this) { abandoned = cancelled.get(); if (!abandoned) stream = opened; }
                if (abandoned) { opened.close(); return; }
                try (InputStream encoded = opened; OutputStream target = decoder.encoded()) {
                    byte[] bytes = new byte[16 * 1024];
                    for (int count; !cancelled.get() && (count = encoded.read(bytes)) >= 0;) {
                        if (count > 0) target.write(bytes, 0, count);
                    }
                }
            } catch (Exception failure) {
                if (!cancelled.get()) { inputFailed = true; finish("failed"); cancel(); }
            } finally { inputThread = null; }
        }
        void finish(String status) { if (!terminal) { terminal = true; emit(this, false, status); } }
        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            synchronized (this) { notifyAll(); }
            Thread out = outputThread, in = inputThread;
            if (out != null) out.interrupt(); if (in != null) in.interrupt();
            if (decoder != null || sink != null || stream != null) {
                try { cleanup.execute(this::closeResources); } catch (RejectedExecutionException shutdown) { /* Worker finally closes resources. */ }
            }
        }
        void closeResources() {
            Decoder decoderToClose; Sink sinkToClose; InputStream streamToClose;
            synchronized (this) {
                if (!resourcesClosed.compareAndSet(false, true)) return;
                decoderToClose = decoder; sinkToClose = sink; streamToClose = stream;
            }
            // Kill the decoder first to release both blocked pipe ends before closing HTTP/audio.
            if (decoderToClose != null) try { decoderToClose.close(); } catch (RuntimeException ignored) { }
            if (streamToClose != null) try { streamToClose.close(); } catch (IOException ignored) { }
            if (sinkToClose != null) try { sinkToClose.close(); } catch (RuntimeException ignored) { }
        }
    }
    private static void scale(byte[] bytes, int length, double gain) {
        if (gain >= .9999) return;
        for (int i = 0; i < length; i += 2) {
            short original = (short) ((bytes[i] & 255) | bytes[i + 1] << 8);
            int sample = (int) Math.round(original * gain);
            bytes[i] = (byte) sample; bytes[i + 1] = (byte) (sample >> 8);
        }
    }
    static ThreadFactory daemon(String name) { return task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }; }
    private static ThreadPoolExecutor pool(String name, int size, int capacity) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), daemon(name));
    }
    private static Decoder ffmpeg(long seekMs) throws IOException {
        Path executable = null;
        String path = System.getenv("PATH");
        if (path != null) for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            try {
                Path candidate = Path.of(directory.replace("\"", ""), System.getProperty("os.name", "").startsWith("Windows") ? "ffmpeg.exe" : "ffmpeg");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) { executable = candidate; break; }
            } catch (RuntimeException invalidPath) { }
        }
        if (executable == null) throw new FileNotFoundException("ui.musicPlayer.decoderRequired");
        List<String> command = new ArrayList<>(List.of(executable.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
                "-protocol_whitelist", "pipe", "-i", "pipe:0"));
        if (seekMs > 0) command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.3f", seekMs / 1000.0)));
        command.addAll(List.of("-vn", "-sn", "-dn", "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "2", "-ar", "48000", "pipe:1"));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        return new Decoder() {
            @Override public InputStream pcm() { return process.getInputStream(); }
            @Override public OutputStream encoded() { return process.getOutputStream(); }
            @Override public int finish() throws InterruptedException { return process.waitFor(3, TimeUnit.SECONDS) ? process.exitValue() : -1; }
            @Override public void close() {
                process.destroyForcibly();
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
        };
    }
    private static Sink soundLine() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        try { line.open(format, RATE * FRAME_BYTES / 5); }
        catch (LineUnavailableException | RuntimeException failure) { line.close(); throw failure; }
        return new Sink() {
            @Override public void start() { line.start(); }
            @Override public void stop() { line.stop(); }
            @Override public void drain() { line.drain(); }
            @Override public int write(byte[] bytes, int offset, int length) { return line.write(bytes, offset, length); }
            @Override public long frames() { return line.getLongFramePosition(); }
            @Override public void close() { line.stop(); line.flush(); line.close(); }
        };
    }
}
