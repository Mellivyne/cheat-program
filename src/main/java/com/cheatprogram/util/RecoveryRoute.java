package com.cheatprogram.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded walking graph, expanded across ticks; unloaded space never counts as walkable. */
public final class RecoveryRoute {
    private record Node(BlockPos position,double score) { }
    private final ClientLevel level;
    private final BlockPos start,goal;
    private final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::score));
    private final Map<BlockPos,BlockPos> parents=new HashMap<>();
    private final Map<BlockPos,Double> costs=new HashMap<>();
    private final Set<BlockPos> closed=new HashSet<>();
    private List<Vec3> path=List.of();
    private String state="ui.recovery.searching";
    public RecoveryRoute(ClientLevel level,BlockPos start,BlockPos goal){
        this.level=level;this.start=start.immutable();this.goal=goal.immutable();
        if(start.distSqr(goal)>64*64){state="ui.recovery.far";return;}
        if(!walkable(this.start)){state="ui.recovery.noPath";return;}
        costs.put(this.start,0d);open.add(new Node(this.start,this.start.distSqr(goal)));
    }
    public String state(){return state;}
    public List<Vec3> path(){return path;}
    public BlockPos start(){return start;}
    public void advance(){
        if(!state.equals("ui.recovery.searching"))return;
        long deadline=System.nanoTime()+800_000;
        for(int budget=0;budget<96&&System.nanoTime()<deadline;budget++){
            if(open.isEmpty()||closed.size()>=6000){state="ui.recovery.noPath";return;}
            BlockPos node=open.remove().position;if(!closed.add(node))continue;
            if(node.distSqr(goal)<=2){
                List<Vec3> reverse=new ArrayList<>();BlockPos cursor=node;
                while(cursor!=null){reverse.add(Vec3.atBottomCenterOf(cursor).add(0,.08,0));cursor=parents.get(cursor);}
                Collections.reverse(reverse);path=List.copyOf(reverse);state="ui.recovery.route";return;
            }
            for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                for(int dy:new int[]{0,1,-1}){
                    BlockPos next=node.offset(direction[0],dy,direction[1]);
                    if(next.distSqr(start)>64*64||closed.contains(next)||!walkable(next))continue;
                    // A step up needs head room above the starting cell, a step down above its destination.
                    BlockPos upper=dy>0?node.above():next.above();
                    if(dy!=0&&!free(upper)||!transitionClear(node,next,dy))continue;
                    double cost=costs.get(node)+1+Math.abs(dy)*.35;
                    if(cost<costs.getOrDefault(next,Double.POSITIVE_INFINITY)){
                        costs.put(next,cost);parents.put(next,node);
                        double dx=next.getX()-goal.getX(),dz=next.getZ()-goal.getZ();
                        open.add(new Node(next,cost+Math.sqrt(dx*dx+dz*dz)+Math.abs(next.getY()-goal.getY())*.5));
                    }
                    break;
                }
            }
        }
    }
    private boolean free(BlockPos position){
        if(!level.hasChunkAt(position)||!level.hasChunkAt(position.above()))return false;
        if(!level.getFluidState(position).isEmpty()||!level.getFluidState(position.above()).isEmpty())return false;
        return !hazard(position)&&!hazard(position.above())
                &&level.noCollision(new AABB(position.getX()+.2,position.getY()+.01,position.getZ()+.2,position.getX()+.8,position.getY()+1.8,position.getZ()+.8));
    }
    private boolean walkable(BlockPos position){
        if(!free(position)||!level.hasChunkAt(position.below())||hazard(position.below()))return false;
        var support=level.getBlockState(position.below()).getCollisionShape(level,position.below());
        // This integer-height graph must have support at its actual foot height; a lower slab
        // or thin trapdoor cannot silently become a floating full-height walking cell.
        return !support.isEmpty()&&support.bounds().maxY>=.999;
    }
    private boolean hazard(BlockPos position){
        var state=level.getBlockState(position);
        return state.is(net.minecraft.world.level.block.Blocks.FIRE)||state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                ||state.is(net.minecraft.world.level.block.Blocks.CACTUS)||state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                ||state.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)||state.is(net.minecraft.world.level.block.Blocks.WITHER_ROSE)
                ||state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)||state.is(net.minecraft.world.level.block.Blocks.COBWEB)
                ||state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)&&state.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)
                ||state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)&&state.getValue(net.minecraft.world.level.block.CampfireBlock.LIT);
    }
    private boolean transitionClear(BlockPos from,BlockPos to,int dy){
        AABB body=new AABB(from.getX()+.2,from.getY()+.01,from.getZ()+.2,from.getX()+.8,from.getY()+1.8,from.getZ()+.8);
        int dx=to.getX()-from.getX(),dz=to.getZ()-from.getZ();
        if(dy>0)return level.noCollision(body.expandTowards(0,dy,0))
                &&level.noCollision(body.move(0,dy,0).expandTowards(dx,0,dz));
        if(dy<0)return level.noCollision(body.expandTowards(dx,0,dz))
                &&level.noCollision(body.move(dx,0,dz).expandTowards(0,dy,0));
        return level.noCollision(body.expandTowards(dx,0,dz));
    }
    public boolean stillClear(){
        // Revalidate only the nearby portion before rendering after a block update.
        for(int i=0;i<Math.min(path.size(),12);i++)if(!walkable(BlockPos.containing(path.get(i))))return false;
        return true;
    }
}
