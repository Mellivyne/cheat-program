package com.cheatprogram.mixin;

import com.cheatprogram.util.ChatHistoryStore;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;

import java.time.Instant;

@Mixin(ChatListener.class)
public abstract class ChatHistoryListenerMixin {
	@WrapMethod(method = "showMessageToPlayer")
	private boolean cheatprogram$archiveAcceptedPlayerMessage(ChatType.Bound type, PlayerChatMessage message, Component decorated,
			GameProfile profile, boolean secureOnly, Instant receivedAt, Operation<Boolean> original) {
		ChatHistoryStore.beginPlayerMessage();
		try {
			boolean displayed = original.call(type, message, decorated, profile, secureOnly, receivedAt);
			if (displayed) ChatHistoryStore.capturePlayer(message, profile, type, receivedAt);
			return displayed;
		} finally { ChatHistoryStore.endPlayerMessage(); }
	}
}
