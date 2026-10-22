package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.util.CapeTextures;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;

/** Local cosmetic only: neither player skin nor elytra texture is modified. */
public final class CustomCape extends Module {
	private record Original(PlayerSkin skin, boolean capeShown) { }
	private static volatile Original original;
	public CustomCape() { super("customcape", Category.VISUAL); }
	@Override public void onEnable() { CapeTextures.initialize(); }
	@Override public void onTick() { CapeTextures.initialize(); }

	public static boolean appliesTo(AbstractClientPlayer player) {
		return mc.player != null && player == mc.player && player.getUUID().equals(mc.player.getUUID())
				&& ModuleManager.on(CustomCape.class) && CapeTextures.currentTexture() != null;
	}

	public static PlayerSkin skin(AbstractClientPlayer player, PlayerSkin skin) {
		if (!appliesTo(player)) return skin;
		original = new Original(skin, player.isModelPartShown(PlayerModelPart.CAPE));
		return new PlayerSkin(skin.body(), new net.minecraft.core.ClientAsset.ResourceTexture(CapeTextures.currentTexture(), CapeTextures.currentTexture()), skin.elytra(), skin.model(), skin.secure());
	}

	/** Preserve WingsLayer's original cape fallback, including the player's original CAPE visibility preference. */
	public static ResourceLocation originalWingsTexture() {
		Original saved = original;
		if (saved == null) return null;
		return saved.skin().elytra() != null ? saved.skin().elytra().texturePath() : saved.capeShown() && saved.skin().cape() != null ? saved.skin().cape().texturePath() : null;
	}
}
