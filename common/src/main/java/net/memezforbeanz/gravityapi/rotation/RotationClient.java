package net.memezforbeanz.gravityapi.rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.memezforbeanz.gravityapi.placement.CubeRotation;

public final class RotationClient {
    private RotationClient() {}
    public static void receive(RotationSync packet) {
        var minecraft = Minecraft.getInstance(); var level = minecraft.level;
        if (level == null || packet.data() == null) return;
        BlockPos origin = new BlockPos(packet.x() << 4, level.getMinBuildHeight(), packet.z() << 4);
        RotationStorage storage = RotationLookup.storage(level, origin);
        if (storage == null) return;
        if (packet.full()) {
            var before = storage.snapshot();
            storage.deserializeNBT(packet.data());
            var after = storage.snapshot();
            before.long2ByteEntrySet().forEach(e -> { if (!after.containsKey(e.getLongKey()) || after.get(e.getLongKey()) != e.getByteValue()) dirty(minecraft, e.getLongKey()); });
            after.long2ByteEntrySet().forEach(e -> { if (!before.containsKey(e.getLongKey())) dirty(minecraft, e.getLongKey()); });
        } else {
            BlockPos pos = BlockPos.of(packet.data().getLong("Pos"));
            CubeRotation r = CubeRotation.byIndex(packet.data().getByte("Rotation"));
            boolean changed = !java.util.Objects.equals(storage.get(pos), r);
            storage.set(pos, r, false);
            var expected = net.minecraft.world.level.block.Block.stateById(packet.data().getInt("State")).getBlock();
            if (r != null && level.getBlockState(pos).getBlock() != expected) storage.expect(pos, expected);
            if (changed) dirty(minecraft, pos.asLong());
        }
    }

    // Include neighbours: an altered occluder changes their faces too.
    private static void dirty(Minecraft minecraft, long packed) {
        BlockPos pos = BlockPos.of(packed);
        minecraft.levelRenderer.setBlocksDirty(pos.getX()-1, pos.getY()-1, pos.getZ()-1, pos.getX()+1, pos.getY()+1, pos.getZ()+1);
    }
}
