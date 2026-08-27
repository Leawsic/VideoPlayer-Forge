package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.VideoPlayerMain;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Forge replacement for the Fabric custom payload channel.
 * <p>
 * Everything is serialised by hand into a raw {@code byte[]} (see {@link ServerPacketHandler} and the
 * client counterpart), so a single opaque packet type carries all traffic.
 */
public final class VideoPayload {
    public static final ResourceLocation ID = new ResourceLocation(VideoPlayerMain.MOD_ID, "video");
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ID)
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    /** Installed by the client initialiser; keeps client-only classes off the dedicated server. */
    public static volatile Consumer<ByteBuf> clientHandler = buf -> {
    };

    private VideoPayload() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, VideoPacket.class, VideoPacket::encode, VideoPacket::decode, VideoPacket::handle);
    }

    public static void sendToPlayer(ServerPlayer player, byte[] data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new VideoPacket(data));
    }

    public static void sendToServer(byte[] data) {
        CHANNEL.sendToServer(new VideoPacket(data));
    }

    public record VideoPacket(byte[] data) {
        public static void encode(VideoPacket packet, FriendlyByteBuf buf) {
            buf.writeBytes(packet.data);
        }

        public static VideoPacket decode(FriendlyByteBuf buf) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return new VideoPacket(data);
        }

        public static void handle(VideoPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context ctx = supplier.get();
            ByteBuf buf = Unpooled.wrappedBuffer(packet.data);
            if (ctx.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
                ServerPlayer sender = ctx.getSender();
                if (sender != null) {
                    ctx.enqueueWork(() -> {
                        try {
                            ServerPacketHandler.handle(sender, buf);
                        } catch (Exception e) {
                            sender.connection.disconnect(Component.literal(String.valueOf(e)));
                        }
                    });
                }
            } else {
                ctx.enqueueWork(() -> clientHandler.accept(buf));
            }
            ctx.setPacketHandled(true);
        }
    }
}
