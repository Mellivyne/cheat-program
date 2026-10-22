package com.cheatprogram.util;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.movement.Flight;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.module.movement.NoFall;
import com.cheatprogram.module.movement.TeleportAura;
import com.cheatprogram.module.utility.Blink;
import com.cheatprogram.module.visual.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Shared fall protection. Server-thread checks consume only an immutable, host-scoped publication. */
public final class FallProtection {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final FallPacketGuard PACKETS=new FallPacketGuard();
    private static Object world,connection;
    private static LocalPlayer body;
    private static boolean previousRequest,finishLanding;
    private static long landingGeneration;
    private static volatile long serverLandingAcknowledged=Long.MIN_VALUE;
    private record Protection(LocalPlayer local,UUID uuid,ResourceKey<Level> dimension,MinecraftServer server,long generation,boolean finishOnly) {
        boolean owns(Entity entity){
            if(entity==null||!entity.isAlive()||entity.isRemoved()||entity.isPassenger()||entity.isSpectator()
                    ||entity.level().dimension()!=dimension)return false;
            if(entity.level().isClientSide())return entity==local;
            return server!=null&&entity instanceof ServerPlayer player&&player.level().getServer()==server&&uuid.equals(player.getUUID());
        }
    }
    private static volatile Protection protection;
    private FallProtection(){}
    private static boolean requested(){
        return ModuleManager.on(NoFall.class)||Flight.requestsFallProtection()||FlatFlight.requestsFallProtection();
    }
    private static boolean usable(){
        return MC.player!=null&&MC.level!=null&&MC.getConnection()!=null&&MC.player.isAlive()&&!MC.player.isRemoved()
                &&!MC.player.isSpectator()&&!MC.player.isPassenger()&&!MC.player.noPhysics
                &&Config.persistenceAllowed()&&!Config.restartRequired()&&!Freecam.isActive()
                &&!ModuleManager.on(Blink.class)&&!TeleportAura.ownsMotion();
    }
    private static void clear(){
        protection=null;body=null;world=connection=null;previousRequest=finishLanding=false;landingGeneration++;PACKETS.reset();
    }
    /** Called before movement, including while a module is disabled or the world is replaced. */
    public static void tickLifecycle(){refresh();}
    public static void refresh(){
        if(!usable()){clear();return;}
        var p=MC.player;var level=MC.level;var link=MC.getConnection();
        if(body!=p||world!=level||connection!=link){
            protection=null;body=p;world=level;connection=link;previousRequest=finishLanding=false;
            landingGeneration++;
            PACKETS.rebase(p.getY(),p.fallDistance);
        }
        boolean contact=p.onGround()||p.isInWater()||p.isInLava()||p.onClimbable();
        boolean enabled=requested();
        MinecraftServer server=MC.getSingleplayerServer();
        if(contact)PACKETS.naturalReset();
        if(server!=null){
            // Client collision can run ahead of queued server movement. Release only after the
            // server consumes its own landing/liquid/climbable check for this exact session.
            if(previousRequest&&!enabled){finishLanding=true;landingGeneration++;}
            if(enabled||finishLanding&&serverLandingAcknowledged==landingGeneration)finishLanding=false;
        }else{
            if(contact)finishLanding=false;
            else if(previousRequest&&!enabled)finishLanding=true;
        }
        previousRequest=enabled;
        protection=(enabled||finishLanding)&&!p.isFallFlying()
                ?new Protection(p,p.getUUID(),level.dimension(),server,landingGeneration,!enabled&&finishLanding):null;
    }
    public static boolean owns(Entity entity){Protection current=protection;return current!=null&&current.owns(entity);}
    /** Server-thread acknowledgement happens after protecting the queued check itself. */
    public static boolean protectServerFall(ServerPlayer player,boolean onGround){
        Protection current=protection;
        if(current==null||!current.owns(player))return false;
        player.resetFallDistance();
        if(current.finishOnly&&(onGround||player.isInWater()||player.isInLava()||player.onClimbable()))
            serverLandingAcknowledged=current.generation;
        return true;
    }
    /** Corrections are accepted normally; a pre-correction descent estimate cannot be reused. */
    public static void onPositionCorrection(){
        if(MC.player!=null){PACKETS.rebase(MC.player.getY(),MC.player.fallDistance);refresh();}
        else clear();
    }
    /** Rewrite only a genuine descending position packet; no extra StatusOnly landing packets. */
    public static Packet<?> movementPacket(Packet<?> packet){
        if(!(packet instanceof ServerboundMovePlayerPacket move)||!move.hasPosition()||!MC.isSameThread())return packet;
        refresh();if(body==null)return packet;
        boolean protectedFall=protection!=null&&!body.isFallFlying()
                &&!body.isInWater()&&!body.isInLava()&&!body.onClimbable();
        // The integrated host uses authoritative fall handling; permitted vanilla flight needs no report rewrite.
        boolean report=protectedFall&&MC.getSingleplayerServer()==null
                &&!(body.getAbilities().flying&&Flight.serverAllowsFlight());
        boolean shield=PACKETS.position(move.getY(body.getY()),move.isOnGround(),report,
                Math.max(0,body.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)));
        if(!shield||move.isOnGround())return packet;
        if(move instanceof ServerboundMovePlayerPacket.PosRot)
            return new ServerboundMovePlayerPacket.PosRot(move.getX(0),move.getY(0),move.getZ(0),move.getYRot(0),move.getXRot(0),true,move.horizontalCollision());
        if(move instanceof ServerboundMovePlayerPacket.Pos)
            return new ServerboundMovePlayerPacket.Pos(move.getX(0),move.getY(0),move.getZ(0),true,move.horizontalCollision());
        return packet;
    }
}
