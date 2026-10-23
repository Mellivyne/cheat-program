package com.cheatprogram.gui;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.ChatStatusMarks;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.ChatHistoryStore;
import com.cheatprogram.util.ChatHistoryStore.Entry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local player-filtered chat history. */
public final class ChatHistoryScreen extends GuiBase {
	private static final float W = 740, H = 580, LIST_X = 20, LIST_Y = 142, LIST_W = 700, LIST_H = 298;
	private static final int PAGE_SIZE = 200;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	private final Screen parent;
	private final TextSetting selectedPlayer;
	
	private final TextField player = new TextField(64);
	private final Scroller scroll = new Scroller();
	private final Anim open = new Anim(0);
	private final List<Row> rows = new ArrayList<>();
	private List<Entry> entries = List.of();
	private long revision = Long.MIN_VALUE;
	private String filter = "", statusKey = "";
	private int cacheFontPixels = -1;
	private int pageStart = -1, visibleStart, visibleEnd;
	private boolean scrollToBottom = true;
	private record Row(Entry entry, List<String> lines, String date, String time, float y, float h, float width) { }

	public ChatHistoryScreen(Screen parent, TextSetting selectedPlayer) {
		super(Lang.t("ui.chattracker.title"));
		this.parent = parent; this.selectedPlayer = selectedPlayer;
		player.setText(selectedPlayer.get());
		player.onChange = value -> { selectedPlayer.set(value); pageStart = -1; scrollToBottom = true; statusKey = ""; };
		player.onEnter = () -> refresh(true);
		
	}

	@Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		beginFrame();
		if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; }
		R2D.begin(graphics);
		try {
			float scale = Math.min(R2D.width() / (W + 40), R2D.height() / (H + 40));
			scale = Math.max(.25f, Math.min(scale, Math.max(.5f, Math.min(R2D.width() / 1060f, R2D.height() / 760f)
					* (float) ClientSettings.INSTANCE.uiScale.fraction())));
			c.set(scale, (R2D.width() - W * scale) / 2, (R2D.height() - H * scale) / 2); updateMouse();
			if (!customPanelBackground()) {
				c.rect(-c.originX / scale, -c.originY / scale, R2D.width() / scale, R2D.height() / scale, Anim.black(.18f));
				Backdrop.capture(Theme.frost()); R2D.applyState();
			}
			float shown = open.to(1, dt, 16);
			c.push(); c.scaleAround(.97f + .03f * Anim.easeOutBack(shown), W / 2, H / 2);
			R2D.pushAlpha(shown);
			if (!drawCustomPanelSurface(graphics, W, H, 24)) {
				c.shadow(0, 6, W, H, 24, 30, Anim.black(.25f));
				c.lens(0, 0, W, H, 24, Theme.lift(.08f), Theme.sheetGlass(c.scale));
			}
			c.textC(BOLD, Lang.t("ui.chattracker.title"), 24, 29, 16, Theme.TEXT);
			c.textC(REG, c.ellipsize(REG, ChatHistoryStore.contextLabel(), 9, 600), 24, 48, 9, Theme.TEXT_3);
			iconButton("chat.close", Icon.CLOSE, W - 29, 28, 16, Theme.TEXT_2, this::onClose);
			textField(player, Lang.t("ui.chattracker.playerHint"), Icon.USER, 24, 65, 512, 29);
			button("chat.refresh",Lang.t("ui.chattracker.refresh"),Icon.REFRESH,544,65,172,29,true,()->refresh(true));
			c.textC(REG, Lang.t("ui.chattracker.scope"), 24, 111, 9, Theme.TEXT_3);
			c.textC(REG, Lang.t("ui.chattracker.ticksHint"), 24, 125, 8.5f, Theme.TEXT_3);
			refresh(false); history(graphics); pager();
			{
				c.textC(REG, Lang.t("ui.chattracker.historyHint"), 24, 503, 9.5f, Theme.TEXT_2);
				c.textC(REG, Lang.t("ui.chattracker.storageHint"), 24, 523, 8.5f, Theme.TEXT_3);
			}
			button("chat.back", Lang.t("ui.back"), Icon.BACK, 24, 544, 104, 25, false, this::onClose);

			R2D.popAlpha(); c.pop();
		} finally { R2D.end(); endFrame(); }
	}

	private void refresh(boolean force) {
		String query = player.text.strip().toLowerCase(Locale.ROOT);
		long currentRevision = ChatHistoryStore.revision();
		boolean queryChanged = !filter.equals(query);
		int fontPixels = Math.max(1, Math.round(c.scale * 10.5f));
		if (!force && !queryChanged && revision == currentRevision && cacheFontPixels == fontPixels) return;
		boolean followLatest = pageStart < 0 && scroll.target >= scroll.max - 24;
		if (queryChanged) { pageStart = -1; scrollToBottom = true; }
		filter = query; revision = currentRevision; cacheFontPixels = fontPixels;
		entries = query.isBlank() ? List.of() : ChatHistoryStore.entries(query);

		visibleStart = pageStart < 0 ? Math.max(0, entries.size() - PAGE_SIZE) : Math.min(pageStart, Math.max(0, entries.size() - 1));
		visibleEnd = Math.min(entries.size(), visibleStart + PAGE_SIZE);
		rows.clear(); float y = 7; String day = "";
		for (int i = visibleStart; i < visibleEnd; i++) {
			Entry entry = entries.get(i);
			Instant at = Instant.ofEpochMilli(entry.timestamp());
			String date = DATE.format(at), header = date.equals(day) ? "" : date;
			day = date;
			if (!header.isEmpty()) y += 25;
			List<String> lines = wrapMessage(entry.text(), 554);
			float width = 158;
			for (String line : lines) width = Math.max(width, c.textWidth(REG, line, 10.5f) + 26);
			width = Math.min(586, Math.max(width, c.textWidth(MED, entry.sender(), 8.5f) + 32));
			float height = Math.max(1, lines.size()) * 14 + 43;
			rows.add(new Row(entry, lines, header, TIME.format(at), y, height, width));
			y += height + 9;
		}
		scroll.max = Math.max(0, y + 4 - LIST_H);
		if (scrollToBottom || followLatest) { scroll.target = scroll.max; scroll.value.snap(scroll.max); scrollToBottom = false; }
		else scroll.target = Math.min(scroll.target, scroll.max);
	}

	/** Linear wrapping avoids repeatedly measuring a long accumulated line for every code point. */
	private List<String> wrapMessage(String value, float maxWidth) {
		List<String> result = new ArrayList<>(); StringBuilder line = new StringBuilder(); float width = 0;
		for (int i = 0; i < value.length();) {
			int cp = value.codePointAt(i); i += Character.charCount(cp);
			if (cp == '\n') { result.add(line.toString()); line.setLength(0); width = 0; continue; }
			String glyph = new String(Character.toChars(cp)); float advance = c.textWidth(REG, glyph, 10.5f);
			if (!line.isEmpty() && width + advance > maxWidth) { result.add(line.toString()); line.setLength(0); width = 0; }
			line.appendCodePoint(cp); width += advance;
		}
		if (!line.isEmpty() || result.isEmpty()) result.add(line.toString());
		return result;
	}

	private void history(GuiGraphics graphics) {
		c.lens(LIST_X, LIST_Y, LIST_W, LIST_H, 16, Theme.lift(.025f), Theme.lensGlass(c.scale));
		scroll.update(dt);
		c.clip(LIST_X + 3, LIST_Y + 3, LIST_W - 6, LIST_H - 6);
		pushHitClip(LIST_X + 3, LIST_Y + 3, LIST_W - 6, LIST_H - 6);
		hit(LIST_X, LIST_Y, LIST_W, LIST_H).scroll(scroll::scroll);
		try {
			if (rows.isEmpty()) {
				String emptyKey = filter.isBlank() ? "ui.chattracker.enterPlayer" : ChatHistoryStore.loading() ? "ui.chattracker.loading" : "ui.chattracker.empty";
				c.textMid(REG, Lang.t(emptyKey), W / 2, LIST_Y + LIST_H / 2, 10.5f, Theme.TEXT_3);
			}
			for (Row row : rows) {
				float y = LIST_Y + row.y - scroll.get();
				if (y + row.h < LIST_Y - 2 || y - 26 > LIST_Y + LIST_H + 2) continue;
				if (!row.date.isEmpty()) c.textMid(MED, row.date, W / 2, y - 13, 8.5f, Theme.TEXT_3);
				float x = row.entry.outgoing() ? LIST_X + LIST_W - row.width - 12 : LIST_X + 12;
				c.lens(x, y, row.width, row.h, 13, Theme.lift(row.entry.outgoing() ? .12f : .065f), Theme.chipGlass(c.scale));
				c.textC(MED, c.ellipsize(MED, row.entry.sender(), 8.5f, row.width - 24), x + 12, y + 13, 8.5f, Theme.TEXT_3);
				float ty = y + 30;
				for (String line : row.lines) {
					if (ty >= LIST_Y - 14 && ty <= LIST_Y + LIST_H + 14) c.textC(REG, line, x + 12, ty, 10.5f, Theme.TEXT);
					ty += 14;
				}
				float meta = y + row.h - 12;
				String kind = row.entry.direct() ? row.entry.outgoing() ? "ui.privatechat.outgoing" : "ui.privatechat.incoming" : "ui.chattracker.public";
				c.textC(REG, Lang.t(kind), x + 12, meta, 7.5f, Theme.TEXT_3);
				c.textRight(REG, row.time, x + row.width - 37, meta, 8.5f, 0xFF66C95F);
				ChatStatusMarks.draw(graphics, c, x + row.width - 29, meta - 6, 17, 0xFF58C65A);
			}
		} finally { popHitClip(); c.unclip(); }
		if (scroll.max > 0) {
			float thumb = Math.max(20, LIST_H * LIST_H / (scroll.max + LIST_H));
			c.rrect(LIST_X + LIST_W - 4, LIST_Y + (LIST_H - thumb) * scroll.get() / scroll.max, 2, thumb, 1, Anim.white(.3f));
		}
	}

	private void pager() {
		button("chat.older", Lang.t("ui.chattracker.older"), Icon.CHEVRON_LEFT, 24, 450, 100, 25, false,
				() -> changePage(Math.max(0, visibleStart - PAGE_SIZE)));
		button("chat.newer", Lang.t("ui.chattracker.newer"), Icon.CHEVRON_RIGHT, 132, 450, 100, 25, false,
				() -> changePage(visibleEnd >= entries.size() ? -1 : visibleEnd));
		button("chat.latest", Lang.t("ui.chattracker.latest"), null, 240, 450, 80, 25, false, () -> changePage(-1));
		c.textRight(REG, Lang.t("ui.chattracker.count", entries.isEmpty() ? 0 : visibleStart + 1, visibleEnd, entries.size()),
				W - 25, 462.5f, 9, Theme.TEXT_3);
	}
	private void changePage(int start) {
		if (start == pageStart) { if (start < 0) { scroll.target = scroll.max; } return; }
		pageStart = start; revision = Long.MIN_VALUE; scroll.target = 0; scroll.value.snap(0); scrollToBottom = start < 0;
	}
	@Override public boolean mouseClicked(double x, double y, int button) {
		if (!dispatchClick(x, y, button)) focused = null;
		return true;
	}
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
	@Override public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		if (key == GLFW.GLFW_KEY_TAB) { focused = player; return true; }
		
		if (focused != null) { focused.keyPressed(key, modifiers); return true; }
		return false;
	}
	@Override public boolean charTyped(char chr, int modifiers) { return focused != null && focused.charTyped(chr); }
	@Override public void onClose() { mc.setScreen(parent); }
	@Override public boolean isPauseScreen() { return false; }
}
