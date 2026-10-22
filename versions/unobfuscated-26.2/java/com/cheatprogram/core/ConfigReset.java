package com.cheatprogram.core;

import com.cheatprogram.ai.AiConfig;
import com.cheatprogram.ai.AiController;
import com.cheatprogram.ai.AiPlayers;
import com.cheatprogram.ai.BotPlayers;
import com.cheatprogram.ai.CredentialStore;
import com.cheatprogram.ai.voice.AiVoiceControl;
import com.cheatprogram.ai.voice.VoiceEnvironment;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.render.ControlPanelTextures;
import com.cheatprogram.render.WorldControlPanel;
import com.cheatprogram.util.CapeTextures;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicit, reversible initialization. All native filesystem operations stay on a dedicated worker. */
public final class ConfigReset {
    public enum State { READY, VALIDATING, STOPPING, RECYCLING, COMPLETE, FAILED }
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Cheat Program initialization"); thread.setDaemon(true); return thread;
    });
    private static volatile State state = State.READY;
    private static volatile String status = "ui.reset.ready";
    private ConfigReset() { }
    public static State state() { return state; }
    public static String statusKey() { return status; }
    public static boolean busy() { return state == State.VALIDATING || state == State.STOPPING || state == State.RECYCLING; }
    public static Path directory() { return Config.directory().toAbsolutePath().normalize(); }

    /** Only an explicit reset-screen confirmation calls this; callers cannot supply another directory. */
    public static synchronized void initialize() {
        if (busy() || state == State.COMPLETE) return;
        state = State.VALIDATING; status = "ui.reset.validating";
        WORKER.execute(() -> {
            try {
                Path selected = WindowsRecycleBin.validatedDirectory();
                Config.freezeForInitialization(); // Waits for writers already inside their shared IO barrier.
                state = State.STOPPING; status = "ui.reset.stopping";
                CompletableFuture<Void> stopped = new CompletableFuture<>();
                MC.execute(() -> stopOnClient(stopped));
                stopped.get(8, TimeUnit.SECONDS);
                VoiceEnvironment.stopForReset().get(4, TimeUnit.SECONDS);
                state = State.RECYCLING; status = "ui.reset.recycling";
                synchronized (Config.persistenceLock()) { WindowsRecycleBin.recycleValidatedDirectory(selected); }
                MC.execute(() -> {
                    Config.resetOptionsInMemory(); state = State.COMPLETE; status = "ui.reset.complete";
                    Notifications.push(Lang.t("ui.reset.title"), Lang.t("ui.reset.restartReminder"), true);
                });
            } catch (Exception | LinkageError failure) {
                String key = failure.getMessage();
                status = key != null && key.startsWith("ui.reset.") ? key : "ui.reset.recycleFailed";
                state = State.FAILED;
            }
        });
    }
    private static void stopOnClient(CompletableFuture<Void> done) {
        try {
            if(MC.gui.screen()!=null&&(MC.gui.screen().getClass().getSimpleName().equals("WorldPanelInputScreen")
                    ||MC.gui.screen().getClass().getSimpleName().equals("AppCastInputScreen")))MC.gui.setScreen(null);
            AiVoiceControl.shutdown(); AiController.stop();
            WorldControlPanel.shutdown(); AppCast.shutdown();
            com.cheatprogram.module.visual.FloatingSubtitles.shutdown();
            for (Module module : ModuleManager.all()) module.setEnabled(false);
            AiPlayers.clearMemoryForInitialization(); AiConfig.clearMemoryForInitialization(); CredentialStore.clearMemoryForInitialization();
            BotPlayers.removeAll(BotPlayers.Group.NORMAL); BotPlayers.removeAll(BotPlayers.Group.TRAINING);
            CapeTextures.stopForInitialization(); ControlPanelTextures.release();
            com.cheatprogram.media.MusicService.shutdown();
            done.complete(null);
        } catch (Throwable failure) { done.completeExceptionally(failure); }
    }
}
