package com.cheatprogram.module.utility;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.BlinkTransition;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class Blink extends Module {
	private final NumberSetting maxPackets = add(new NumberSetting("maxPackets", 300, 50, 1000, 10));
	private final BoolSetting releaseAnimation = add(new BoolSetting("releaseAnimation", true));
	private final NumberSetting transitionDuration = add(new NumberSetting("transitionDuration", 250, 100, 800, 25, "ms")).visibleWhen(releaseAnimation::on);
	private final BoolSetting playerAfterimage = add(new BoolSetting("playerAfterimage", false)).visibleWhen(releaseAnimation::on);
	private final NumberSetting afterimageOpacity = add(new NumberSetting("afterimageOpacity", 45, 10, 100, 5, "%"))
			.visibleWhen(() -> releaseAnimation.on() && playerAfterimage.on());
	private final BoolSetting transitionThroughWalls = add(new BoolSetting("transitionThroughWalls", false)).visibleWhen(releaseAnimation::on);
	private final BoolSetting animateAutoFlush = add(new BoolSetting("animateAutoFlush", false)).visibleWhen(releaseAnimation::on);
	private final List<Packet<?>> queue = new ArrayList<>();
	private boolean flushing;
	private Vec3 start;
	private ClientLevel anchorWorld;
	private LocalPlayer anchorPlayer;

	public Blink() {
		super("blink", Category.UTILITY);
	}

	@Override
	public String suffix() {
		return String.valueOf(queue.size());
	}

	@Override
	public void onEnable() {
		BlinkTransition.clear();
		queue.clear();
		setAnchor();
	}

	@Override
	public void onDisable() {
		Vec3 previous = start, destination = mc.player == null ? null : mc.player.position();
		boolean animate = releaseAnimation.on() && anchorWorld == mc.level && anchorPlayer == mc.player;
		try {
			flush();
			if (animate) transition(previous, destination);
		} finally { start = null; anchorWorld = null; anchorPlayer = null; }
	}
	private void setAnchor() {
		start = mc.player != null ? mc.player.position() : null; anchorWorld = mc.level; anchorPlayer = mc.player;
	}
	private void transition(Vec3 previous, Vec3 destination) {
		BlinkTransition.start(previous, destination, transitionDuration.intVal(), playerAfterimage.on(), afterimageOpacity.floatVal() / 100,
				transitionThroughWalls.on());
	}
	@Override public void onTick() {
		if (anchorWorld != mc.level || anchorPlayer != mc.player) {
			// Buffered movement belongs to one player in one world. Never release it into a new connection.
			queue.clear(); BlinkTransition.clear(); setAnchor();
		}
	}

	@Override
	public boolean onPacketSend(Packet<?> packet) {
		if (flushing || !(packet instanceof ServerboundMovePlayerPacket)) return false;
		if (mc.player == null || mc.level == null || !mc.player.isAlive()) { queue.clear(); return false; }
		if (anchorWorld != mc.level || anchorPlayer != mc.player) { queue.clear(); BlinkTransition.clear(); setAnchor(); }
		queue.add(packet);
		if (queue.size() >= maxPackets.intVal()) {
			Vec3 previous = start, destination = mc.player == null ? null : mc.player.position();
			boolean animate = releaseAnimation.on() && animateAutoFlush.on() && anchorWorld == mc.level && anchorPlayer == mc.player;
			flush();
			if (animate) transition(previous, destination);
			setAnchor();
		}
		return true;
	}

	private void flush() {
		if (mc.getConnection() == null || anchorWorld != mc.level || anchorPlayer != mc.player || mc.player == null || !mc.player.isAlive()) {
			queue.clear();
			return;
		}
		flushing = true;
		try {
			for (Packet<?> p : queue) mc.getConnection().getConnection().send(p);
		} finally {
			flushing = false;
			queue.clear();
		}
	}

	@Override
	public void onRender3D(Render3D r) {
		if (start == null || mc.player == null || anchorWorld != mc.level || anchorPlayer != mc.player) return;
		r.begin();
		float w = mc.player.getBbWidth() / 2f;
		AABB box = new AABB(start.x - w, start.y, start.z - w, start.x + w, start.y + mc.player.getBbHeight(), start.z + w);
		r.box(box, Anim.white(0.06f), Anim.white(0.6f), 1.5f);
		r.end();
	}
}
