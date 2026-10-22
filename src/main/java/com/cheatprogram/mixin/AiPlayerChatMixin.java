package com.cheatprogram.mixin;

import com.cheatprogram.ai.AiPlayers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class AiPlayerChatMixin {
	@Shadow protected EditBox input;
	@Inject(method = "init", at = @At("TAIL"))
	private void cheatprogram$fullAiChat(CallbackInfo info) {
		if (AiPlayers.acceptsChat()) input.setMaxLength(Integer.MAX_VALUE);
	}
	@Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$talkToAiPlayers(String inputText, boolean addHistory, CallbackInfo info) {
		String text = inputText == null ? "" : inputText.strip();
		if (text.isEmpty() || text.startsWith("/") || !AiPlayers.acceptsChat()) return;
		AiPlayers.userChat(text);
		if (text.length() > 256) {
			Minecraft client = Minecraft.getInstance();
			if (addHistory) client.gui.getChat().addRecentChat(text);
			client.gui.getChat().addMessage(Component.literal("<" + client.player.getName().getString() + "> " + text));
			info.cancel(); // Long singleplayer messages stay local instead of entering a 256-char network packet.
		}
	}
}
