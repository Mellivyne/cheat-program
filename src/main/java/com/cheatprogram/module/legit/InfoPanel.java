package com.cheatprogram.module.legit;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudLayout;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.util.Cps;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class InfoPanel extends Module {
	private final BoolSetting fps = add(new BoolSetting("fps", true));
	private final BoolSetting cps = add(new BoolSetting("cps", true));
	private final BoolSetting coords = add(new BoolSetting("coords", true));
	private final BoolSetting direction = add(new BoolSetting("direction", true));
	private final BoolSetting bps = add(new BoolSetting("bps", true));
	private double lastX, lastZ;
	private double speed;

	public InfoPanel() {
		super("info", Category.LEGIT);
	}

	@Override
	public void onTick() {
		double dx = mc.player.getX() - lastX;
		double dz = mc.player.getZ() - lastZ;
		speed = speed * 0.6 + Math.sqrt(dx * dx + dz * dz) * 20 * 0.4;
		lastX = mc.player.getX();
		lastZ = mc.player.getZ();
	}

	@Override
	public void onRender2D(HudContext ctx) {
		Canvas c = ctx.c;
		List<String[]> lines = new ArrayList<>();
		if (fps.on()) lines.add(new String[]{"FPS", String.valueOf(mc.getFps())});
		if (cps.on()) lines.add(new String[]{"CPS", Cps.left() + "  ·  " + Cps.right()});
		if (coords.on()) lines.add(new String[]{"XYZ", String.format(Locale.ROOT, "%.0f  %.0f  %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ())});
		if (direction.on()) {
			Direction d = mc.player.getDirection();
			String axis = switch (d) {
				case NORTH -> "-Z";
				case SOUTH -> "+Z";
				case WEST -> "-X";
				default -> "+X";
			};
			String name = Lang.t("hud.direction." + switch (d) {
				case NORTH -> "n";
				case SOUTH -> "s";
				case WEST -> "w";
				default -> "e";
			});
			lines.add(new String[]{Lang.t("setting.direction"), name + "  " + axis});
		}
		if (bps.on()) lines.add(new String[]{Lang.t("setting.bps"), String.format(Locale.ROOT, "%.2f m/s", speed)});
		if (lines.isEmpty()) return;
		float labelW = 0;
		float valueW = 0;
		for (String[] l : lines) {
			labelW = Math.max(labelW, c.textWidth(Fonts.Face.REGULAR, l[0], 9));
			valueW = Math.max(valueW, c.textWidth(Fonts.Face.MEDIUM, l[1], 9));
		}
		float w = 12 + labelW + 12 + valueW + 12;
		float lineH = 14;
		float h = lines.size() * lineH + 10;
		float x = HudLayout.x("info", w, ctx.width);
		float y = HudLayout.y("info", h, ctx.height);
		ctx.panel(x, y, w, h, 14);
		float ly = y + 5 + lineH / 2f;
		for (String[] l : lines) {
			c.textC(Fonts.Face.REGULAR, l[0], x + 12, ly, 9, Theme.TEXT_3);
			c.textC(Fonts.Face.MEDIUM, l[1], x + 12 + labelW + 12, ly, 9, Theme.TEXT);
			ly += lineH;
		}
		HudLayout.report("info", x, y, w, h);
	}
}
