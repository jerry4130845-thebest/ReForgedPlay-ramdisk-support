package com.replaymod.core.utils;

import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
//#if MC>=10904
import net.minecraft.util.Identifier;
//#endif

//#if MC<=10710 || MC>=12002
import io.netty.buffer.Unpooled;
//#endif

/**
 * Restrictions set by the server,
 * @see <a href="https://gist.github.com/Johni0702/2547c463e51f65f312cb">Replay Restrictions Gist</a>
 */
public class Restrictions {
    //#if MC>=11400
    public static final Identifier PLUGIN_CHANNEL = Identifier.of("replaymod", "restrict");
    //#else
    //$$ public static final String PLUGIN_CHANNEL = "Replay|Restrict";
    //#endif
    /** Raw restriction bytes, retained instead of being discarded as an unknown custom payload. */
    public record Payload(byte[] data) implements CustomPayload {
        public static final Id<Payload> ID = new Id<>(PLUGIN_CHANNEL);
        public static final PacketCodec<PacketByteBuf, Payload> CODEC = PacketCodec.ofStatic(
                (buffer, payload) -> buffer.writeBytes(payload.data),
                buffer -> {
                    int size = buffer.readableBytes();
                    if (size > 1048576) {
                        throw new IllegalArgumentException("Replay restriction payload exceeds 1 MiB");
                    }
                    byte[] data = new byte[size];
                    buffer.readBytes(data);
                    return new Payload(data);
                });

        @Override
        public Id<Payload> getId() {
            return ID;
        }
    }

    private boolean noXray;
    private boolean noNoclip;
    private boolean onlyFirstPerson;
    private boolean onlyRecordingPlayer;

    public String handle(CustomPayloadS2CPacket packet) {
        //#if MC>=12002
        if (!(packet.payload() instanceof Payload payload)) {
            // A discarded/foreign payload cannot be treated as an empty restriction list.
            return PLUGIN_CHANNEL.toString();
        }
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.wrappedBuffer(payload.data()));
        //#elseif MC>=10800
        //$$ PacketByteBuf buffer = packet.getData();
        //#else
        //$$ PacketBuffer buffer = new PacketBuffer(Unpooled.wrappedBuffer(packet.func_149168_d()));
        //#endif
        try {
            while (buffer.isReadable()) {
                String name = buffer.readString(64);
                boolean active = buffer.readBoolean();
//            if ("no_xray".equals(name)) {
//                noXray = active;
//            } else if ("no_noclip".equals(name)) {
//                noNoclip = active;
//            } else if ("only_first_person".equals(name)) {
//                onlyFirstPerson = active;
//            } else if ("only_recording_player".equals(name)) {
//                onlyRecordingPlayer = active;
//            } else {
                return name;
//            }
            }
            return null;
        } catch (RuntimeException malformed) {
            // The replay sender skips thrown packet errors; return an unknown restriction instead
            // so malformed restriction data follows its terminate-replay path, not its skip path.
            return PLUGIN_CHANNEL.toString();
        } finally {
            buffer.release();
        }
    }

    public boolean isNoXray() {
        return noXray;
    }

    public boolean isNoNoclip() {
        return noNoclip;
    }

    public boolean isOnlyFirstPerson() {
        return onlyFirstPerson;
    }

    public boolean isOnlyRecordingPlayer() {
        return onlyRecordingPlayer;
    }
}
