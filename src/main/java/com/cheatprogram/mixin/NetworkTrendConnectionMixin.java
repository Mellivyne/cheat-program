package com.cheatprogram.mixin;

import com.cheatprogram.util.NetworkTelemetrySource;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(Connection.class)
public abstract class NetworkTrendConnectionMixin implements NetworkTelemetrySource {
    @Unique private final AtomicLong cheatprogram$lastArrival = new AtomicLong();
    @Unique private final AtomicLong cheatprogram$maxArrivalGap = new AtomicLong();
    @Unique private final AtomicLong cheatprogram$arrivalCount = new AtomicLong();
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void cheatprogram$networkPacketArrival(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        long now = System.nanoTime(), previous = cheatprogram$lastArrival.getAndSet(now);
        if (previous != 0) cheatprogram$maxArrivalGap.accumulateAndGet(Math.max(0, now - previous), Math::max);
        cheatprogram$arrivalCount.incrementAndGet();
    }
    @Override public Arrival cheatprogram$takeNetworkArrival() {
        return new Arrival(cheatprogram$arrivalCount.get(), cheatprogram$lastArrival.get(), cheatprogram$maxArrivalGap.getAndSet(0));
    }
}
