package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Owns silent local lightning visuals. All calls run on the Minecraft client thread. */
public final class ClientLightning {
	private static final long MIN_INTERVAL_NS = 333_333_334L;
	private static final long MAX_LIFETIME_NS = 1_500_000_000L;
	private static int nextId = -1_000_000;
	private final List<LocalBolt> bolts = new ArrayList<>();
	private long lastStrike;

	public void sync(ClientLevel level, boolean enabled) {
		long now = System.nanoTime();
		for (Iterator<LocalBolt> it = bolts.iterator(); it.hasNext();) {
			LocalBolt bolt = it.next();
			if (!enabled || bolt.level() != level || bolt.isRemoved() || now - bolt.createdAt >= MAX_LIFETIME_NS) {
				remove(bolt);
				it.remove();
			}
		}
		if (!enabled || level == null) lastStrike = 0;
	}

	public void strike(ClientLevel level, Vec3 position) {
		if (Minecraft.getInstance().level != level || level == null) return;
		if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) return;
		sync(level, true);
		long now = System.nanoTime();
		if (lastStrike != 0 && now - lastStrike < MIN_INTERVAL_NS) return;
		int id = allocateId(level);
		if (id == 0) return;
		LocalBolt bolt = new LocalBolt(level, now);
		bolt.setId(id);
		bolt.snapTo(position.x, position.y, position.z, 0, 0);
		level.addEntity(bolt);
		bolts.add(bolt);
		lastStrike = now;
	}

	public void clear() {
		for (LocalBolt bolt : bolts) remove(bolt);
		bolts.clear();
		lastStrike = 0;
	}

	private static int allocateId(ClientLevel level) {
		for (int attempt = 0; attempt < 1024; attempt++) {
			int candidate = nextId;
			nextId = nextId == Integer.MIN_VALUE ? -1_000_000 : nextId - 1;
			if (level.getEntity(candidate) == null) return candidate;
		}
		return 0;
	}

	private static void remove(LocalBolt bolt) {
		ClientLevel level = (ClientLevel) bolt.level();
		// Never remove an unrelated entity if another local feature has reused an id.
		if (level.getEntity(bolt.getId()) == bolt) level.removeEntity(bolt.getId(), Entity.RemovalReason.DISCARDED);
		else if (!bolt.isRemoved()) bolt.discard();
	}

	/** Keeps the vanilla type, shape/seed renderer and flash cadence without vanilla tick side effects. */
	private static final class LocalBolt extends LightningBolt {
		private final long createdAt;
		private int visualLife = 2;
		private int flashes;

		private LocalBolt(ClientLevel level, long createdAt) {
			super(net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT, level);
			this.createdAt = createdAt;
			flashes = random.nextInt(3) + 1;
			setVisualOnly(true);
			setSilent(true);
			noPhysics = true;
		}

		@Override
		public void tick() {
			// LightningBolt.tick plays thunder, flashes the sky and can touch blocks on the server.
			// This entity is client-only and deliberately runs only the visual lifetime/seed changes.
			if (!(level() instanceof ClientLevel) || System.nanoTime() - createdAt >= MAX_LIFETIME_NS) {
				discard();
				return;
			}
			if (--visualLife < 0) {
				if (flashes == 0) discard();
				else if (visualLife < -random.nextInt(10)) {
					flashes--;
					visualLife = 1;
					seed = random.nextLong();
				}
			}
		}

		@Override
		public boolean shouldRenderAtSqrDistance(double distance) {
			double range = Math.max(128.0, 64.0 * getViewScale());
			return distance < range * range;
		}
	}
}
