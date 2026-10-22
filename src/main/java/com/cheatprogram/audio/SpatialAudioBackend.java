package com.cheatprogram.audio;

import com.cheatprogram.mixin.SpatialAudioChannelAccessor;
import com.mojang.blaze3d.audio.Channel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.openal.EXTEfx.*;

/** Audio-thread-only OpenAL objects. World queries and native capture remain outside this class. */
public final class SpatialAudioBackend {
    private static final int SAMPLE_RATE = 48_000;
    private static final int MAX_PENDING_BYTES = 15_360; // 80 ms of stereo PCM; prefer fresh audio after a stall.
    private static final int MAX_PACKET_BYTES = 3_840; // At most 20 ms per reusable mono buffer.
    private static final int MAX_QUEUED_BYTES = 7_680; // 80 ms of mono PCM in OpenAL, independently of packet sizes.
    private static final int MAX_BUFFERS = 8;
    private static final int START_BYTES = 1_920; // 20 ms pre-roll absorbs 10 ms capture scheduling jitter.
    private static final Object PCM_LOCK = new Object();
    private static final Object PUMP_LOCK = new Object();
    private static final ArrayDeque<byte[]> PCM = new ArrayDeque<>();
    private static int pendingBytes;
    private static boolean pcmDiscontinuity=true;
    private static final StereoDownmixer downmixer=new StereoDownmixer();
    private static final AtomicBoolean scheduled = new AtomicBoolean();
    private static final AtomicLong pumpRevision = new AtomicLong();
    private static volatile long streamRevision;
    private static volatile SoundEngineExecutor audioExecutor;
    private static volatile boolean engineReady, paused, efx, failed, playbackReady, active, pcmPlaying;
    private static volatile long lastInputNanos, lastPlaybackNanos;
    private static volatile String state = "ui.spatial.backend.waiting";
    private static volatile Vec3 gameSource;
    private static volatile long gameSoundNanos;
    private static volatile Config config = Config.off();
    // Everything below is owned exclusively by Minecraft's Sound engine executor.
    private static long context;
    private static int castSource, castFilter;
    private static final Map<Integer, Integer> buffers = new HashMap<>();
    private static final ArrayDeque<Integer> spareBuffers = new ArrayDeque<>();
    private static ByteBuffer monoScratch;
    private static volatile int queuedBytes;
    private static long appliedStreamRevision;
    private static final Map<Integer, GameEffect> gameEffects = new HashMap<>();
    private static final float[] sourcePosition = new float[3], listenerPosition = new float[3];
    private static final RoomEffect castRoom = new RoomEffect(), gameRoom = new RoomEffect();
    private static Config appliedConfig;
    private static volatile boolean gameCompatible = true;

    private record Config(boolean game, boolean cast, AcousticScene.Response gameResponse,
                          AcousticScene.Response castResponse, double wet, double echo,
                          double volume, double maxDistance, Vec3 position) {
        static Config off() { return new Config(false, false, null, null, 1, 1, 1, 48, Vec3.ZERO); }
    }

    private record GameEffect(Channel channel, int filter) {}

    private SpatialAudioBackend() {}

    public static void configure(boolean gameEnabled, boolean castEnabled, AcousticScene.Response gameResponse,
                                 AcousticScene.Response castResponse, double wet, double echo,
                                 double volume, double maxDistance, Vec3 castPosition) {
        config = new Config(gameEnabled, castEnabled, gameResponse, castResponse, number(wet, 0, 2),
                number(echo, 0, 2), number(volume, 0, 2), number(maxDistance, 4, 512),
                finite(castPosition) ? castPosition : Vec3.ZERO);
        if (!castEnabled) clearPcm();
        requestPump();
    }

    /** Copies interleaved little-endian signed PCM16 stereo, exactly 48 kHz. */
    public static void pushPcm(byte[] samples) {
        if (!config.cast || samples == null || samples.length < 4 || paused) return;
        int end = samples.length & ~3;
        int start = Math.max(0, end - MAX_PENDING_BYTES);
        synchronized (PCM_LOCK) {
            if (!config.cast || paused) return;
            if(start>0)pcmDiscontinuity=true;
            for (int at = start; at < end; at += MAX_PACKET_BYTES) {
                int length = Math.min(MAX_PACKET_BYTES, end - at);
                while (pendingBytes + length > MAX_PENDING_BYTES && !PCM.isEmpty()) {pendingBytes -= PCM.removeFirst().length;pcmDiscontinuity=true;}
                PCM.addLast(Arrays.copyOfRange(samples, at, at + length)); pendingBytes += length;
            }
            lastInputNanos = System.nanoTime();
        }
        requestPump();
    }

    /** A new selected process/capture session must never inherit the previous stream's queued audio. */
    public static void resetCastStream() {
        synchronized (PCM_LOCK) { PCM.clear(); pendingBytes = 0; pcmDiscontinuity=true; streamRevision++; }
        pcmPlaying = false; lastInputNanos = lastPlaybackNanos = 0;
        requestPump();
    }

    public static void stop() { config = Config.off(); gameSource = null; gameSoundNanos = 0; clearPcm(); requestPump(); }
    public static boolean supported() { return engineReady && !failed; }
    public static boolean efxSupported() { return engineReady && efx && !failed; }
    public static boolean gameProcessingAvailable() { return config.game && engineReady && efx && gameCompatible && !failed; }
    public static boolean playbackReady() { return engineReady && playbackReady && !failed; }
    /** Recent OpenAL PLAYING state with submitted PCM, rather than an allocated but silent source. */
    public static boolean playingPcm() { return playbackReady() && !paused && pcmPlaying && System.nanoTime() - lastPlaybackNanos < 250_000_000L; }
    public static int queuedPcmBytes() { return playbackReady() && !paused ? queuedBytes : 0; }
    public static long lastInputNanos() { return lastInputNanos; }
    public static long lastPlaybackNanos() { return lastPlaybackNanos; }
    public static String status() { return state; }
    public static Vec3 lastGameSource() { return gameSource; }
    public static long lastGameSoundNanos() { return gameSoundNanos; }

    public static void engineTick(SoundEngineExecutor executor, boolean loaded,
                                  Map<SoundInstance, ChannelAccess.ChannelHandle> sounds, boolean isPaused) {
        audioExecutor = executor;
        engineReady = loaded;
        paused = isPaused;
        if (!loaded) { playbackReady = false; pcmPlaying = false; clearPcm(); state = "ui.spatial.backend.waiting"; return; }
        requestPump();
        if (!config.game) return;
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : sounds.entrySet()) {
            if (eligible(entry.getKey())) entry.getValue().execute(SpatialAudioBackend::applyGameChannel);
        }
    }

    public static void soundStarted(SoundEngineExecutor executor, SoundInstance sound, ChannelAccess.ChannelHandle handle) {
        if (!config.game || handle == null || !eligible(sound)) return;
        audioExecutor = executor;
        engineReady = true;
        gameSource = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        gameSoundNanos = System.nanoTime();
        handle.execute(SpatialAudioBackend::applyGameChannel);
    }

    private static boolean eligible(SoundInstance sound) {
        if (sound == null || sound.isRelative() || sound.getAttenuation() == SoundInstance.Attenuation.NONE
                || sound.getSource() != SoundSource.PLAYERS) return false;
        Vec3 position = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        AcousticScene.Response response = config.gameResponse;
        return finite(position) && (response == null || position.distanceToSqr(response.listener()) <= config.maxDistance * config.maxDistance);
    }

    /** Called before vanilla flushes/replaces its audio executor and destroys the current context. */
    public static void engineStopping(SoundEngineExecutor executor) {
        // Publish the stop while holding the same lock as scheduling. Every already accepted
        // pump is then ahead of the cleanup barrier, so vanilla's flush cannot strand its flag.
        synchronized (PUMP_LOCK) { engineReady = false; }
        clearPcm();
        if (executor.isSameThread()) {
            try { cleanup(); } finally { audioExecutor = null; state = "ui.spatial.backend.waiting"; }
            return;
        }
        CompletableFuture<Void> cleaned = new CompletableFuture<>();
        executor.execute(() -> {
            try { cleanup(); } finally { cleaned.complete(null); }
        });
        // This is a teardown barrier, not a per-frame blocking operation.
        cleaned.join();
        audioExecutor = null;
        state = "ui.spatial.backend.waiting";
    }

    public static void channelDestroyed(int source) {
        SoundEngineExecutor executor = audioExecutor;
        if (executor != null && executor.isSameThread()) releaseGame(source, true);
    }

    private static void requestPump() {
        pumpRevision.incrementAndGet();
        synchronized (PUMP_LOCK) { schedulePump(); }
    }

    private static void schedulePump() {
        SoundEngineExecutor executor = audioExecutor;
        if ((!config.game && !config.cast && !active) || !engineReady || executor == null || !scheduled.compareAndSet(false, true)) return;
        // schedule() always enqueues, including retries from this executor's own thread. This
        // keeps continuous capture fair to vanilla tasks and prevents recursive pump retries.
        executor.schedule(() -> {
            long revision = pumpRevision.get();
            try { pump(); }
            catch (RuntimeException | LinkageError error) {
                failed = true; playbackReady = false; state = "ui.spatial.backend.unavailable";
                try { cleanup(); } catch (RuntimeException | LinkageError ignored) {}
            } finally {
                scheduled.set(false);
                // A capture packet/configure may arrive after this pump drained the queue but before
                // its scheduled flag was cleared. Do not leave it waiting for the next game tick.
                if (revision != pumpRevision.get()) requestPump();
            }
        });
    }

    private static void pump() {
        if (!engineReady) return;
        long current = ALC10.alcGetCurrentContext();
        if (current == 0) { playbackReady = false; state = "ui.spatial.backend.waiting"; return; }
        if (context != current) {
            // IDs belong to their originating context. Never delete recycled IDs in a new context.
            forgetObjects(); context = current; failed = false;
            active = true;
            efx = AL.getCapabilities().ALC_EXT_EFX;
            gameCompatible = !hasOtherAcoustics();
        }
        if (failed) return;
        Config settings = config;
        if (!settings.game) restoreGame();
        if (!settings.cast) releaseCast();
        if (!settings.game && !settings.cast) { cleanup(); state = "ui.spatial.backend.idle"; return; }
        boolean changed = appliedConfig == null || settings.gameResponse != appliedConfig.gameResponse
                || settings.castResponse != appliedConfig.castResponse || settings.wet != appliedConfig.wet
                || settings.echo != appliedConfig.echo || settings.game != appliedConfig.game || settings.cast != appliedConfig.cast;
        if (efx && changed) {
            try {
                if (settings.game && gameCompatible) gameRoom.configure(settings.gameResponse, settings.wet, settings.echo);
                if (settings.cast) castRoom.configure(settings.castResponse, settings.wet, settings.echo);
                if (AL10.alGetError() != AL10.AL_NO_ERROR) disableEfx();
            } catch (RuntimeException | LinkageError unavailableEffects) { disableEfx(); }
        }
        if (settings.cast) {
            if (castSource == 0) createCast();
            if (castSource != 0) {
                if (appliedStreamRevision != streamRevision) {
                    clearCastQueue(); appliedStreamRevision = streamRevision;
                    // A new selected application must not inherit the previous audio stream and its reverb tail.
                    if(efx)try{AL11.alSource3i(castSource,AL_AUXILIARY_SEND_FILTER,AL_EFFECTSLOT_NULL,0,AL_FILTER_NULL);castRoom.destroy();castRoom.configure(settings.castResponse,settings.wet,settings.echo);if(AL10.alGetError()!=AL10.AL_NO_ERROR)disableEfx();}catch(RuntimeException|LinkageError unavailableEffects){disableEfx();}
                }
                AL10.alSource3f(castSource, AL10.AL_POSITION, (float)settings.position.x, (float)settings.position.y, (float)settings.position.z);
                float distanceGain = configureCastDistance(settings.position, settings.maxDistance);
                // Module gain is applied with a limiter before OpenAL; AL_MAX_GAIN must not silently cap boosts.
                AL10.alSourcef(castSource, AL10.AL_GAIN, distanceGain * (settings.volume<=0?0:1) * (efx ? 1 : broadband(settings.castResponse)));
                if (efx) attach(castSource, castFilter, castRoom, settings.castResponse);
                if (paused) {
                    clearPcm();
                    clearCastQueue();
                } else { reclaimBuffers(); queuePcm(); }
            }
        }
        appliedConfig = settings;
        state = efx ? (settings.game && !gameCompatible ? "ui.spatial.backend.gameConflict" : "ui.spatial.backend.ready") : "ui.spatial.backend.efxUnavailable";
    }

    private static void createCast() {
        castSource = AL10.alGenSources();
        if (castSource == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) throw new IllegalStateException("OpenAL source unavailable");
        AL10.alSourcei(castSource, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AL10.alSourcei(castSource, AL10.AL_LOOPING, AL10.AL_FALSE);
        AL10.alSourcef(castSource, AL10.AL_REFERENCE_DISTANCE, 1);
        AL10.alSourcef(castSource, AL10.AL_ROLLOFF_FACTOR, 1);
        monoScratch = MemoryUtil.memAlloc(MAX_PACKET_BYTES / 2).order(ByteOrder.LITTLE_ENDIAN);
        if (efx) castFilter = alGenFilters();
        playbackReady = true;
    }

    private static float configureCastDistance(Vec3 position, double maxDistance) {
        AL10.alSourcef(castSource, AL10.AL_MAX_DISTANCE, (float)maxDistance);
        // A short unity reference zone precedes the gentler distance curve in the scene helper.
        // Own gain avoids changing Minecraft's global distance model and fades at the user cap.
        AL10.alSourcef(castSource, AL10.AL_ROLLOFF_FACTOR, 0);
        AL10.alGetListenerfv(AL10.AL_POSITION, listenerPosition);
        double dx = position.x - listenerPosition[0], dy = position.y - listenerPosition[1], dz = position.z - listenerPosition[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float)AcousticScene.distanceGain(distance,maxDistance);
    }

    private static void reclaimBuffers() {
        int count = AL10.alGetSourcei(castSource, AL10.AL_BUFFERS_PROCESSED);
        while (count-- > 0) {
            int buffer = AL10.alSourceUnqueueBuffers(castSource);
            Integer bytes = buffers.remove(buffer);
            if (bytes != null) { queuedBytes -= bytes; spareBuffers.addLast(buffer); }
        }
    }

    private static void queuePcm() {
        // A STOPPED source treats newly queued buffers as processed; INITIAL preserves pre-roll.
        if(buffers.isEmpty()&&AL10.alGetSourcei(castSource,AL10.AL_SOURCE_STATE)==AL10.AL_STOPPED)AL10.alSourceRewind(castSource);
        while (buffers.size() < MAX_BUFFERS) {
            byte[] packet;boolean discontinuity;
            synchronized (PCM_LOCK) {
                packet = PCM.peekFirst();
                if (packet == null || queuedBytes + packet.length / 2 > MAX_QUEUED_BYTES) break;
                PCM.removeFirst(); pendingBytes -= packet.length;discontinuity=pcmDiscontinuity;pcmDiscontinuity=false;
            }
            int bytes = packet.length / 2;
            monoScratch.clear();
            discontinuity|=buffers.isEmpty()&&AL10.alGetSourcei(castSource,AL10.AL_SOURCE_STATE)!=AL10.AL_PLAYING;
            downmixer.process(packet,monoScratch,discontinuity,config.volume);
            monoScratch.flip();
            int buffer = spareBuffers.isEmpty() ? AL10.alGenBuffers() : spareBuffers.removeFirst();
            if (buffer == 0) throw new IllegalStateException("OpenAL buffer unavailable");
            boolean queued = false;
            try {
                AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, monoScratch, SAMPLE_RATE);
                AL10.alSourceQueueBuffers(castSource, buffer);
                buffers.put(buffer, bytes); queuedBytes += bytes; queued = true;
            } finally { if (!queued) AL10.alDeleteBuffers(buffer); }
        }
        if (!buffers.isEmpty() && AL10.alGetSourcei(castSource, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING
                && (queuedBytes>=START_BYTES||System.nanoTime()-lastInputNanos>=20_000_000L)) AL10.alSourcePlay(castSource);
        pcmPlaying = appliedStreamRevision == streamRevision && !buffers.isEmpty()
                && AL10.alGetSourcei(castSource, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
        if (pcmPlaying) lastPlaybackNanos = System.nanoTime();
    }

    /** Pure PCM helper, also usable in offline checks without opening an audio device. */
    public static byte[] downmixStereo16(byte[] stereo) {
        if (stereo == null) return new byte[0];
        int frames = stereo.length / 4;
        byte[] mono = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            int at = i * 4;
            int left = (short)((stereo[at] & 255) | (stereo[at + 1] << 8));
            int right = (short)((stereo[at + 2] & 255) | (stereo[at + 3] << 8));
            int mixed = (left + right) / 2;
            mono[i * 2] = (byte)mixed; mono[i * 2 + 1] = (byte)(mixed >>> 8);
        }
        return mono;
    }

    /** Stateful stereo fold-down. Anti-phase material fades toward one channel instead of disappearing. */
    public static final class StereoDownmixer {
        private double blend,gain=1,last;private boolean initialized;private int ramp;
        public void reset(){blend=last=0;gain=1;initialized=false;ramp=0;}
        public void process(byte[] stereo,ByteBuffer output,boolean splice,double volume){
            int frames=stereo==null?0:stereo.length/4;if(frames==0)return;
            double leftEnergy=0,rightEnergy=0,cross=0;
            for(int i=0;i<frames;i++){int at=i*4;double l=sample(stereo,at),r=sample(stereo,at+2);leftEnergy+=l*l;rightEnergy+=r*r;cross+=l*r;}
            double midEnergy=(leftEnergy+rightEnergy+2*cross)*.25,targetBlend=0;
            if(leftEnergy+rightEnergy>frames*16&&cross<-.25*Math.sqrt(leftEnergy*rightEnergy)&&midEnergy<.175*(leftEnergy+rightEnergy)){
                targetBlend=initialized&&Math.abs(blend)>.1?Math.copySign(1,blend):leftEnergy>=rightEnergy?1:-1;
            }
            double targetGain=number(volume,0,2);
            if(!initialized){blend=targetBlend;gain=targetGain;initialized=true;}
            if(splice)ramp=96;double previous=last;
            for(int i=0;i<frames;i++){
                blend+=(targetBlend-blend)*.0006942;gain+=(targetGain-gain)*.004158;
                int at=i*4;double l=sample(stereo,at),r=sample(stereo,at+2),mixed=((l+r)+(l-r)*blend)*.5/32768.0*gain;
                if(ramp>0){double t=1-ramp/96.0;mixed=previous+(mixed-previous)*t;ramp--;}
                double limited=limit(mixed);output.putShort((short)Math.round(limited*32767));last=limited;
            }
        }
        private static int sample(byte[] pcm,int at){return (short)((pcm[at]&255)|(pcm[at+1]<<8));}
        /** Smooth unity-slope knee, with finite output even when an explicit boost would clip. */
        public static double limit(double value){if(!Double.isFinite(value))return 0;double magnitude=Math.abs(value);return magnitude<=.98?value:Math.copySign(.98+.02*Math.tanh((magnitude-.98)/.02),value);}
    }
    private static void applyGameChannel(Channel channel) {
        if (!engineReady || !config.game || !efx || !gameCompatible || failed || context == 0) return;
        int source = ((SpatialAudioChannelAccessor)channel).cheatprogram$audioSource();
        if (!AL10.alIsSource(source)) return;
        AcousticScene.Response response = config.gameResponse;
        // The module currently probes one recent game emitter. Its wall loss and room paths must
        // not be copied onto another player elsewhere, or retained after a channel moves away.
        AL10.alGetSourcefv(source, AL10.AL_POSITION, sourcePosition);
        if (response == null || !nearGameProbe(response.source())) {
            releaseGame(source, true); return;
        }
        GameEffect current = gameEffects.get(source);
        if (current != null && current.channel != channel) releaseGame(source, false);
        current = gameEffects.get(source);
        if (current == null) {
            current = new GameEffect(channel, alGenFilters()); gameEffects.put(source, current);
        }
        attach(source, current.filter, gameRoom, response);
    }

    private static boolean nearGameProbe(Vec3 position) {
        double x = sourcePosition[0] - position.x, y = sourcePosition[1] - position.y, z = sourcePosition[2] - position.z;
        return x * x + y * y + z * z <= 4;
    }

    private static void attach(int source, int filter, RoomEffect room, AcousticScene.Response response) {
        if (filter != 0) {
            // Bandpass allows different low/mid/high losses while retaining positional mono playback.
            alFilteri(filter, AL_FILTER_TYPE, AL_FILTER_BANDPASS);
            double low = response == null ? 1 : response.lowGain();
            double mid = response == null ? 1 : response.midGain();
            double high = response == null ? 1 : response.highGain();
            double gain = Math.max(.0001, Math.max(low, Math.max(mid, high)));
            alFilterf(filter, AL_BANDPASS_GAIN, (float)number(gain, 0, 1));
            alFilterf(filter, AL_BANDPASS_GAINLF, (float)number(low / gain, 0, 1));
            alFilterf(filter, AL_BANDPASS_GAINHF, (float)number(high / gain, 0, 1));
            AL10.alSourcei(source, AL_DIRECT_FILTER, filter);
        }
        if (room.slot != 0) AL11.alSource3i(source, AL_AUXILIARY_SEND_FILTER, room.slot, 0, AL_FILTER_NULL);
    }

    private static void releaseGame(int source, boolean detach) {
        GameEffect effect = gameEffects.remove(source);
        if (effect == null) return;
        if (detach && AL10.alIsSource(source)) {
            AL10.alSourcei(source, AL_DIRECT_FILTER, AL_FILTER_NULL);
            AL11.alSource3i(source, AL_AUXILIARY_SEND_FILTER, AL_EFFECTSLOT_NULL, 0, AL_FILTER_NULL);
        }
        if (effect.filter != 0) alDeleteFilters(effect.filter);
    }

    private static void restoreGame() {
        for (int source : gameEffects.keySet().toArray(Integer[]::new)) releaseGame(source, true);
        gameRoom.destroy();
    }

    private static void clearCastQueue() {
        if (castSource != 0) {
            AL10.alSourceStop(castSource);
            int queued = AL10.alGetSourcei(castSource, AL10.AL_BUFFERS_QUEUED);
            while (queued-- > 0) {
                int buffer = AL10.alSourceUnqueueBuffers(castSource);
                if (buffers.remove(buffer) != null) spareBuffers.addLast(buffer);
            }
        }
        for (int buffer : buffers.keySet()) spareBuffers.addLast(buffer);
        buffers.clear(); queuedBytes = 0; pcmPlaying = false;downmixer.reset();
    }

    private static void releaseCast() {
        playbackReady = false;
        if (castSource != 0) {
            clearCastQueue();
            if (efx) {
                AL10.alSourcei(castSource, AL_DIRECT_FILTER, AL_FILTER_NULL);
                AL11.alSource3i(castSource, AL_AUXILIARY_SEND_FILTER, AL_EFFECTSLOT_NULL, 0, AL_FILTER_NULL);
            }
            AL10.alDeleteSources(castSource); castSource = 0;
        }
        while (!spareBuffers.isEmpty()) AL10.alDeleteBuffers(spareBuffers.removeFirst());
        if (monoScratch != null) { MemoryUtil.memFree(monoScratch); monoScratch = null; }
        if (castFilter != 0) { alDeleteFilters(castFilter); castFilter = 0; }
        castRoom.destroy();
    }

    private static void disableEfx() {
        restoreGame();
        if (castSource != 0) {
            AL10.alSourcei(castSource, AL_DIRECT_FILTER, AL_FILTER_NULL);
            AL11.alSource3i(castSource, AL_AUXILIARY_SEND_FILTER, AL_EFFECTSLOT_NULL, 0, AL_FILTER_NULL);
        }
        if (castFilter != 0) { alDeleteFilters(castFilter); castFilter = 0; }
        castRoom.destroy(); efx = false;
    }

    private static void cleanup() {
        long current = ALC10.alcGetCurrentContext();
        if (current != 0 && current == context) { restoreGame(); releaseCast(); }
        forgetObjects();
    }

    private static void forgetObjects() {
        if (monoScratch != null) { MemoryUtil.memFree(monoScratch); monoScratch = null; }
        context = 0; castSource = castFilter = 0; playbackReady = pcmPlaying = false; buffers.clear(); gameEffects.clear();
        spareBuffers.clear(); queuedBytes = 0; downmixer.reset(); appliedStreamRevision = streamRevision;
        castRoom.forget(); gameRoom.forget(); appliedConfig = null; efx = false; active = false;
    }

    private static boolean hasOtherAcoustics() {
        // These mods own game-source filter/send state, which EFX does not expose for reliable readback.
        // Leave their game sounds intact; our isolated cast source remains fully supported.
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded("sound_physics_remastered") || loader.isModLoaded("soundphysics")
                || loader.isModLoaded("sound_physics") || loader.isModLoaded("soundfilters")
                || loader.isModLoaded("sound_filters") || loader.isModLoaded("enhanced_audio");
    }

    private static float broadband(AcousticScene.Response response) {
        return response == null ? 1 : (float)number(.2 * response.lowGain() + .55 * response.midGain() + .25 * response.highGain(), 0, 1);
    }
    private static void clearPcm() { synchronized (PCM_LOCK) { PCM.clear(); pendingBytes = 0; pcmDiscontinuity=true; } }
    private static boolean finite(Vec3 p) { return p != null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
    private static double number(double value, double min, double max) { return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min; }

    private static final class RoomEffect {
        int effect, slot;
        boolean eax;

        void configure(AcousticScene.Response response, double wet, double echo) {
            if (effect == 0) {
                effect = alGenEffects();
                if (effect == 0) throw new IllegalStateException("No effect object available");
                alEffecti(effect, AL_EFFECT_TYPE, AL_EFFECT_EAXREVERB);
                eax = AL10.alGetError() == AL10.AL_NO_ERROR;
                if (!eax) alEffecti(effect, AL_EFFECT_TYPE, AL_EFFECT_REVERB);
                slot = alGenAuxiliaryEffectSlots();
                if (slot == 0) throw new IllegalStateException("No effect slot available");
            }
            double decay = response == null ? .1 : response.reverbSeconds();
            double gain = response == null ? 0 : response.reverbGain();
            double early = response == null ? 0 : response.earlyReflectionGain();
            double earlyTime = response == null ? 0 : response.earlyReflectionDelay();
            double echoTime = response == null ? .1 : response.echoDelay();
            double echoGain = response == null ? 0 : response.echoGain();
            // Echo shares EAX reverb's wet bus. Keep it audible when the reverb control is zero,
            // while preserving a bounded total bus gain and a truly dry zero/zero boundary.
            double wetGain = number(gain * wet + echoGain * echo * .25, 0, .38);
            double high = response == null ? 1 : response.reverbHighGain(), low = response == null ? 1 : response.reverbLowGain();
            double diffuse = response == null ? .6 : response.diffusion();
            ReflectionTone tone=response==null?ReflectionTone.NEUTRAL:response.tone();
            double highDecay=tone.highDecay()*(.25+.75*high);
            if (eax) {
                alEffectf(effect, AL_EAXREVERB_DIFFUSION, (float)number(diffuse, 0, 1));
                alEffectf(effect, AL_EAXREVERB_GAIN, (float)wetGain);
                alEffectf(effect, AL_EAXREVERB_GAINHF, (float)number(high, .02, 1));
                alEffectf(effect, AL_EAXREVERB_GAINLF, (float)number(low, .02, 1));
                alEffectf(effect, AL_EAXREVERB_DECAY_TIME, (float)number(decay, .1, 20));
                alEffectf(effect, AL_EAXREVERB_HFREFERENCE,(float)tone.highReference());
                alEffectf(effect, AL_EAXREVERB_LFREFERENCE,(float)tone.lowReference());
                alEffectf(effect, AL_EAXREVERB_DECAY_HFRATIO, (float)number(highDecay, .1, 2));
                alEffectf(effect, AL_EAXREVERB_DECAY_LFRATIO,(float)tone.lowDecay());
                alEffecti(effect, AL_EAXREVERB_DECAY_HFLIMIT,AL10.AL_FALSE);
                alEffectf(effect, AL_EAXREVERB_REFLECTIONS_GAIN, (float)number(early * wet, 0, 1));
                alEffectf(effect, AL_EAXREVERB_REFLECTIONS_DELAY, (float)number(earlyTime, 0, .3));
                alEffectf(effect, AL_EAXREVERB_LATE_REVERB_GAIN, 1f);
                alEffectf(effect, AL_EAXREVERB_ECHO_TIME, (float)number(echoTime, .075, .25));
                alEffectf(effect, AL_EAXREVERB_ECHO_DEPTH, (float)number(echoGain * echo, 0, .45));
            } else {
                alEffectf(effect, AL_REVERB_DIFFUSION, (float)number(diffuse, 0, 1));
                alEffectf(effect, AL_REVERB_GAIN, (float)wetGain);
                alEffectf(effect, AL_REVERB_GAINHF, (float)number(high, .02, 1));
                alEffectf(effect, AL_REVERB_DECAY_TIME, (float)number(decay, .1, 20));
                alEffectf(effect, AL_REVERB_DECAY_HFRATIO, (float)number(highDecay, .1, 2));
                alEffectf(effect, AL_REVERB_REFLECTIONS_GAIN, (float)number(early * wet, 0, 1));
                alEffectf(effect, AL_REVERB_REFLECTIONS_DELAY, (float)number(earlyTime, 0, .3));
                alEffectf(effect, AL_REVERB_LATE_REVERB_GAIN, 1f);
                alEffectf(effect, AL_REVERB_LATE_REVERB_DELAY, (float)number(echoTime, 0, .1));
            }
            alAuxiliaryEffectSloti(slot, AL_EFFECTSLOT_EFFECT, effect);
        }

        void destroy() {
            if (slot != 0) {
                alAuxiliaryEffectSloti(slot, AL_EFFECTSLOT_EFFECT, AL_EFFECT_NULL);
                alDeleteAuxiliaryEffectSlots(slot);
            }
            if (effect != 0) alDeleteEffects(effect);
            forget();
        }

        void forget() { effect = slot = 0; eax = false; }
    }
}





