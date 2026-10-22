package com.cheatprogram.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * Bounded geometric-acoustics approximation. Jobs are advanced on the client thread, never in render().
 * World coordinates are treated as metres; three broad bands are descriptive, not measured acoustics.
 * Decay uses the Eyring energy-loss model, with explicit opening/leakage and range limits.
 */
public final class AcousticScene {
    private static final double SPEED_OF_SOUND = 343;
    private static final double EPSILON = .003;
    private static final int MAX_SEGMENTS = 32768;
    private static final int MAX_CACHED_CELLS = 32768;
    private static final int SEGMENT_CHUNK = 128;
    private static final int MAX_SHAPE_BOXES = 128;
    private static final double REFERENCE_DISTANCE = 4, DISTANCE_ROLLOFF = .65;
    private static final CollisionContext COLLISION_CONTEXT = CollisionContext.empty();
    private static final Vec3[] DIFFUSE_DIRECTIONS = new Vec3[997];
    private static final Vec3[] NORMALS = {new Vec3(-1, 0, 0), new Vec3(1, 0, 0), new Vec3(0, -1, 0),
            new Vec3(0, 1, 0), new Vec3(0, 0, -1), new Vec3(0, 0, 1)};

    public record ProbeSettings(int rays, int bounces, double range, double airAbsorption,
                                double absorptionScale, double scatteringScale) {
        public ProbeSettings {
            rays = Math.max(12, Math.min(32768, rays));
            bounces = Math.max(1, Math.min(16, bounces));
            range = clamp(range, 4, 160, 48);
            airAbsorption = clamp(airAbsorption, 0, 4, 1);
            absorptionScale = clamp(absorptionScale, .05, 4, 1);
            scatteringScale = clamp(scatteringScale, 0, 3, 1);
        }
        public ProbeSettings(int rays, int bounces, double range, double airAbsorption) {
            this(rays, bounces, range, airAbsorption, 1, 1);
        }
    }

    public record Segment(Vec3 from, Vec3 to, double energy, int bounce, String material) {
        public Segment {
            energy = clamp(energy, 0, 1, 0);
            bounce = Math.max(0, bounce);
            material = material == null ? "air" : material;
        }
    }

    public record Response(Vec3 source, Vec3 listener, double lowGain, double midGain, double highGain,
                           double reverbSeconds, double reverbGain,
                           double earlyReflectionDelay, double earlyReflectionGain,
                           double echoDelay, double echoGain, double enclosure, double volume,
                           List<Segment> segments, int rays, boolean complete,
                           double reverbHighGain, double reverbLowGain, double diffusion, ReflectionTone tone) {
        public Response {
            // A stack of solid walls must be allowed to become inaudible, rather than stop at a floor.
            lowGain = clamp(lowGain, 0, 1, 0);
            midGain = clamp(midGain, 0, 1, 0);
            highGain = clamp(highGain, 0, 1, 0);
            reverbSeconds = clamp(reverbSeconds, .1, 6, .1);
            reverbGain = clamp(reverbGain, 0, .8, 0);
            earlyReflectionDelay = clamp(earlyReflectionDelay, 0, .3, 0);
            earlyReflectionGain = clamp(earlyReflectionGain, 0, 1, 0);
            echoDelay = clamp(echoDelay, .075, .5, .1);
            echoGain = clamp(echoGain, 0, .75, 0);
            enclosure = clamp(enclosure, 0, 1, 0);
            volume = clamp(volume, 0, 8_000_000, 0);
            // Our chunk snapshots own immutable arrays. Recopying every historical ray here made
            // each later tick slower, even when visualization was disabled.
            segments = segments instanceof SegmentSnapshot ? segments : List.copyOf(segments);
            rays = Math.max(0, rays);
            reverbHighGain = clamp(reverbHighGain, .05, 1, 1);
            reverbLowGain = clamp(reverbLowGain, .05, 1, 1);
            diffusion = clamp(diffusion, 0, 1, .5);
            tone=tone==null?ReflectionTone.NEUTRAL:tone;
        }

        public Response(Vec3 source,Vec3 listener,double lowGain,double midGain,double highGain,
                        double reverbSeconds,double reverbGain,double earlyReflectionDelay,double earlyReflectionGain,
                        double echoDelay,double echoGain,double enclosure,double volume,List<Segment> segments,int rays,
                        boolean complete,double reverbHighGain,double reverbLowGain,double diffusion) {
            this(source,listener,lowGain,midGain,highGain,reverbSeconds,reverbGain,earlyReflectionDelay,earlyReflectionGain,
                    echoDelay,echoGain,enclosure,volume,segments,rays,complete,reverbHighGain,reverbLowGain,diffusion,ReflectionTone.NEUTRAL);
        }

        /** Compatibility constructor. Aggregate once; the audio thread must never scan ray geometry. */
        public Response(Vec3 source, Vec3 listener, double lowGain, double midGain, double highGain,
                        double reverbSeconds, double reverbGain,
                        double earlyReflectionDelay, double earlyReflectionGain,
                        double echoDelay, double echoGain, double enclosure, double volume,
                        List<Segment> segments, int rays, boolean complete) {
            this(source, listener, lowGain, midGain, highGain, reverbSeconds, reverbGain,
                    earlyReflectionDelay, earlyReflectionGain, echoDelay, echoGain, enclosure, volume,
                    segments, rays, complete, wetBandRatio(segments, true), wetBandRatio(segments, false),
                    segmentDiffusion(segments));
        }
        /** Broadband pressure loss along this particular source-listener path, not a universal wall label. */
        public double obstruction() {
            double distance = source.distanceTo(listener);
            double clear = .25 * Math.exp(-distance * .0002) + .5 * Math.exp(-distance * .0008)
                    + .25 * Math.exp(-distance * .003);
            return clamp(1 - (.25 * lowGain + .5 * midGain + .25 * highGain) / Math.max(.001, clear), 0, 1, 1);
        }
        /** Approximate -50 dB pressure threshold with the current wall path held fixed.
         * Actual audibility also depends on source level, hardware and the user's hearing. */
        public double estimatedAudibleDistance(double maxRange) {
            double maximum = clamp(maxRange, 1, 2048, 160);
            double observedDistance = source.distanceTo(listener);
            double low = clamp(lowGain * Math.exp(observedDistance * .0002), 0, 1, 0);
            double mid = clamp(midGain * Math.exp(observedDistance * .0008), 0, 1, 0);
            double high = clamp(highGain * Math.exp(observedDistance * .003), 0, 1, 0);
            double begin = 0, end = maximum;
            for (int i = 0; i < 28; i++) {
                double distance = (begin + end) * .5;
                double pressure = (.25 * low * Math.exp(-distance * .0002)
                        + .5 * mid * Math.exp(-distance * .0008)
                        + .25 * high * Math.exp(-distance * .003)) * distanceGain(distance, maximum);
                if (pressure >= .00316227766) begin = distance; else end = distance;
            }
            return begin;
        }
        private static double segmentDiffusion(List<Segment> segments) {
            double weighted = 0, total = 0;
            for (Segment segment : segments) {
                if (segment.material().equals("air")) continue;
                weighted += AcousticMaterials.byCategory(segment.material()).scattering() * segment.energy();
                total += segment.energy();
            }
            return total > 1e-9 ? weighted / total : .5;
        }
        private static double wetBandRatio(List<Segment> segments, boolean high) {
            double numerator = 0, denominator = 0;
            for (Segment segment : segments) {
                if (segment.material().equals("air")) continue;
                AcousticMaterials.Profile p = AcousticMaterials.byCategory(segment.material());
                numerator += (1 - (high ? p.highAbsorption() : p.lowAbsorption())) * segment.energy();
                denominator += (1 - p.midAbsorption()) * segment.energy();
            }
            return denominator > 1e-9 ? clamp(numerator / denominator, .05, 1, 1) : 1;
        }
    }

    private AcousticScene() {}

    public static Job begin(ClientLevel level, Vec3 source, Vec3 listener, ProbeSettings settings) {
        return new Job(level, source, listener, settings);
    }

    public static Response dry(Vec3 source, Vec3 listener) {
        return new Response(source, listener, 1, 1, 1, .1, 0, 0, 0, .1, 0, 0, 0, List.of(), 0, true);
    }

    /** Cheap listener refresh between room bakes. A newly blocked/moved path invalidates old wet
     * sound, while a previously verified indirect path around an obstacle remains audible. */
    public static Response refreshDirect(ClientLevel level, Vec3 source, Vec3 listener,
                                          ProbeSettings settings, Response previous) {
        Job direct = new Job(level, source, listener, settings);
        direct.probeDirect();
        if (previous == null)
            return new Response(source, listener, direct.directLow, direct.directMid, direct.directHigh,
                    .1, 0, 0, 0, .1, 0, 0, 0, List.of(), 0, false);
        Vec3 sourceMotion = source.subtract(previous.source());
        Vec3 listenerMotion = listener.subtract(previous.listener());
        boolean coherentMotion = sourceMotion.subtract(listenerMotion).distanceToSqr(Vec3.ZERO) < .25
                && sourceMotion.distanceToSqr(Vec3.ZERO) < 16 && listenerMotion.distanceToSqr(Vec3.ZERO) < 16;
        boolean sameLocalArea = coherentMotion || sourceMotion.distanceToSqr(Vec3.ZERO) < 4
                && listenerMotion.distanceToSqr(Vec3.ZERO) < 4;
        double previousDistance = previous.source().distanceTo(previous.listener());
        boolean wasBlocked = previous.lowGain() < .995 * Math.exp(-previousDistance * .0002 * settings.airAbsorption())
                || previous.midGain() < .995 * Math.exp(-previousDistance * .0008 * settings.airAbsorption())
                || previous.highGain() < .995 * Math.exp(-previousDistance * .003 * settings.airAbsorption());
        double distanceChange = source.distanceTo(listener) - previousDistance;
        boolean noNewBarrier = transmissionMaintained(previous.lowGain() * Math.exp(-distanceChange * .0002 * settings.airAbsorption()), direct.directLow)
                && transmissionMaintained(previous.midGain() * Math.exp(-distanceChange * .0008 * settings.airAbsorption()), direct.directMid)
                && transmissionMaintained(previous.highGain() * Math.exp(-distanceChange * .003 * settings.airAbsorption()), direct.directHigh);
        double wetGate = sameLocalArea && noNewBarrier && (!direct.directBlocked || wasBlocked) ? 1 : 0;
        return new Response(source, listener, direct.directLow, direct.directMid, direct.directHigh,
                previous.reverbSeconds(), previous.reverbGain() * wetGate,
                previous.earlyReflectionDelay(), previous.earlyReflectionGain() * wetGate,
                previous.echoDelay(), previous.echoGain() * wetGate, previous.enclosure(), previous.volume(),
                previous.segments(), previous.rays(), previous.complete(),
                previous.reverbHighGain(), previous.reverbLowGain(), previous.diffusion(),previous.tone());
    }

    private static boolean transmissionMaintained(double previous, double current) {
        return previous - current < .01 || current >= previous * .5;
    }

    /** Cumulative deterministic work counters; timing includes response publication. */
    public record Metrics(long casts, long visibilityCasts, long voxelVisits, long worldQueries,
                          long cacheHits, long snapshotEntries, int cachedCells, int sampledRays,
                          long lastStepNanos, long maxStepNanos) {}

    public static final class Job {
        private final ClientLevel level;
        private final Vec3 source, listener;
        private final double directDistance;
        private final ProbeSettings settings;
        private final SegmentBuilder segments = new SegmentBuilder();
        private final Map<BlockState, AcousticMaterials.Profile> materials = new IdentityHashMap<>();
        private final Map<BlockPos, Cell> cells = new HashMap<>();
        private final Map<VoxelShape, AABB[]> shapeBoxes = new IdentityHashMap<>();
        private final double[] intervalScratch = new double[MAX_SHAPE_BOXES * 2];
        private final double[] clipScratch = new double[2];
        private final BlockPos.MutableBlockPos cellCursor = new BlockPos.MutableBlockPos();
        private final Thread ownerThread = Thread.currentThread();
        private long casts, visibilityCasts, voxelVisits, worldQueries, cacheHits, snapshotEntries;
        private long lastStepNanos, maxStepNanos;
        private double estimatedBounceNanos = 1000;
        private int done, firstHits, allHits;
        private double firstDistance, lowAbs, midAbs, highAbs, scattering;
        private double earlyEnergy, earlyWeightedDelay, lateEnergy, lateWeightedDelay;
        private double wetLow, wetMid, wetHigh, scatterWeighted, surfaceWeight;
        private double toneWeight,toneHighReference,toneLowReference,toneHighDecay,toneLowDecay;
        private double minX, minY, minZ, maxX, maxY, maxZ;
        private double directLow = 1, directMid = 1, directHigh = 1;
        private boolean directProbed, directBlocked;
        private Path active;
        private Response response;

        private Job(ClientLevel level, Vec3 source, Vec3 listener, ProbeSettings settings) {
            if (!Minecraft.getInstance().isSameThread())
                throw new IllegalStateException("Acoustic world probes must begin on the client thread");
            if (level == null || source == null || listener == null || settings == null)
                throw new IllegalArgumentException("Acoustic probe requires a world, source, listener and settings");
            if (!finite(source) || !finite(listener)) throw new IllegalArgumentException("Non-finite acoustic position");
            this.level = level;
            this.source = source;
            this.listener = listener;
            this.directDistance = source.distanceTo(listener);
            this.settings = settings;
            minX = maxX = source.x; minY = maxY = source.y; minZ = maxZ = source.z;
            response = new Response(source, listener, 1, 1, 1, .1, 0, 0, 0, .1, 0, 0, 0, List.of(), 0, false);
        }

        /** Compatibility entry point. Work is also bounded in time, rather than tying 32 full rays to every tick. */
        public boolean step(int raysBudget) {
            return step(raysBudget, 1_000_000L);
        }

        /** A path can pause between bounces; elapsed budget is checked at each bounded pair of casts.
         * Partial responses and line geometry are available after every call, including the first. */
        public boolean step(int raysBudget, long nanosBudget) {
            if (Thread.currentThread() != ownerThread)
                throw new IllegalStateException("Acoustic world probes must remain on their client thread");
            if (done >= settings.rays()) return true;
            if (raysBudget <= 0) return false;
            long started = System.nanoTime();
            long available = Math.max(100_000L, Math.min(2_000_000L, nanosBudget));
            long deadline = started + available;
            long firstVisit = voxelVisits;
            if (!directProbed) { probeDirect(); directProbed = true; }
            int until = Math.min(settings.rays(), done + Math.max(1, Math.min(32768, raysBudget)));
            int workLimit = Math.max(1, (int) Math.min(32768, available * .9 / Math.max(250, estimatedBounceNanos)));
            int iterations = 0;
            while (done < until && iterations < workLimit) {
                if (iterations > 0 && System.nanoTime() >= deadline) break;
                // A deterministic secondary ceiling also bounds exceptionally fast empty-world
                // traversals and high settings; at most one final pair of casts can cross it.
                if (iterations > 0 && voxelVisits - firstVisit >= 32768) break;
                if (active == null) active = new Path(source, sphere(done, settings.rays()), settings);
                if (traceBounce(active, done)) { active = null; done++; }
                iterations++;
            }
            response = snapshot();
            lastStepNanos = System.nanoTime() - started;
            if (iterations > 0) estimatedBounceNanos = estimatedBounceNanos * .65 + lastStepNanos / (double) iterations * .35;
            maxStepNanos = Math.max(maxStepNanos, lastStepNanos);
            return done == settings.rays();
        }

        public double progress() { return done / (double) settings.rays(); }
        public Response response() { return response; }
        public Metrics metrics() {
            return new Metrics(casts, visibilityCasts, voxelVisits, worldQueries, cacheHits,
                    snapshotEntries, cells.size(), done, lastStepNanos, maxStepNanos);
        }

        private void probeDirect() {
            double distance = source.distanceTo(listener);
            if (distance < .01) return;
            // Distance rolloff stays in OpenAL. These gains represent only spectral air/occlusion loss.
            directLow = Math.exp(-distance * .0002 * settings.airAbsorption());
            directMid = Math.exp(-distance * .0008 * settings.airAbsorption());
            directHigh = Math.exp(-distance * .003 * settings.airAbsorption());
            double[] wall = transmission(source, listener);
            directBlocked = wall[0] < .995 || wall[1] < .995 || wall[2] < .995;
            directLow *= wall[0]; directMid *= wall[1]; directHigh *= wall[2];
        }

        private boolean traceBounce(Path path, int index) {
                Hit hit = cast(path.point, path.direction, Math.min(settings.range(), path.remaining));
                double energy = (path.low + path.mid + path.high) / 3;
                addSegment(path.point, hit.point, energy / (1 + path.distance * .018), path.bounce,
                        hit.solid ? hit.material.category() : "air");
                path.distance += hit.distance;
                path.remaining -= hit.distance;
                if (!hit.solid) return true; // unloaded chunks and world bounds are openings, never loaded by probing
                AcousticMaterials.Profile material = hit.material;
                if (path.bounce == 0) {
                    firstHits++;
                    firstDistance += hit.distance;
                    minX = Math.min(minX, hit.point.x); maxX = Math.max(maxX, hit.point.x);
                    minY = Math.min(minY, hit.point.y); maxY = Math.max(maxY, hit.point.y);
                    minZ = Math.min(minZ, hit.point.z); maxZ = Math.max(maxZ, hit.point.z);
                }
                double al = absorption(material.lowAbsorption()), am = absorption(material.midAbsorption());
                double ah = absorption(material.highAbsorption());
                double scatter = Math.min(.98, material.scattering() * settings.scatteringScale());
                lowAbs += al; midAbs += am; highAbs += ah; scattering += scatter; allHits++;
                wetLow += (1 - al) * energy; wetMid += (1 - am) * energy;
                wetHigh += (1 - ah) * energy; scatterWeighted += scatter * energy; surfaceWeight += energy;
                path.low *= (1 - al) * Math.exp(-hit.distance * .0002 * settings.airAbsorption());
                path.mid *= (1 - am) * Math.exp(-hit.distance * .0008 * settings.airAbsorption());
                path.high *= (1 - ah) * Math.exp(-hit.distance * .003 * settings.airAbsorption());
                Vec3 specular = path.direction.subtract(hit.normal.scale(2 * path.direction.dot(hit.normal))).normalize();
                collectReflection(hit, specular, path.distance, (path.low + path.mid + path.high) / 3, scatter, path.bounce);
                if ((path.low + path.mid + path.high) / 3 < .003) return true;
                Vec3 diffuse = hemisphere(index * 31 + path.bounce * 71, hit.normal);
                path.direction = specular.scale(1 - scatter).add(diffuse.scale(scatter)).normalize();
                if (path.direction.dot(hit.normal) < .02) path.direction = path.direction.add(hit.normal.scale(.08)).normalize();
                path.point = hit.point.add(hit.normal.scale(EPSILON));
                path.bounce++;
                return path.bounce >= settings.bounces() || path.remaining <= .05;
        }

        private void collectReflection(Hit hit, Vec3 reflected, double pathDistance, double energy,
                                       double scatter, int bounce) {
            double listenerDistance = hit.point.distanceTo(listener);
            if (listenerDistance < .03 || listenerDistance > settings.range()) return;
            Vec3 toListener = listener.subtract(hit.point).scale(1 / listenerDistance);
            // One visibility ray per bounce. Blocked reflection paths do not become audible echoes.
            if (hit.normal.dot(toListener) < -.01) return;
            double angle = Math.max(0, reflected.dot(toListener));
            double coupling = (1 - scatter) * Math.pow(angle, 10) + scatter * .32 * Math.max(0, hit.normal.dot(toListener));
            if (coupling < .015) return;
            visibilityCasts++;
            if (cast(hit.point.add(hit.normal.scale(EPSILON)), toListener, listenerDistance, true) != null) return;
            double excess = Math.max(0, pathDistance + listenerDistance - directDistance);
            double delay = excess / SPEED_OF_SOUND;
            double received = energy * coupling / (1 + Math.pow((pathDistance + listenerDistance) / 12, 2));
            // Only a real, unblocked surface-to-listener path can colour the reflected sound.
            ReflectionTone tone=ReflectionTone.material(hit.material.category());
            toneWeight+=received;toneHighReference+=tone.highReference()*received;
            toneLowReference+=tone.lowReference()*received;toneHighDecay+=tone.highDecay()*received;toneLowDecay+=tone.lowDecay()*received;
            if (delay <= .08 || bounce == 0) {
                earlyEnergy += received; earlyWeightedDelay += delay * received;
            } else {
                lateEnergy += received; lateWeightedDelay += delay * received;
            }
        }

        private double absorption(double base) { return clamp(base * settings.absorptionScale(), .005, .99, base); }

        private Response snapshot() {
            int sampled = Math.max(1, done + (active == null ? 0 : 1));
            double enclosure = Math.min(1, firstHits / (double) sampled);
            double dx = Math.max(1, maxX - minX), dy = Math.max(1, maxY - minY), dz = Math.max(1, maxZ - minZ);
            double volume = firstHits == 0 ? 0 : dx * dy * dz;
            double area = 2 * (dx * dy + dx * dz + dy * dz);
            double meanDistance = firstHits == 0 ? settings.range() : firstDistance / firstHits;
            double absorption = allHits == 0 ? .5 : (lowAbs + midAbs + highAbs) / (allHits * 3);
            double diffuse = allHits == 0 ? .5 : scattering / allHits;
            double decay = decaySeconds(volume, area, absorption, enclosure, settings.airAbsorption());
            // Geometry spreading suppresses very large rooms; outdoors leaks nearly all late energy.
            double roomScale = Math.cbrt(Math.max(1, volume));
            double sizeGain = 1 / (1 + Math.pow(roomScale / 24, 1.5));
            double confinement = Math.pow(enclosure, 2.5);
            double reflected = Math.max(0, 1 - absorption);
            // Every wet contribution needs an actual surface-to-listener visible path.
            // Source room enclosure alone must never create a wet send through a sealed wall.
            double connectedEnergy = (earlyEnergy + lateEnergy) / sampled;
            double connectivity = Math.min(1, Math.sqrt(Math.max(0, connectedEnergy)) * 2.2);
            double reverb = confinement * sizeGain * reflected * (.22 + .55 * diffuse) * connectivity;
            double earlyGain = Math.sqrt(Math.max(0, earlyEnergy / sampled)) * 1.5 * confinement;
            double earlyDelay = earlyEnergy > 1e-9 ? earlyWeightedDelay / earlyEnergy : meanDistance * 2 / SPEED_OF_SOUND;
            double echo = Math.sqrt(Math.max(0, lateEnergy / sampled)) * 1.6 * confinement * sizeGain;
            // A room-size heuristic cannot invent a discrete echo: only actual delayed visible paths count.
            double echoDelay = lateEnergy > 1e-9 ? lateWeightedDelay / lateEnergy : meanDistance * 2 / SPEED_OF_SOUND;
            List<Segment> geometry = segments.snapshot();
            snapshotEntries = segments.copiedEntries;
            return new Response(source, listener, directLow, directMid, directHigh, decay, reverb,
                    earlyDelay, earlyGain, echoDelay, echo, enclosure, volume, geometry, done, done == settings.rays(),
                    wetMid > 1e-9 ? wetHigh / wetMid : 1,
                    wetMid > 1e-9 ? wetLow / wetMid : 1,
                    surfaceWeight > 1e-9 ? scatterWeighted / surfaceWeight : .5,
                    toneWeight>1e-9?new ReflectionTone(toneHighReference/toneWeight,toneLowReference/toneWeight,
                            toneHighDecay/toneWeight,toneLowDecay/toneWeight):ReflectionTone.NEUTRAL);
        }

        private void addSegment(Vec3 from, Vec3 to, double energy, int bounce, String material) {
            // The first preview never waits for a decimation stride; high-quality bakes stay bounded.
            int stride = Math.max(1, (int) Math.ceil(settings.rays() * settings.bounces() / (double) MAX_SEGMENTS));
            if ((done < 12 || done % stride == 0) && segments.size() < MAX_SEGMENTS && from.distanceToSqr(to) > 1e-5)
                segments.add(new Segment(from, to, energy, bounce, material));
        }

        private double[] transmission(Vec3 from, Vec3 to) {
            double length = from.distanceTo(to);
            if (length < .001) return new double[]{1, 1, 1};
            // Unknown/unloaded geometry is never declared acoustically transparent.
            if (length > 512) return new double[]{0, 0, 0};
            Vec3 direction = to.subtract(from).scale(1 / length);
            int x = floor(from.x), y = floor(from.y), z = floor(from.z);
            int sx = direction.x >= 0 ? 1 : -1, sy = direction.y >= 0 ? 1 : -1, sz = direction.z >= 0 ? 1 : -1;
            double tx = boundary(from.x, direction.x, x, sx), ty = boundary(from.y, direction.y, y, sy);
            double tz = boundary(from.z, direction.z, z, sz);
            double dx = direction.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.x);
            double dy = direction.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.y);
            double dz = direction.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.z);
            double visited = 0, low = 1, mid = 1, high = 1;
            int maxVisits = Math.min(2048, (int)Math.ceil(length * 1.75) + 8);
            for (int count = 0; count < maxVisits && visited < length; count++) {
                BlockPos pos = cellCursor.set(x, y, z);
                voxelVisits++;
                Cell cell = cell(pos);
                if (!cell.known) return new double[]{0, 0, 0};
                double exit = Math.min(length, Math.min(tx, Math.min(ty, tz)));
                if (exit > visited) {
                    VoxelShape shape = cell.shape != null ? cell.shape : cell.fluidShape;
                    if (shape != null) {
                        double thickness = occupiedLength(cell.boxes, from, direction, pos, Math.max(0, visited), exit, intervalScratch, clipScratch);
                        if (thickness > 1e-7) {
                            AcousticMaterials.Profile material = cell.material;
                            low *= transmissionGain(material.transmission(0), thickness);
                            mid *= transmissionGain(material.transmission(1), thickness);
                            high *= transmissionGain(material.transmission(2), thickness);
                            if (low < 1e-8 && mid < 1e-8 && high < 1e-8) return new double[]{0, 0, 0};
                        }
                    }
                }
                if (tx <= ty && tx <= tz) { visited = tx; tx += dx; x += sx; }
                else if (ty <= tz) { visited = ty; ty += dy; y += sy; }
                else { visited = tz; tz += dz; z += sz; }
            }
            return visited + 1e-7 >= length ? new double[]{low, mid, high} : new double[]{0, 0, 0};
        }

        private Hit cast(Vec3 origin, Vec3 direction, double length) {
            return cast(origin, direction, length, false);
        }

        private Hit cast(Vec3 origin, Vec3 direction, double length, boolean visibilityOnly) {
            casts++;
            int x = floor(origin.x), y = floor(origin.y), z = floor(origin.z);
            int sx = direction.x >= 0 ? 1 : -1, sy = direction.y >= 0 ? 1 : -1, sz = direction.z >= 0 ? 1 : -1;
            double tx = boundary(origin.x, direction.x, x, sx), ty = boundary(origin.y, direction.y, y, sy);
            double tz = boundary(origin.z, direction.z, z, sz);
            double dx = direction.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.x);
            double dy = direction.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.y);
            double dz = direction.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.z);
            double visited = 0;
            BlockPos originBlock = cellCursor.set(x, y, z);
            boolean startsInLiquid = cell(originBlock).fluid;
            int maxVisits = Math.min(2048, (int) Math.ceil(length * 1.75) + 8);
            for (int count = 0; count < maxVisits && visited <= length; count++) {
                BlockPos pos = cellCursor.set(x, y, z);
                voxelVisits++;
                Cell cell = cell(pos);
                if (!cell.known) return visibilityOnly ? (visited + .03 < length ? Hit.BLOCKED : null)
                        : new Hit(point(origin, direction, visited), Vec3.ZERO, visited, false, AcousticMaterials.STONE);
                VoxelShape shape = cell.shape != null ? cell.shape : startsInLiquid ? null : cell.fluidShape;
                if (shape != null) {
                    if (intersect(cell.boxes, origin, direction, pos, length, clipScratch)) {
                        double distance = clipScratch[0];
                        if (visibilityOnly) return distance + .03 < length ? Hit.BLOCKED : null;
                        return new Hit(point(origin, direction, distance), NORMALS[(int) clipScratch[1]], distance, true, cell.material);
                    }
                }
                if (tx <= ty && tx <= tz) { visited = tx; tx += dx; x += sx; }
                else if (ty <= tz) { visited = ty; ty += dy; y += sy; }
                else { visited = tz; tz += dz; z += sz; }
            }
            return visibilityOnly ? null : new Hit(point(origin, direction, length), Vec3.ZERO, length, false, AcousticMaterials.STONE);
        }

        /** A bake samples local geometry once per occupied cell. It never loads a chunk or shares
         * live world access with the audio thread; the next bake observes block/door changes. */
        private Cell cell(BlockPos pos) {
            Cell previous = cells.get(pos);
            if (previous != null) { cacheHits++; return previous; }
            Cell result;
            if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)
                    || !level.getWorldBorder().isWithinBounds(pos)) result = Cell.UNKNOWN;
            else {
                worldQueries++;
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) result = Cell.AIR;
                else {
                    VoxelShape shape = state.getCollisionShape(level, pos, COLLISION_CONTEXT);
                    boolean fluid = !state.getFluidState().isEmpty();
                    VoxelShape fluidShape = shape.isEmpty() && fluid
                            ? Shapes.box(0, 0, 0, 1, state.getFluidState().getOwnHeight(), 1) : null;
                    VoxelShape geometry = shape.isEmpty() ? fluidShape : shape;
                    result = new Cell(true, shape.isEmpty() ? null : shape, fluidShape, fluid, boxes(geometry),
                            materials.computeIfAbsent(state, AcousticMaterials::resolve));
                }
            }
            if (cells.size() < MAX_CACHED_CELLS) cells.put(pos.immutable(), result);
            return result;
        }

        private AABB[] boxes(VoxelShape shape) {
            if (shape == null) return Cell.NO_BOXES;
            AABB[] cached = shapeBoxes.get(shape);
            if (cached != null) return cached;
            List<AABB> list = shape.toAabbs();
            AABB[] result;
            if (list.size() <= MAX_SHAPE_BOXES) result = list.toArray(AABB[]::new);
            else {
                // Pathological custom shapes remain conservative, rather than dropping later solid boxes.
                double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = -x0, y1 = x1, z1 = x1;
                for (AABB box : list) {
                    x0 = Math.min(x0, box.minX); y0 = Math.min(y0, box.minY); z0 = Math.min(z0, box.minZ);
                    x1 = Math.max(x1, box.maxX); y1 = Math.max(y1, box.maxY); z1 = Math.max(z1, box.maxZ);
                }
                result = new AABB[]{new AABB(x0, y0, z0, x1, y1, z1)};
            }
            if (shapeBoxes.size() < 4096) shapeBoxes.put(shape, result);
            return result;
        }
    }

    private record Cell(boolean known, VoxelShape shape, VoxelShape fluidShape, boolean fluid, AABB[] boxes,
                        AcousticMaterials.Profile material) {
        private static final AABB[] NO_BOXES = new AABB[0];
        private static final Cell AIR = new Cell(true, null, null, false, NO_BOXES, AcousticMaterials.STONE);
        private static final Cell UNKNOWN = new Cell(false, null, null, false, NO_BOXES, AcousticMaterials.STONE);
    }

    /** Nearest collision-box face, without VoxelShape.clip's per-cast intermediate vectors/lists. */
    private static boolean intersect(AABB[] boxes, Vec3 origin, Vec3 direction, BlockPos pos, double length, double[] result) {
        double ox = origin.x - pos.getX(), oy = origin.y - pos.getY(), oz = origin.z - pos.getZ();
        double nearest = length + 1e-8; int normal = -1;
        for (AABB box : boxes) {
            double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
            int entry = -1, exit = -1;
            if (Math.abs(direction.x) < 1e-12) { if (ox < box.minX || ox > box.maxX) continue; }
            else {
                double a = (box.minX - ox) / direction.x, b = (box.maxX - ox) / direction.x;
                near = Math.min(a, b); far = Math.max(a, b); entry = direction.x > 0 ? 0 : 1; exit = entry ^ 1;
            }
            if (Math.abs(direction.y) < 1e-12) { if (oy < box.minY || oy > box.maxY) continue; }
            else {
                double a = (box.minY - oy) / direction.y, b = (box.maxY - oy) / direction.y;
                double lo = Math.min(a, b), hi = Math.max(a, b);
                if (lo > near) { near = lo; entry = direction.y > 0 ? 2 : 3; }
                if (hi < far) { far = hi; exit = direction.y > 0 ? 3 : 2; }
            }
            if (near > far) continue;
            if (Math.abs(direction.z) < 1e-12) { if (oz < box.minZ || oz > box.maxZ) continue; }
            else {
                double a = (box.minZ - oz) / direction.z, b = (box.maxZ - oz) / direction.z;
                double lo = Math.min(a, b), hi = Math.max(a, b);
                if (lo > near) { near = lo; entry = direction.z > 0 ? 4 : 5; }
                if (hi < far) { far = hi; exit = direction.z > 0 ? 5 : 4; }
            }
            if (near > far || far <= 1e-7) continue;
            double distance = near > 1e-7 ? near : far;
            int face = near > 1e-7 ? entry : exit;
            if (face >= 0 && distance < nearest) { nearest = distance; normal = face; }
        }
        result[0] = nearest; result[1] = normal;
        return normal >= 0;
    }

    private static Vec3 point(Vec3 origin, Vec3 direction, double distance) {
        return new Vec3(origin.x + direction.x * distance, origin.y + direction.y * distance, origin.z + direction.z * distance);
    }

    /** Full chunks are sealed forever; publication only copies the current 128-entry tail and
     * chunk references. Audio/render consumers retain a stable, immutable prefix while baking. */
    private static final class SegmentBuilder {
        private final List<Segment[]> full = new ArrayList<>();
        private Segment[] tail;
        private int tailSize, size;
        private long copiedEntries;
        private int size() { return size; }
        private void add(Segment segment) {
            if (tail == null) tail = new Segment[SEGMENT_CHUNK];
            if (tailSize == SEGMENT_CHUNK) {
                full.add(tail); tail = new Segment[SEGMENT_CHUNK]; tailSize = 0;
            }
            tail[tailSize++] = segment; size++;
        }
        private List<Segment> snapshot() {
            if (size == 0) return List.of();
            Segment[][] chunks = full.toArray(new Segment[full.size() + 1][]);
            chunks[full.size()] = Arrays.copyOf(tail, tailSize);
            copiedEntries += full.size() + tailSize;
            return new SegmentSnapshot(this, chunks, size);
        }
    }

    private static final class SegmentSnapshot extends AbstractList<Segment> implements RandomAccess {
        private final Object bake;
        private final Segment[][] chunks;
        private final int size;
        private SegmentSnapshot(Object bake, Segment[][] chunks, int size) { this.bake = bake; this.chunks = chunks; this.size = size; }
        @Override public int size() { return size; }
        @Override public Segment get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return chunks[index / SEGMENT_CHUNK][index % SEGMENT_CHUNK];
        }
    }

    /** Native immutable publications prove a shared prefix in O(1), without scanning historical rays. */
    public static boolean sameSegmentPrefix(List<Segment> first, List<Segment> second, int count) {
        if (count < 0 || first == null || second == null || first.size() < count || second.size() < count) return false;
        if (first == second || count == 0) return true;
        if (first instanceof SegmentSnapshot a && second instanceof SegmentSnapshot b) return a.bake == b.bake;
        for (int i = 0; i < count; i++) if (first.get(i) != second.get(i)) return false;
        return true;
    }

    private static final class Path {
        private Vec3 point, direction;
        private double low = 1, mid = 1, high = 1, distance, remaining;
        private int bounce;
        private Path(Vec3 source, Vec3 direction, ProbeSettings settings) {
            this.point = source;
            this.direction = direction;
            this.remaining = settings.range() * Math.min(4, settings.bounces());
        }
    }

    /** Gameplay pressure spreading with a four-metre source reference zone. Wall loss remains separate. */
    public static double distanceAmplitude(double metres) {
        double distance = clamp(metres, 0, 1e9, 1e9);
        return REFERENCE_DISTANCE / (REFERENCE_DISTANCE + DISTANCE_ROLLOFF * Math.max(0, distance - REFERENCE_DISTANCE));
    }

    /** The cast backend owns this factor exactly once; it does not change Minecraft's global distance model. */
    public static double distanceGain(double metres, double maxRange) {
        double distance = clamp(metres, 0, 1e9, 1e9), maximum = clamp(maxRange, 1, 2048, 64);
        double t = clamp((distance - maximum * .8) / (maximum * .2), 0, 1, 1);
        return distanceAmplitude(distance) * (1 - t * t * (3 - 2 * t));
    }

    /** Compose wall loss by actual intersected thickness, rather than by just the first solid face. */
    public static double transmissionGain(double pressurePerMetre, double metres) {
        return Math.pow(clamp(pressurePerMetre, .000001, 1, .001), clamp(metres, 0, 1024, 0));
    }

    /** Union of collision-box intervals, so stairs, slabs and overlapping boxes are not full cubes. */
    private static double occupiedLength(AABB[] boxes, Vec3 origin, Vec3 direction,
                                         BlockPos pos, double minimum, double maximum, double[] intervals, double[] interval) {
        if (boxes.length == 0 || maximum <= minimum) return 0;
        int used = 0;
        for (AABB box : boxes) {
            interval[0] = minimum; interval[1] = maximum;
            if (clipAxis(origin.x, direction.x, box.minX + pos.getX(), box.maxX + pos.getX(), interval)
                    && clipAxis(origin.y, direction.y, box.minY + pos.getY(), box.maxY + pos.getY(), interval)
                    && clipAxis(origin.z, direction.z, box.minZ + pos.getZ(), box.maxZ + pos.getZ(), interval)
                    && interval[1] > interval[0]) {
                // Collision shapes are small; insertion sorting avoids comparator and per-box array allocations.
                int at = used;
                while (at > 0 && intervals[(at - 1) * 2] > interval[0]) {
                    intervals[at * 2] = intervals[(at - 1) * 2]; intervals[at * 2 + 1] = intervals[(at - 1) * 2 + 1]; at--;
                }
                intervals[at * 2] = interval[0]; intervals[at * 2 + 1] = interval[1]; used++;
            }
        }
        if (used == 0) return 0;
        double start = intervals[0], end = intervals[1], total = 0;
        for (int i = 1; i < used; i++) {
            if (intervals[i * 2] > end + 1e-8) {
                total += end - start; start = intervals[i * 2]; end = intervals[i * 2 + 1];
            } else end = Math.max(end, intervals[i * 2 + 1]);
        }
        return Math.max(0, Math.min(maximum - minimum, total + end - start));
    }

    private static boolean clipAxis(double origin, double direction, double min, double max, double[] interval) {
        if (Math.abs(direction) < 1e-12) return origin >= min && origin <= max;
        double from = (min - origin) / direction, to = (max - origin) / direction;
        interval[0] = Math.max(interval[0], Math.min(from, to));
        interval[1] = Math.min(interval[1], Math.max(from, to));
        return interval[1] >= interval[0];
    }

    /** Eyring RT60 with an opening as an absorbing boundary; finite limits are intentional for gameplay. */
    public static double decaySeconds(double volume, double surfaceArea, double absorption,
                                      double enclosure, double airAbsorption) {
        if (!(volume > 0) || !(surfaceArea > 0)) return .1;
        double enclosed = clamp(enclosure, 0, 1, 0);
        double alpha = clamp(absorption + (1 - enclosed) * .85, .005, .995, .5);
        double area = clamp(surfaceArea, 1, 1e9, 1);
        double v = clamp(volume, 0, 8_000_000, 0);
        double air = clamp(airAbsorption, 0, 4, 1) * .001;
        double rt = .161 * v / (-area * Math.log1p(-alpha) + 4 * air * v);
        double hugeRoomDamping = 1 + Math.max(0, Math.cbrt(v) - 24) / 64;
        return clamp(rt * (.12 + .88 * enclosed * enclosed) / hugeRoomDamping, .1, 6, .1);
    }

    /** Distinct echo energy loses strength with distance, absorption, openings and diffuse scattering. */
    public static double echoStrength(double meanDistance, double absorption, double enclosure, double scattering) {
        double distance = clamp(meanDistance, 0, 512, 0);
        return Math.pow(clamp(enclosure, 0, 1, 0), 2.5)
                * (1 - clamp(absorption, 0, 1, 1)) * (1 - clamp(scattering, 0, 1, 1) * .65)
                * Math.min(1, distance / 8) / (1 + Math.pow(distance / 15, 2)) * .32;
    }

    private record Hit(Vec3 point, Vec3 normal, double distance, boolean solid, AcousticMaterials.Profile material) {
        private static final Hit BLOCKED = new Hit(Vec3.ZERO, Vec3.ZERO, 0, true, AcousticMaterials.STONE);
    }

    private static Vec3 sphere(int index, int total) {
        // Prefix-balanced low-discrepancy directions: the very first tick covers the whole room.
        // A fixed-total Fibonacci latitude sweep clustered partial responses near the ceiling.
        double latitude = Integer.toUnsignedLong(Integer.reverse(index + 1)) * 0x1.0p-32;
        double y = 1 - 2 * latitude;
        double radius = Math.sqrt(Math.max(0, 1 - y * y));
        double phi = index * 2.399963229728653;
        return new Vec3(Math.cos(phi) * radius, y, Math.sin(phi) * radius);
    }

    private static Vec3 hemisphere(int seed, Vec3 normal) {
        int index = Math.floorMod(seed * 53 + 19, 997);
        Vec3 sample = DIFFUSE_DIRECTIONS[index];
        if (sample == null) DIFFUSE_DIRECTIONS[index] = sample = sphere(index, 997);
        if (sample.dot(normal) < 0) sample = sample.scale(-1);
        return sample.add(normal.scale(.25)).normalize();
    }

    private static int floor(double value) { int i = (int) value; return value < i ? i - 1 : i; }
    private static double boundary(double value, double direction, int cell, int step) {
        return direction == 0 ? Double.POSITIVE_INFINITY : ((step > 0 ? cell + 1 : cell) - value) / direction;
    }
    private static boolean finite(Vec3 position) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
    }
    private static double clamp(double value, double low, double high, double fallback) {
        return Math.max(low, Math.min(high, Double.isFinite(value) ? value : fallback));
    }
}
