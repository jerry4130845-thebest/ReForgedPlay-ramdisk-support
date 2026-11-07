//#if MC>=10800
package com.replaymod.replay;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.replaymod.core.mixin.MinecraftAccessor;
import com.replaymod.core.mixin.TimerAccessor;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.rar.RandomAccessReplay;
import de.johni0702.minecraft.gui.utils.EventRegistrations;
import de.johni0702.minecraft.gui.versions.callbacks.PreTickCallback;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.NetworkState;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.ClientConnection;
import com.replaymod.core.utils.Restrictions;
import com.replaymod.core.versions.MCVer;
import com.replaymod.replaystudio.io.ReplayInputStream;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.PacketByteBuf;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.function.Consumer;

//#if MC>=11200
import com.replaymod.core.utils.WrappedTimer;
//#endif

import static com.replaymod.core.versions.MCVer.asMc;
import static com.replaymod.core.versions.MCVer.getMinecraft;
import static com.replaymod.core.versions.MCVer.getPacketTypeRegistry;
import static com.replaymod.replay.ReplayModReplay.LOGGER;

/**
 * Sends only chunk updates and entity position updates but tries to do so as quickly as possible.
 * To do so, it performs an initial analysis of the replay, scanning all of its packets and storing entity positions
 * and chunk states while doing so.
 * This allows it to later jump to any time by doing a diff from the current time (including backwards jumping).
 */
@ChannelHandler.Sharable
public class QuickReplaySender extends ChannelHandlerAdapter implements ReplaySender {
    private final MinecraftClient mc = getMinecraft();

    private final ReplayModReplay mod;
    private final RandomAccessReplay replay;
    private final EventHandler eventHandler = new EventHandler();
    private ChannelHandlerContext ctx;

    private int currentTimeStamp;
    private double replaySpeed = 1;

    /**
     * Whether async mode is enabled.
     * Async mode is emulated by registering an event handler on client tick.
     */
    private boolean asyncMode;
    private long lastAsyncUpdateTime;

    private ListenableFuture<Void> initPromise;

    private final ReplayFile replayFile;
    private final FullReplaySender fullReplaySender;

    public QuickReplaySender(ReplayModReplay mod, ReplayFile replayFile, FullReplaySender fullReplaySender) {
        this.mod = mod;
        this.replayFile = replayFile;
        this.fullReplaySender = fullReplaySender;
        this.replay = new RandomAccessReplay(replayFile, getPacketTypeRegistry(State.PLAY)) {
            @Override
            protected void dispatch(com.replaymod.replaystudio.protocol.Packet packet) {
                // PacketStateTree retains its cached packet before dispatch; synthesized packets
                // also transfer ownership to the sink. Release that reference on EVERY exit.
                try {
                    if (!mc.isOnThread()) {
                        throw new IllegalStateException("Quick replay dispatch must run on the client thread");
                    }
                    if (fullReplaySender.isReplayStopped()) {
                        throw new IllegalStateException("Replay has stopped");
                    }
                    synchronized (ctx.channel()) {
                        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
                        try {
                            NetworkState<?> state = ctx.pipeline().get(ClientConnection.class).getInboundProtocol();
                            if (!ctx.channel().config().isAutoRead() || state.id() != asMc(packet.getRegistry().getState())) {
                                throw new IllegalStateException("Quick replay protocol mismatch: "
                                        + packet.getRegistry().getState() + " / " + state.id());
                            }
                            buffer.writeVarInt(packet.getId());
                            buffer.writeBytes(packet.getBuf().nioBuffer());
                            Packet<?> mcPacket = state.codec().decode(buffer);
                            if (buffer.isReadable()) throw new IllegalArgumentException("Unread quick replay packet bytes");
                            // RAR emits Reconfigure -> registries/known packs -> ConfigFinish.
                            // Vanilla handles these synchronously here on the client thread.
                            ctx.fireChannelRead(mcPacket);
                            if (fullReplaySender.isReplayStopped() || !ctx.channel().isOpen()) {
                                throw new IllegalStateException("Quick replay packet was rejected");
                            }
                        } finally {
                            buffer.release();
                        }
                    }
                } finally {
                    packet.release();
                }
            }
        };
    }

    /** RAR intentionally omits arbitrary payloads. Validate the source, even on a cache hit,
     * so seeking past a future restriction cannot bypass it. No registry data is needed to
     * read the custom-payload channel and the restriction's raw bytes. */
    static final class RestrictionScanner {
        private final Restrictions restrictions = new Restrictions();
        private final int playId;
        private final int configId;
        private java.io.ByteArrayOutputStream split;
        private State splitState;

        RestrictionScanner() {
            var sample = new CustomPayloadS2CPacket(new Restrictions.Payload(new byte[0]));
            playId = MCVer.getPacketId(NetworkPhase.PLAY, NetworkSide.CLIENTBOUND, sample);
            configId = MCVer.getPacketId(NetworkPhase.CONFIGURATION, NetworkSide.CLIENTBOUND, sample);
        }

        void accept(State state, int id, PacketByteBuf buffer, boolean allowSplit) throws IOException {
            if (!(state == State.PLAY && id == playId || state == State.CONFIGURATION && id == configId)) return;
            var channel = buffer.readIdentifier();
            if (Restrictions.PLUGIN_CHANNEL.equals(channel)) {
                if (state != State.PLAY) throw new IOException("Restriction payload outside PLAY");
                String unknown = restrictions.handle(new CustomPayloadS2CPacket(Restrictions.Payload.CODEC.decode(buffer)));
                if (unknown != null) throw new IOException("Unsupported or malformed replay restriction: " + unknown);
            } else if (net.neoforged.neoforge.network.payload.SplitPacketPayload.TYPE.id().equals(channel)) {
                // RAR discards arbitrary custom payloads, including NeoForge's split transport.
                // Check its reconstructed packet before enabling quick mode, even on a cache hit.
                if (!allowSplit) throw new IOException("Nested NeoForge split packet");
                byte[] payload = net.neoforged.neoforge.network.payload.SplitPacketPayload.STREAM_CODEC.decode(buffer).payload();
                if (payload.length == 0 || buffer.isReadable()) throw new IOException("Malformed NeoForge split payload");
                int flag = payload[0] & 255;
                if (flag == 1) {
                    if (split != null) throw new IOException("Interrupted NeoForge split packet");
                    split = new java.io.ByteArrayOutputStream();
                    splitState = state;
                } else if ((flag != 0 && flag != 2) || split == null || state != splitState) {
                    throw new IOException("Out-of-order NeoForge split packet");
                }
                split.write(payload, 1, payload.length - 1);
                if (flag == 2) {
                    PacketByteBuf complete = new PacketByteBuf(Unpooled.wrappedBuffer(split.toByteArray()));
                    split = null;
                    try {
                        accept(state, complete.readVarInt(), complete, false);
                    } finally {
                        complete.release();
                    }
                }
            }
        }

        void finish() throws IOException {
            if (split != null) throw new IOException("Incomplete NeoForge split packet at end of replay");
        }
    }

    private void validateRestrictions() throws IOException {
        RestrictionScanner scanner = new RestrictionScanner();
        try (ReplayInputStream input = replayFile.getPacketData(getPacketTypeRegistry(State.LOGIN))) {
            com.replaymod.replaystudio.PacketData data;
            while ((data = input.readPacket()) != null) {
                com.replaymod.replaystudio.protocol.Packet packet = data.getPacket();
                try {
                    State state = packet.getRegistry().getState();
                    PacketByteBuf buffer = new PacketByteBuf(Unpooled.wrappedBuffer(packet.getBuf().nioBuffer()));
                    try {
                        scanner.accept(state, packet.getId(), buffer, true);
                    } finally {
                        buffer.release();
                    }
                } finally {
                    packet.release();
                }
            }
            scanner.finish();
        } catch (IOException | RuntimeException failure) {
            fullReplaySender.failReplay(failure);
            throw failure;
        }
    }

    public void register() {
        eventHandler.register();
    }

    public void unregister() {
        eventHandler.unregister();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    public ListenableFuture<Void> getInitializationPromise() {
        return initPromise;
    }

    public ListenableFuture<Void> initialize(Consumer<Double> progress) {
        if (initPromise != null) {
            return initPromise;
        }
        SettableFuture<Void> promise = SettableFuture.create();
        initPromise = promise;
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                validateRestrictions();
                replay.load(progress);
                LOGGER.info("Initialized quick replay sender in " + (System.currentTimeMillis() - start) + "ms");
            } catch (Throwable e) {
                LOGGER.error("Initializing quick replay sender:", e);
                mod.getCore().runLaterWithoutLock(() -> {
                    mod.getCore().printWarningToChat("Error initializing quick replay sender: %s", e.getLocalizedMessage());
                    promise.setException(e);
                });
                return;
            }
            mod.getCore().runLaterWithoutLock(() -> promise.set(null));
        }).start();
        return promise;
    }

    private void ensureInitialized(Runnable body) {
        if (initPromise == null) {
            LOGGER.warn("QuickReplaySender used without prior initialization!", new Throwable());
            initialize(progress -> {});
        }
        Futures.addCallback(initPromise, new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                if (!fullReplaySender.isReplayStopped()) body.run();
            }

            @Override
            public void onFailure(Throwable t) {
                // Error already printed by initialize method
            }
        }, Runnable::run);
    }

    void release() {
        asyncMode = false;
        // Initialization completes its promise on the client thread, after load has stopped.
        // Do not race release against the background cache loader or block the client on it.
        if (initPromise != null) initPromise.addListener(replay::release, Runnable::run);
    }

    public void restart() {
        if (!mc.isOnThread()) throw new IllegalStateException("Quick replay reset must run on the client thread");
        replay.reset();
        currentTimeStamp = 0;
    }

    @Override
    public int currentTimeStamp() {
        return currentTimeStamp;
    }

    @Override
    public void setReplaySpeed(double factor) {
        if (factor != 0) {
            if (paused() && asyncMode) {
                lastAsyncUpdateTime = System.currentTimeMillis();
            }
            this.replaySpeed = factor;
        }
        TimerAccessor timer = (TimerAccessor) ((MinecraftAccessor) mc).getTimer();
        //#if MC>=11200
        timer.setTickLength(WrappedTimer.DEFAULT_MS_PER_TICK / (float) factor);
        //#else
        //$$ timer.setTimerSpeed((float) factor);
        //#endif
    }

    @Override
    public double getReplaySpeed() {
        return replaySpeed;
    }

    @Override
    public boolean isAsyncMode() {
        return asyncMode;
    }

    @Override
    public void setAsyncMode(boolean async) {
        if (this.asyncMode == async) return;
        ensureInitialized(() -> {
            this.asyncMode = async;
            if (async) {
                lastAsyncUpdateTime = System.currentTimeMillis();
            }
        });
    }

    @Override
    public void setSyncModeAndWait() {
        setAsyncMode(false);
        // No waiting required, we emulated async mode via tick events
    }

    @Override
    public void jumpToTime(int value) {
        sendPacketsTill(value);
    }

    private class EventHandler extends EventRegistrations {
        { on(PreTickCallback.EVENT, this::onTick); }
        private void onTick() {
            if (!asyncMode || paused()) return;

            long now = System.currentTimeMillis();
            long realTimePassed = now - lastAsyncUpdateTime;
            lastAsyncUpdateTime = now;
            int replayTimePassed = (int) (realTimePassed * replaySpeed);
            sendPacketsTill(currentTimeStamp + replayTimePassed);
        }
    }

    @Override
    public void sendPacketsTill(int replayTime) {
        ensureInitialized(() -> {
            if (fullReplaySender.isReplayStopped()) return;
            try {
                if (!mc.isOnThread()) throw new IllegalStateException("Quick replay seek must run on the client thread");
                replay.seek(replayTime);
                currentTimeStamp = replayTime;
            } catch (IOException | RuntimeException failure) {
                fullReplaySender.failReplay(failure);
            }
        });
    }
}
//#endif
