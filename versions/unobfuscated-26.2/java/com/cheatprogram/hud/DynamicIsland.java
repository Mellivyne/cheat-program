package com.cheatprogram.hud;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.module.visual.Personal;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.Spring;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Top-centre "dynamic island" in clear liquid glass. Idle it shows the client, player, connection and
 * frame rate; when something happens it morphs into a stack of notifications (module toggles,
 * screenshots, profile changes) and shows live scaffold stats, then shrinks back into the pill.
 */
public final class DynamicIsland {
	private static final float IDLE_H = 22;
	private static final float ENTRY_H = 31;
	private static final float SCAFFOLD_H = 42;
	private static final float PAD = 5;
	private static final float ICON_BOX = 20;
	private static final float SIZE = 9f;

	private final Spring width = new Spring(240);
	private final Spring height = new Spring(IDLE_H);
	private final Anim idleVis = new Anim(1);
	private final Anim listVis = new Anim(0);


	private final MusicCapsule music = new MusicCapsule();

	private record Item(Icon icon, String strong, String rest, boolean logo) {
	}

	public void render(HudContext ctx, float dt, boolean showNotifications) {
		render(ctx, dt, showNotifications, true);
	}

	public void render(HudContext ctx, float dt, boolean showNotifications, boolean includeModuleToggles) {
		Minecraft mc = Minecraft.getInstance();
		Canvas c = ctx.c;
		AiVoiceHud.islandRendered();
		float voicePresence = AiVoiceHud.presence();
		if (voicePresence < .002f) LoginWelcome.update(dt,!ctx.editing&&mc.gui.screen()==null);
		boolean welcome=LoginWelcome.active();
		IslandEvents.update(ctx, dt, welcome || voicePresence > .002f);
		boolean event = !welcome && IslandEvents.active();
		float normalVisibility=LoginWelcome.normalVisibility() * (event ? IslandEvents.normalVisibility() : 1);
		long now = System.currentTimeMillis();
		List<Notifications.Entry> entries = showNotifications ? Notifications.entries().stream()
				.filter(entry -> includeModuleToggles || entry.kind != Notifications.Kind.MODULE)
				.toList() : List.of();



		List<Item> items = idleItems(mc);
		float baseW = PAD * 2 + 8 + measureIdle(c, items);
		music.update(c, dt, Math.max(24, ctx.width - baseW - 45));
		float idleW = baseW + music.idleWidth();

		float listW = 0;
		float listH = 0;
		
		for (Notifications.Entry e : entries) {
			listW = Math.max(listW, entryWidth(c, e));
			listH += ENTRY_H * e.visibility(now);
		}
		boolean list = !entries.isEmpty();
		float targetW = Math.min(ctx.width - 12, list ? Math.max(Math.max(listW, music.rowWidth()), 168) : idleW);
		float targetH = list ? Math.max(listH + music.rowHeight() + PAD * 2, IDLE_H) : IDLE_H;
		if (event) {
			targetW = IslandEvents.expandedWidth(c, targetW, ctx.width - 12);
			targetH = IslandEvents.expandedHeight(targetH, music.rowHeight());
		}
		targetW=Math.max(24,Math.min(ctx.width-12,LoginWelcome.expandedWidth(targetW)));
		targetH=Math.max(IDLE_H,Math.min(ctx.height-8,LoginWelcome.expandedHeight(targetH)));
		targetW=Anim.lerp(targetW,AiVoiceHud.width(ctx),voicePresence);
		targetH=Anim.lerp(targetH,AiVoiceHud.height(),voicePresence);
		width.target = targetW;
		height.target = targetH;
		float widthDamping = voicePresence>.002f?AiVoiceHud.damping():music.absorbing() ? 18f : 25f;
		float w = width.update(dt, 230f, event && voicePresence < .002f ? IslandEvents.damping(widthDamping) : widthDamping);
		float h = height.update(dt, 230f, voicePresence>.002f?AiVoiceHud.damping():event ? IslandEvents.damping(25f) : 25f);
		if (event || voicePresence>.002f) {
			w = Math.max(24, Math.min(ctx.width - 12, w));
			h = Math.max(IDLE_H, Math.min(ctx.height - 8, h));
		}

		// The outgoing content leaves quickly; the incoming one waits until the shape is nearly there.
		boolean nearTarget = Math.abs(w - targetW) < targetW * 0.18f + 6f;
		idleVis.to(!list && nearTarget ? 1f : 0f, dt, list ? 28f : 12f);
		listVis.to(list && (nearTarget || listVis.value > 0.4f) ? 1f : 0f, dt, list ? 12f : 28f);

		float x = voicePresence>.002f?HudLayout.x("island",w,ctx.width):music.active() || event ? (ctx.width - w) / 2f : HudLayout.x("island", w, ctx.width);
		float y = HudLayout.y("island", h, ctx.height);
		float radius=Math.min(h/2,welcome?LoginWelcome.radius(15):event?IslandEvents.radius(15):15);
		radius=Anim.lerp(radius,Math.min(h/2,18),voicePresence);
        R2D.GlassStyle clearGlass=new R2D.GlassStyle(22f*c.scale,12f*c.scale,.35f,1.4f*c.scale,0f,2f);
        R2D.pushAlpha(1-voicePresence);
        try{ctx.liquidPanel(x,y,w,h,radius,clearGlass,.015f);}finally{R2D.popAlpha();}
		c.clip(x, y, w, h);
		R2D.pushAlpha(normalVisibility * (1 - voicePresence));
		if (idleVis.value > 0.01f) {
			R2D.pushAlpha(idleVis.value);
			drawIdle(c, items, music, x, y, w, h);
			R2D.popAlpha();
		}
		if (listVis.value > 0.01f) {
			R2D.pushAlpha(listVis.value);
			float ty = y + PAD;
			if (music.rowHeight() > 0.01f) {
				music.drawSlot(c, x + 9, ty + 11, false);
				ty += music.rowHeight();
			}
			
			for (Notifications.Entry e : entries) {
				float v = e.visibility(now);
				if (v > 0.01f) {
					R2D.pushAlpha(v);
					drawEntry(c, e, x, ty + (1f - v) * 5f, now);
					R2D.popAlpha();
				}
				ty += ENTRY_H * v;
			}
			R2D.popAlpha();
		}
		R2D.popAlpha();
		c.unclip();
		if (event) {
			R2D.pushAlpha(1 - voicePresence);
			try {
			IslandEvents.draw(ctx, x, y, w, h, music.rowHeight());
			if (music.rowHeight() > .01f) {
				c.clip(x + 2, y + 1, Math.max(1, w - 4), Math.max(1, h - 2));
				R2D.pushAlpha(IslandEvents.contentVisibility());
				try { music.drawSlot(c, x + 9, y + h - music.rowHeight() / 2, false); }
				finally { R2D.popAlpha(); c.unclip(); }
			}
			} finally { R2D.popAlpha(); }
		}
		if(welcome){
			R2D.pushAlpha(1-voicePresence);
			try{LoginWelcome.draw(ctx,x,y,w,h);}finally{R2D.popAlpha();}
		}
		AiVoiceHud.draw(ctx,x,y,w,h);
		HudLayout.report("island", x, y, w, h);
		float musicX = event || list ? x + 9 : x + (w - measureIdle(c, items) - music.idleWidth()) / 2f;
		float musicCy = event ? y + h - music.rowHeight() / 2 : list ? y + PAD + 11 : y + h / 2;
		if(!welcome&&voicePresence<.002f)music.drawAnnouncement(ctx, x, y, w, h, musicX, musicCy);
	}

	// ---------------------------------------------------------------- idle pill

	private static List<Item> idleItems(Minecraft mc) {
		List<Item> items = new ArrayList<>();
		items.add(new Item(null, "Cheat", "Program", true));
		Personal personal=ModuleManager.get(Personal.class);
		items.add(new Item(Icon.USER,personal==null?mc.getUser().getName():personal.displayName(),"",false));
		ServerData server = mc.getCurrentServer();
		if (mc.hasSingleplayerServer() || server == null) {
			items.add(new Item(Icon.SERVER, "", Lang.t("ui.island.singleplayer"), false));
		} else {
			items.add(new Item(Icon.SERVER, "", Lang.t("ui.island.to", server.ip), false));
		}
		PlayerInfo info = mc.getConnection() != null && mc.player != null
				? mc.getConnection().getPlayerInfo(mc.player.getUUID()) : null;
		String ping = info != null ? Math.max(0, info.getLatency()) + " ms" : "— ms";
		items.add(new Item(null, ping, "", false));
		items.add(new Item(Icon.ACTIVITY, mc.getFps() + " fps", "", false));
		return items;
	}

	private static float itemWidth(Canvas c, Item item) {
		float w = item.icon == null ? 0 : SIZE + 4;
		if (item.logo) {
			return w + c.textWidth(Fonts.Face.BLACK, item.strong, SIZE + 0.5f) + 2.5f + c.textWidth(Fonts.Face.LIGHT, item.rest, SIZE + 0.5f);
		}
		if (!item.strong.isEmpty()) w += c.textWidth(Fonts.Face.MEDIUM, item.strong, SIZE);
		if (!item.rest.isEmpty()) w += (item.strong.isEmpty() ? 0 : 3) + c.textWidth(Fonts.Face.REGULAR, item.rest, SIZE);
		return w;
	}

	private static float measureIdle(Canvas c, List<Item> items) {
		float w = 0;
		for (Item item : items) w += itemWidth(c, item);
		return w + (items.size() - 1) * 13f;
	}

	private static void drawIdle(Canvas c, List<Item> items, MusicCapsule music, float x, float y, float w, float h) {
		float cx = x + (w - measureIdle(c, items) - music.idleWidth()) / 2f;
		float cy = y + h / 2f;
		music.drawSlot(c, cx, cy, true);
		cx += music.idleWidth();
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			float tx = cx;
			if (item.icon != null) {
				c.icon(item.icon, cx + SIZE / 2f, cy, SIZE, Theme.TEXT);
				tx += SIZE + 4;
			}
			if (item.logo) {
				tx += c.textC(Fonts.Face.BLACK, item.strong, tx, cy, SIZE + 0.5f, Theme.TEXT) + 2.5f;
				c.textC(Fonts.Face.LIGHT, item.rest, tx, cy, SIZE + 0.5f, Theme.TEXT_2);
			} else {
				if (!item.strong.isEmpty()) tx += c.textC(Fonts.Face.MEDIUM, item.strong, tx, cy, SIZE, Theme.TEXT) + 3;
				if (!item.rest.isEmpty()) c.textC(Fonts.Face.REGULAR, item.rest, tx, cy, SIZE, Theme.TEXT_2);
			}
			cx += itemWidth(c, item);
			if (i < items.size() - 1) {
				c.circle(cx + 6.5f, cy, 1.2f, Anim.white(0.45f));
				cx += 13f;
			}
		}
	}

	// ---------------------------------------------------------------- notification entries

	private static String detailText(Notifications.Entry e) {
		return e.kind == Notifications.Kind.MODULE
				? e.title + " " + Lang.t(e.positive ? "ui.island.enabled" : "ui.island.disabled")
				: e.detail;
	}

	private static String titleText(Notifications.Entry e) {
		return e.kind == Notifications.Kind.MODULE ? Lang.t("ui.island.toggled") : e.title;
	}

	private static float entryWidth(Canvas c, Notifications.Entry e) {
		float tw = c.textWidth(Fonts.Face.BOLD, titleText(e), 9.5f);
		float dw = c.textWidth(Fonts.Face.MEDIUM, detailText(e), 8.5f);
		return 10 + ICON_BOX + 9 + Math.max(tw, dw) + 14;
	}

	private static void drawEntry(Canvas c, Notifications.Entry e, float x, float ty, long now) {
		float ix = x + 10;
		float cy = ty + ENTRY_H / 2f;
		if (e.kind == Notifications.Kind.MODULE) {
			// The switch flips once the entry has appeared, like a real toggle being pressed.
			float flip = Anim.easeOutCubic((now - e.created - 90) / 300f);
			miniToggle(c, ix, cy - 5.5f, ICON_BOX, 11, e.positive ? flip : 1f - flip);
		} else {
			c.rrect(ix, cy - ICON_BOX / 2f, ICON_BOX, ICON_BOX, 7, Anim.white(0.16f));
			c.icon(e.icon, ix + ICON_BOX / 2f, cy, 11, Theme.TEXT);
		}
		float tx = ix + ICON_BOX + 9;
		c.textC(Fonts.Face.BOLD, titleText(e), tx, ty + 11, 9.5f, Theme.TEXT);
		if (e.kind == Notifications.Kind.MODULE) {
			float dx = tx + c.textC(Fonts.Face.MEDIUM, e.title, tx, ty + 22.5f, 8.5f, Theme.TEXT_2) + 4;
			c.textC(Fonts.Face.BOLD, Lang.t(e.positive ? "ui.island.enabled" : "ui.island.disabled"), dx, ty + 22.5f, 8.5f,
					e.positive ? Theme.TEXT : Theme.TEXT_4);
		} else {
			c.textC(Fonts.Face.MEDIUM, e.detail, tx, ty + 22.5f, 8.5f, Theme.TEXT_2);
		}
	}

	private static void miniToggle(Canvas c, float x, float y, float w, float h, float on) {
		c.rrect(x, y, w, h, h / 2f, Anim.lerpColor(Anim.white(0.20f), Anim.white(0.95f), on));
		float knob = h - 3;
		float kx = x + 1.5f + (w - 3 - knob) * on;
		c.circle(kx + knob / 2f, y + h / 2f, knob / 2f, Anim.lerpColor(0xFFEDEDED, 0xFF0B0B0D, on));
	}
}
