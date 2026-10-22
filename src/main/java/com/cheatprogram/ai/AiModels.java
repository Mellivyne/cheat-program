package com.cheatprogram.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A real, key-scoped catalogue. Credentials are never logged, persisted or tried against other providers. */
public final class AiModels {
	public record Model(String id, String name, String tier, boolean usable, int strength) { }
	public record State(String provider, String status, boolean loading, boolean needsProviderChoice, List<Model> models) {
		public State { models = List.copyOf(models); }
	}
	private record Query(long generation, String provider, URI uri, String key) { }
	private record Catalogue(List<Model> models, boolean truncated) { }
	private static final Object LOCK = new Object();
	private static final String OPENAI = "https://api.openai.com/v1";
	private static final String DEEPSEEK = "https://api.deepseek.com";
	private static final int MAX_MODELS = 10_000, MAX_PAGES = 100, MAX_BODY = 2_097_152;
	private static final Pattern GPT = Pattern.compile("(?:^|[:/])gpt-(\\d+)(?:\\.(\\d+))?");
	private static final Pattern O = Pattern.compile("(?:^|[:/])o([134])(?:-|$|:)");
	private static final Pattern DEEP_VERSION = Pattern.compile("deepseek-v(\\d+)(?:\\.(\\d+))?");
	private static final ScheduledExecutorService NETWORK = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "Cheat Program AI models");
		thread.setDaemon(true);
		return thread;
	});
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	private static volatile State state = new State("", "请输入 API Key", false, false, List.of());
	private static long generation;
	private static String selectedProvider = "", authorizedEndpoint = "";
	private static Future<?> pending;
	private static CompletableFuture<?> exchange;

	private AiModels() { }
	public static State state() { return state; }

	/** Invoke after the in-memory key setter. Typing/pasting is debounced by 800 ms. */
	public static void keyChanged() {
		synchronized (LOCK) {
			cancel();
			AiConfig config = AiController.config();
			String key = config.apiKey();
			if (key.isBlank()) { state = new State(selectedProvider, "请输入 API Key", false, false, List.of()); return; }
			URI uri;
			try { uri = modelsUri(config.endpoint()); }
			catch (RuntimeException ignored) { state = new State("", "API 地址无效", false, false, List.of()); return; }
			if (key.startsWith("sk-proj-") || key.startsWith("sk-svcacct-")) {
				// A newly pasted uniquely recognized key takes priority over a previous provider choice.
				config.endpoint(OPENAI);
				// This key was just entered and its prefix identifies OpenAI; rebind only this fresh input.
				config.apiKey(key);
				uri = modelsUri(OPENAI);
				selectedProvider = "openai";
				authorizedEndpoint = uri.toString();
			} else if (selectedProvider.isBlank() || !uri.toString().equals(authorizedEndpoint)) {
				selectedProvider = authorizedEndpoint = "";
				state = new State("", "此 Key 前缀不能确认平台，请选择平台或刷新当前地址", false, true, List.of());
				return;
			}
			begin(uri, key, 800);
		}
	}

	/** Provider changes clear credentials that belonged to the previous endpoint. */
	public static void selectProvider(String id) {
		synchronized (LOCK) {
			cancel();
			String provider = id == null ? "" : id.toLowerCase(Locale.ROOT);
			AiConfig config = AiController.config();
			if (provider.equals("openai")) config.endpoint(OPENAI);
			else if (provider.equals("deepseek")) config.endpoint(DEEPSEEK);
			else if (!provider.equals("custom")) {
				state = new State("", "请选择 API 平台", false, true, List.of()); return;
			}
			try {
				URI uri = modelsUri(config.endpoint());
				selectedProvider = provider;
				authorizedEndpoint = uri.toString();
				begin(uri, config.apiKey(), 0);
			} catch (RuntimeException ignored) { state = new State(provider, "API 地址无效", false, false, List.of()); }
		}
	}

	/** Explicitly authorizes the current endpoint, e.g. after editing a custom base URL. */
	public static void refresh() {
		synchronized (LOCK) {
			cancel();
			AiConfig config = AiController.config();
			try {
				URI uri = modelsUri(config.endpoint());
				selectedProvider = providerAt(uri);
				authorizedEndpoint = uri.toString();
				begin(uri, config.apiKey(), 0);
			} catch (RuntimeException ignored) { state = new State("", "API 地址无效", false, false, List.of()); }
		}
	}

	/** Cancels discovery and drops the catalogue/provider authorization; does not change the config key. */
	public static void clear() {
		synchronized (LOCK) {
			cancel();
			selectedProvider = authorizedEndpoint = "";
			state = new State("", "请输入 API Key", false, false, List.of());
		}
	}

	private static void cancel() {
		generation++;
		if (pending != null) pending.cancel(true);
		if (exchange != null) exchange.cancel(true);
		pending = null;
		exchange = null;
	}

	private static void begin(URI uri, String key, long delayMs) {
		if (key.isBlank()) { state = new State(selectedProvider, "请输入 API Key", false, false, List.of()); return; }
		state = new State(selectedProvider, delayMs > 0 ? "等待输入完成…" : "读取模型列表…", true, false, List.of());
		Query query = new Query(generation, selectedProvider, uri, key);
		pending = NETWORK.schedule(() -> discover(query), delayMs, TimeUnit.MILLISECONDS);
	}

	private static boolean current(Query query) {
		synchronized (LOCK) {
			if (generation != query.generation() || Thread.currentThread().isInterrupted()) return false;
			AiConfig config = AiController.config();
			try { return config.apiKey().equals(query.key()) && modelsUri(config.endpoint()).equals(query.uri()); }
			catch (RuntimeException ignored) { return false; }
		}
	}

	private static void discover(Query query) {
		try {
			if (!current(query)) return;
			Catalogue catalogue = readCatalogue(query);
			List<Model> models = catalogue.models();
			long usable = models.stream().filter(Model::usable).count();
			String status = models.isEmpty() ? "服务未返回模型" : "已读取 " + models.size() + " 个模型，" + usable + " 个可选";
			if (catalogue.truncated()) status += "（目录达到读取上限，可能不完整）";
			else status += "；顺序按系列/档位推断";
			publish(query, status, models, true);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		} catch (TimeoutException ignored) {
			publish(query, "读取模型超时，请稍后刷新", List.of(), false);
		} catch (CatalogueError error) {
			publish(query, error.getMessage(), List.of(), false);
		} catch (Exception ignored) {
			// Never surface request URLs, server error bodies, exception text or credentials.
			publish(query, "无法读取模型列表，请检查地址、Key 与网络", List.of(), false);
		}
	}

	private static Catalogue readCatalogue(Query query) throws Exception {
		LinkedHashMap<String, Model> found = new LinkedHashMap<>();
		Set<URI> visited = new HashSet<>();
		URI next = query.uri();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
		for (int page = 0; next != null && page < MAX_PAGES && found.size() < MAX_MODELS; page++) {
			if (!current(query)) throw new InterruptedException();
			if (!visited.add(next)) throw new CatalogueError("服务的模型分页重复，请检查兼容接口");
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) throw new TimeoutException();
			HttpRequest request = HttpRequest.newBuilder(next).timeout(Duration.ofNanos(Math.min(remaining, TimeUnit.SECONDS.toNanos(15))))
					.header("Accept", "application/json").header("Authorization", "Bearer " + query.key()).GET().build();
			CompletableFuture<HttpResponse<byte[]>> future;
			synchronized (LOCK) {
				if (!current(query)) throw new InterruptedException();
				future = HTTP.sendAsync(request, info -> new LimitedBody());
				exchange = future;
			}
			HttpResponse<byte[]> response;
			try { response = future.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(17)), TimeUnit.NANOSECONDS); }
			finally {
				if (!future.isDone()) future.cancel(true);
				synchronized (LOCK) { if (exchange == future) exchange = null; }
			}
			if (!current(query)) throw new InterruptedException();
			int code = response.statusCode();
			if (code < 200 || code >= 300) {
				throw new CatalogueError(code == 401 || code == 403 ? "Key 或模型目录权限无效（HTTP " + code + "）"
						: code == 429 ? "模型目录请求受限，请稍后刷新（HTTP 429）"
						: code == 404 || code == 405 ? "此 API 地址未提供兼容的 /models 目录"
						: "模型目录服务不可用（HTTP " + code + "）");
			}
			JsonObject json = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray data = json.getAsJsonArray("data");
			if (data == null) throw new CatalogueError("模型目录格式不兼容：缺少 data 列表");
			for (JsonElement element : data) {
				if (!element.isJsonObject()) continue;
				Model model = describe(element.getAsJsonObject(), query);
				if (model != null) found.putIfAbsent(model.id(), model);
				if (found.size() >= MAX_MODELS) break;
			}
			next = nextPage(json, query.uri(), query.key());
		}
		ArrayList<Model> sorted = new ArrayList<>(found.values());
		sorted.sort(Comparator.comparing(Model::usable).reversed().thenComparing(Comparator.comparingInt(Model::strength).reversed())
				.thenComparing(Model::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Model::id));
		return new Catalogue(List.copyOf(sorted), next != null || found.size() >= MAX_MODELS);
	}

	private static void publish(Query query, String status, List<Model> models, boolean recommend) {
		Minecraft.getInstance().execute(() -> {
			synchronized (LOCK) {
				if (!current(query)) return;
				AiConfig config = AiController.config();
				if (recommend && models.stream().noneMatch(m -> m.usable() && m.id().equals(config.model())))
					models.stream().filter(Model::usable).findFirst().ifPresent(m -> config.model(m.id()));
				state = new State(query.provider(), status, false, false, models);
			}
		});
	}

	static URI modelsUri(String endpoint) {
		URI uri = URI.create(endpoint.strip());
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null || uri.getUserInfo() != null
				|| uri.getFragment() != null || uri.getQuery() != null) throw new IllegalArgumentException();
		if ((uri.getHost().equalsIgnoreCase("api.openai.com") || uri.getHost().equalsIgnoreCase("api.deepseek.com"))
				&& !scheme.equals("https")) throw new IllegalArgumentException();
		String path = uri.getRawPath();
		while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		for (String suffix : List.of("/chat/completions", "/responses", "/models")) {
			if (path.toLowerCase(Locale.ROOT).endsWith(suffix)) { path = path.substring(0, path.length() - suffix.length()); break; }
		}
		return URI.create(scheme + "://" + uri.getRawAuthority() + path + "/models");
	}

	static String providerAt(URI uri) {
		if (uri.getPort() != -1 && uri.getPort() != 443) return "custom";
		if (uri.getHost().equalsIgnoreCase("api.openai.com")) return "openai";
		if (uri.getHost().equalsIgnoreCase("api.deepseek.com")) return "deepseek";
		return "custom";
	}

	static URI nextPage(JsonObject json, URI base, String key) throws CatalogueError {
		String link = string(json, "next");
		if (link.isBlank()) link = string(json, "next_page");
		if (!link.isBlank() && (link.startsWith("/") || link.startsWith("?") || link.contains("://"))) {
			URI next;
			try { next = link.startsWith("?") ? URI.create(base.toString() + link) : base.resolve(link); }
			catch (RuntimeException ignored) { throw new CatalogueError("模型分页地址无效"); }
			if (!next.getScheme().equalsIgnoreCase(base.getScheme()) || next.getHost() == null
					|| !next.getHost().equalsIgnoreCase(base.getHost()) || next.getPort() != base.getPort()
					|| next.getUserInfo() != null || next.getFragment() != null || !next.getRawPath().equals(base.getRawPath())
					|| next.toString().contains(key)) throw new CatalogueError("模型分页要求跳转到其他地址，已停止读取");
			return next;
		}
		String cursor = string(json, "next_cursor");
		if (!cursor.isBlank()) return URI.create(base + "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
		if (Boolean.TRUE.equals(bool(json, "has_more"))) {
			String last = string(json, "last_id");
			if (last.isBlank()) throw new CatalogueError("模型分页缺少 last_id，无法读取完整目录");
			return URI.create(base + "?after=" + URLEncoder.encode(last, StandardCharsets.UTF_8));
		}
		return null;
	}

	private static Model describe(JsonObject json, Query query) {
		return describe(json, query.provider(), query.key());
	}
	static Model describe(JsonObject json, String provider, String key) {
		String rawId = string(json, "id");
		if (rawId.isBlank()) return null;
		String id = safe(rawId, key, 512);
		String name = safe(string(json, "name"), key, 160);
		if (name.isBlank()) name = id;
		String identity = id.toLowerCase(Locale.ROOT);
		String owner = string(json, "owned_by").toLowerCase(Locale.ROOT);
		String family = provider.equals("custom") ? owner.contains("openai") || owner.equals("system") ? "openai"
				: owner.contains("deepseek") ? "deepseek" : "custom" : provider;
		boolean nonChat = identity.matches(".*(embedding|whisper|transcrib|tts|moderation|rerank|realtime|audio|dall-e|image|sora|video|search|deep-research|instruct|babbage|davinci|curie|ada-).*" )
				|| identity.contains("computer-use");
		JsonArray outputs = array(json, "output_modalities");
		if (outputs != null && !contains(outputs, "text")) nonChat = true;
		Boolean toolFlag = tools(json);
		boolean knownTools = false;
		int strength = 0;
		String tier = "未标注";
		Matcher gpt = GPT.matcher(identity), o = O.matcher(identity);
		if (family.equals("openai") && gpt.find()) {
			int major = integer(gpt.group(1)), minor = integer(gpt.group(2));
			knownTools = major >= 4 || identity.startsWith("gpt-3.5-turbo") && !identity.contains("-0301");
			tier = tierOf(identity);
			strength = Math.min(20, major) * 1000 + Math.min(99, minor) * 10 + tierWeight(tier);
		} else if (family.equals("openai") && o.find()) {
			knownTools = !identity.startsWith("o1-preview") && !identity.startsWith("o1-mini");
			tier = tierOf(identity);
			strength = 4000 + integer(o.group(1)) * 150 + tierWeight(tier);
		} else if (family.equals("deepseek") && identity.startsWith("deepseek-")) {
			knownTools = identity.equals("deepseek-chat") || identity.equals("deepseek-flash")
					|| identity.startsWith("deepseek-v4") || identity.startsWith("deepseek-v3.2");
			tier = tierOf(identity + " " + name.toLowerCase(Locale.ROOT));
			Matcher version = DEEP_VERSION.matcher((identity + " " + name).toLowerCase(Locale.ROOT));
			strength = (version.find() ? integer(version.group(1)) * 1000 + integer(version.group(2)) * 10 : 0) + tierWeight(tier);
		}
		if (identity.contains("chatgpt") || identity.contains("-chat-")) knownTools = false;
		boolean knownNoTools = identity.startsWith("o1-preview") || identity.startsWith("o1-mini");
		boolean usable = !nonChat && !knownNoTools && !Boolean.FALSE.equals(toolFlag) && id.equals(rawId) && id.length() <= 128;
		if (!knownTools) { tier = "未标注（能力未确认）"; strength = 0; }
		if (Boolean.TRUE.equals(toolFlag) && !knownTools) tier = "未标注";
		if (nonChat) tier = "非工具对话";
		return new Model(id, name, tier, usable, strength);
	}

	private static String tierOf(String identity) {
		if (identity.matches(".*(?:-|\\s)(pro|astra)(?:-|\\s|$).*")) return "高阶（推断）";
		if (identity.matches(".*(?:-|\\s)(nano)(?:-|\\s|$).*")) return "极轻量（推断）";
		if (identity.matches(".*(?:-|\\s)(mini|flash|luna)(?:-|\\s|$).*")) return "轻量（推断）";
		return "标准（推断）";
	}
	private static int tierWeight(String tier) {
		return tier.startsWith("高阶") ? 500 : tier.startsWith("标准") ? 400 : tier.startsWith("轻量") ? 200 : 100;
	}
	private static Boolean tools(JsonObject json) {
		for (String key : List.of("supports_tools", "supports_function_calling", "tool_calling", "function_calling")) {
			Boolean value = bool(json, key);
			if (value != null) return value;
		}
		JsonElement capabilities = json.get("capabilities");
		if (capabilities != null && capabilities.isJsonObject()) {
			Boolean result = tools(capabilities.getAsJsonObject());
			if (result != null) return result;
			return bool(capabilities.getAsJsonObject(), "tools");
		}
		if (capabilities != null && capabilities.isJsonArray() && hasTools(capabilities.getAsJsonArray())) return true;
		JsonArray parameters = array(json, "supported_parameters");
		return parameters != null && hasTools(parameters) ? true : null;
	}
	private static boolean hasTools(JsonArray values) {
		return contains(values, "tools") || contains(values, "tool_calling") || contains(values, "function_calling");
	}
	private static boolean contains(JsonArray values, String needle) {
		for (JsonElement value : values) if (value.isJsonPrimitive() && value.getAsString().equalsIgnoreCase(needle)) return true;
		return false;
	}
	private static JsonArray array(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}
	private static Boolean bool(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
	}
	private static String string(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
	}
	private static int integer(String value) {
		try { return value == null ? 0 : Math.min(99, Integer.parseInt(value)); }
		catch (NumberFormatException ignored) { return 0; }
	}
	private static String safe(String value, String key, int maximum) {
		String text = value.replace(key, "[Key隐藏]").replaceAll("[\\x00-\\x1f\\x7f]", "").strip();
		return text.length() > maximum ? text.substring(0, maximum) : text;
	}
	static final class CatalogueError extends Exception {
		CatalogueError(String message) { super(message); }
	}
	static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
		private Flow.Subscription subscription;
		private long size;
		private boolean finished;
		public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
		public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
		public void onNext(List<ByteBuffer> buffers) {
			if (finished) return;
			for (ByteBuffer buffer : buffers) size += buffer.remaining();
			if (size > MAX_BODY) { finished = true; subscription.cancel(); delegate.onError(new IOException("catalogue too large")); }
			else delegate.onNext(buffers);
		}
		public void onError(Throwable error) { if (!finished) { finished = true; delegate.onError(error); } }
		public void onComplete() { if (!finished) { finished = true; delegate.onComplete(); } }
	}
}
