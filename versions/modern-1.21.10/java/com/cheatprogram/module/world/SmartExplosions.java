package com.cheatprogram.module.world;

import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.core.Lang;
import com.cheatprogram.util.Targets;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Predicate;

/** Bounded loaded-block selection. Damage values are estimates, never server hit promises. */
final class SmartExplosions {
    private static final Minecraft mc = Minecraft.getInstance();
    final BoolSetting enabled = new BoolSetting("smartSelection", false);
    final NumberSetting range = new NumberSetting("smartRange", 4.5, 1, 6, .1, "");
    final NumberSetting minDamage = new NumberSetting("minTargetDamage", 4, 0, 36, .5, "HP");
    final NumberSetting maxSelf = new NumberSetting("maxSelfDamage", 6, 0, 36, .5, "HP");
    final BoolSetting marker = new BoolSetting("selectionMarker", true);
    private long nextScan;
    private Object world;
    private Choice choice;
    record Choice(BlockHitResult hit, LivingEntity target, float targetDamage, float selfDamage) {}

    void reset() { nextScan=0; choice=null; world=null; }

    Choice find(boolean anchor, Predicate<BlockPos> valid) {
        if (mc.player==null || mc.level==null) { reset(); return null; }
        if (world!=mc.level) { reset(); world=mc.level; }
        long now=System.currentTimeMillis();
        if (now<nextScan && choice!=null && Targets.isValid(choice.target()) && valid.test(choice.hit().getBlockPos())
                && WorldInteractSupport.reachable(choice.hit())) return choice;
        if (now<nextScan) return null;
        nextScan=now+200; choice=null;
        LivingEntity target=Targets.best(12,360,"distance",true);
        if (target==null || target instanceof Player p && p.isCreative()) return null;
        Vec3 eye=mc.player.getEyePosition();
        double reach=Math.min(range.get(),mc.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE));
        int r=(int)Math.ceil(reach); BlockPos origin=BlockPos.containing(eye);
        List<BlockHitResult> candidates=new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r,-r,-r),origin.offset(r,r,r))) {
            if (!mc.level.hasChunkAt(pos) || !mc.level.getWorldBorder().isWithinBounds(pos)
                    || eye.distanceToSqr(Vec3.atCenterOf(pos))>(reach+1)*(reach+1)) continue;
            BlockState state=mc.level.getBlockState(pos);
            if (!(anchor ? state.is(Blocks.RESPAWN_ANCHOR) : state.is(Blocks.OBSIDIAN))) continue;
            if (!valid.test(pos)) continue;
            BlockHitResult hit=anchor ? visibleFace(pos) : new BlockHitResult(Vec3.atCenterOf(pos).add(0,.5,0),Direction.UP,pos.immutable(),false);
            if (hit!=null && eye.distanceToSqr(hit.getLocation())<=reach*reach && WorldInteractSupport.reachable(hit)) candidates.add(hit);
        }
        // Prefer close-to-target candidates before the relatively costly vanilla exposure rays.
        candidates.sort(Comparator.comparingDouble(h -> center(h.getBlockPos(),anchor).distanceToSqr(target.position())));
        double best=-Double.MAX_VALUE; long start=System.nanoTime();
        for (int i=0;i<Math.min(16,candidates.size());i++) {
            if (i>0 && System.nanoTime()-start>5_000_000L) break;
            BlockHitResult hit=candidates.get(i); Vec3 center=center(hit.getBlockPos(),anchor);
            BlockPos ignore=anchor ? hit.getBlockPos() : null;
            float self=damage(center,anchor?5:6,mc.player,ignore);
            if (self>maxSelf.get() || self>=mc.player.getHealth()+mc.player.getAbsorptionAmount()-1) continue;
            float dealt=damage(center,anchor?5:6,target,ignore);
            if (dealt<minDamage.get()) continue;
            double score=dealt-self*1.25;
            if(score>best){best=score;choice=new Choice(hit,target,dealt,self);}
        }
        return choice;
    }

    boolean safe(Vec3 point, boolean anchor, BlockPos ignore) {
        float self=damage(point,anchor?5:6,mc.player,ignore);
        return self<=maxSelf.get() && self<mc.player.getHealth()+mc.player.getAbsorptionAmount()-1;
    }

    private static Vec3 center(BlockPos pos,boolean anchor) {
        return anchor ? Vec3.atCenterOf(pos) : Vec3.atBottomCenterOf(pos.above());
    }
    private static BlockHitResult visibleFace(BlockPos pos) {
        Vec3 eye=mc.player.getEyePosition(), center=Vec3.atCenterOf(pos);
        for(Direction d:Direction.values()) {
            Vec3 end=center.add(d.getStepX()*.501,d.getStepY()*.501,d.getStepZ()*.501);
            BlockHitResult ray=mc.level.clip(new ClipContext(eye,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(pos)) return ray;
        }
        return null;
    }
    void render(Render3D r) {
        if(!enabled.on() || !marker.on() || choice==null || mc.level!=world || !Targets.isValid(choice.target()))return;
        r.begin();try {r.box(new AABB(choice.hit().getBlockPos()).inflate(.005),0x14FFFFFF,0xEEFFFFFF,1.6f);}
        finally {r.end();}
    }
    void hud(HudContext hud,boolean anchor) {
        if(!enabled.on() || !marker.on() || choice==null || world!=mc.level || mc.screen!=null || hud.editing || !Targets.isValid(choice.target()))return;
        String text=Lang.t(anchor?"explosion.anchor":"explosion.crystal")+" · "+Lang.t("explosion.estimate",
                String.format(Locale.ROOT,"%.1f",choice.targetDamage()),String.format(Locale.ROOT,"%.1f",choice.selfDamage()));
        hud.c.textMid(Fonts.Face.REGULAR,text,hud.width/2,hud.height/2+(anchor?54:42),8,0xCCFFFFFF);
    }

    /** Vanilla distance/exposure formula, followed by visible difficulty, armor, effects and enchantments. */
    private static float damage(Vec3 point,float radius,LivingEntity entity,BlockPos removed) {
        if(entity==null || !entity.isAlive() || entity instanceof Player p && (p.isCreative()||p.isSpectator()))return 0;
        double distance=entity.position().distanceTo(point)/(radius*2);
        if(distance>1)return 0;
        double impact=(1-distance)*exposure(point,entity,removed);
        float value=(float)((impact*impact+impact)/2*7*radius*2+1);
        if(entity instanceof Player) value=switch(mc.level.getDifficulty()) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(value/2+1,value);
            case HARD -> value*1.5f;
            default -> value;
        };
        float armor=entity.getArmorValue(), tough=(float)entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        value*=1-Mth.clamp(armor-value/(2+tough/4),armor*.2f,20)/25;
        var resistance=entity.getEffect(MobEffects.RESISTANCE);
        if(resistance!=null)value*=Math.max(0,1-.2f*(resistance.getAmplifier()+1));
        int protection=0;
        for(var stack:java.util.List.of(entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET), entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS), entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST), entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)))for(var enchant:stack.getEnchantments().entrySet()) {
            if(enchant.getKey().is(Enchantments.PROTECTION))protection+=enchant.getIntValue();
            if(enchant.getKey().is(Enchantments.BLAST_PROTECTION))protection+=2*enchant.getIntValue();
        }
        return Math.max(0,value*(1-Math.min(20,protection)/25f));
    }
    private static float exposure(Vec3 point,LivingEntity entity,BlockPos removed) {
        // RespawnAnchorBlock removes the anchor before computing exposure. This view mirrors that
        // without mutating client blocks or creating a prediction world.
        BlockGetter blocks=removed==null?mc.level:new BlockGetter(){
            public BlockEntity getBlockEntity(BlockPos p){return p.equals(removed)?null:mc.level.getBlockEntity(p);}
            public BlockState getBlockState(BlockPos p){return p.equals(removed)?Blocks.AIR.defaultBlockState():mc.level.getBlockState(p);}
            public FluidState getFluidState(BlockPos p){return p.equals(removed)?Fluids.EMPTY.defaultFluidState():mc.level.getFluidState(p);}
            public int getHeight(){return mc.level.getHeight();}
            public int getMinY(){return mc.level.getMinY();}
        };
        AABB b=entity.getBoundingBox();
        double dx=1/((b.maxX-b.minX)*2+1),dy=1/((b.maxY-b.minY)*2+1),dz=1/((b.maxZ-b.minZ)*2+1);
        double ox=(1-Math.floor(1/dx)*dx)/2,oz=(1-Math.floor(1/dz)*dz)/2;
        int clear=0,total=0;
        for(double x=0;x<=1;x+=dx)for(double y=0;y<=1;y+=dy)for(double z=0;z<=1;z+=dz){
            Vec3 sample=new Vec3(Mth.lerp(x,b.minX,b.maxX)+ox,Mth.lerp(y,b.minY,b.maxY),Mth.lerp(z,b.minZ,b.maxZ)+oz);
            if(blocks.clip(new ClipContext(sample,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,entity)).getType()==HitResult.Type.MISS)clear++;
            total++;
        }
        return total==0?0:(float)clear/total;
    }
}
