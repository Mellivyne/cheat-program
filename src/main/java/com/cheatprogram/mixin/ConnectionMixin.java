package com.cheatprogram.mixin;

import com.cheatprogram.core.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
	@ModifyVariable(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",at=@At("HEAD"),argsOnly=true,ordinal=0)
	private Packet<?> cheatprogram$protectDescendingMovement(Packet<?> packet){
		ClientPacketListener connection=Minecraft.getInstance().getConnection();
		return connection!=null&&connection.getConnection()==(Object)this
				?com.cheatprogram.util.FallProtection.movementPacket(packet):packet;
	}
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$send(Packet<?> packet, PacketSendListener listener, boolean flush, CallbackInfo ci) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null || connection.getConnection() != (Object) this) return;
		if (ModuleManager.onPacketSend(packet)) ci.cancel();
	}
}
