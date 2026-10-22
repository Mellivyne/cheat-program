package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.PersonalReports;
import com.cheatprogram.util.PersonalReports.Prepared;
import com.cheatprogram.util.PersonalReports.ReportFile;
import com.cheatprogram.util.PersonalReports.Snapshot;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Raw reports stay local. Submission requires an exact destination + sanitized-payload review. */
public final class PersonalReportsScreen extends GuiBase {
	private static final float W = 720, H = 452;
	private static final int PAGE_LINES = 64;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
	private final Screen parent;
	private final TextField note = new TextField(PersonalReports.MAX_NOTE_CHARACTERS), endpoint = new TextField(2048);
	private final Scroller scroll = new Scroller();
	private List<ReportFile> files = List.of();
	private Snapshot snapshot;
	private Prepared prepared, reviewed;
	private URI reviewedEndpoint;
	private Path lastSaved;
	private String status = "", statusArg = "";
	private int tab, previewPage, loadGeneration;
	private boolean raw, loading, listing, saving;
	private float panelH, offset;
	private Object wrappedSource;
	private List<String> wrapped = List.of();
	private int wrappedPage = -1, wrappedFont = -1;
	private float wrappedWidth;
	@Override protected void onTypographyChanged(){wrappedSource=null;wrappedFont=-1;}
	public PersonalReportsScreen(Screen parent) {
		super(Lang.t("ui.reports.title")); this.parent = parent;
		snapshot = PersonalReports.empty(); prepared = PersonalReports.prepare(snapshot, "");
		note.onChange = value -> { refreshPrepared(); reviewed = null; };
		endpoint.onChange = value -> reviewed = null;
		refreshFiles();
	}
	@Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		beginFrame(); if (!preparePanelBackground(g, partialTick)) { endFrame(); return; }
		R2D.begin(g);
		try {
			panelH = layoutPanel(W, H); drawPanelSurface(g, W, panelH, 24);
			c.textC(BOLD, Lang.t("ui.reports.title"), 24, 29, 16, Theme.TEXT);
			iconButton("reports.close", Icon.CLOSE, W - 28, 29, 16, Theme.TEXT_2, this::onClose);
			c.textC(REG, Lang.t("ui.reports.localHint"), 24, 54, 9.5f, Theme.TEXT_3);
			button("reports.tab.files", Lang.t("ui.reports.files"), Icon.FOLDER, 24, 75, 154, 28, tab == 0, () -> changeTab(0));
			button("reports.tab.preview", Lang.t("ui.reports.preview"), Icon.EYE, 190, 75, 154, 28, tab == 1, () -> changeTab(1));
			button("reports.tab.submit", Lang.t("ui.reports.submitTab"), Icon.NAVIGATION, 356, 75, 184, 28, tab == 2, () -> changeTab(2));
			float bodyH = Math.max(70, panelH - 207);
			if (tab == 0) list(bodyH); else if (tab == 1) preview(bodyH); else submission(bodyH);
			String message = PersonalReports.sending() ? Lang.t("ui.reports.sending") : status.isEmpty() ? "" : Lang.t(status, statusArg);
			c.textC(REG, c.ellipsize(REG, message, 9.5f, W - 48), 24, panelH - 73, 9.5f, Theme.TEXT_2);
			button("reports.back", Lang.t("ui.back"), Icon.BACK, 24, panelH - 45, 94, 27, false, this::onClose);
			if (tab == 2 && reviewed != null) button("reports.sendConfirmed", Lang.t("ui.reports.sendConfirmed"), Icon.NAVIGATION, W - 246, panelH - 45, 222, 27, true, this::submitReviewed);
			else if (tab == 1) {
				button("reports.preview.prev", Lang.t("ui.reports.previous"), Icon.CHEVRON_LEFT, W - 234, panelH - 45, 99, 27, false, () -> page(-1));
				button("reports.preview.next", Lang.t("ui.reports.next"), Icon.CHEVRON_RIGHT, W - 123, panelH - 45, 99, 27, false, () -> page(1));
			}
		} finally { R2D.end(); endFrame(); }
	}
	private void changeTab(int value) { tab = value; scroll.target = 0; scroll.value.snap(0); focused = null; drag = null; wrappedSource = null; }
	private void refreshPrepared() {
		prepared = PersonalReports.prepare(snapshot, note.text); wrappedSource = null; previewPage = 0;
	}
	private void refreshFiles() {
		if (listing) return; listing = true;
		PersonalReports.scan().whenComplete((result, failure) -> mc.execute(() -> {
			listing = false;
			if (failure != null) { status = "ui.reports.listFailed"; statusArg = ""; return; }
			files = result; status = "ui.reports.fileCount"; statusArg = Integer.toString(files.size());
		}));
	}
	private void select(ReportFile file) {
		int generation = ++loadGeneration; loading = true; status = "ui.reports.loading"; statusArg = ""; reviewed = null;
		PersonalReports.load(file).whenComplete((result, failure) -> mc.execute(() -> {
			if (generation != loadGeneration) return;
			loading = false;
			if (failure != null) { status = "ui.reports.readFailed"; return; }
			snapshot = result; refreshPrepared(); raw = false; changeTab(1);
			status = result.truncated() ? "ui.reports.truncated" : "ui.reports.loaded"; statusArg = "";
		}));
	}
	private void list(float bodyH) {
		button("reports.refresh", Lang.t("ui.reports.refresh"), Icon.REFRESH, 24, 114, 104, 27, false, this::refreshFiles);
		button("reports.sourceFolder", Lang.t("ui.reports.openFolder"), Icon.FOLDER, 140, 114, 188, 27, false, () -> openPath(PersonalReports.sourceDirectory()));
		float top = 151, listH = Math.max(30, bodyH - 39); scroll.max = Math.max(0, files.size() * 46 - listH); scroll.update(dt);
		c.clip(20, top, W - 40, listH); pushHitClip(20, top, W - 40, listH); hit(20, top, W - 40, listH).scroll(scroll::scroll);
		try {
			if (files.isEmpty()) c.textC(REG, Lang.t(listing ? "ui.reports.loading" : "ui.reports.noFiles"), 28, top + 22, 10.5f, Theme.TEXT_3);
			for (int i = 0; i < files.size(); i++) {
				float y = top + i * 46 - scroll.get(); if (y + 42 < top || y > top + listH) continue;
				ReportFile file = files.get(i);
				c.lens(24, y, W - 48, 41, 12, Theme.lift(.045f), Theme.chipGlass(c.scale));
				c.textC(MED, c.ellipsize(MED, file.name(), 10.5f, W - 190), 36, y + 13, 10.5f, Theme.TEXT);
				c.textC(REG, DATE.format(Instant.ofEpochMilli(file.modified())) + "  ·  " + file.bytes() / 1024 + " KiB", 36, y + 29, 9.5f, Theme.TEXT_3);
				button("reports.select." + i, Lang.t("ui.reports.preview"), Icon.EYE, W - 116, y + 7, 80, 27, false, () -> select(file));
			}
		} finally { popHitClip(); c.unclip(); }
		panelScrollBar("reports.files", scroll, W - 12, top, listH);
	}
	private List<String> previewLines() { return raw ? snapshot.originalLines() : prepared.text().lines().toList(); }
	private void page(int direction) {
		int pages = Math.max(1, (previewLines().size() + PAGE_LINES - 1) / PAGE_LINES);
		previewPage = Math.max(0, Math.min(pages - 1, previewPage + direction)); scroll.target = 0; scroll.value.snap(0); wrappedSource = null;
	}
	private void preview(float bodyH) {
		button("reports.preview.anonymous", Lang.t("ui.reports.anonymous"), Icon.SHIELD, 24, 114, 154, 27, !raw, () -> { raw = false; previewPage = 0; wrappedSource = null; scroll.target = 0; });
		button("reports.preview.raw", Lang.t("ui.reports.original"), Icon.EYE, 190, 114, 154, 27, raw, () -> { raw = true; previewPage = 0; wrappedSource = null; scroll.target = 0; });
		button("reports.preview.savedFolder", Lang.t("ui.reports.exportFolder"), Icon.FOLDER, 508, 114, 188, 27, false, () -> openPath(PersonalReports.exportDirectory()));
		c.textC(REG, Lang.t(raw ? "ui.reports.rawLocalOnly" : "ui.reports.anonymousHint"), 24, 158, 9.5f, Theme.TEXT_3);
		List<String> source = previewLines(); int pages = Math.max(1, (source.size() + PAGE_LINES - 1) / PAGE_LINES);
		previewPage = Math.min(previewPage, pages - 1);
		c.textRight(REG, (previewPage + 1) + " / " + pages, W - 24, 158, 9.5f, Theme.TEXT_3);
		int begin = previewPage * PAGE_LINES, end = Math.min(source.size(), begin + PAGE_LINES);
		List<String> page = source.subList(Math.min(begin, source.size()), end);
		textPreview(raw ? snapshot : prepared, page, 24, 174, W - 48, Math.max(30, bodyH - 62), false);
	}
	private void submission(float bodyH) {
		float top = 114;
		if (reviewed != null) {
			button("reports.review.edit", Lang.t("ui.reports.edit"), Icon.BACK, 24, top, 112, 27, false, () -> { reviewed = null; scroll.target = 0; wrappedSource = null; });
			c.textC(MED, Lang.t("ui.reports.reviewDestination"), 148, top + 13, 10.5f, Theme.TEXT);
			c.textC(REG, c.ellipsize(REG, reviewedEndpoint.toASCIIString(), 10.5f, W - 48), 24, top + 47, 10.5f, Theme.TEXT_2);
			c.textC(REG, Lang.t("ui.reports.networkPrivacy"), 24, top + 70, 9.5f, Theme.TEXT_3);
			textPreview(reviewed, reviewed.json().lines().toList(), 24, top + 89, W - 48, Math.max(30, bodyH - 91), true);
			return;
		}
		float contentH = 342; scroll.max = Math.max(0, contentH - bodyH); scroll.update(dt); offset = scroll.get();
		c.clip(16, top, W - 32, bodyH); pushHitClip(16, top, W - 32, bodyH); hit(16, top, W - 32, bodyH).scroll(scroll::scroll);
		try {
			c.textC(REG, Lang.t("ui.reports.note"), 24, sy(127), 10.5f, Theme.TEXT_2);
			textField(note, Lang.t("ui.reports.noteHint"), null, 24, sy(140), W - 48, 31);
			c.textC(REG, Lang.t("ui.reports.endpoint"), 24, sy(198), 10.5f, Theme.TEXT_2);
			textField(endpoint, "https://", Icon.LINK, 24, sy(211), W - 48, 31);
			c.textC(REG, Lang.t("ui.reports.endpointHint"), 24, sy(263), 9.5f, Theme.TEXT_3);
			c.textC(REG, Lang.t("ui.reports.networkPrivacy"), 24, sy(284), 9.5f, Theme.TEXT_3);
			button("reports.review", Lang.t("ui.reports.review"), Icon.EYE, 24, sy(310), W - 48, 31, true, this::review);
			button("reports.save.txt", Lang.t("ui.reports.saveTxt"), Icon.SAVE, 24, sy(356), 210, 28, false, () -> save(false));
			button("reports.save.json", Lang.t("ui.reports.saveJson"), Icon.SAVE, 246, sy(356), 210, 28, false, () -> save(true));
			button("reports.open.exports", Lang.t("ui.reports.exportFolder"), Icon.FOLDER, 468, sy(356), 228, 28, false, () -> openPath(PersonalReports.exportDirectory()));
			c.textC(REG, Lang.t("ui.reports.payloadSize", prepared.bytes()), 24, sy(410), 9.5f, Theme.TEXT_3);
		} finally { popHitClip(); c.unclip(); }
		panelScrollBar("reports.editor", scroll, W - 12, top, bodyH);
	}
	private float sy(float value) { return value - offset; }
	private void textPreview(Object sourceKey, List<String> lines, float x, float y, float width, float height, boolean all) {
		int font = Math.round(c.scale * 10.5f);
		if (wrappedSource != sourceKey || wrappedPage != previewPage || wrappedFont != font || wrappedWidth != width) {
			List<String> out = new ArrayList<>();
			for (String line : lines) {
				String bounded = line.length() > 1200 ? line.substring(0, 1200) + " …" : line;
				List<String> chunks = c.wrap(REG, bounded.replace('\t', ' '), 10.5f, width - 24);
				out.addAll(chunks.isEmpty() ? List.of("") : chunks);
			}
			wrapped = List.copyOf(out); wrappedSource = sourceKey; wrappedPage = previewPage; wrappedFont = font; wrappedWidth = width;
		}
		scroll.max = Math.max(0, wrapped.size() * 15 + 20 - height); scroll.update(dt);
		c.lens(x, y, width, height, 13, Theme.lift(.025f), Theme.lensGlass(c.scale));
		c.clip(x + 5, y + 5, width - 10, height - 10); pushHitClip(x + 5, y + 5, width - 10, height - 10); hit(x, y, width, height).scroll(scroll::scroll);
		try {
			if (lines.isEmpty()) c.textC(REG, Lang.t(loading ? "ui.reports.loading" : "ui.reports.noOriginal"), x + 12, y + 23, 10.5f, Theme.TEXT_3);
			int from = Math.max(0, (int) (scroll.get() / 15) - 1), to = Math.min(wrapped.size(), from + (int) (height / 15) + 4);
			for (int i = from; i < to; i++) c.textC(REG, wrapped.get(i), x + 12, y + 16 + i * 15 - scroll.get(), 10.5f, Theme.TEXT_2);
		} finally { popHitClip(); c.unclip(); }
		panelScrollBar(all ? "reports.reviewText" : "reports.previewText", scroll, x + width - 4, y + 6, height - 12);
	}
	private void review() {
		if (loading || PersonalReports.sending()) return;
		try {
			reviewedEndpoint = PersonalReports.endpoint(endpoint.text); refreshPrepared(); reviewed = prepared;
			scroll.target = 0; scroll.value.snap(0); focused = null; wrappedSource = null; status = "ui.reports.reviewHint"; statusArg = "";
		} catch (IllegalArgumentException invalid) { status = "ui.reports.invalidEndpoint"; statusArg = ""; }
	}
	private void submitReviewed() {
		if (reviewed == null || reviewedEndpoint == null || PersonalReports.sending()) return;
		Prepared payload = reviewed; URI destination = reviewedEndpoint;
		PersonalReports.submit(destination, payload).whenComplete((result, failure) -> mc.execute(() -> {
			if (failure != null) { status = "ui.reports.sendFailed"; statusArg = ""; return; }
			status = result.statusKey(); statusArg = Integer.toString(result.httpStatus());
			if (status.equals("ui.reports.sent")) reviewed = null;
		}));
	}
	private void save(boolean json) {
		if (saving || loading) return; saving = true; refreshPrepared();
		PersonalReports.save(prepared, json).whenComplete((result, failure) -> mc.execute(() -> {
			saving = false; if (failure != null) { status = "ui.reports.saveFailed"; statusArg = ""; return; }
			status = result.statusKey(); lastSaved = result.savedFile(); statusArg = lastSaved == null ? "" : lastSaved.getFileName().toString();
		}));
	}
	private void openPath(Path path) {
		try { Util.getPlatform().openPath(path); } catch (RuntimeException failure) { status = "ui.reports.openFailed"; statusArg = ""; }
	}
	@Override public boolean mouseClicked(double x, double y, int button) { if (!dispatchClick(x, y, button)) focused = null; return true; }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		if (tab == 2 && reviewed == null && key == GLFW.GLFW_KEY_TAB) { focused = focused == note ? endpoint : note; return true; }
		if (focused != null) return focused.keyPressed(key, modifiers);
		return false;
	}
	@Override public boolean charTyped(char chr, int modifiers) { return focused != null && focused.charTyped(chr); }
	@Override public boolean isPauseScreen() { return false; }
	@Override public void onClose() { mc.setScreen(parent); }
}
