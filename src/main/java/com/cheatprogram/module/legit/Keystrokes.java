package com.cheatprogram.module.legit;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudLayout;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.Cps;
import net.minecraft.client.KeyMapping;

import java.util.HashMap;
import java.util.Map;

public class Keystrokes extends Module {
	private final BoolSetting showMouse = add(new BoolSetting("showMouse", true));
	private final BoolSetting showSpace = add(new BoolSetting("showSpace", true));
	private final Map<String, Anim> anims = new HashMap<>();
	private long lastFrame = System.nanoTime();

	public Keystrokes() {
		super("keystrokes", Category.LEGIT);
	}

	@Override
	public void onRender2D(HudContext ctx) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		float k = 24;
		float gap = 3;
		float w = k * 3 + gap * 2;
		float h = k * 2 + gap;
		if (showMouse.on()) h += gap + 20;
		if (showSpace.on()) h += gap + 12;
		float x = HudLayout.x("keystrokes", w, ctx.width);
		float y = HudLayout.y("keystrokes", h, ctx.height);
		var o = mc.options;
		key(ctx, "W", o.keyUp, x + k + gap, y, k, k, null, dt);
		key(ctx, "A", o.keyLeft, x, y + k + gap, k, k, null, dt);
		key(ctx, "S", o.keyDown, x + k + gap, y + k + gap, k, k, null, dt);
		key(ctx, "D", o.keyRight, x + (k + gap) * 2, y + k + gap, k, k, null, dt);
		float cy = y + (k + gap) * 2;
		if (showMouse.on()) {
			float mw = (w - gap) / 2f;
			key(ctx, "LMB", o.keyAttack, x, cy, mw, 20, Cps.left() + " CPS", dt);
			key(ctx, "RMB", o.keyUse, x + mw + gap, cy, mw, 20, Cps.right() + " CPS", dt);
			cy += 20 + gap;
		}
		if (showSpace.on()) {
			float t = press("SPACE", o.keyJump.isDown(), dt);
			ctx.panel(x, cy, w, 12, 6);
			if (t > 0.01f) ctx.c.rrect(x, cy, w, 12, 6, Anim.white(0.30f * t));
			ctx.c.circle(x + w / 2f, cy + 6, 1.6f, Anim.lerpColor(Theme.TEXT_3, Theme.TEXT, t));
		}
		HudLayout.report("keystrokes", x, y, w, h);
	}

	private float press(String id, boolean down, float dt) {
		return anims.computeIfAbsent(id, i -> new Anim(0)).to(down ? 1f : 0f, dt, 20f);
	}

	private void key(HudContext ctx, String label, KeyMapping mapping, float x, float y, float w, float h, String sub, float dt) {
		Canvas c = ctx.c;
		float t = press(label, mapping.isDown(), dt);
		ctx.panel(x, y, w, h, 9);
		if (t > 0.01f) {
			c.shadow(x, y, w, h, 9, 8, Anim.white(0.18f * t));
			c.rrect(x, y, w, h, 9, Anim.white(0.30f * t));
		}
		int color = Theme.TEXT;
		if (sub == null) {
			c.textMid(Fonts.Face.MEDIUM, label, x + w / 2f, y + h / 2f, 10, color);
		} else {
			c.textMid(Fonts.Face.MEDIUM, label, x + w / 2f, y + h / 2f - 3.5f, 8.5f, color);
			c.textMid(Fonts.Face.REGULAR, sub, x + w / 2f, y + h / 2f + 5f, 6.5f, Theme.TEXT_3);
		}
	}
}
