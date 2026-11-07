package com.replaymod.recording.mixin;

import com.replaymod.recording.packet.PacketListener;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class MixinClientConnection {
    @Shadow
    private Channel channel;

    @Inject(method = "channelActive", at = @At("RETURN"))
    private void captureLoginBeforeDecoding(ChannelHandlerContext ctx, CallbackInfo ci) {
        ClientConnection connection = (ClientConnection) (Object) this;
        if (connection.getSide() == NetworkSide.CLIENTBOUND) {
            PacketListener.installLoginCapture(connection, ctx);
        }
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
            at = @At("HEAD"))
    private void identifyCapturedLoginPacket(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (((ClientConnection) (Object) this).getSide() == NetworkSide.CLIENTBOUND) {
            PacketListener.observeLoginPacket(ctx.channel(), packet);
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void releaseLoginCapture(ChannelHandlerContext ctx, CallbackInfo ci) {
        if (((ClientConnection) (Object) this).getSide() == NetworkSide.CLIENTBOUND) {
            PacketListener.closeLoginCapture(ctx.channel(), null);
        }
    }

    @Inject(method = "exceptionCaught", at = @At("HEAD"))
    private void failLoginCapture(ChannelHandlerContext ctx, Throwable failure, CallbackInfo ci) {
        if (((ClientConnection) (Object) this).getSide() == NetworkSide.CLIENTBOUND) {
            PacketListener.closeLoginCapture(ctx.channel(), failure);
        }
    }

    @Inject(method = "setCompressionThreshold", at = @At("RETURN"))
    private void ensureReplayModRecorderIsAfterDecompress(CallbackInfo ci) {
        if (((ClientConnection) (Object) this).getSide() == NetworkSide.CLIENTBOUND) {
            PacketListener.repositionRecorders(channel);
        }
    }
}
