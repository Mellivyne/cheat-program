package com.cheatprogram.hud;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;

/** Everything a module needs to draw on the in-game HUD, in HUD units. */
public final class HudContext {
	public final Canvas c;
	public final float width;
	public final float height;
	public final float partialTick;
	public final boolean editing;
	private boolean captured;

	public HudContext(Canvas c, float width, float height, float partialTick, boolean editing) {
		this.c = c;
		this.width = width;
		this.height = height;
		this.partialTick = partialTick;
		this.editing = editing;
	}

	public boolean glass() {
		Hud hud = ModuleManager.get(Hud.class);
		return hud == null || hud.glass();
	}

	/** A HUD surface: liquid glass when enabled, otherwise a translucent dark panel. */
	public void panel(float x, float y, float w, float h, float r) {
		c.shadow(x, y + 2f, w, h, r, 9, Anim.black(0.14f));
		if (glass()) {
			if (!captured) {
				Backdrop.capture(Theme.frost());
				R2D.applyState();
				captured = true;
			}
			c.lens(x, y, w, h, r, Theme.lift(0.07f), Theme.chipGlass(c.scale));
		} else {
			c.rrect(x, y, w, h, r, Anim.black(0.50f), Anim.black(0.58f));
		}
	}

	/** A welcome surface shares this frame's backdrop with the original island and other HUD glass. */
	public void liquidPanel(float x,float y,float w,float h,float r,R2D.GlassStyle style,float lift) {
		c.shadow(x,y+3,w,h,r,13,Anim.black(.17f));
		if(!captured){Backdrop.capture(Theme.frost());R2D.applyState();captured=true;}
		c.lens(x,y,w,h,r,lift,style);
	}
}
