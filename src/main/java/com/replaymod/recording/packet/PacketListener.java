package com.replaymod.recording.packet;

import com.github.steveice10.netty.buffer.PooledByteBufAllocator;
import com.github.steveice10.packetlib.tcp.io.ByteBufNetOutput;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.files.ManagedReplayFile;
import com.replaymod.core.utils.Restrictions;
import com.replaymod.core.utils.Utils;
import com.replaymod.core.versions.MCVer;
import com.replaymod.editor.gui.MarkerProcessor;
import com.replaymod.recording.ReplayModRecording;
import com.replaymod.recording.Setting;
import com.replaymod.recording.gui.GuiSavingReplay;
import com.replaymod.recording.handler.ConnectionEventHandler;
import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.data.Marker;
import com.replaymod.replaystudio.io.ReplayOutputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import de.johni0702.minecraft.gui.container.VanillaGuiScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.entity.Entity;
import net.minecraft.network.NetworkState;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.state.LoginStates;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.PlainTextContent;
import net.minecraft.util.crash.CrashReport;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//#if MC>=12002
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
//#else
//$$ import net.minecraft.network.packet.s2c.play.PlayerSpawnS2CPacket;
//#endif

//#if MC>=10800
//#if MC<10904
//$$ import net.minecraft.network.play.server.S46PacketSetCompressionLevel;
//#endif
import net.minecraft.network.packet.s2c.login.LoginCompressionS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.NetworkSide;
//#endif

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.replaymod.core.versions.MCVer.*;
import static com.replaymod.replaystudio.util.Utils.writeInt;
import static java.util.Objects.requireNonNull;

//#if MC>=11904
import net.minecraft.network.handler.PacketBundleHandler;
import java.util.ArrayList;
//#endif

@ChannelHandler.Sharable // so we can re-order it
public class PacketListener extends ChannelInboundHandlerAdapter {

    public static final String RAW_RECORDER_KEY = "replay_recorder_raw";
    public static final String DECODED_RECORDER_KEY = "replay_recorder_decoded";

    public static final String DECOMPRESS_KEY = "decompress";
    public static final String DECODER_KEY = "decoder";

    private static final MinecraftClient mc = getMinecraft();
    private static final Logger logger = LogManager.getLogger();

    private static final ResourcePackSendS2CPacket RESOURCE_PACK_SEND_PACKET =
            //#if MC>=12003
            new ResourcePackSendS2CPacket(null, "", "", false, null)
            //#elseif MC>=11700
            //$$ new ResourcePackSendS2CPacket("", "", false, null)
            //#else
            //$$ new ResourcePackSendS2CPacket()
            //#endif
            ;
    private static final int PACKET_ID_RESOURCE_PACK_SEND = getPacketId(NetworkPhase.PLAY, RESOURCE_PACK_SEND_PACKET);
    //#if MC>=12002
    private static final int PACKET_ID_CONFIG_RESOURCE_PACK_SEND = getPacketId(NetworkPhase.CONFIGURATION, RESOURCE_PACK_SEND_PACKET);
    //#endif
    //#if MC>=11700
    private static final int PACKET_ID_LOGIN_COMPRESSION = getPacketId(NetworkPhase.LOGIN, new LoginCompressionS2CPacket(0));
    //#else
    //$$ private static final int PACKET_ID_LOGIN_COMPRESSION = getPacketId(NetworkPhase.LOGIN, new LoginCompressionS2CPacket());
    //#endif
    //#if MC<10904
    //$$ private static final int PACKET_ID_PLAY_COMPRESSION = getPacketId(EnumConnectionState.PLAY, new S46PacketSetCompressionLevel());
    //#endif

    private static final String LOGIN_RECORDER_KEY = "replay_recorder_login";
    private static final AttributeKey<EarlyLoginCapture> LOGIN_CAPTURE = AttributeKey.valueOf("ReplayModRecording_loginCapture");
    private static final int MAX_BOOTSTRAP_PACKETS = 256;
    private static final int MAX_BOOTSTRAP_BYTES = 8 * 1024 * 1024;
    private static final int PACKET_ID_LOGIN_QUERY = getPacketId(NetworkPhase.LOGIN,
            new net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket(0, null));
    private static final int PACKET_ID_LOGIN_SUCCESS = getPacketId(NetworkPhase.LOGIN,
            new net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket(null, false));

    /** Installed before the first remote frame, not from the already-decoded LOGIN callback. */
    public static void installLoginCapture(ClientConnection connection, ChannelHandlerContext ctx) {
        ChannelPipeline pipeline = ctx.pipeline();
        if (connection.isLocal() || pipeline.get("ReplayModReplay_replaySender") != null
                || pipeline.get("splitter") == null || ctx.channel().hasAttr(LOGIN_CAPTURE)) return;
        String decoder = pipeline.get(DECODER_KEY) != null ? DECODER_KEY : "inbound_config";
        EarlyLoginCapture capture = new EarlyLoginCapture(connection);
        ctx.channel().attr(LOGIN_CAPTURE).set(capture);
        try {
            pipeline.addBefore(decoder, LOGIN_RECORDER_KEY, capture);
        } catch (RuntimeException failure) {
            capture.fail(failure); // Keep a failure tombstone for any later recording attempt.
        }
    }

    public static void observeLoginPacket(Channel channel, net.minecraft.network.packet.Packet<?> packet) {
        EarlyLoginCapture capture = channel.attr(LOGIN_CAPTURE).get();
        if (capture != null) capture.observe(packet);
    }

    /** Completes even a disabled/failed start; never leave its raw prefix waiting indefinitely. */
    public static void finishLoginCapture(Channel channel) {
        EarlyLoginCapture capture = channel.attr(LOGIN_CAPTURE).get();
        ChannelHandler handler = channel.pipeline().get(RAW_RECORDER_KEY);
        if (handler instanceof PacketListener listener) {
            listener.loginCapture = capture;
            if (!channel.isOpen()) {
                listener.reportRecordingFailure(new IOException("Connection closed while recording was starting"));
                listener.stopRecording();
            }
        } else if (capture != null) {
            capture.discard();
        }
    }

    public static void closeLoginCapture(Channel channel, Throwable failure) {
        if (channel == null) return;
        EarlyLoginCapture capture = channel.attr(LOGIN_CAPTURE).get();
        if (capture != null) {
            if (failure != null) capture.fail(failure);
            else capture.close();
        }
        if (failure == null) channel.attr(LOGIN_CAPTURE).set(null);
    }

    public static void repositionRecorders(Channel channel) {
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> repositionRecorders(channel));
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        String decoder = pipeline.get(DECODER_KEY) != null ? DECODER_KEY
                : pipeline.get("inbound_config") != null ? "inbound_config" : null;
        if (decoder == null) return;
        for (String key : new String[]{LOGIN_RECORDER_KEY, RAW_RECORDER_KEY}) {
            ChannelHandler handler = pipeline.get(key);
            if (handler == null) continue;
            List<String> names = pipeline.names();
            if (names.indexOf(DECOMPRESS_KEY) <= names.indexOf(key)) continue;
            EarlyLoginCapture capture = handler instanceof EarlyLoginCapture early ? early : null;
            if (capture != null) capture.moving = true;
            try {
                pipeline.remove(handler);
                pipeline.addBefore(decoder, key, handler);
            } finally {
                if (capture != null) capture.moving = false;
            }
        }
    }

    @ChannelHandler.Sharable // Compression can reposition this same, connection-owned instance.
    private static final class EarlyLoginCapture extends ChannelInboundHandlerAdapter {
        private final ClientConnection connection;
        private final ArrayDeque<LoginFrame> frames = new ArrayDeque<>();
        private ChannelHandlerContext context;
        private java.util.concurrent.ScheduledFuture<?> timeout;
        private WeakReference<net.minecraft.network.packet.Packet<?>> firstDecoded;
        private PacketListener owner;
        private Object inFlightMessage;
        private LoginFrame inFlightFrame;
        private int bytes;
        private boolean started, stopped, handedOff, moving;
        private Throwable failure;

        private EarlyLoginCapture(ClientConnection connection) { this.connection = connection; }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                synchronized (this) {
                    if (!stopped && !handedOff && msg instanceof ByteBuf buffer) {
                        NetworkState<?> state = connection.getInboundProtocol();
                        if (!started && state.id() != NetworkPhase.LOGIN) {
                            discard(); // STATUS/ping is not a recording; no raw data is retained.
                        } else if (!started && !ReplayMod.instance.getSettingsRegistry().get(Setting.RECORD_SERVER)) {
                            discard();
                        } else if (buffer.isReadable()) {
                            int id = new PacketByteBuf(buffer.slice()).readVarInt();
                            boolean first = !started && (id == PACKET_ID_LOGIN_QUERY || id == PACKET_ID_LOGIN_SUCCESS);
                            if (started || first) {
                                int size = buffer.readableBytes();
                                if (frames.size() >= MAX_BOOTSTRAP_PACKETS || size > MAX_BOOTSTRAP_BYTES - bytes) {
                                    throw new IOException("Initial recording prefix exceeds 256 packets / 8 MiB");
                                }
                                byte[] copy = new byte[size];
                                buffer.getBytes(buffer.readerIndex(), copy); // No retain, no changed live reader index.
                                LoginFrame frame = new LoginFrame(state, copy, first);
                                frames.addLast(frame);
                                bytes += size;
                                inFlightMessage = msg;
                                inFlightFrame = frame;
                                if (first) {
                                    started = true;
                                    timeout = ctx.executor().schedule(() -> fail(new IOException(
                                            "Recording did not take ownership of its LOGIN prefix within 30 seconds")),
                                            30, TimeUnit.SECONDS);
                                }
                            }
                        }
                    }
                }
            } catch (Exception error) {
                fail(error); // Live networking continues, but a recording can no longer be published.
            }
            try {
                ctx.fireChannelRead(msg);
            } finally {
                synchronized (this) {
                    if (inFlightMessage == msg) {
                        inFlightMessage = null;
                        inFlightFrame = null;
                    }
                }
            }
        }

        private synchronized void observe(net.minecraft.network.packet.Packet<?> packet) {
            if (inFlightFrame != null && inFlightFrame.first && !stopped) {
                if (!(packet instanceof net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket)
                        && !(packet instanceof net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket)) {
                    fail(new IOException("LOGIN raw/decoded packet association was lost"));
                } else {
                    // Only an identity receipt, not ownership of the decoded/mod payload object.
                    firstDecoded = new WeakReference<>(packet);
                }
            }
        }

        private synchronized boolean consumeInitialPacket(net.minecraft.network.packet.Packet<?> packet) {
            if (firstDecoded != null && firstDecoded.get() == packet) {
                firstDecoded.clear();
                firstDecoded = null;
                return true; // The exact raw frame is queued/transferred; never re-encode it.
            }
            return false;
        }

        private synchronized boolean captured(Object msg) {
            return inFlightMessage == msg && inFlightFrame != null;
        }

        /** Called only by the fully positioned raw handler's event-loop handlerAdded callback. */
        private synchronized void handOff(PacketListener listener) {
            if (handedOff) return;
            owner = listener;
            listener.loginCapture = this;
            List<Packet> prefix = new ArrayList<>(frames.size());
            try {
                if (stopped || failure != null) {
                    throw new IOException("The initial LOGIN recording prefix is unavailable", failure);
                }
                if (!started) throw new IOException("Recorder attached before a captured LOGIN start packet");
                for (LoginFrame frame : frames) {
                    ByteBuf buffer = Unpooled.wrappedBuffer(frame.bytes);
                    try {
                        prefix.add(listener.prepareRecordedPacket(context, frame.state, decodePacket(frame.state, buffer)));
                    } finally {
                        buffer.release();
                    }
                }
                handedOff = true;
                clearFrames();
                listener.completeBootstrap(prefix); // Takes all Packet ownership, FIFO before synthetic packets.
                prefix.clear();
                removeHandler();
            } catch (Exception error) {
                prefix.forEach(Packet::release);
                fail(error);
                listener.completeBootstrap(Collections.emptyList());
            }
        }

        private synchronized void fail(Throwable error) {
            if (failure == null) failure = error;
            stopped = true;
            clearFrames();
            if (firstDecoded != null) firstDecoded.clear();
            firstDecoded = null;
            if (owner != null) owner.reportRecordingFailure(failure);
        }

        private synchronized void discard() {
            stopped = true;
            clearFrames();
            if (firstDecoded != null) firstDecoded.clear();
            firstDecoded = null;
            removeHandler();
        }

        private synchronized void close() {
            if (!handedOff && started && owner != null) fail(new IOException("Connection closed before LOGIN handoff"));
            discard();
            owner = null;
        }

        private void clearFrames() {
            // Cached frames are owned byte arrays, never retained inbound ByteBufs.
            for (LoginFrame frame : frames) frame.bytes = null;
            frames.clear();
            bytes = 0;
            if (timeout != null) timeout.cancel(false);
            timeout = null;
        }

        private void removeHandler() {
            ChannelHandlerContext ctx = context;
            if (ctx == null) return;
            Runnable remove = () -> {
                if (ctx.pipeline().context(this) != null) ctx.pipeline().remove(this);
            };
            try {
                if (ctx.executor().inEventLoop()) remove.run();
                else ctx.executor().execute(remove);
            } catch (RuntimeException error) {
                // Data/receipts were already cleared. A shutting-down executor may no
                // longer accept pipeline cleanup, but must not resurrect a usable prefix.
                logger.error("Removing LOGIN raw capture", error);
                fail(error);
            }
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (!moving && !handedOff && !stopped) fail(new IOException("LOGIN raw capture removed before handoff"));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            close();
            ctx.fireChannelInactive();
        }
    }

    private static final class LoginFrame {
        private final NetworkState<?> state;
        private byte[] bytes;
        private final boolean first;
        private LoginFrame(NetworkState<?> state, byte[] bytes, boolean first) {
            this.state = state;
            this.bytes = bytes;
            this.first = first;
        }
    }

    private final ReplayMod core;
    private final Path outputPath;
    private final ReplayFile replayFile;

    private final ResourcePackRecorder resourcePackRecorder;

    private final ExecutorService saveService = Executors.newSingleThreadExecutor();
    private final ReplayOutputStream packetOutputStream;

    private ReplayMetaData metaData;

    private volatile ChannelHandlerContext context = null;

    private final Object packetQueueLock = new Object();
    private final ArrayDeque<Packet> packetQueue = new ArrayDeque<>();
    private long queuedBytes, pendingClientBytes;
    private volatile boolean handlerReady;
    private boolean bootstrapComplete, closing;
    // Set only by the client fence, after its sole final metadata submission attempt.
    private boolean saveTasksClosed;
    private final Set<PendingPacket> pendingClientPackets = new HashSet<>();

    private static final class PendingPacket {
        private Packet packet;
        private final int size;
        private PendingPacket(Packet packet) {
            this.packet = packet;
            this.size = packet.getBuf().readableBytes();
        }
    }
    private volatile EarlyLoginCapture loginCapture;

    private final long startTime;
    private long lastSentPacket;
    private long timePassedWhilePaused;
    private volatile boolean serverWasPaused;

    /**
     * Used to keep track of the last metadata save job submitted to the save service and
     * as such prevents unnecessary writes.
     */
    private final AtomicInteger lastSaveMetaDataId = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<Throwable> recordingFailure = new java.util.concurrent.atomic.AtomicReference<>();

    private void reportRecordingFailure(Throwable failure) {
        if (recordingFailure.compareAndSet(null, failure)) {
            logger.error("Recording packet stream failed", failure);
            synchronized (packetQueueLock) { releaseQueuedPackets(); }
            EarlyLoginCapture capture = loginCapture;
            if (capture != null && capture.context != null) {
                // Never take the capture lock while holding packetQueueLock: handoff has
                // the opposite lock order. The event loop also owns pipeline mutations.
                try { capture.context.executor().execute(() -> capture.fail(failure)); }
                catch (RuntimeException rejected) { logger.error("LOGIN capture cleanup could not be scheduled", rejected); }
            }
            try {
                core.runLater(() -> core.printWarningToChat("replaymod.chat.recordingfailed"));
            } catch (RuntimeException noticeFailure) {
                logger.error("Recording failure notification could not be scheduled", noticeFailure);
            }
        } else if (recordingFailure.get() != failure) {
            // Do not replace the first cause, or build cycles when LOGIN capture reports it back.
            logger.error("Additional recording failure (first cause retained)", failure);
        }
    }

    public PacketListener(ReplayMod core, Path outputPath, ReplayFile replayFile, ReplayMetaData metaData) throws IOException {
        this.core = core;
        this.outputPath = outputPath;
        this.replayFile = replayFile;
        this.metaData = metaData;
        this.resourcePackRecorder = new QueuedResourcePackRecorder();
        this.packetOutputStream = replayFile.writePacketData();
        this.startTime = metaData.getDate();

        saveMetaData();
    }

    @FunctionalInterface
    private interface SaveTask {
        void run() throws IOException;
    }

    /** Caller holds packetQueueLock. No ignored Future can hide a failed ancillary write. */
    private boolean submitSaveTask(SaveTask task) {
        try {
            saveService.execute(() -> {
                try {
                    task.run();
                } catch (IOException | RuntimeException failure) {
                    reportRecordingFailure(failure);
                }
            });
            return true;
        } catch (RuntimeException failure) {
            reportRecordingFailure(failure);
            return false;
        }
    }

    private void submitRegularSaveTask(SaveTask task) {
        synchronized (packetQueueLock) {
            if (closing || saveTasksClosed || recordingFailure.get() != null) return;
            submitSaveTask(task);
        }
    }

    private void saveMetaData() {
        saveMetaData(false);
    }

    private void saveMetaData(boolean finalMetadata) {
        synchronized (packetQueueLock) {
            if (saveTasksClosed || (!finalMetadata && (closing || recordingFailure.get() != null))) return;
            try {
                // A worker must not serialize a live object while the client changes duration/players.
                ReplayMetaData snapshot = new ReplayMetaData(metaData);
                // This ReplayStudio copy constructor clones players but omits the nullable protocol field.
                if (metaData.getRawProtocolVersion() != null) snapshot.setProtocolVersion(metaData.getRawProtocolVersion());
                int id = lastSaveMetaDataId.get() + 1;
                if (submitSaveTask(() -> {
                    synchronized (packetQueueLock) {
                        // The submitter commits this id before releasing the same lock.
                        if (lastSaveMetaDataId.get() != id) return;
                    }
                    synchronized (replayFile) {
                        if (ReplayMod.isMinimalMode()) {
                            snapshot.setFileFormat("MCPR");
                            snapshot.setFileFormatVersion(ReplayMetaData.CURRENT_FILE_FORMAT_VERSION);
                            snapshot.setProtocolVersion(MCVer.getProtocolVersion());
                            snapshot.setGenerator("ReplayMod in Minimal Mode");
                            try (OutputStream out = replayFile.write("metaData.json")) {
                                String json = (new Gson()).toJson(snapshot);
                                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        } else {
                            replayFile.writeMetaData(MCVer.getPacketTypeRegistry(State.LOGIN), snapshot);
                        }
                    }
                })) {
                    // Rejected or closing-time calls must not invalidate the last accepted task.
                    lastSaveMetaDataId.set(id);
                }
            } catch (RuntimeException failure) {
                reportRecordingFailure(failure);
            }
        }
    }

    private final class QueuedResourcePackRecorder extends ResourcePackRecorder {
        private QueuedResourcePackRecorder() {
            super(PacketListener.this.replayFile);
        }

        @Override
        public void recordResourcePack(Path file, int requestId) {
            // The inherited UUID overload still resolves its id NOW, on the callback thread.
            // No recorder-monitor -> packetQueueLock nesting: do not synchronize the UUID overload.
            submitRegularSaveTask(() -> {
                // Minecraft owns this source. It may disappear before execution: that is a fatal
                // recording error, not permission to publish an index with missing pack contents.
                byte[] bytes = Files.readAllBytes(file);
                String hash = Hashing.sha1().hashBytes(bytes).toString();
                synchronized (replayFile) {
                    Map<Integer, String> existing = replayFile.getResourcePackIndex();
                    Map<Integer, String> index = existing == null ? new HashMap<>() : new HashMap<>(existing);
                    if (!index.containsValue(hash)) {
                        try (OutputStream out = replayFile.writeResourcePack(hash)) {
                            out.write(bytes);
                        }
                    }
                    // Only closed, fully written content can become visible through the index.
                    index.put(requestId, hash);
                    replayFile.writeResourcePackIndex(index);
                }
            }); // Once admitted, this whole task drains even if closing becomes true later.
        }
    }

    public void save(net.minecraft.network.packet.Packet packet) {
        EarlyLoginCapture capture = loginCapture;
        if (capture != null && capture.consumeInitialPacket(packet)) return;
        if (recordingFailure.get() != null) return;
        if (packet instanceof net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket query
                && query.payload() instanceof net.minecraft.network.packet.s2c.login.UnknownLoginQueryRequestPayload) {
            // A missing hook/receipt must remain fatal, not become an empty unknown payload.
            reportRecordingFailure(new IOException("LOGIN query has no original-byte capture receipt"));
            return;
        }
        Packet encoded;
        try {
            encoded = encodeMcPacket(getConnectionState(), packet);
        } catch (Exception e) {
            reportRecordingFailure(e);
            return;
        }
        save(encoded);
    }

    public void save(Packet packet) {
        synchronized (packetQueueLock) {
            if (closing || recordingFailure.get() != null) {
                packet.release();
                return;
            }
            if (handlerReady) {
                dispatchPacket(packet);
                return;
            }
            int size = packet.getBuf().readableBytes();
            if (packetQueue.size() >= MAX_BOOTSTRAP_PACKETS || size > MAX_BOOTSTRAP_BYTES - queuedBytes) {
                packet.release();
                reportRecordingFailure(new IOException("Packets waiting for recorder installation exceed the bootstrap limit"));
                return;
            }
            packetQueue.addLast(packet);
            queuedBytes += size;
        }
    }

    /** All queues own their Packet references. Scheduled client tasks own no buffers. */
    private void releaseQueuedPackets() {
        Packet packet;
        while ((packet = packetQueue.pollFirst()) != null) packet.release();
        queuedBytes = 0;
        for (PendingPacket pending : pendingClientPackets) {
            if (pending.packet != null) pending.packet.release();
            pending.packet = null;
        }
        pendingClientPackets.clear();
        pendingClientBytes = 0;
    }

    private void completeBootstrap(List<Packet> prefix) {
        synchronized (packetQueueLock) {
            long prefixBytes = 0;
            for (Packet packet : prefix) prefixBytes += packet.getBuf().readableBytes();
            if (prefix.size() + packetQueue.size() > MAX_BOOTSTRAP_PACKETS
                    || prefixBytes + queuedBytes > MAX_BOOTSTRAP_BYTES) {
                reportRecordingFailure(new IOException("Combined raw/synthetic LOGIN prefix exceeds the bootstrap limit"));
            }
            if (closing || recordingFailure.get() != null) {
                prefix.forEach(Packet::release);
                releaseQueuedPackets();
                handlerReady = true;
                return;
            }
            // handlerAdded may run later than the initiating LOGIN callback. Synthetic
            // packets wait behind the entire raw prefix, never just behind its last frame.
            for (int i = prefix.size() - 1; i >= 0; i--) {
                Packet packet = prefix.get(i);
                packetQueue.addFirst(packet);
                queuedBytes += packet.getBuf().readableBytes();
            }
            handlerReady = true;
            try {
                if (mc.isOnThread()) drainBootstrap();
                else mc.send(this::drainBootstrap);
            } catch (RuntimeException failure) {
                reportRecordingFailure(failure);
            }
        }
    }

    private void dispatchPacket(Packet packet) {
        if (mc.isOnThread() && bootstrapComplete) {
            writePacket(packet);
            return;
        }
        PendingPacket pending = new PendingPacket(packet);
        if (!bootstrapComplete && (packetQueue.size() + pendingClientPackets.size() >= MAX_BOOTSTRAP_PACKETS
                || pending.size > MAX_BOOTSTRAP_BYTES - queuedBytes - pendingClientBytes)) {
            packet.release();
            reportRecordingFailure(new IOException("Packets awaiting client-thread LOGIN handoff exceed the bootstrap limit"));
            return;
        }
        pendingClientPackets.add(pending);
        pendingClientBytes += pending.size;
        try {
            // One task per live frame is intentional. Coalescing several frames into a
            // drain would move later raw frames ahead of an earlier vanilla callback's
            // synthetic packets. Cancellation clears the token, not just its queue entry.
            mc.send(() -> {
                synchronized (packetQueueLock) {
                    if (pendingClientPackets.remove(pending)) pendingClientBytes -= pending.size;
                    Packet accepted = pending.packet;
                    pending.packet = null;
                    if (accepted != null) {
                        if (recordingFailure.get() == null) writePacket(accepted);
                        else accepted.release();
                    }
                }
            });
        } catch (RuntimeException failure) {
            reportRecordingFailure(failure);
        }
    }

    private void drainBootstrap() {
        synchronized (packetQueueLock) {
            if (!handlerReady || bootstrapComplete) return;
            bootstrapComplete = true;
            if (recordingFailure.get() != null) {
                releaseQueuedPackets();
                return;
            }
            Packet packet;
            while ((packet = packetQueue.pollFirst()) != null) {
                queuedBytes -= packet.getBuf().readableBytes();
                writePacket(packet); // Transfers ownership to the single save-service worker.
            }
        }
    }

    private void writePacket(Packet packet) {
        // Callers hold packetQueueLock; closing alone must NOT reject packets accepted before the fence.
        if (saveTasksClosed) {
            packet.release();
            reportRecordingFailure(new IOException("Accepted packet reached the writer after the client close fence"));
            return;
        }
        try {
            //#if MC>=11800
            if (packet.getRegistry().getState() == State.LOGIN && packet.getId() == PACKET_ID_LOGIN_COMPRESSION) {
                packet.release();
                return; // Replay data is never compressed on the packet level
            }
            //#if MC<10904
            //$$ if (packet.getRegistry().getState() == State.PLAY && packet.getId() == PACKET_ID_PLAY_COMPRESSION) {
            //$$     return; // Replay data is never compressed on the packet level
            //$$ }
            //#endif
            //#endif

            long now = System.currentTimeMillis();
            if (serverWasPaused) {
                timePassedWhilePaused = now - startTime - lastSentPacket;
                serverWasPaused = false;
            }
            int timestamp = (int) (now - startTime - timePassedWhilePaused);
            lastSentPacket = timestamp;
            PacketData packetData = new PacketData(timestamp, packet);
            saveService.execute(() -> {
                if (recordingFailure.get() != null) {
                    packet.release();
                    return;
                }
                try {
                    if (ReplayMod.isMinimalMode()) {
                        // Minimal mode, ReplayStudio might not know our packet ids, so we cannot use it
                        com.github.steveice10.netty.buffer.ByteBuf packetIdBuf = PooledByteBufAllocator.DEFAULT.buffer();
                        com.github.steveice10.netty.buffer.ByteBuf packetBuf = packetData.getPacket().getBuf();
                        try {
                            new ByteBufNetOutput(packetIdBuf).writeVarInt(packetData.getPacket().getId());

                            int packetIdLen = packetIdBuf.readableBytes();
                            int packetBufLen = packetBuf.readableBytes();
                            writeInt(packetOutputStream, (int) packetData.getTime());
                            writeInt(packetOutputStream, packetIdLen + packetBufLen);
                            packetIdBuf.readBytes(packetOutputStream, packetIdLen);
                            packetBuf.getBytes(packetBuf.readerIndex(), packetOutputStream, packetBufLen);
                        } finally {
                            packetIdBuf.release();
                            packetBuf.release();
                        }
                    } else {
                        packetOutputStream.write(packetData);
                    }
                } catch (IOException | RuntimeException failure) {
                    // Surface worker failure and refuse to publish an incomplete packet stream.
                    reportRecordingFailure(failure);
                }
            });
        } catch(Exception e) {
            packet.release(); // Submission failed before the save service took ownership.
            reportRecordingFailure(e);
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.context = ctx;

        String decoder = ctx.pipeline().get(DECODER_KEY) != null ? DECODER_KEY
                : ctx.pipeline().get("inbound_config") != null ? "inbound_config" : null;
        if (decoder != null) {
            // LoginSuccess removes the decoder BEFORE onSuccess starts recording. The
            // old connection hook consequently inserts us at the pipeline head. Re-anchor
            // beside inbound_config, after framing/decompression, before it becomes decoder.
            List<String> names = ctx.pipeline().names();
            if (names.indexOf(RAW_RECORDER_KEY) + 1 != names.indexOf(decoder)) {
                ctx.pipeline().remove(this);
                ctx.pipeline().addBefore(decoder, RAW_RECORDER_KEY, this);
                return;
            }
        }
        if (ctx.pipeline().get(DECODED_RECORDER_KEY) == null) {
            ctx.pipeline().addAfter(decoder != null ? decoder : RAW_RECORDER_KEY,
                    DECODED_RECORDER_KEY, new DecodedPacketListener());
        }
        EarlyLoginCapture capture = ctx.channel().attr(LOGIN_CAPTURE).get();
        if (!handlerReady) {
            if (capture != null) capture.handOff(this);
            else completeBootstrap(Collections.emptyList()); // Integrated/local connections have no raw decoder.
        }
        if (!ctx.channel().isOpen()) {
            reportRecordingFailure(new IOException("Recording handler was installed after the connection closed"));
            stopRecording();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        stopRecording();
        if (ctx != null) ctx.fireChannelInactive();
    }

    private void stopRecording() {
        EarlyLoginCapture capture = loginCapture;
        if (capture != null) capture.close();
        synchronized (packetQueueLock) {
            if (closing) return;
            closing = true;
            if (!handlerReady) {
                reportRecordingFailure(new IOException("Recording closed before initial packet handoff completed"));
            }
            try {
                // A client-thread fence: all accepted raw/synthetic packets precede save-service shutdown.
                // The Runnable owns no Packet references, so failure can clear the queue immediately.
                mc.send(() -> {
                    drainBootstrap();
                    finishRecording();
                });
            } catch (RuntimeException failure) {
                reportRecordingFailure(failure);
                saveService.shutdown(); // Retain working data; do not publish/close an incomplete replay.
            }
        }
    }

    private void finishRecording() {
        GuiSavingReplay guiSavingReplay = new GuiSavingReplay(core);
        synchronized (packetQueueLock) {
            if (saveTasksClosed) return;
            // This runs on the client, after every accepted PendingPacket task and bootstrap drain.
            metaData.setDuration((int) lastSentPacket);
            saveMetaData(true); // The only metadata submission allowed after closing was set.
            saveTasksClosed = true;
            try {
                saveService.shutdown(); // Never interrupt/cancel accepted FIFO work.
            } catch (RuntimeException failure) {
                reportRecordingFailure(failure);
                showSavingFailure(guiSavingReplay, failure);
                return; // No proven quiescence: retain streams, work and managed locks.
            }
        }

        try {
            core.runLater(() -> {
                ConnectionEventHandler connectionEventHandler = ReplayModRecording.instance.getConnectionEventHandler();
                if (connectionEventHandler.getPacketListener() == this) {
                    connectionEventHandler.reset();
                }
                guiSavingReplay.open();
            });
        } catch (RuntimeException failure) {
            reportRecordingFailure(failure);
        }
        try {
            new Thread(() -> finishSavingReplay(guiSavingReplay), "ReplayMod recording save").start();
        } catch (RuntimeException failure) {
            reportRecordingFailure(failure);
            showSavingFailure(guiSavingReplay, failure);
        }
    }

    private void finishSavingReplay(GuiSavingReplay guiSavingReplay) {
        try {
            if (!saveService.awaitTermination(10, TimeUnit.SECONDS)) {
                // Do NOT latch this via reportRecordingFailure: queued writePacket jobs must still drain.
                // Returning deliberately leaves handles/locks intact, even if the worker stops later.
                showSavingFailure(guiSavingReplay, new IOException(
                        "Timed out waiting for recording writes; no streams closed or replay published. "
                                + "Working data and handles are retained; recovery may require restart: " + outputPath));
                return;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            showSavingFailure(guiSavingReplay, new IOException(
                    "Interrupted while waiting for recording writes; working data, handles and locks retained: " + outputPath,
                    failure));
            return;
        }

        // Executor termination, not replayFile's monitor, is the barrier for packet and pack writes.
        try {
            packetOutputStream.close();
        } catch (IOException | RuntimeException failure) {
            reportRecordingFailure(failure);
        }
        if (recordingFailure.get() != null) {
            closeFailedRecording();
            showSavingFailure(guiSavingReplay, recordingFailure.get());
            return;
        }

        try {
            if (!MarkerProcessor.producesAnyOutput(replayFile)) {
                // Export a complete, CREATE_NEW raw copy. A raced collision, not exists(), decides retry.
                String replayName = FilenameUtils.getBaseName(outputPath.getFileName().toString());
                Path rawFolder = core.folders.getRawReplayFolder();
                for (long suffix = 0; ; suffix++) {
                    Path rawPath = rawFolder.resolve(suffix == 0 ? outputPath.getFileName().toString()
                            : replayName + "." + suffix + ".mcpr");
                    try {
                        replayFile.saveTo(rawPath.toFile());
                    } catch (FileAlreadyExistsException collision) {
                        continue; // No other IO failure authorizes changing the destination and retrying.
                    }
                    replayFile.close(); // Managed close verifies the exported revision before cleanup.
                    logger.info("Recording with no marker output preserved at {}", rawPath);
                    core.runLater(guiSavingReplay::close);
                    return;
                }
            }

            replayFile.save(); // Includes successful rebase to the original logical outputPath.
            // The writer normalizes format/protocol on its snapshot, not on the client's live object.
            ReplayMetaData savedMetaData = replayFile.getMetaData();
            replayFile.close();
            List<Pair<Path, ReplayMetaData>> outputPaths;
            if (core.getSettingsRegistry().get(Setting.AUTO_POST_PROCESS) && !ReplayMod.isMinimalMode()) {
                outputPaths = MarkerProcessor.apply(outputPath, guiSavingReplay.getProgressBar()::setProgress);
            } else {
                outputPaths = Collections.singletonList(Pair.of(outputPath, savedMetaData));
            }
            core.runLater(() -> {
                try {
                    guiSavingReplay.presentRenameDialog(outputPaths);
                } catch (RuntimeException failure) {
                    reportRecordingFailure(failure);
                    showSavingFailure(guiSavingReplay, failure);
                }
            });
        } catch (IOException | RuntimeException failure) {
            reportRecordingFailure(failure);
            closeFailedRecording();
            showSavingFailure(guiSavingReplay, failure);
        }
    }

    /** Only after the executor is proven terminated; never call arbitrary destructive library close on failure. */
    private void closeFailedRecording() {
        if (!(replayFile instanceof ManagedReplayFile)) {
            logger.warn("Non-managed failed recording retained without destructive close: {}", outputPath);
            return;
        }
        try {
            // Safe close either preserves a complete recovery snapshot or retains the tree/locks and throws.
            replayFile.close();
        } catch (IOException | RuntimeException failure) {
            reportRecordingFailure(failure);
        }
    }

    /** UI/logging only: wait failures must not make still-running packet jobs skip their data. */
    private void showSavingFailure(GuiSavingReplay guiSavingReplay, Throwable failure) {
        logger.error("Recording save did not complete for " + outputPath, failure);
        Throwable first = recordingFailure.get();
        Throwable displayed = first == null || first == failure ? failure : new IOException(failure.getMessage(), first);
        CrashReport crashReport = CrashReport.create(displayed, "Saving or post-processing replay file");
        try {
            core.runLater(() -> Utils.error(logger, VanillaGuiScreen.wrap(mc.currentScreen), crashReport, guiSavingReplay::close));
        } catch (RuntimeException noticeFailure) {
            logger.error("Saving failure UI could not be scheduled; consult the retained-work error above", noticeFailure);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            recordIncoming(ctx, msg);
        } catch (Exception failure) {
            // This adapter owns the inbound buffer until it forwards it. Netty does not
            // release it automatically when recording/codec validation throws.
            io.netty.util.ReferenceCountUtil.release(msg);
            reportRecordingFailure(failure);
            throw failure;
        }
    }

    private void recordIncoming(ChannelHandlerContext ctx, Object msg) throws Exception {
        if(ctx == null) {
            if(context == null) {
                return;
            } else {
                ctx = context;
            }
        }
        this.context = ctx;

        // An in-flight frame may meet the newly inserted recorder after the early
        // capture already copied it. Identity, not bytes/packet id, prevents duplication.
        EarlyLoginCapture capture = loginCapture;
        if (capture != null && capture.captured(msg)) {
            super.channelRead(ctx, msg);
            return;
        }
        NetworkState<?> connectionState = getConnectionState();

        Packet packet = null;
        if (msg instanceof ByteBuf) {
            // for regular connections, we're expecting to observe `ByteBuf`s here
            ByteBuf buf = (ByteBuf) msg;
            if (buf.readableBytes() > 0) {
                packet = decodePacket(connectionState, buf);
            }
        } else if (msg instanceof net.minecraft.network.packet.Packet) {
            // for integrated server connections MC is passing the packet objects directly, so we need to encode them
            // ourselves to be able to store them
            //#if MC>=11904
            PacketBundleHandler bundleHandler = connectionState.bundleHandler();
            List<Packet> packets = new ArrayList<>(1);
            try {
                if (bundleHandler == null) {
                    packets.add(encodeMcPacket(connectionState, (net.minecraft.network.packet.Packet<?>) msg));
                } else {
                    bundleHandler.forEachPacket((net.minecraft.network.packet.Packet<?>) msg,
                            unbundledPacket -> packets.add(encodeMcPacket(connectionState, unbundledPacket)));
                }
            } catch (RuntimeException failure) {
                packets.forEach(Packet::release);
                throw failure;
            }
            if (packets.size() > 1) {
                int submitted = 0;
                try {
                    while (submitted < packets.size()) {
                        saveRecordedPacket(ctx, connectionState, packets.get(submitted++));
                    }
                } finally {
                    // The current packet is owned by saveRecordedPacket; unsent packets
                    // still belong to this bundle, including on a resource-pack error.
                    while (submitted < packets.size()) packets.get(submitted++).release();
                }
                super.channelRead(ctx, msg);
                return;
            }
            packet = packets.isEmpty() ? null : packets.get(0);
            //#else
            //$$ packet = encodeMcPacket(connectionState, (net.minecraft.network.Packet) msg);
            //#endif
        }

        if (packet != null) saveRecordedPacket(ctx, connectionState, packet);
        super.channelRead(ctx, msg);
    }

    private void saveRecordedPacket(ChannelHandlerContext ctx, NetworkState<?> connectionState, Packet packet) {
        save(prepareRecordedPacket(ctx, connectionState, packet));
    }

    private Packet prepareRecordedPacket(ChannelHandlerContext ctx, NetworkState<?> connectionState, Packet packet) {
        if (connectionState.id() == NetworkPhase.PLAY && packet.getId() == PACKET_ID_RESOURCE_PACK_SEND
                || connectionState.id() == NetworkPhase.CONFIGURATION && packet.getId() == PACKET_ID_CONFIG_RESOURCE_PACK_SEND) {
            try {
                ClientConnection connection = ctx.pipeline().get(ClientConnection.class);
                ResourcePackSendS2CPacket rewritten = resourcePackRecorder.handleResourcePack(connection,
                        (ResourcePackSendS2CPacket) decodeMcPacket(connectionState, packet));
                return encodeMcPacket(connectionState, rewritten);
            } finally {
                packet.release();
            }
        } else {
            // Preserve original bytes for all other remote packets, including unknown/modded
            // payloads. Re-encoding a decoded DiscardedPayload would destroy their contents.
            return packet;
        }
    }

    private NetworkState<?> getConnectionState() {
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            return LoginStates.S2C;
        }
        // Includes the registry manager and NeoForge connection type bound by the real
        // configuration listener. Never manufacture an empty-registry PLAY codec.
        return requireNonNull(ctx.pipeline().get(ClientConnection.class)).getInboundProtocol();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Packet encodeMcPacket(NetworkState<?> connectionState, net.minecraft.network.packet.Packet<?> packet) {
        if (packet instanceof net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket query
                && query.payload() instanceof net.minecraft.network.packet.s2c.login.UnknownLoginQueryRequestPayload) {
            throw new IllegalArgumentException("Cannot reconstruct discarded LOGIN query bytes from a packet object");
        }
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            // The state codec writes BOTH VarInt packet id and payload. decodePacket strips
            // exactly that id and copies the payload before this temporary buffer is released.
            PacketCodec<ByteBuf, net.minecraft.network.packet.Packet<?>> codec = (PacketCodec) connectionState.codec();
            codec.encode(byteBuf, packet);
            return decodePacket(connectionState, byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    private static net.minecraft.network.packet.Packet<?> decodeMcPacket(NetworkState<?> connectionState, Packet packet) {
        PacketByteBuf packetBuf = new PacketByteBuf(Unpooled.buffer());
        try {
            packetBuf.writeVarInt(packet.getId());
            packetBuf.writeBytes(packet.getBuf().nioBuffer());
            net.minecraft.network.packet.Packet<?> decoded = connectionState.codec().decode(packetBuf);
            if (packetBuf.isReadable()) {
                throw new IllegalArgumentException("Unread replay packet bytes");
            }
            return decoded;
        } finally {
            packetBuf.release();
        }
    }

    private static Packet decodePacket(NetworkState<?> connectionState, ByteBuf buf) {
        // slice changes neither the incoming reader index nor ownership of the live packet.
        PacketByteBuf packetBuf = new PacketByteBuf(buf.slice());
        int packetId = packetBuf.readVarInt();
        byte[] bytes = new byte[packetBuf.readableBytes()];
        packetBuf.readBytes(bytes);
        return new Packet(
                MCVer.getPacketTypeRegistry(connectionState),
                packetId,
                com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(bytes)
        );
    }

    private static int getPacketId(NetworkPhase phase, net.minecraft.network.packet.Packet<?> packet) {
        return MCVer.getPacketId(phase, NetworkSide.CLIENTBOUND, packet);
    }

    public void addMarker(String name) {
        addMarker(name, (int) getCurrentDuration());
    }

    public void addMarker(String name, int timestamp) {
        synchronized (packetQueueLock) {
            if (closing || saveTasksClosed || recordingFailure.get() != null) return;
            try {
                Entity view = mc.getCameraEntity();
                Marker marker = new Marker();
                marker.setName(name);
                marker.setTime(timestamp);
                if (view != null) {
                    marker.setX(view.getX());
                    marker.setY(view.getY());
                    marker.setZ(view.getZ());
                    marker.setYaw(view.getYaw());
                    marker.setPitch(view.getPitch());
                }
                // Roll is always 0. Accepted marker writes drain in the same FIFO as packets.
                submitSaveTask(() -> {
                    synchronized (replayFile) {
                        Set<Marker> markers = replayFile.getMarkers().or(HashSet::new);
                        markers.add(marker);
                        replayFile.writeMarkers(markers);
                    }
                });
            } catch (RuntimeException failure) {
                reportRecordingFailure(failure);
            }
        }
    }

    public long getCurrentDuration() {
        return lastSentPacket;
    }

    public void setServerWasPaused() {
        this.serverWasPaused = true;
    }

    public ResourcePackRecorder getResourcePackRecorder() {
        return resourcePackRecorder;
    }

    private class DecodedPacketListener extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            inspectDecodedPacket(msg);
            super.channelRead(ctx, msg);
        }

        private void inspectDecodedPacket(Object msg) {
            // transitionInbound inserts a new bundler before this observer on entering PLAY.
            // Inspect its contents without altering or duplicating the original recorded bytes.
            if (msg instanceof net.minecraft.network.packet.BundlePacket<?> bundle) {
                bundle.getPackets().forEach(this::inspectDecodedPacket);
                return;
            }

            if (msg instanceof CustomPayloadS2CPacket) {
                CustomPayloadS2CPacket packet = (CustomPayloadS2CPacket) msg;
                if (Restrictions.PLUGIN_CHANNEL.equals(packet.payload().getId().id())) {
                    save(new DisconnectS2CPacket(net.minecraft.text.Text.literal("Please update to view this replay.")));
                }
            }

            //#if MC>=12002
            if (msg instanceof EntitySpawnS2CPacket packet && packet.getEntityType() == EntityType.PLAYER) {
                UUID uuid = packet.getUuid();
            //#else
            //$$ if (msg instanceof PlayerSpawnS2CPacket) {
                //#if MC>=10800
                //$$ UUID uuid = ((PlayerSpawnS2CPacket) msg).getPlayerUuid();
                //#else
                //$$ UUID uuid = ((S0CPacketSpawnPlayer) msg).func_148948_e().getId();
                //#endif
            //#endif
                synchronized (packetQueueLock) {
                    if (closing || saveTasksClosed || recordingFailure.get() != null) return;
                    Set<String> uuids = new HashSet<>(Arrays.asList(metaData.getPlayers()));
                    uuids.add(uuid.toString());
                    metaData.setPlayers(uuids.toArray(new String[uuids.size()]));
                    saveMetaData();
                }
            }
        }
    }
}
