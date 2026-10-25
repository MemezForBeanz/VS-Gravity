package net.memezforbeanz.gravityapi.rotation;

import net.memezforbeanz.gravityapi.network.GravityNetwork;
import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import java.util.function.Supplier;

/** Full chunk snapshots on watch; a one-entry delta uses -1 for deletion. */
public record RotationSync(int x, int z, boolean full, CompoundTag data) {
    public RotationSync(FriendlyByteBuf buf) { this(buf.readInt(), buf.readInt(), buf.readBoolean(), buf.readNbt()); }
    public void encode(FriendlyByteBuf buf) { buf.writeInt(x); buf.writeInt(z); buf.writeBoolean(full); buf.writeNbt(data); }
    public static void sendFull(LevelChunk chunk, ServerPlayer player) {
        RotationStorage storage = ((RotationChunk) chunk).gravityapi$rotations();
        // A freshly sent client chunk starts with an empty map, so empty snapshots carry nothing.
        if (storage == null || storage.isEmpty()) return;
        GravityNetwork.sendNonLocal(new RotationSync(chunk.getPos().x, chunk.getPos().z, true, storage.serializeNBT()), player);
    }
    public static void sendChange(LevelChunk chunk, BlockPos pos, CubeRotation r) {
        CompoundTag tag = new CompoundTag(); tag.putLong("Pos", pos.asLong()); tag.putInt("State", net.minecraft.world.level.block.Block.getId(chunk.getBlockState(pos))); tag.putByte("Rotation", (byte) (r == null ? -1 : r.index()));
        GravityNetwork.CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), new RotationSync(chunk.getPos().x, chunk.getPos().z, false, tag));
    }
    public static void correctPrediction(ServerPlayer player, BlockPos pos) {
        LevelChunk chunk = player.serverLevel().getChunkAt(pos);
        RotationStorage storage = ((RotationChunk) chunk).gravityapi$rotations();
        CubeRotation rotation = storage == null ? null : storage.get(pos);
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong()); tag.putInt("State", net.minecraft.world.level.block.Block.getId(chunk.getBlockState(pos)));
        tag.putByte("Rotation", (byte) (rotation == null ? -1 : rotation.index()));
        GravityNetwork.sendNonLocal(new RotationSync(chunk.getPos().x, chunk.getPos().z, false, tag), player);
    }
    public static void handle(RotationSync packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> RotationClient.receive(packet));
        context.setPacketHandled(true);
    }
}
