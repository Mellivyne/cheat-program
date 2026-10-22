package com.cheatprogram;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.util.CapeTextures;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheatProgram implements ClientModInitializer {
	public static final String MOD_ID = "cheatprogram";
	public static final Logger LOGGER = LoggerFactory.getLogger("Cheat Program");

	@Override
	public void onInitializeClient() {
		ClientSettings.INSTANCE = new ClientSettings();
		ModuleManager.init();
		Config.loadAll();
		CapeTextures.initialize();
		Runtime.getRuntime().addShutdownHook(new Thread(Config::saveAll, "Cheat Program config save"));
		LOGGER.info("Cheat Program loaded {} modules", ModuleManager.all().size());
	}
}
