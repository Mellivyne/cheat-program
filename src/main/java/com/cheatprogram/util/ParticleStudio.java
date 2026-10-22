package com.cheatprogram.util;

import com.cheatprogram.CheatProgram;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Editable particle composition; world anchors explicitly include server/save and dimension. */
public final class ParticleStudio {
    public static final int MAX_ROUNDS = 128, MAX_EMITTERS = 256, MAX_TOTAL = 4096;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Minecraft MC = Minecraft.getInstance();
    private static Project project;
    private static boolean dirty;
    private static long saveAfter;
    private static long revision;
    private static String ioStatus = "";
    private static boolean unreadableOriginal;
    private ParticleStudio() { }
    public static final class Project {
        public int version = 2;
        public List<Round> rounds = new ArrayList<>();
    }
    public static final class Round {
        public String id = UUID.randomUUID().toString(), name = "";
        public int duration = 100;
        public boolean hold;
        public List<Emitter> emitters = new ArrayList<>();
    }
    public static final class Emitter {
        public String id = UUID.randomUUID().toString(), particle = "minecraft:end_rod", options = "";
        public boolean shown = true, fixed, rotateWithPlayer = true;
        public String world = "", dimension = "", symmetry = "none";
        public double anchorX, anchorY, anchorZ, x = .7, y = 1, z;
        public double pitch, yaw, roll, vx, vy = .025, vz, spread = .025, scale = 1;
        public int every = 4, count = 1, start, duration, lifetime;
        public ParticlePath.Config path = new ParticlePath.Config();
        public transient String error = "";
        public Emitter copy() {
            Emitter e = GSON.fromJson(GSON.toJson(this), Emitter.class); e.id = UUID.randomUUID().toString(); return e;
        }
        public Vec3 offset() { return new Vec3(x, y, z); }
        public Vec3 anchor() {
            if (fixed) return new Vec3(anchorX, anchorY, anchorZ);
            return MC.player == null ? Vec3.ZERO : MC.player.position();
        }
        public boolean inWorld() {
            return MC.level != null && (!fixed || world.equals(worldKey()) && dimension.equals(MC.level.dimension().location().toString()));
        }
        public Vec3 toWorld(Vec3 offset) {
            if (!fixed && rotateWithPlayer && MC.player != null) offset = offset.yRot((float)Math.toRadians(-MC.player.yBodyRot));
            return anchor().add(offset);
        }
        public Vec3 velocity() {
            Vector3f value = new Vector3f((float)vx, (float)vy, (float)vz);
            new Quaternionf().rotationYXZ((float)Math.toRadians(yaw),(float)Math.toRadians(pitch),(float)Math.toRadians(roll)).transform(value);
            Vec3 v = new Vec3(value.x,value.y,value.z);
            return !fixed && rotateWithPlayer && MC.player != null ? v.yRot((float)Math.toRadians(-MC.player.yBodyRot)) : v;
        }
        public List<Vec3> mirrored() {
            ArrayList<Vec3> result = new ArrayList<>(8);
            boolean sx = symmetry.contains("x"), sy = symmetry.contains("y"), sz = symmetry.contains("z");
            for (int ix=0;ix<(sx?2:1);ix++) for(int iy=0;iy<(sy?2:1);iy++) for(int iz=0;iz<(sz?2:1);iz++) {
                Vec3 point = new Vec3(ix==0?x:-x,iy==0?y:-y,iz==0?z:-z);
                if (!result.contains(point)) result.add(point);
            }
            return result;
        }
        /** Keep sign branches distinct even when a path starts on a symmetry plane. */
        public List<Vec3> mirrorSigns() {
            ArrayList<Vec3> result=new ArrayList<>(8);
            boolean sx=symmetry.contains("x"),sy=symmetry.contains("y"),sz=symmetry.contains("z");
            for(int ix=0;ix<(sx?2:1);ix++)for(int iy=0;iy<(sy?2:1);iy++)for(int iz=0;iz<(sz?2:1);iz++)
                result.add(new Vec3(ix==0?1:-1,iy==0?1:-1,iz==0?1:-1));
            return result;
        }
        public void pin(Vec3 position) {
            fixed = true; anchorX = position.x; anchorY = position.y; anchorZ = position.z;
            world = worldKey(); dimension = MC.level == null ? "" : MC.level.dimension().location().toString(); touch();
        }
    }
    public static Path path() { return MC.gameDirectory.toPath().resolve("config/cheatprogram-open/particles.json"); }
    public static Project project() {
        if (project != null) return project;
        project = new Project();
        try {
            Path path = path();
            if (Files.exists(path)) {
                if (Files.size(path) > 4_194_304) throw new IllegalArgumentException("Particle composition exceeds 4 MiB");
                Project loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Project.class);
                if (loaded != null) project = loaded;
            }
        } catch (Exception error) { unreadableOriginal=true;ioStatus = "ui.particles.loadFailed"; CheatProgram.LOGGER.warn("Cannot read particle composition", error); }
        sanitize(project); return project;
    }
    public static void touch() { dirty = true; revision++; saveAfter = System.currentTimeMillis() + 700; }
    public static long revision() { return revision; }
    public static void flush(boolean immediate) {
        synchronized(com.cheatprogram.core.Config.persistenceLock()) {
        if(!com.cheatprogram.core.Config.persistenceAllowed())return;
        if (!dirty || !immediate && System.currentTimeMillis() < saveAfter) return;
        try {
            Path path = path(), temp = path.resolveSibling("particles.json.tmp"); Files.createDirectories(path.getParent());
            if(unreadableOriginal&&Files.exists(path)) {
                Path backup=path.resolveSibling("particles.unreadable-"+System.currentTimeMillis()+".json");Files.copy(path,backup);unreadableOriginal=false;
            }
            Files.writeString(temp, GSON.toJson(project()), StandardCharsets.UTF_8);
            try { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
            dirty = false; ioStatus = "";
        } catch (Exception error) { ioStatus = "ui.particles.saveFailed"; saveAfter = System.currentTimeMillis()+5000; CheatProgram.LOGGER.warn("Cannot save particle composition", error); }
        }
    }
    public static String ioStatus() { return ioStatus; }
    public static int total() { return project().rounds.stream().mapToInt(r -> r.emitters.size()).sum(); }
    public static Round addRound() {
        if (project().rounds.size() >= MAX_ROUNDS) return null;
        Round round = new Round(); project().rounds.add(round); touch(); return round;
    }
    public static Emitter addEmitter(Round round, String type, String options) {
        if (round.emitters.size() >= MAX_EMITTERS || total() >= MAX_TOTAL) return null;
        Emitter emitter = new Emitter(); emitter.particle = type; emitter.options = options; round.emitters.add(emitter); touch(); return emitter;
    }
    public static String worldKey() {
        var server = MC.getSingleplayerServer();
        if (server != null) return "world:"+server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var data = MC.getCurrentServer();
        if (data != null) return "server:"+data.ip.strip().toLowerCase(Locale.ROOT);
        return MC.getConnection() == null ? "" : "connection:"+MC.getConnection().getConnection().getRemoteAddress();
    }
    private static void sanitize(Project p) {
        if (p.rounds == null) p.rounds = new ArrayList<>();
        p.rounds.removeIf(r -> r == null); if(p.rounds.size()>MAX_ROUNDS)p.rounds=new ArrayList<>(p.rounds.subList(0,MAX_ROUNDS));
        boolean migrateBodyFollow = p.version < 2;
        HashSet<String> ids = new HashSet<>(); int total=0;
        for(Round r:p.rounds) {
            r.id=unique(r.id,ids); r.name=text(r.name,80);r.duration=clamp(r.duration,1,72000);
            if(r.emitters==null)r.emitters=new ArrayList<>();r.emitters.removeIf(e->e==null);
            int limit=Math.min(MAX_EMITTERS,MAX_TOTAL-total); if(r.emitters.size()>limit)r.emitters=new ArrayList<>(r.emitters.subList(0,limit));total+=r.emitters.size();
            for(Emitter e:r.emitters) {
                // Version 1 defaulted this off, so compositions appeared left behind on turning.
                // Version 2 restores body-relative follow; later explicit off choices are retained.
                if(migrateBodyFollow && !e.fixed)e.rotateWithPlayer=true;
                e.id=unique(e.id,ids);e.particle=text(e.particle,256);e.options=text(e.options,8192);e.world=text(e.world,4096);e.dimension=text(e.dimension,256);
                if(!List.of("none","x","y","z","xy","xz","yz","xyz").contains(e.symmetry))e.symmetry="none";
                e.anchorX=finite(e.anchorX,-30_000_000,30_000_000);e.anchorY=finite(e.anchorY,-2048,2048);e.anchorZ=finite(e.anchorZ,-30_000_000,30_000_000);
                e.x=finite(e.x,-1024,1024);e.y=finite(e.y,-1024,1024);e.z=finite(e.z,-1024,1024);
                e.pitch=finite(e.pitch,-360,360);e.yaw=finite(e.yaw,-360,360);e.roll=finite(e.roll,-360,360);
                e.vx=finite(e.vx,-4,4);e.vy=finite(e.vy,-4,4);e.vz=finite(e.vz,-4,4);e.spread=finite(e.spread,0,8);e.scale=finite(e.scale,.05,8);
                e.path=ParticlePath.sanitize(e.path);
                e.every=clamp(e.every,1,1200);e.count=clamp(e.count,1,100);e.start=clamp(e.start,0,72000);e.duration=clamp(e.duration,0,72000);e.lifetime=clamp(e.lifetime,0,1200);e.error="";
            }
        }
        p.version=2;
        if(migrateBodyFollow)touch();
        if(p.rounds.isEmpty())p.rounds.add(new Round());
    }
    private static String unique(String id,HashSet<String> ids){if(id==null||id.length()>100||!ids.add(id)){id=UUID.randomUUID().toString();ids.add(id);}return id;}
    private static String text(String value,int max){return value==null?"":value.substring(0,Math.min(value.length(),max));}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static double finite(double v,double lo,double hi){return Double.isFinite(v)?Math.max(lo,Math.min(hi,v)):0;}
}
