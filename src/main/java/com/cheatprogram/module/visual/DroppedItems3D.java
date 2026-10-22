package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Map;
import java.util.WeakHashMap;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.render.WorldLines;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Poses the real dropped-item model; entity physics, pickup and server state stay vanilla. */
public final class DroppedItems3D extends Module {
	private final ModeSetting style = add(new ModeSetting("style", "drop_physical",
			"drop_physical", "drop_showcase", "drop_original"));
	private final NumberSetting size = add(new NumberSetting("size", 100, 50, 200, 5, "%"));
	private final NumberSetting spinSpeed = add(new NumberSetting("spinSpeed", 100, 0, 360, 5, "°/s"));
	private final NumberSetting thickness = add(new NumberSetting("thickness", 250, 100, 600, 25, "%"));
	private final BoolSetting stackedCopies = add(new BoolSetting("stackedCopies", true));
	private final BoolSetting rareGlow = add(new BoolSetting("rareGlow", true));
	private final BoolSetting commonGlow = add(new BoolSetting("commonGlow", false)).visibleWhen(rareGlow::on);
	private final NumberSetting glowRange = add(new NumberSetting("glowRange", 32, 4, 96, 4)).visibleWhen(rareGlow::on);
	private final NumberSetting glowStrength = add(new NumberSetting("glowStrength", 70, 10, 150, 5, "%")).visibleWhen(rareGlow::on);
	private final NumberSetting glowLimit = add(new NumberSetting("glowLimit", 32, 4, 128, 4)).visibleWhen(rareGlow::on);
	private final NumberSetting bobStrength = add(new NumberSetting("bobStrength", 60, 0, 150, 5, "%"))
			.visibleWhen(() -> !physical());
	private final NumberSetting settleTime = add(new NumberSetting("settleTime", 200, 0, 1000, 25, "ms"))
			.visibleWhen(this::physical);
	private final Map<ItemEntity, Motion> motions = new WeakHashMap<>();
	private List<ItemEntity> glowingItems = List.of();
	private int glowScanTick;
	private ClientLevel world;

	public DroppedItems3D() { super("droppeditems3d", Category.VISUAL); }

	@Override public String suffix() { return style.display(); }
	@Override public void onEnable() { clear(); }
	@Override public void onDisable() { clear(); }
	/** Called outside worlds too, so the title screen does not retain the previous client world. */
	public void checkLifecycle() { if (world != mc.level) clear(); }

	private boolean physical() { return style.is("drop_physical"); }
	private void clear() { motions.clear(); glowingItems=List.of(); glowScanTick=0; world = null; }

	@Override public void onTick() {
		bindWorld();
		motions.entrySet().removeIf(entry -> entry.getKey().isRemoved() || entry.getKey().level() != mc.level);
		if(!rareGlow.on()){glowingItems=List.of();return;}
		if(glowScanTick++%4==0)scanGlowingItems();
	}

	private void bindWorld() {
		if (world != mc.level) { motions.clear(); glowingItems=List.of();glowScanTick=0;world = mc.level; }
	}
	private void scanGlowingItems(){
		if(!inGame()){glowingItems=List.of();return;}
		int limit=glowLimit.intVal(),scanned=0;
		var nearest=new PriorityQueue<ItemEntity>(limit,Comparator.comparingDouble((ItemEntity item)->item.distanceToSqr(mc.player)).reversed());
		for(var entity:mc.level.entitiesForRendering()){
			if(++scanned>4096)break;
			if(!(entity instanceof ItemEntity item)||item.isRemoved()||item.getItem().isEmpty()||item.distanceToSqr(mc.player)>glowRange.val()*glowRange.val())continue;
			if(item.getItem().getRarity()==Rarity.COMMON&&!commonGlow.on())continue;
			if(nearest.size()<limit)nearest.add(item);
			else if(item.distanceToSqr(mc.player)<nearest.peek().distanceToSqr(mc.player)){nearest.poll();nearest.add(item);}
		}
		glowingItems=List.copyOf(nearest);
	}
	@Override public void onRender3D(Render3D renderer) {
		if(!rareGlow.on()||!inGame())return;
		var lines=new ArrayList<WorldLines.Segment>();int count=0;
		for(ItemEntity item:glowingItems){
			if(item.level()!=mc.level||item.isRemoved()||item.getItem().isEmpty()||item.distanceToSqr(mc.player)>glowRange.val()*glowRange.val())continue;
			Rarity rarity=item.getItem().getRarity();if(rarity==Rarity.COMMON&&!commonGlow.on())continue;
			if(++count>glowLimit.intVal())break;
			int rgb=switch(rarity){case UNCOMMON->0xffe8a0;case RARE->0x90cfff;case EPIC->0xe0a0ff;default->0xffffff;};
			float pulse=(float)(.7+.3*Math.sin((item.tickCount+renderer.partialTick)/10));
			int alpha=Math.min(220,(int)(110*pulse*glowStrength.fraction()));
			Vec3 center=item.getPosition(renderer.partialTick).add(0,.04,0);
			for(int ring=0;ring<3;ring++)for(int step=0;step<24;step++){
				double a=step*Math.PI/12,b=(step+1)*Math.PI/12,r=.19+ring*.055;
				lines.add(new WorldLines.Segment(center.add(Math.cos(a)*r,ring*.012,Math.sin(a)*r),center.add(Math.cos(b)*r,ring*.012,Math.sin(b)*r),((alpha/(ring+1))<<24)|rgb));
			}
		}
		WorldLines.draw(renderer,lines,4,false);
	}

	/** Immutable extraction output: rear-view and main-camera renders cannot advance animation twice. */
	public record Frame(float yaw, float pitch, float roll, float grounded) {}

	public Frame extract(ItemEntity entity, ItemEntityRenderState state, float partialTick) {
		bindWorld();
		if (world == null || entity.level() != world) return null;
		Motion motion = motions.computeIfAbsent(entity, item -> new Motion(item, state.item.isGui3d()));
		motion.update(entity, state.item.isGui3d(), spinSpeed.floatVal() / 20F, settleTime.floatVal() / 50F);
		float t = Mth.clamp(partialTick, 0, 1);
		return new Frame(Mth.lerp(t, motion.previousYaw, motion.yaw),
				Mth.lerp(t, motion.previousPitch, motion.pitch),
				Mth.lerp(t, motion.previousRoll, motion.roll),
				Mth.lerp(t, motion.previousGrounded, motion.grounded));
	}

	/** Runs inside the vanilla entity pose and reuses vanilla multi-copy rendering and item lighting. */
	public void render(PoseStack pose, ItemEntityRenderState state, Frame frame, Runnable drawOriginalModels) {
		pose.pushPose();
		int previousCount = state.count;
		try {
			// The caller has applied vanilla bobbing and spin. Undo those exact transforms first.
			pose.mulPose(Axis.YP.rotation(-ItemEntity.getSpin(state.ageInTicks, state.bobOffset)));
			float vanillaBob = Mth.sin(state.ageInTicks / 10F + state.bobOffset) * 0.1F + 0.1F;
			pose.translate(0, -vanillaBob - 0.25F * state.item.transform().scale.y(), 0);
			float scale = size.floatVal() / 100F;
			boolean blockModel = state.item.isGui3d();
			float depth = blockModel ? 1F : thickness.floatVal() / 100F;
			int copies = stackedCopies.on() ? state.count : Math.min(state.count, 1);
			if (physical()) {
				// Flat generated item meshes have real side faces. Lay that extruded mesh on the floor;
				// block and special 3D models retain their upright shape and full resource-pack geometry.
				float groundHeight = blockModel ? 0.5F * Math.abs(state.item.transform().scale.y()) * scale + 0.015F
						: 0.03125F * Math.abs(state.item.transform().scale.z()) * depth * scale + 0.015F;
				if (copies > 1) groundHeight += blockModel ? 0.15F * scale
						: 0.09375F * (copies - 1) * 0.5F * Math.abs(state.item.transform().scale.z()) * depth * scale;
				float airborneHeight = 0.25F * Math.abs(state.item.transform().scale.y()) * scale + 0.12F;
				pose.translate(0, Mth.lerp(frame.grounded(), airborneHeight, groundHeight), 0);
				pose.mulPose(Axis.YP.rotationDegrees(frame.yaw()));
				pose.mulPose(Axis.ZP.rotationDegrees(frame.roll()));
				pose.mulPose(Axis.XP.rotationDegrees(frame.pitch()));
			} else {
				float bob = Mth.sin(state.ageInTicks / 10F + state.bobOffset) * 0.1F * bobStrength.floatVal() / 100F;
				pose.translate(0, 0.25F * state.item.transform().scale.y() * scale + 0.1F + bob, 0);
				float angle = state.ageInTicks * spinSpeed.floatVal() / 20F
						+ state.bobOffset * Mth.RAD_TO_DEG;
				pose.mulPose(Axis.YP.rotationDegrees(angle));
				if (style.is("drop_showcase")) {
					pose.mulPose(Axis.XP.rotationDegrees(blockModel ? 12F : 18F));
					pose.mulPose(Axis.ZP.rotationDegrees(8F * Mth.sin(state.ageInTicks / 18F + state.bobOffset)));
				}
			}
			pose.scale(scale, scale, scale * depth);
			state.count = copies;
			drawOriginalModels.run();
		} finally {
			state.count = previousCount;
			pose.popPose();
		}
	}

	private static final class Motion {
		private int tick;
		private boolean blockModel;
		private float yaw, pitch, roll, grounded;
		private float previousYaw, previousPitch, previousRoll, previousGrounded;

		private Motion(ItemEntity item, boolean blockModel) {
			tick = item.tickCount;
			this.blockModel = blockModel;
			grounded = item.onGround() ? 1F : 0F;
			yaw = item.bobOffs * Mth.RAD_TO_DEG + (item.getId() * 137.50776F) % 360F;
			pitch = grounded > 0 && !blockModel ? 90F : 0F;
			copyPrevious();
		}

		private void update(ItemEntity item, boolean currentBlockModel, float degreesPerTick, float settleTicks) {
			if (tick == item.tickCount && blockModel == currentBlockModel) return;
			int elapsed = Mth.clamp(item.tickCount - tick, 1, 20);
			tick = item.tickCount;
			blockModel = currentBlockModel;
			copyPrevious();
			float easing = settleTicks <= 0 ? 1F : (float) (1 - Math.exp(-elapsed / Math.max(0.5F, settleTicks * 0.4F)));
			boolean landed = item.onGround();
			grounded += ((landed ? 1F : 0F) - grounded) * easing;
			if (landed) {
				pitch += Mth.wrapDegrees((blockModel ? 0F : 90F) - pitch) * easing;
				roll += Mth.wrapDegrees(-roll) * easing;
			} else {
				yaw += degreesPerTick * elapsed;
				pitch += degreesPerTick * 0.7F * elapsed;
				roll += degreesPerTick * 0.35F * elapsed;
			}
			// Keep angles bounded together, preserving the short interpolated step over a full turn.
			if (Math.abs(yaw) > 7200F) { float wrap = (float) Math.floor(yaw / 360F) * 360F; yaw -= wrap; previousYaw -= wrap; }
			if (Math.abs(pitch) > 7200F) { float wrap = (float) Math.floor(pitch / 360F) * 360F; pitch -= wrap; previousPitch -= wrap; }
			if (Math.abs(roll) > 7200F) { float wrap = (float) Math.floor(roll / 360F) * 360F; roll -= wrap; previousRoll -= wrap; }
		}

		private void copyPrevious() {
			previousYaw = yaw; previousPitch = pitch; previousRoll = roll; previousGrounded = grounded;
		}
	}
}
