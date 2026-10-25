package com.cheatprogram.ai;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.ai.voice.VoiceProfile;
import com.cheatprogram.ai.voice.SpeechVoices;
import com.cheatprogram.util.SpeechEmotion;
import com.cheatprogram.util.DeviceLanguage;
import com.cheatprogram.mixin.AiPlayerListAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client-owned profiles and brains, with all physical actors confined to their integrated server thread. */
public final class AiPlayers {
	public static final UUID SHARED_CONNECTION_ID = new UUID(0L, 0L);
	private static final Minecraft MC = Minecraft.getInstance();
	private static final AiConfig SHARED_CONNECTION = new AiConfig(directory().resolve("shared_api.json"));
	private static final CredentialStore CREDENTIALS = CredentialStore.open(directory().resolve("credentials.dpapi"));
	private static final ExecutorService FILES = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "AI player profiles"); t.setDaemon(true); return t; });
	static {
		java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try { FILES.submit(() -> { }).get(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
		}, "Cheat Program AI player preference flush"));
	}
	private static volatile List<Profile> profiles = List.of();
	private static final Map<UUID, Runtime> RUNTIMES = new HashMap<>(); // Minecraft client thread only.
	private static final Map<UUID, SpawnSpec> DESIRED = new ConcurrentHashMap<>();
	private static final Map<UUID, AiActor> ACTORS = new ConcurrentHashMap<>(); // Each actor is touched only by its owning server.
	private static final Map<UUID, Long> RESPAWN_AT = new ConcurrentHashMap<>();
	private static volatile MinecraftServer boundServer;
	private static ClientLevel boundLevel;
	private static volatile UUID ownerId;
	private static long serverTicks;
	private static boolean initialized;
	private record SpawnSpec(UUID id, String name, String mode) { }
	private static final JsonArray TOOLS = tools();

	public static final class Profile {
		private final UUID id;
		private volatile String name, persona, endpoint, model, apiKey = "", status = "未召唤";
		private volatile String personality = "", gender = "none", customGender = "";
		private volatile String mode = "survival";
		private volatile String language = DeviceLanguage.system();
		private volatile VoiceProfile voice=new VoiceProfile();
		private volatile String emotion="neutral";
		private volatile boolean hardcoreDead;
		private volatile boolean useShared, spawned, freePlaying;
		private volatile ResourceLocation skinTexture;
		private Profile(UUID id, String name, String persona, String endpoint, String model) {
			this.id = id; this.name = name; this.persona = persona; this.endpoint = endpoint; this.model = model;
		}
		public UUID id() { return id; }
		public String name() { return name; }
		public String persona() { return persona; }
		public String personality() { return personality; }
		public String gender() { return gender; }
		public String customGender() { return customGender; }
		public String mode() { return mode; }
		public String language() { return language; }
		public VoiceProfile voice() { return voice; }
		public String emotion() { return emotion; }
		public boolean useShared() { return useShared; }
		public String endpoint() { return endpoint; }
		public String model() { return model; }
		public String apiKey() { return apiKey; }
		public boolean spawned() { return spawned; }
		public boolean freePlaying() { return freePlaying; }
		public String status() { return status; }
		public ResourceLocation skinTexture() { return skinTexture; }
		@Override public String toString() { return "AI player profile " + id; }
	}
	private static final class Runtime {
		final Profile profile; final AiPlayerBrain brain;
		final ArrayDeque<AiPlayerBrain.Action> queue = new ArrayDeque<>();
		JsonObject observation = new JsonObject();
		boolean active, waiting;
		long generation, nextPlan;
		Runtime(Profile profile) {
			this.profile = profile;
			brain = new AiPlayerBrain(profile.name, profile.persona, profile.personality, profile.gender, profile.customGender, profile.freePlaying);
			brain.language(profile.language);
			brain.voiceEmotion(profile.voice.enabled && profile.voice.emotionEnabled);
		}
	}
	private AiPlayers() { }
	private static void initialize() {
		if (initialized) return; initialized = true;
		load();
	}
	public static List<Profile> profiles() { initialize(); return profiles; }
	public static AiConfig sharedConfig() { return SHARED_CONNECTION; }
	public static void sharedConnectionChanged() {
		for (Runtime run : RUNTIMES.values()) {
			if (!run.profile.useShared) continue;
			boolean active = run.active || run.waiting || run.brain.busy() || !run.queue.isEmpty();
			cancelRuntime(run, "AI 玩家共用 API 已更新。");
			run.active = active; run.nextPlan = 0;
		}
	}
	public static Profile create() {
		return create(null);
	}
	public static Profile create(UUID templateId) {
		initialize();
		AiConfig shared = sharedConfig();
		Profile template = profile(templateId);
		String endpoint = template != null && !template.useShared ? template.endpoint : shared.endpoint();
		String model = template != null && !template.useShared ? template.model : shared.model();
		// Reuse the selected platform/model for a new character, never its credential or shared scope.
		Profile profile = new Profile(UUID.randomUUID(), "AI 玩家 " + (profiles.size() + 1), "像一个真实 Minecraft 玩家一样交流和游玩，遇到危险先保证自己的安全。", endpoint, model);
		ArrayList<Profile> next = new ArrayList<>(profiles); next.add(profile); profiles = List.copyOf(next); return profile;
	}
	private static Profile profile(UUID id) { for (Profile value : profiles) if (value.id.equals(id)) return value; return null; }
	private static Runtime runtime(Profile value) { return RUNTIMES.computeIfAbsent(value.id, ignored -> new Runtime(value)); }
	public static void updateVoice(UUID id,VoiceProfile settings) {
		Profile value=profile(id);if(value==null||settings==null)return;
		// Keep snapshots independent of GUI edits while synthesis runs on its own worker.
		value.voice=VoiceProfile.load(settings.save());SpeechVoices.stop(id);
		Runtime run=RUNTIMES.get(id);if(run!=null)run.brain.voiceEmotion(value.voice.enabled&&value.voice.emotionEnabled);
		save();
	}
	public static void setLanguage(UUID id,String language){
		initialize(); Profile value=profile(id);if(value==null)return;
		String choice=normalizeLanguage(language);if(choice.equals(value.language))return;
		value.language=choice;
		if(!choice.equals("auto")){
			VoiceProfile voice=VoiceProfile.load(value.voice.save());voice.language=voiceLanguage(choice);
			value.voice=voice;SpeechVoices.stop(id);
		}
		reconfigure(value,"AI language changed.");save();
	}
	private static String normalizeLanguage(String value){
		if(value==null)return DeviceLanguage.system();
		String choice=singleLine(value).toLowerCase(java.util.Locale.ROOT);
		return choice.equals("auto")?choice:DeviceLanguage.resolve(choice);
	}
	private static String voiceLanguage(String language){return switch(language){case "zh_tw"->"zh";case "en"->"en-us";default->language;};}
	private static VoiceProfile speakingVoice(Profile profile){
		VoiceProfile voice=VoiceProfile.load(profile.voice.save());
		if(!profile.language.equals("auto"))voice.language=voiceLanguage(profile.language);
		return voice;
	}
	public static void update(UUID id, String name, String persona, boolean useShared, String endpoint, String model, String key) {
		initialize();
		Profile value = profile(id); if (value == null) return;
		String address = singleLine(endpoint), credential = singleLine(key);
		if (credential.length() > 4096) credential = credential.substring(0, 4096);
		if (!CredentialStore.sameEndpoint(value.endpoint, address) && credential.equals(value.apiKey)) credential = "";
		value.name = clean(name).isBlank() ? "AI 玩家" : clean(name); value.persona = clean(persona);
		value.useShared = useShared; value.endpoint = address; value.model = singleLine(model); value.apiKey = credential;
		CREDENTIALS.put(credentialSlot(id), address, credential);
		reconfigure(value, "配置已更新。");
		updateActorName(value);
		save();
	}
	public static void deleteApiKey(UUID id) {
		initialize(); Profile value = profile(id); if (value == null) return;
		value.apiKey = ""; CREDENTIALS.delete(credentialSlot(id));
		reconfigure(value, "API Key 已删除。"); AiPlayerModels.clear(id); save();
	}
	public static String credentialStatusKey(UUID id) { return CREDENTIALS.statusKey(credentialSlot(id)); }
	private static String credentialSlot(UUID id) { return "player." + id; }
	/** Character edits preserve API selection, credentials, mode and conversation history. */
	public static void updateCharacter(UUID id, String name, String persona, String personality, String gender, String customGender) {
		Profile value = profile(id); if (value == null) return;
		String updatedName = clean(name).isBlank() ? "AI 玩家" : clean(name);
		String updatedPersona = clean(persona), updatedPersonality = clean(personality), updatedGender = normalizeGender(gender), updatedCustomGender = clean(customGender);
		if (value.name.equals(updatedName) && value.persona.equals(updatedPersona) && value.personality.equals(updatedPersonality)
				&& value.gender.equals(updatedGender) && value.customGender.equals(updatedCustomGender)) return;
		boolean renamed = !value.name.equals(updatedName);
		value.name = updatedName; value.persona = updatedPersona; value.personality = updatedPersonality;
		value.gender = updatedGender; value.customGender = updatedCustomGender;
		reconfigure(value, "角色设定已更新。");
		if (renamed) updateActorName(value);
	}
	private static void reconfigure(Profile value, String reason) {
		Runtime run = runtime(value);
		boolean active = run.active || run.waiting || run.brain.busy() || !run.queue.isEmpty();
		cancelRuntime(run, reason);
		run.brain.configure(value.name, value.persona, value.personality, value.gender, value.customGender, value.freePlaying);
		run.brain.language(value.language);
		run.brain.voiceEmotion(value.voice.enabled&&value.voice.emotionEnabled);
		run.active = active; run.nextPlan = System.nanoTime() + 2_000_000_000L;
	}
	private static void updateActorName(Profile value) {
		UUID id = value.id;
		SpawnSpec spec = DESIRED.get(id); if (spec != null) DESIRED.put(id, new SpawnSpec(id, value.name, value.mode));
		MinecraftServer server = boundServer;
		if (server != null) server.execute(() -> {
			AiActor actor = ACTORS.get(id); if (actor == null) return;
			actor.rename(value.name); server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, actor));
		});
	}
	public static void setMode(UUID id, String mode) {
		initialize(); Profile value = profile(id); if (value == null) return;
		String selected = normalizeMode(mode); if (value.mode.equals(selected)) return;
		value.mode = selected;
		if (!selected.equals("hardcore")) value.hardcoreDead = false;
		Runtime run = runtime(value);
		boolean active = run.active || run.waiting || run.brain.busy() || !run.queue.isEmpty();
		cancelRuntime(run, "角色模式已切换。"); run.active = active; run.nextPlan = System.nanoTime() + 500_000_000L;
		run.brain.hear("角色设置", "当前角色模式切换为 " + selected + "，以最新真实观察中的模式及能力为准。", false);
		SpawnSpec spec = DESIRED.get(id);
		if (spec != null) DESIRED.put(id, new SpawnSpec(id, value.name, selected));
		MinecraftServer server = boundServer;
		if (server != null) server.execute(() -> {
			AiActor actor = ACTORS.get(id);
			if (server == boundServer && actor != null && actor.server == server && actor.isAlive())
				actor.gameMode.changeGameModeForPlayer(gameType(value.mode));
		});
		value.status = value.spawned ? "角色模式已更新" : "未召唤"; save();
	}
	public static void remove(UUID id) {
		Profile value = profile(id); if (value == null) return;
		value.apiKey = ""; CREDENTIALS.delete(credentialSlot(id));
		despawn(id); Runtime run = RUNTIMES.remove(id); if (run != null) run.brain.close();
		profiles = profiles.stream().filter(candidate -> !candidate.id.equals(id)).toList();
		AiPlayerModels.clear(id); AiPlayerSkins.remove(id, skinFile(id)); save();
	}
	public static void spawn(UUID id) {
		if(com.cheatprogram.core.Config.restartRequired())return;
		initialize(); bindSession(); Profile value = profile(id); if (value == null) return;
		if (!singleplayer() || boundServer == null) { value.status = "请进入单人世界后召唤"; return; }
		if (value.mode.equals("hardcore") && value.hardcoreDead) { value.status = "极限角色已死亡，切换其他模式后可重新召唤"; return; }
		if (DESIRED.containsKey(id)) return;
		value.status = "正在寻找安全落点"; DESIRED.put(id, new SpawnSpec(id, value.name, value.mode)); runtime(value);
	}
	public static void despawn(UUID id) {
		SpeechVoices.stop(id);
		DESIRED.remove(id); Profile value = profile(id);
		if (value != null) { value.spawned = false; value.freePlaying = false; value.status = "已收回"; }
		Runtime run = RUNTIMES.get(id); if (run != null) { cancelRuntime(run, "AI 玩家已收回。"); run.brain.setFreePlay(false); }
		MinecraftServer server = boundServer; if (server != null) server.execute(() -> removeActor(server, id));
	}
	public static void setFreePlaying(UUID id, boolean value) {
		if(value&&com.cheatprogram.core.Config.restartRequired())return;
		initialize(); bindSession();
		Profile profile = profile(id); if (profile == null) return;
		if (value && !singleplayer()) { profile.status = "请进入单人世界后开始"; return; }
		if (value && profile.mode.equals("hardcore") && profile.hardcoreDead) { profile.status = "极限角色已死亡，切换其他模式后可重新召唤"; return; }
		profile.freePlaying = value; Runtime run = runtime(profile);
		cancelRuntime(run, value ? "切换为自由游玩。" : "已停止自由游玩。"); run.brain.setFreePlay(value);
		if (value) {
			spawn(id);
			run.brain.hear(MC.player.getName().getString(), "现在开始自主自由游玩；此前已经停止的任务不要恢复。", true);
			run.nextPlan = 0;
		}
		else profile.status = profile.spawned ? "待命，可在聊天栏交流" : "未召唤";
	}
	public static void setAllFreePlaying(boolean value) { for (Profile profile : profiles()) setFreePlaying(profile.id, value); }
	public static CompletableFuture<Void> importSkin(UUID id, BufferedImage image) {
		Profile value = profile(id); if (value == null) return CompletableFuture.failedFuture(new IllegalArgumentException("AI 玩家不存在。"));
		CompletableFuture<Void> done = new CompletableFuture<>();
		AiPlayerSkins.importImage(id, image, skinFile(id)).whenComplete((texture, error) -> MC.execute(() -> {
			if (error != null) { done.completeExceptionally(error); return; }
			if (profile(id) != value) { done.completeExceptionally(new IllegalStateException("AI 玩家已删除。")); return; }
			value.skinTexture = texture; save(); done.complete(null);
		}));
		return done;
	}
	public static String name(UUID id) { Profile value = profile(id); return value == null ? null : value.name; }
	public static boolean isActor(Entity entity) { return entity instanceof AiActor; }
	public static net.minecraft.resources.ResourceLocation skin(AbstractClientPlayer player, net.minecraft.resources.ResourceLocation original) {
		Profile value = profile(player.getUUID()); if (value == null || value.skinTexture == null) return original;
		return value.skinTexture;
	}
    /** Direct, explicit image message for one selected character; never broadcast attachments to peers. */
    public static boolean directMessage(UUID id,String text,List<AiImage> images){
        initialize();Profile profile=profile(id);
        if(!singleplayer()||MC.player==null||profile==null||!profile.spawned)return false;
        if((text==null||text.isBlank())&&images.isEmpty())return false;
        Runtime run=runtime(profile);cancelRuntime(run,"收到新的用户消息。");
        run.brain.hear(MC.player.getName().getString(),text==null?"":text,true,List.copyOf(images));
        run.active=true;run.nextPlan=0;profile.status="收到消息，正在思考";return true;
    }
	public static boolean acceptsChat() { return singleplayer() && profiles.stream().anyMatch(Profile::spawned); }
	public static void userChat(String text) {
		if (!acceptsChat() || text == null || text.isBlank() || text.stripLeading().startsWith("/")) return;
		String full = clean(text).strip(), speaker = MC.player.getName().getString();
		List<Profile> addressed = new ArrayList<>();
		boolean all = full.equals("@all") || full.startsWith("@all ") || full.startsWith("@all:") || full.startsWith("@all：");
		if (full.startsWith("@") && !all) {
			for (Profile candidate : profiles) if (full.equals("@" + candidate.name) || full.startsWith("@" + candidate.name + " ")
					|| full.startsWith("@" + candidate.name + ":") || full.startsWith("@" + candidate.name + "：")) addressed.add(candidate);
			if (addressed.stream().noneMatch(Profile::spawned)) {
				MC.gui.getChat().addMessage(Component.literal("未找到已召唤的同名 AI 玩家，请先召唤该角色或使用 @all。")); return;
			}
		}
		for (Profile profile : profiles) {
			if (!profile.spawned) continue;
			Runtime run = runtime(profile); boolean respond = addressed.isEmpty() || addressed.contains(profile);
			if (respond) cancelRuntime(run, "收到新的完整用户需求。");
			run.brain.hear(speaker, full, respond);
			if (respond) { run.active = true; run.nextPlan = 0; profile.status = "收到聊天，正在思考"; }
		}
	}
	private static void speak(Profile speaker, String text) { speak(speaker,text,"neutral",.6); }
	private static void speak(Profile speaker, String text,String emotion,double intensity) {
		String complete = clean(text); if (complete.isBlank()) return;
		SpeechEmotion.Utterance marked=SpeechEmotion.parse(complete,speaker.voice.emotionEnabled);
		complete=marked.text();if(complete.isBlank())return;
		if(!marked.emotion().equals("neutral")){emotion=marked.emotion();intensity=marked.intensity();}
		speaker.emotion=SpeechEmotion.normalize(emotion);
		MC.gui.getChat().addMessage(Component.literal("<" + speaker.name + "> " + complete));
		SpeechVoices.speak(speaker.id,speakingVoice(speaker),complete,speaker.emotion,SpeechEmotion.intensity(intensity));
		for (Profile other : profiles) if (other != speaker && other.spawned) runtime(other).brain.hear(speaker.name, complete, false);
	}
	/** Root calls this even when no world exists; leaving cancels brains, model discovery and server bodies. */
	public static void onTick() {
		initialize(); bindSession();
		if (!singleplayer() || MC.isPaused() || MC.getOverlay() != null) return;
		long now = System.nanoTime();
		for (Profile profile : profiles) {
			if (!profile.spawned) continue;
			Runtime run = runtime(profile);
			if (run.waiting || run.brain.busy()) continue;
			if (!run.queue.isEmpty()) { execute(run, run.queue.removeFirst()); continue; }
			if (!(run.active || profile.freePlaying) || now < run.nextPlan || !run.observation.has("self")) continue;
			long generation = run.generation;
			AiConfig shared = sharedConfig();
			AiPlayerBrain.Connection connection = profile.useShared
					? new AiPlayerBrain.Connection(shared.endpoint(), shared.model(), shared.apiKey())
					: new AiPlayerBrain.Connection(profile.endpoint, profile.model, profile.apiKey);
			profile.status = "正在思考";
			run.nextPlan = now + 500_000_000L;
			run.brain.plan(connection, run.observation, TOOLS, decision -> {
				if (!current(run, generation)) return;
				if (!decision.speech().isBlank()) speak(profile, decision.speech(),decision.emotion(),decision.intensity());
				run.queue.addAll(decision.actions());
				if (decision.actions().isEmpty()) { run.active = false; profile.status = profile.freePlaying ? "自由游玩，观察环境" : "待命，可在聊天栏交流"; run.nextPlan = System.nanoTime() + 3_000_000_000L; }
				else { run.active = true; profile.status = "已安排自己的动作"; }
			}, error -> {
				if (!current(run, generation)) return;
				profile.status = error; run.active = false; run.nextPlan = System.nanoTime() + 30_000_000_000L;
			});
		}
	}
	private static void execute(Runtime run, AiPlayerBrain.Action action) {
		long generation = run.generation; Profile profile = run.profile; JsonObject args = action.arguments().deepCopy();
		String kind;
		try { kind = action.function().equals("minecraft_action") && args.has("action") ? args.get("action").getAsString() : ""; }
		catch (RuntimeException ignored) { kind = ""; }
		if (kind.equals("chat")) {
			String text = args.has("text") && !args.get("text").isJsonNull() ? args.get("text").getAsString() : "";
			String emotion=args.has("emotion")&&!args.get("emotion").isJsonNull()?args.get("emotion").getAsString():"neutral";
			double intensity=.6;try{if(args.has("intensity"))intensity=args.get("intensity").getAsDouble();}catch(RuntimeException ignored){}
			speak(profile, text,emotion,intensity); run.brain.feedback(action, !text.isBlank(), "已在 Minecraft 聊天栏发送完整文本；启用的本地语音按实际引擎状态排队，不代表已合成成功。", run.observation); return;
		}
		MinecraftServer server = boundServer;
		if (server == null) { run.brain.feedback(action, false, "单人世界已关闭。", run.observation); return; }
		run.waiting = true; profile.status = "执行自己的动作：" + kind; String requested = kind;
		server.execute(() -> {
			AiActor actor = ACTORS.get(profile.id);
			if (server != boundServer || actor == null || !actor.isAlive() || !DESIRED.containsKey(profile.id)) {
				MC.execute(() -> complete(run, generation, action, false, "AI 人物尚未召唤或已离开世界。", new JsonObject())); return;
			}
			actor.begin(requested, args, result -> {
				JsonObject observation = observation(profile, actor);
				MC.execute(() -> complete(run, generation, action, result.ok(), result.message(), observation));
			});
		});
	}
	private static void complete(Runtime run, long generation, AiPlayerBrain.Action action, boolean ok, String message, JsonObject observation) {
		if (!current(run, generation)) return;
		run.waiting = false; if (observation.has("self")) run.observation = observation;
		run.brain.feedback(action, ok, message, run.observation);
		run.profile.status = message; run.nextPlan = System.nanoTime() + 250_000_000L;
	}
	private static boolean current(Runtime run, long generation) { return run.generation == generation && profile(run.profile.id) == run.profile && run.profile.spawned && DESIRED.containsKey(run.profile.id); }
	private static void cancelRuntime(Runtime run, String reason) {
		SpeechVoices.stop(run.profile.id);
		run.generation++; run.brain.cancel(); run.queue.clear(); run.waiting = false; run.active = false;
		MinecraftServer server = boundServer; if (server != null) server.execute(() -> { AiActor actor = ACTORS.get(run.profile.id); if (actor != null) actor.cancel(reason); });
	}
	private static boolean singleplayer() { return MC.player != null && MC.level != null && MC.getSingleplayerServer() != null; }
	private static void bindSession() {
		MinecraftServer server = singleplayer() ? MC.getSingleplayerServer() : null;
		if (server == boundServer && MC.level == boundLevel) return;
		for(Profile profile:profiles)SpeechVoices.stop(profile.id);
		MinecraftServer previous = boundServer;
		DESIRED.clear();
		for (Runtime run : RUNTIMES.values()) { cancelRuntime(run, "世界已切换。"); run.brain.setFreePlay(false); run.observation = new JsonObject(); }
		for (Profile profile : profiles) { profile.spawned = false; profile.freePlaying = false; profile.hardcoreDead = false; profile.status = "未召唤"; }
		AiPlayerModels.clearAll();
		if (previous != null) previous.execute(() -> removeAllActors(previous));
		boundServer = server; boundLevel = MC.level; ownerId = server == null ? null : MC.player.getUUID();
	}
	/** Vanilla integrated-server tick hook; Fabric API is deliberately not required by this mod. */
	public static void onServerTick(MinecraftServer server) {
		if (server != boundServer || ownerId == null) return;
		ServerPlayer owner = server.getPlayerList().getPlayer(ownerId); if (owner == null) return;
		long tick = ++serverTicks;
		for (UUID id : List.copyOf(ACTORS.keySet())) if (!DESIRED.containsKey(id)) removeActor(server, id);
		for (SpawnSpec spec : DESIRED.values()) {
			AiActor actor = ACTORS.get(spec.id);
			if (actor != null && actor.server != server) { ACTORS.remove(spec.id, actor); actor = null; }
			if (actor != null && (!actor.isAlive() || actor.isRemoved())) {
				boolean permanent = spec.mode.equals("hardcore") && actor.getHealth() <= 0;
				if (permanent) actor.markHardcoreDeath();
				removeActor(server, spec.id);
				if (permanent) DESIRED.remove(spec.id, spec); else RESPAWN_AT.put(spec.id, tick + 80);
				MC.execute(() -> {
					if (server != boundServer || !permanent && !DESIRED.containsKey(spec.id)) return;
					Profile profile = profile(spec.id); if (profile == null) return;
					profile.spawned = false;
					if (permanent && profile.mode.equals("hardcore")) {
						profile.hardcoreDead = true; profile.freePlaying = false; profile.status = "极限角色已死亡，不能以极限模式重新召唤";
					} else profile.status = "死亡，等待在安全位置重生";
					Runtime run = RUNTIMES.get(spec.id); if (run != null) cancelRuntime(run, "AI 人物死亡，旧动作已取消。");
				});
				continue;
			}
			if (actor == null && tick >= RESPAWN_AT.getOrDefault(spec.id, 0L) && owner.isAlive()) actor = createActor(server, owner, spec);
			if (actor == null || tick % 10 != 0) continue;
			Profile selected = profile(spec.id);
			JsonObject observation = observation(selected, actor);
			MC.execute(() -> {
				Profile profile = profile(spec.id); if (profile == null || !DESIRED.containsKey(spec.id) || boundServer != server) return;
				profile.spawned = true; Runtime run = runtime(profile); run.observation = observation;
				if (profile.status.equals("正在寻找安全落点") || profile.status.startsWith("死亡")) profile.status = "已召唤，可在聊天栏交流";
			});
		}
	}
	private static AiActor createActor(MinecraftServer server, ServerPlayer owner, SpawnSpec spec) {
		if (server != boundServer || !DESIRED.containsKey(spec.id)) return null;
		ServerLevel level = owner.serverLevel(); BlockPos ground = null;
		search: for (int radius = 2; radius <= 7; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
			if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
			for (int dy = 2; dy >= -6; dy--) {
				BlockPos candidate = owner.blockPosition().offset(dx, dy, dz);
				if (AiActorNavigation.standable(level, owner, candidate)) { ground = candidate; break search; }
			}
		}
		if (ground == null) {
			DESIRED.remove(spec.id, spec); MC.execute(() -> { Profile value = profile(spec.id); if (server == boundServer && value != null && !DESIRED.containsKey(spec.id)) { value.status = "附近没有安全落点，请换个位置后重试"; value.freePlaying = false; } }); return null;
		}
		AiActor actor = null;
		boolean registered = false;
		try {
			actor = new AiActor(server, level, new GameProfile(spec.id, "AI_" + spec.id.toString().replace("-", "").substring(0, 12)), spec.name);
			// PlayerList.remove/save retains this UUID's real world-specific inventory between recalls.
			server.getPlayerList().load(actor);
			actor.setServerLevel(level); actor.rename(spec.name);
			if (spec.mode.equals("hardcore") && actor.hardcoreDied()) {
				actor.discard(); DESIRED.remove(spec.id, spec);
				MC.execute(() -> {
					Profile value = profile(spec.id);
					if (server != boundServer || value == null || !value.mode.equals("hardcore")) return;
					value.hardcoreDead = true; value.spawned = false; value.freePlaying = false;
					value.status = "极限角色已在该世界死亡，切换其他模式后可重新召唤";
					Runtime run = RUNTIMES.get(spec.id); if (run != null) cancelRuntime(run, "极限角色已死亡。");
				});
				return null;
			}
			if (!spec.mode.equals("hardcore")) actor.clearHardcoreDeath();
			if (actor.getHealth() <= 0) { actor.setHealth(actor.getMaxHealth()); actor.getFoodData().setFoodLevel(20); actor.removeAllEffects(); }
			actor.moveTo(ground.getX()+.5,ground.getY(),ground.getZ()+.5,owner.getYRot(),0);
			actor.initInventoryMenu(); actor.setCustomName(Component.literal(spec.name)); actor.setCustomNameVisible(true);
			ACTORS.put(spec.id, actor);
			server.getPlayerList().getPlayers().add(actor); registered = true;
			((AiPlayerListAccessor) server.getPlayerList()).cheatprogram$playersByUuid().put(spec.id, actor);
			// ADD_PLAYER must precede both the tracked entity and any UPDATE_GAME_MODE packet.
			server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(actor)));
			level.addNewPlayer(actor);
			actor.gameMode.changeGameModeForPlayer(gameType(spec.mode));
			RESPAWN_AT.remove(spec.id); return actor;
		} catch (RuntimeException failure) {
			DESIRED.remove(spec.id, spec);
			try {
				if (registered) removeActor(server, spec.id);
				else if (actor != null) { ACTORS.remove(spec.id, actor); actor.discard(); }
			} catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
			CheatProgram.LOGGER.error("AI player {} could not be spawned", spec.id, failure);
			MC.execute(() -> {
				Profile value = profile(spec.id);
				if (server != boundServer || value == null || DESIRED.containsKey(spec.id)) return;
				value.spawned = false; value.freePlaying = false; value.status = "AI 玩家召唤失败，已清理角色；请查看游戏日志";
				Runtime run = RUNTIMES.get(spec.id); if (run != null) cancelRuntime(run, "召唤失败，已停止。");
			});
			return null;
		}
	}
	private static void removeActor(MinecraftServer server, UUID id) {
		AiActor actor = ACTORS.get(id); if (actor == null || actor.server != server || !ACTORS.remove(id, actor)) return;
		RESPAWN_AT.remove(id);
		actor.cancel("AI 玩家已收回。"); server.getPlayerList().remove(actor);
	}
	private static void removeAllActors(MinecraftServer server) { for (UUID id : List.copyOf(ACTORS.keySet())) removeActor(server, id); if (server == boundServer) RESPAWN_AT.clear(); }
	public static void onServerStopping(MinecraftServer server) { removeAllActors(server); }

	public static void save() {
		if(!com.cheatprogram.core.Config.persistenceAllowed())return;
		initialize(); sharedConfig().save();
		JsonArray data = new JsonArray();
		for (Profile profile : profiles) {
			CREDENTIALS.put(credentialSlot(profile.id), profile.endpoint, profile.apiKey);
			JsonObject value = new JsonObject(); value.addProperty("id", profile.id.toString()); value.addProperty("name", profile.name);
			value.addProperty("persona", profile.persona); value.addProperty("useShared", profile.useShared);
			value.addProperty("personality", profile.personality); value.addProperty("gender", profile.gender); value.addProperty("customGender", profile.customGender);
			value.addProperty("mode", profile.mode);
			value.addProperty("language",profile.language);
			value.add("voice",profile.voice.save());
			value.addProperty("endpoint", profile.endpoint); value.addProperty("model", profile.model);
			value.addProperty("skin", profile.id + ".png"); data.add(value); // No credential field enters the saved JSON.
		}
		String serialized = data.toString();
		FILES.execute(() -> {
			synchronized(com.cheatprogram.core.Config.persistenceLock()) {
			if(!com.cheatprogram.core.Config.persistenceAllowed())return;
			try {
				Path target = directory().resolve("players.json"); Files.createDirectories(target.getParent());
				Path temporary = target.resolveSibling("players.json.tmp"); Files.writeString(temporary, serialized, StandardCharsets.UTF_8);
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ignored) { }
			}
		});
	}
	/** Client-thread stop; server-owned actors are removed on their own thread. */
	public static void clearMemoryForInitialization(){
		for(Profile profile:List.copyOf(profiles))despawn(profile.id);
		for(Runtime runtime:List.copyOf(RUNTIMES.values()))runtime.brain.close();
		RUNTIMES.clear();DESIRED.clear();RESPAWN_AT.clear();
		for(Profile profile:profiles)profile.apiKey="";
		profiles=List.of();initialized=true;
	}
	private static void load() {
		try {
			Path file = directory().resolve("players.json"); if (!Files.isRegularFile(file)) return;
			ArrayList<Profile> found = new ArrayList<>();
			for (JsonElement element : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
				try {
					JsonObject value = element.getAsJsonObject(); UUID id = UUID.fromString(value.get("id").getAsString());
					if (found.stream().anyMatch(existing -> existing.id.equals(id))) continue;
					Profile profile = new Profile(id, string(value, "name", "AI 玩家"), string(value, "persona", ""), string(value, "endpoint", "https://api.openai.com/v1"), string(value, "model", "gpt-4.1-mini"));
					profile.apiKey = CREDENTIALS.read(credentialSlot(id), profile.endpoint);
					profile.useShared = !value.has("useShared") || value.get("useShared").getAsBoolean(); found.add(profile);
					profile.mode = normalizeMode(string(value, "mode", "survival"));
					profile.language=normalizeLanguage(string(value,"language",DeviceLanguage.system()));
					if(value.has("voice")&&value.get("voice").isJsonObject())profile.voice=VoiceProfile.load(value.getAsJsonObject("voice"));
					profile.personality = string(value, "personality", ""); profile.gender = normalizeGender(string(value, "gender", "none"));
					profile.customGender = string(value, "customGender", "");
					AiPlayerSkins.load(id, skinFile(id)).thenAccept(texture -> MC.execute(() -> { Profile current = profile(id); if (current == profile) current.skinTexture = texture; }));
				} catch (RuntimeException ignored) { }
			}
			profiles = List.copyOf(found);
		} catch (Exception ignored) { }
	}
	private static Path directory() { return FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open").resolve("ai_players"); }
	private static Path skinFile(UUID id) { return directory().resolve("skins").resolve(id + ".png"); }
	private static String string(JsonObject value, String key, String fallback) {
		try{return value.has(key)&&value.get(key).isJsonPrimitive()?clean(value.get(key).getAsString()):fallback;}
		catch(RuntimeException invalid){return fallback;}
	}
	private static String clean(String value) { return value == null ? "" : value.replace("\u0000", ""); }
	private static String singleLine(String value) { return clean(value).replace("\r", "").replace("\n", "").strip(); }
	private static String normalizeMode(String value) {
		return switch (singleLine(value)) {
			case "creative", "adventure", "spectator", "hardcore" -> singleLine(value);
			default -> "survival";
		};
	}
	static String normalizeGender(String value) {
		return switch (singleLine(value)) {
			case "female", "male", "trans_female", "trans_male", "custom" -> singleLine(value);
			default -> "none";
		};
	}
	private static GameType gameType(String mode) {
		return switch (mode) {
			case "creative" -> GameType.CREATIVE;
			case "adventure" -> GameType.ADVENTURE;
			case "spectator" -> GameType.SPECTATOR;
			default -> GameType.SURVIVAL;
		};
	}
	private static JsonObject observation(Profile profile, AiActor actor) {
		JsonObject data = actor.observation();
		if (profile != null) {
			JsonObject self = data.getAsJsonObject("self");
			self.addProperty("configuredMode", profile.mode);
			self.addProperty("hardcore", profile.mode.equals("hardcore"));
			self.addProperty("autoRespawn", !profile.mode.equals("hardcore"));
		}
		return data;
	}
	private static JsonArray tools() {
		JsonObject properties = new JsonObject(); JsonObject action = property("string", "只操控自己的 AI 人物。observe观察，look转向，move普通移动，walk_to多段安全寻路，follow跟随指定实体，mine原版挖掘，place主手放块，use_item使用/打开箱子，attack原版攻击，pickup走近捡起真实掉落物，select_hotbar选择自身快捷栏，container_click操作自己的已打开容器，close_container关自己的容器，chat聊天，stop停止动作。");
		JsonArray actions = new JsonArray(); for (String name : List.of("observe","look","move","walk_to","follow","mine","place","use_item","attack","pickup","select_hotbar","container_click","close_container","chat","stop")) actions.add(name);
		action.add("enum", actions); properties.add("action", action);
		for (String coordinate : List.of("x","y","z")) properties.add(coordinate, property("number", "绝对世界坐标；mine/place 使用方块坐标；look/walk_to 使用世界位置。"));
		properties.add("entityId", property("integer", "观察中真实实体的 ID；follow/attack/pickup 必需。"));
		properties.add("forward", property("number", "移动前后 -1 到 1。")); properties.add("strafe", property("number", "左右 -1 到 1。"));
		properties.add("ticks", property("integer", "本次移动/跟随/持续用物时间，1 至 1200 ticks，20 ticks 为一秒；可以多次调用持续，整体任务无轮数限制。"));
		for (String flag : List.of("jump","sneak","sprint")) properties.add(flag, property("boolean", "移动时是否启用 " + flag));
		properties.add("distance", property("number", "跟随保持距离 1 至 8 格，默认 2.5。"));
		properties.add("hotbar", property("integer", "自己的快捷栏 1 至 9 槽位，不会创建物品。"));
		properties.add("hand", property("string", "使用 main 主手或 off 副手。"));
		properties.add("slot", property("integer", "观察 container.slots 中真实槽位编号。")); properties.add("button", property("integer", "容器点击 0 左键或 1 右键。"));
		properties.add("click", property("string", "quick_move 移动物品或 pickup 普通点击。")); properties.add("text", property("string", "chat 要在 Minecraft 聊天栏完整显示的消息。"));
		JsonObject emotion=property("string","chat可选语音情绪元数据，不改变聊天内容。" );
		JsonArray emotions=new JsonArray();for(String mood:SpeechEmotion.VALUES)emotions.add(mood);emotion.add("enum",emotions);properties.add("emotion",emotion);
		properties.add("intensity",property("number","chat可选情绪强度0到1，仅用于本地语音。"));
		JsonObject parameters = new JsonObject(); parameters.addProperty("type", "object"); parameters.add("properties", properties); parameters.addProperty("additionalProperties", false);
		JsonArray required = new JsonArray(); required.add("action"); parameters.add("required", required);
		JsonObject function = new JsonObject(); function.addProperty("name", "minecraft_action"); function.addProperty("description", "通过真实 integrated ServerPlayer 执行自己的游戏动作，不操作原玩家、Freecam 或其他机器人的身体。参数来自观察，不编造物品或坐标。"); function.add("parameters", parameters); function.addProperty("strict", false);
		JsonObject tool = new JsonObject(); tool.addProperty("type", "function"); tool.add("function", function); JsonArray tools = new JsonArray(); tools.add(tool); return tools;
	}
	private static JsonObject property(String type, String description) { JsonObject value = new JsonObject(); value.addProperty("type", type); value.addProperty("description", description); return value; }
}
