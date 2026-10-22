package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.gui.ContainerSearchScreen;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.ContainerIndex;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

public final class ContainerSearch extends Module {
	public final TextSetting query = add(new TextSetting("query", "", 256));
	private final BoolSetting marker = add(new BoolSetting("marker", true));
	private BlockPos selected;
	private String selectedContext = "";
	public ContainerSearch() { super("containersearch", Category.WORLD); }
	public void open(Screen parent) { mc.gui.setScreen(new ContainerSearchScreen(parent, this)); }
	public void locate(ContainerIndex.Entry entry) { selected = entry.position(); selectedContext = ContainerIndex.context(); if (!isEnabled()) setEnabled(true); mc.gui.setScreen(null); }
	public void clearMarker() { selected = null; }
	@Override public void onRender3D(Render3D renderer) {
		if (!marker.on() || selected == null || !selectedContext.equals(ContainerIndex.context()) || mc.level == null
				|| !mc.level.hasChunkAt(selected)) return;
		renderer.begin(); try { renderer.box(new AABB(selected), 0x19FFFFFF, 0xEAFFFFFF, 2f); renderer.line(renderer.crosshair(), net.minecraft.world.phys.Vec3.atCenterOf(selected), 0xA0FFFFFF, 1.5f); }
		finally { renderer.end(); }
	}
}
