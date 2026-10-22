package com.cheatprogram.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit per-profile discovery, with the same parser/ranking as the shared picker and no shared credentials. */
public final class AiPlayerModels {
	private static final Object LOCK = new Object();
	private static final Map<UUID, Slot> SLOTS = new HashMap<>();
	private static final ExecutorService NETWORK = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "Cheat Program AI player models"); thread.setDaemon(true); return thread;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	private static final class Slot {
		long generation;
		Future<?> request;
		CompletableFuture<?> exchange;
		AiModels.State state = new AiModels.State("", "请选择平台后刷新模型", false, false, List.of());
	}
	private record Query(UUID id, Slot slot, long generation, URI uri, String provider, String key) { }
	private AiPlayerModels() { }

	public static AiModels.State state(UUID id) {
		synchronized (LOCK) { Slot slot = SLOTS.get(id); return slot == null ? new AiModels.State("", "请选择平台后刷新模型", false, false, List.of()) : slot.state; }
	}
	/** The GUI calls this only after an explicit provider or refresh click, authorizing this endpoint. */
	public static void refresh(UUID id, String endpoint, String key) {
		if (id == null) return;
		synchronized (LOCK) {
			Slot slot = SLOTS.computeIfAbsent(id, ignored -> new Slot()); cancel(slot);
			if (key == null || key.isBlank()) { slot.state = new AiModels.State("", "请输入此 AI 玩家的 API Key", false, false, List.of()); return; }
			URI uri;
			try { uri = AiModels.modelsUri(endpoint); }
			catch (RuntimeException invalid) { slot.state = new AiModels.State("", "API 地址无效", false, false, List.of()); return; }
			String provider = AiModels.providerAt(uri);
			slot.state = new AiModels.State(provider, "读取此玩家的模型列表…", true, false, List.of());
			Query query = new Query(id, slot, slot.generation, uri, provider, key);
			slot.request = NETWORK.submit(() -> discover(query));
		}
	}
	public static void clear(UUID id) { synchronized (LOCK) { Slot slot = SLOTS.remove(id); if (slot != null) cancel(slot); } }
	public static void clearAll() { synchronized (LOCK) { for (Slot slot : SLOTS.values()) cancel(slot); SLOTS.clear(); } }
	private static void cancel(Slot slot) {
		slot.generation++; if (slot.request != null) slot.request.cancel(true); if (slot.exchange != null) slot.exchange.cancel(true);
		slot.request = null; slot.exchange = null;
	}
	private static boolean current(Query query) {
		synchronized (LOCK) { return !Thread.currentThread().isInterrupted() && SLOTS.get(query.id()) == query.slot() && query.slot().generation == query.generation(); }
	}
	private static void discover(Query query) {
		try {
			LinkedHashMap<String, AiModels.Model> found = new LinkedHashMap<>(); Set<URI> visited = new HashSet<>();
			URI next = query.uri(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
			for (int page = 0; next != null && page < 100 && found.size() < 10_000; page++) {
				if (!current(query)) throw new InterruptedException();
				if (!visited.add(next)) throw new AiModels.CatalogueError("模型目录分页重复，请检查兼容接口");
				long remaining = deadline - System.nanoTime(); if (remaining <= 0) throw new TimeoutException();
				HttpRequest request = HttpRequest.newBuilder(next).timeout(Duration.ofNanos(Math.min(remaining, TimeUnit.SECONDS.toNanos(15))))
						.header("Accept", "application/json").header("Authorization", "Bearer " + query.key()).GET().build();
				CompletableFuture<HttpResponse<byte[]>> future;
				synchronized (LOCK) {
					if (!current(query)) throw new InterruptedException();
					future = HTTP.sendAsync(request, ignored -> new AiModels.LimitedBody()); query.slot().exchange = future;
				}
				HttpResponse<byte[]> response;
				try { response = future.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(17)), TimeUnit.NANOSECONDS); }
				finally {
					if (!future.isDone()) future.cancel(true);
					synchronized (LOCK) { if (query.slot().exchange == future) query.slot().exchange = null; }
				}
				if (!current(query)) throw new InterruptedException();
				int code = response.statusCode();
				if (code < 200 || code >= 300) throw new AiModels.CatalogueError(AiProtocol.catalogueHttpError(code, query.uri()));
				JsonObject json = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
				JsonArray data = json.getAsJsonArray("data"); if (data == null) throw new AiModels.CatalogueError("模型目录格式不兼容：缺少 data 列表");
				for (JsonElement element : data) {
					if (!element.isJsonObject()) continue;
					AiModels.Model model = AiModels.describe(element.getAsJsonObject(), query.provider(), query.key());
					if (model != null) found.putIfAbsent(model.id(), model);
					if (found.size() >= 10_000) break;
				}
				next = AiModels.nextPage(json, query.uri(), query.key());
			}
			ArrayList<AiModels.Model> models = new ArrayList<>(found.values());
			models.sort(Comparator.comparing(AiModels.Model::usable).reversed().thenComparing(Comparator.comparingInt(AiModels.Model::strength).reversed())
					.thenComparing(AiModels.Model::name, String.CASE_INSENSITIVE_ORDER).thenComparing(AiModels.Model::id));
			String status = models.isEmpty() ? "服务未返回模型" : "已读取 " + models.size() + " 个模型；顺序按系列/档位推断";
			if (next != null || found.size() >= 10_000) status += "（目录达到读取上限，可能不完整）";
			publish(query, status, models);
		} catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
		catch (TimeoutException ignored) { publish(query, "读取模型超时（" + AiProtocol.serviceHost(query.uri()) + "），请稍后刷新", List.of()); }
		catch (AiModels.CatalogueError error) { publish(query, error.getMessage(), List.of()); }
		catch (Exception ignored) { publish(query, "无法读取模型（" + AiProtocol.serviceHost(query.uri()) + "），请检查此玩家的平台、地址和网络", List.of()); }
	}
	private static void publish(Query query, String message, List<AiModels.Model> models) {
		Minecraft.getInstance().execute(() -> { synchronized (LOCK) {
			if (!current(query)) return;
			query.slot().state = new AiModels.State(query.provider(), message, false, false, models);
			query.slot().request = null;
		} });
	}
}
