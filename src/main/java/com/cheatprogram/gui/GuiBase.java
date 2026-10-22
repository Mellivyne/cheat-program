package com.cheatprogram.gui;

import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.visual.ControlPanel;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.ControlPanelBackground;
import com.cheatprogram.render.ControlPanelTextures;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immediate-mode widget toolkit shared by the click GUI and the HUD editor.
 * Layout happens while drawing; interactive areas are recorded as {@link Hit}s and the
 * input handlers resolve clicks against the regions recorded in the previous frame.
 */
public abstract class GuiBase extends Screen {
	protected static final Fonts.Face REG = Fonts.Face.REGULAR;
	protected static final Fonts.Face MED = Fonts.Face.MEDIUM;
	protected static final Fonts.Face BOLD = Fonts.Face.BOLD;

	protected final Minecraft mc = Minecraft.getInstance();
	protected final Canvas c = new Canvas();
	protected final List<Hit> hits = new ArrayList<>();
	protected final List<Hit> pendingHits = new ArrayList<>();
	private final Map<Object, Anim> anims = new HashMap<>();

	/** Mouse position in canvas units, updated every frame and on input. */
	protected float mx = -1000;
	protected float my = -1000;
	protected float dt = 0.016f;
	private long lastFrame = System.nanoTime();
	private long languageRevision=Long.MIN_VALUE,fontRevision=Long.MIN_VALUE;
	/** While set, widgets ignore hover (an overlay covers them). */
	protected boolean hoverBlocked;
	protected Drag drag;
	protected TextField focused;

	/** Opt-in for full control pages; world-overlay editors keep their transparent canvas. */
	protected boolean preparePanelBackground(net.minecraft.client.gui.GuiGraphics graphics,float partialTick) {
		ControlPanel panel=ModuleManager.get(ControlPanel.class);
		boolean active=panel!=null&&panel.isEnabled()&&!panel.imagePath().isBlank();
		ControlPanelTextures.setGuiVisible(active);
		if(mc.level==null)renderPanorama(graphics,partialTick);
		if(active) {
			ControlPanelTextures.warm(panel.imagePath());
			if(!ControlPanelTextures.settled(panel.imagePath())) {
				graphics.drawCenteredString(mc.font,Lang.t("ui.panel.loading"),width/2,height/2,0xFFFFFFFF);
				return false;
			}
		}
		return true;
	}

	/** Opt-in for older fixed-layout pages without changing their disabled-module appearance. */
	protected boolean drawCustomPanelSurface(net.minecraft.client.gui.GuiGraphics graphics,float panelWidth,float panelHeight,float radius) {
		if(!customPanelBackground())return false;
		drawPanelSurface(graphics,panelWidth,panelHeight,radius);
		return true;
	}
	protected boolean customPanelBackground() {
		ControlPanel panel=ModuleManager.get(ControlPanel.class);
		return panel!=null&&panel.isEnabled()&&!panel.imagePath().isBlank();
	}
	/** Main-panel type size for fixed-size legacy pages, bounded only by their actual content. */
	protected float fixedPanelScale(float panelWidth,float panelHeight) {
		float preferred=Math.max(.55f,Math.min(R2D.width()/1060f,R2D.height()/760f)
				*(float)ClientSettings.INSTANCE.uiScale.fraction());
		float fit=Math.min(R2D.width()/(panelWidth+40),R2D.height()/(panelHeight+40));
		return Math.max(.2f,Math.min(preferred,fit));
	}

	/** Match main-panel text scale; overflow belongs in scrollable content, not a smaller font. */
	protected float layoutPanel(float panelWidth,float preferredHeight) {
		float automatic=Math.min(R2D.width()/1060f,R2D.height()/760f);
		float scale=Math.max(.55f,automatic*(float)ClientSettings.INSTANCE.uiScale.fraction());
		scale=Math.min(scale,Math.max(.2f,(R2D.width()-32)/panelWidth));
		float panelHeight=Math.min(preferredHeight,Math.max(120,(R2D.height()-32)/scale));
		c.set(scale,Math.round((R2D.width()-panelWidth*scale)/2),Math.round((R2D.height()-panelHeight*scale)/2));
		updateMouse();return panelHeight;
	}

	/** Same media and refraction as the main control panel, sampled before any content clip. */
	protected void drawPanelSurface(net.minecraft.client.gui.GuiGraphics graphics,float panelWidth,float panelHeight,float radius) {
		ControlPanel panel=ModuleManager.get(ControlPanel.class);
		boolean custom=panel!=null&&panel.isEnabled()&&!panel.imagePath().isBlank();
		if(custom) {
			int background=Backdrop.capture(Theme.backgroundBlur());R2D.applyState();
			Backdrop.drawFullscreen(com.cheatprogram.render.Shaders.BACKDROP,background,
					.05f+.14f*(1-Theme.transparency()),(float)ClientSettings.INSTANCE.desaturate.fraction(),.35f,1);
			Backdrop.capture(Theme.frost());R2D.applyState();
			c.shadow(0,10,panelWidth,panelHeight,radius,44,Anim.black(.30f));
			float tint=Theme.windowTint();
			c.glass(0,0,panelWidth,panelHeight,radius,Anim.black(tint*.92f),Anim.black(Math.min(1,tint*1.08f)),Theme.windowGlass(c.scale));
			if(ControlPanelBackground.draw(graphics,c,panel,panelWidth,panelHeight,radius))Backdrop.capture(0);
			R2D.applyState();
		} else {
			c.rect(-c.originX/c.scale,-c.originY/c.scale,R2D.width()/c.scale,R2D.height()/c.scale,Anim.black(.18f));
			Backdrop.capture(Theme.frost());R2D.applyState();
			c.shadow(0,6,panelWidth,panelHeight,radius,30,Anim.black(.25f));
			c.lens(0,0,panelWidth,panelHeight,radius,Theme.lift(.08f),Theme.sheetGlass(c.scale));
		}
	}

	protected void panelScrollBar(Object key,Scroller scroll,float x,float y,float height) {
		if(scroll.max<=0||height<20)return;
		float thumb=Math.max(24,height*height/(height+scroll.max));
		float position=y+(height-thumb)*Math.max(0,Math.min(1,scroll.get()/scroll.max));
		c.rrect(x,y,2,height,1,Anim.white(.08f));
		c.rrect(x-1,position,4,thumb,2,Anim.white(over(x-5,y,12,height)?.6f:.3f));
		hit(x-6,y,14,height).scroll(scroll::scroll).left(()->{
			drag=(mouseX,mouseY)->{scroll.target=Math.max(0,Math.min(scroll.max,(mouseY-y-thumb/2)/Math.max(1,height-thumb)*scroll.max));scroll.value.snap(scroll.target);};
			drag.drag(mx,my);
		});
	}

	public interface Click {
		void click(int button);
	}

	public interface Scroll {
		void scroll(double amount);
	}

	public interface Drag {
		void drag(float mouseX, float mouseY);
	}

	public static final class Hit {
		final float x, y, w, h;
		Click click;
		Scroll scroll;

		Hit(float x, float y, float w, float h) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}

		public Hit click(Click c) {
			this.click = c;
			return this;
		}

		public Hit left(Runnable r) {
			this.click = b -> {
				if (b == 0) r.run();
			};
			return this;
		}

		public Hit scroll(Scroll s) {
			this.scroll = s;
			return this;
		}

		boolean contains(float px, float py) {
			return px >= x && py >= y && px < x + w && py < y + h;
		}
	}

	/** Smooth scroll state for a list. */
	public static final class Scroller {
		public float target;
		public final Anim value = new Anim(0);
		public float max;

		public void update(float dt) {
			target = Math.max(0, Math.min(max, target));
			value.to(target, dt, 16f);
		}

		public void scroll(double amount) {
			target -= (float) amount * 36f;
			target = Math.max(0, Math.min(max, target));
		}

		public float get() {
			return value.value;
		}
	}

	protected GuiBase(String title) {
		super(Component.literal(title));
	}

	// ---------------------------------------------------------------- frame helpers

	protected void beginFrame() {
		long nextLanguage=Lang.revision(),nextFont=Fonts.revision();
		if(languageRevision!=nextLanguage||fontRevision!=nextFont){
			languageRevision=nextLanguage;fontRevision=nextFont;drag=null;hits.clear();onTypographyChanged();
		}
		long now = System.nanoTime();
		dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		pendingHits.clear();
		hitClipDepth = 0;
		hoverBlocked = false;
	}
	/** Screens with retained line wrapping discard it when global typography changes. */
	protected void onTypographyChanged(){ }

	protected void endFrame() {
		hits.clear();
		hits.addAll(pendingHits);
	}

	/** Mouse position in framebuffer pixels, without the rounding of GUI coordinates. */
	protected float mousePxX() {
		double[] virtual=com.cheatprogram.render.WorldControlPanel.virtualPointer(this);if(virtual!=null)return (float)virtual[0];
		return (float) (mc.mouseHandler.xpos() * mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getScreenWidth()));
	}

	protected float mousePxY() {
		double[] virtual=com.cheatprogram.render.WorldControlPanel.virtualPointer(this);if(virtual!=null)return (float)virtual[1];
		return (float) (mc.mouseHandler.ypos() * mc.getWindow().getHeight() / Math.max(1, mc.getWindow().getScreenHeight()));
	}

	protected void updateMouse() {
		mx = c.toU(mousePxX());
		my = c.toV(mousePxY());
	}

	/** Sets the mouse from the (scaled) coordinates that input events carry. */
	protected void mouseFromEvent(double guiX, double guiY) {
		var window = mc.getWindow();
		float px = (float) (guiX * window.getWidth() / Math.max(1, window.getGuiScaledWidth()));
		float py = (float) (guiY * window.getHeight() / Math.max(1, window.getGuiScaledHeight()));
		mx = c.toU(px);
		my = c.toV(py);
	}

	private final float[] hitClips = new float[64];
	private int hitClipDepth;

	/** Limits hit regions and hover to a rectangle, e.g. the visible part of a scrolling list. */
	protected void pushHitClip(float x, float y, float w, float h) {
		float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
		if (hitClipDepth > 0) {
			int p = hitClipDepth - 4;
			x0 = Math.max(x0, hitClips[p]);
			y0 = Math.max(y0, hitClips[p + 1]);
			x1 = Math.min(x1, hitClips[p + 2]);
			y1 = Math.min(y1, hitClips[p + 3]);
		}
		hitClips[hitClipDepth++] = x0;
		hitClips[hitClipDepth++] = y0;
		hitClips[hitClipDepth++] = x1;
		hitClips[hitClipDepth++] = y1;
	}

	protected void popHitClip() {
		hitClipDepth -= 4;
	}

	protected Hit hit(float x, float y, float w, float h) {
		if (hitClipDepth > 0) {
			int p = hitClipDepth - 4;
			float x0 = Math.max(x, hitClips[p]);
			float y0 = Math.max(y, hitClips[p + 1]);
			float x1 = Math.min(x + w, hitClips[p + 2]);
			float y1 = Math.min(y + h, hitClips[p + 3]);
			Hit hit = new Hit(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
			if (x1 > x0 && y1 > y0) pendingHits.add(hit);
			return hit;
		}
		Hit hit = new Hit(x, y, w, h);
		pendingHits.add(hit);
		return hit;
	}

	protected boolean over(float x, float y, float w, float h) {
		if (hoverBlocked || drag != null) return false;
		if (hitClipDepth > 0) {
			int p = hitClipDepth - 4;
			if (mx < hitClips[p] || my < hitClips[p + 1] || mx >= hitClips[p + 2] || my >= hitClips[p + 3]) return false;
		}
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	protected boolean inside(float x, float y, float w, float h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	protected float anim(Object key, float target, float speed) {
		Anim a = anims.computeIfAbsent(key, k -> new Anim(target));
		return a.to(target, dt, speed);
	}

	protected float hoverAnim(Object key, boolean hovered) {
		return anim(key, hovered ? 1f : 0f, 18f);
	}

	// ---------------------------------------------------------------- input dispatch

	protected boolean dispatchClick(double mouseX, double mouseY, int button) {
		mouseFromEvent(mouseX, mouseY);
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (h.click != null && h.contains(mx, my)) {
				h.click.click(button);
				return true;
			}
		}
		return false;
	}

	protected boolean dispatchScroll(double mouseX, double mouseY, double amount) {
		mouseFromEvent(mouseX, mouseY);
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (h.scroll != null && h.contains(mx, my)) {
				h.scroll.scroll(amount);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
		if (drag != null) {
			mouseFromEvent(mouseX, mouseY);
			drag.drag(mx, my);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		drag = null;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	// ---------------------------------------------------------------- widgets

	/** iOS style switch, 28x15 units. */
	protected void toggle(Object key, float x, float y, boolean on, float alpha) {
		float t = anim(key, on ? 1f : 0f, 16f);
		float w = 28, h = 15;
		int trackTop = Anim.lerpColor(Anim.white(0.17f), Anim.white(0.96f), t);
		int trackBottom = Anim.lerpColor(Anim.white(0.11f), Anim.white(0.86f), t);
		R2D.pushAlpha(alpha);
		c.rrect(x, y, w, h, h / 2f, trackTop, trackBottom);
		float knob = h - 4;
		float kx = x + 2 + (w - 4 - knob) * Anim.easeOutCubic(t);
		c.shadow(kx, y + 2, knob, knob, knob / 2f, 2.5f, Anim.black(0.35f));
		int knobColor = Anim.lerpColor(0xFFE8E8E8, 0xFF0B0B0D, t);
		c.rrect(kx, y + 2, knob, knob, knob / 2f, knobColor);
		R2D.popAlpha();
	}

	/** Horizontal slider with drag, direct value entry, and right-click entry. */
	protected void slider(NumberSetting s, float x, float y, float w) {
		float t = (float) s.normalized();
		float shown = anim(s, t, 22f);
		boolean hovered = over(x - 4, y - 7, w + 8, 14);
		float h = hoverAnim(s.id + "#hover" + System.identityHashCode(s), hovered || drag != null && draggingKey == s);
		c.rrect(x, y - 1.5f, w, 3f, 1.5f, Anim.white(0.14f));
		c.rrect(x, y - 1.5f, Math.max(3f, w * shown), 3f, 1.5f, Anim.white(0.92f));
		float kr = 5f + 1.2f * h;
		float kx = x + w * shown;
		c.shadow(kx - kr, y - kr, kr * 2, kr * 2, kr, 3f, Anim.black(0.45f));
		c.circle(kx, y, kr, 0xFFFFFFFF);
		hit(x - 6, y - 8, w + 12, 16).click(b -> {
			if (b == 1) { openNumberInput(s); return; }
			if (b != 0) return;
			draggingKey = s;
			drag = (px, py) -> s.setNormalized((px - x) / w);
			drag.drag(mx, my);
		});
		numberInputAccess(s,x,y,w);
	}

	/** Reusable for pages whose slider drawing follows the pointer without smoothing. */
	protected void numberInputAccess(NumberSetting setting,float x,float y,float width) {
		float valueWidth=Math.min(width*.45f,Math.max(42,c.textWidth(REG,setting.format(),11.5f)+12));
		hit(x+width+7-valueWidth,y-31,valueWidth,21).left(()->openNumberInput(setting));
		c.textRight(REG,c.ellipsize(REG,Lang.t("ui.number.hint"),7,width),x+width,y+12,7,Theme.TEXT_4);
	}
	protected void openNumberInput(NumberSetting setting) {
		drag=null;draggingKey=null;focused=null;mc.setScreen(new NumberInputScreen(this,setting));
	}

	protected Object draggingKey;

	protected void rangeSlider(RangeSetting s, float x, float y, float w) {
		float lo = anim(s.id + "#lo" + System.identityHashCode(s), (float) s.norm(s.low()), 22f);
		float hi = anim(s.id + "#hi" + System.identityHashCode(s), (float) s.norm(s.high()), 22f);
		c.rrect(x, y - 1.5f, w, 3f, 1.5f, Anim.white(0.14f));
		float x0 = x + w * lo;
		float x1 = x + w * hi;
		// Both handles form one capsule that stretches between the two values.
		c.shadow(x0 - 5.5f, y - 5.5f, x1 - x0 + 11, 11, 5.5f, 3f, Anim.black(0.45f));
		c.rrect(x0 - 5.5f, y - 5.5f, x1 - x0 + 11, 11, 5.5f, Anim.white(0.30f));
		c.circle(x0, y, 5f, 0xFFFFFFFF);
		c.circle(x1, y, 5f, 0xFFFFFFFF);
		hit(x - 6, y - 8, w + 12, 16).click(b -> {
			if (b == 1) {
				double position=s.denorm((mx-x)/w);boolean low=s.low()==s.high()?mx<x+w/2:Math.abs(position-s.low())<=Math.abs(position-s.high());
				openRangeNumberInput(s,low);return;
			}
			if (b != 0) return;
			double pos = s.denorm((mx - x) / w);
			boolean low = Math.abs(pos - s.low()) < Math.abs(pos - s.high()) || pos < s.low();
			if (s.low() == s.high()) low = pos < s.low();
			final boolean moveLow = low;
			draggingKey = s;
			drag = (px, py) -> {
				double v = s.denorm((px - x) / w);
				if (moveLow) s.setLow(v);
				else s.setHigh(v);
			};
			drag.drag(mx, my);
		});
		float right=x+w+6,highWidth=c.textWidth(MED,NumberSetting.formatNumber(s.high(),s.step)+Lang.unit(s.unit),10.5f),lowWidth=c.textWidth(MED,NumberSetting.formatNumber(s.low(),s.step),10.5f),separator=c.textWidth(MED,"  ↔  ",10.5f);
		hit(right-highWidth-4,y-31,highWidth+8,21).left(()->openRangeNumberInput(s,false));
		hit(right-highWidth-separator-lowWidth-4,y-31,lowWidth+8,21).left(()->openRangeNumberInput(s,true));
		c.textRight(REG,c.ellipsize(REG,Lang.t("ui.number.hint"),7,w),x+w,y+12,7,Theme.TEXT_4);
	}
	protected void openRangeNumberInput(RangeSetting setting,boolean low) {
		NumberSetting endpoint=new NumberSetting(setting.id+(low?".low":".high"),low?setting.low():setting.high(),low?setting.min:setting.low(),low?setting.high():setting.max,setting.step,setting.unit) {
			@Override public String name(){return setting.name()+" · "+Lang.t(low?"ui.number.lower":"ui.number.upper");}
			@Override public void set(Double value){if(low)setting.setLow(value);else setting.setHigh(value);}
		};
		openNumberInput(endpoint);
	}

	/** Pill button showing "Label - Value" with a chevron, like a select box. */
	protected void selectBox(Object key, String label, String value, float x, float y, float w, float h, boolean open, Runnable onClick) {
		boolean hovered = over(x, y, w, h);
		float ha = hoverAnim(key, hovered || open);
		c.lens(x, y, w, h, h / 2f, Theme.lift(0.07f + 0.05f * ha), Theme.lensGlass(c.scale));
		String text = label.isEmpty() ? value : label + " - " + value;
		c.textC(REG, c.ellipsize(REG, text, 10.5f, w - 34), x + 10, y + h / 2f, 10.5f, Theme.TEXT);
		c.icon(open ? Icon.CHEVRON_UP : Icon.CHEVRON_DOWN, x + w - 13, y + h / 2f, 11, Theme.TEXT_3);
		hit(x, y, w, h).left(onClick);
	}

	/** Draws a text field; clicking focuses it. */
	protected void textField(TextField field, String placeholder, Icon icon, float x, float y, float w, float h) {
		boolean focus = focused == field;
		boolean hovered = over(x, y, w, h);
		float fa = anim(field, focus ? 1f : hovered ? 0.5f : 0f, 16f);
		c.lens(x, y, w, h, h / 2f, Theme.lift(0.06f + 0.05f * fa), Theme.lensGlass(c.scale));
		float tx = x + 10;
		if (icon != null) {
			c.icon(icon, x + 15, y + h / 2f, 12, focus ? Theme.TEXT_2 : Theme.TEXT_3);
			tx = x + 28;
		}
		float size = 10.5f;
		float maxW = w - (tx - x) - 10;
		c.clip(tx - 1, y, maxW + 2, h);
		if (field.text.isEmpty()) {
			c.textC(REG, placeholder, tx, y + h / 2f, size, Theme.TEXT_4);
		} else {
			TextField.View visible = field.viewport(512);
			String display = visible.text();
			String before = display.substring(0, Math.min(visible.cursor(), display.length()));
			float caretX = c.textWidth(REG, before, size);
			float shift = Math.max(0, caretX - maxW + 4);
			if (field.allSelected) {
				float tw = c.textWidth(REG, display, size);
				c.rrect(tx - shift - 1, y + h / 2f - 7, tw + 2, 14, 3, Anim.white(0.25f));
			}
			c.textC(REG, display, tx - shift, y + h / 2f, size, Theme.TEXT);
			if (focus && field.caretVisible()) c.rect(tx - shift + caretX, y + h / 2f - 6.5f, 1f, 13, Theme.TEXT);
		}
		if (focus && field.text.isEmpty() && field.caretVisible()) c.rect(tx, y + h / 2f - 6.5f, 1f, 13, Theme.TEXT_2);
		c.unclip();
		hit(x, y, w, h).left(() -> {
			focused = field;
			field.allSelected = false;
			field.cursor = field.text.length();
		});
	}

	/** Clear glass pill button with optional icon; {@code primary} buttons are a little brighter. */
	protected void button(Object key, String label, Icon icon, float x, float y, float w, float h, boolean primary, Runnable action) {
		boolean hovered = over(x, y, w, h);
		float ha = hoverAnim(key, hovered);
		c.lens(x, y, w, h, h / 2f, Theme.lift((primary ? 0.17f : 0.08f) + 0.07f * ha), Theme.lensGlass(c.scale));
		int fg = Theme.TEXT;
		float contentW = c.textWidth(MED, label, 10.5f) + (icon != null ? 16 : 0);
		float cx = x + (w - contentW) / 2f;
		if (icon != null) {
			c.icon(icon, cx + 6, y + h / 2f, 12, fg);
			cx += 16;
		}
		c.textC(MED, label, cx, y + h / 2f, 10.5f, fg);
		hit(x, y, w, h).left(action);
	}

	/** Round icon button with a hover halo. */
	protected void iconButton(Object key, Icon icon, float cx, float cy, float size, int color, Runnable action) {
		float r = size * 0.95f;
		boolean hovered = over(cx - r, cy - r, r * 2, r * 2);
		float ha = hoverAnim(key, hovered);
		if (ha > 0.01f) c.circle(cx, cy, r, Anim.white(0.10f * ha));
		c.icon(icon, cx, cy, size, Anim.lerpColor(color, Theme.TEXT, ha));
		hit(cx - r, cy - r, r * 2, r * 2).left(action);
	}

	/** Small rounded badge with a key name or a count. */
	protected float badge(String text, float x, float cy, float size, int fill, int border, int color) {
		float w = Math.max(16, c.textWidth(MED, text, size) + 10);
		float h = size + 7;
		c.rrect(x, cy - h / 2f, w, h, h / 2f, fill);
		c.textMid(MED, text, x + w / 2f, cy, size, color);
		return w;
	}

	/** True while a text field has keyboard focus (movement keys must not leak to the player then). */
	public boolean isTyping() {
		return focused != null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// The glass backdrop is drawn by the screen itself.
	}
}
