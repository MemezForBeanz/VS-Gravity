package net.memezforbeanz.gravityapi.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.architectury.utils.GameInstance;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.memezforbeanz.gravityapi.rotation.RotationSync;
import java.util.function.*;

public final class GravityNetwork {
    private static boolean registered;
    private static final ResourceLocation ROTATIONS = id("rotations");
    private static final ResourceLocation GRAVITY = id("gravity");
    private static final ResourceLocation SYNC_STATE = id("sync_state");
    private static final ResourceLocation PLATING = id("plating");
    private static ResourceLocation id(String path) { return new ResourceLocation("gravityapi", path); }

    public static void registerMessages() {
        if (registered) return;
        registered = true;
        receiver(NetworkManager.c2s(), SYNC_STATE, UpdateGravitySyncStatePacket::new, UpdateGravitySyncStatePacket.Handler::onMessage);
        receiver(NetworkManager.c2s(), PLATING, UpdatePlatingGravityPacket::new, UpdatePlatingGravityPacket.Handler::onMessage);
        if (Platform.getEnvironment() == Env.CLIENT) ClientReceivers.register();
    }
    private static <T> void receiver(NetworkManager.Side side, ResourceLocation id, Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, Supplier<NetworkManager.PacketContext>> handler) {
        NetworkManager.registerReceiver(side, id, (buf, context) -> handler.accept(decoder.apply(buf), () -> context));
    }
    // Keep client handlers behind a class only initialized in the client environment.
    private static final class ClientReceivers {
        static void register() {
            receiver(NetworkManager.s2c(), ROTATIONS, RotationSync::new, RotationSync::handle);
            receiver(NetworkManager.s2c(), GRAVITY, UpdateGravityCapabilityPacket::new, UpdateGravityCapabilityPacket.Handler::onMessage);
        }
    }
    private static ResourceLocation encode(Object message, FriendlyByteBuf buf) {
        if (message instanceof RotationSync packet) { packet.encode(buf); return ROTATIONS; }
        if (message instanceof UpdateGravityCapabilityPacket packet) { packet.encode(buf); return GRAVITY; }
        if (message instanceof UpdateGravitySyncStatePacket packet) { packet.encode(buf); return SYNC_STATE; }
        if (message instanceof UpdatePlatingGravityPacket packet) { packet.encode(buf); return PLATING; }
        throw new IllegalArgumentException("Unknown gravity packet: " + message.getClass());
    }
    public static <MSG> void sendToServer(MSG message) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        NetworkManager.sendToServer(encode(message, buf), buf);
    }
    public static <MSG> void sendToAll(MSG message) {
        var server = GameInstance.getServer();
        if (server != null) for (ServerPlayer player : server.getPlayerList().getPlayers()) sendNonLocal(message, player);
    }
    public static <MSG> void sendNonLocal(MSG message, ServerPlayer player) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        NetworkManager.sendToPlayer(player, encode(message, buf), buf);
    }
    public static void sendTrackingChunk(LevelChunk chunk, Object message) {
        ServerLevel level = (ServerLevel) chunk.getLevel();
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) sendNonLocal(message, player);
    }
    public static void sendTrackingEntity(Entity entity, Object message) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ResourceLocation id = encode(message, buf);
        ((ServerLevel) entity.level()).getChunkSource().broadcastAndSend(entity, NetworkManager.toPacket(NetworkManager.s2c(), id, buf));
    }
}
