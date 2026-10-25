package net.memezforbeanz.gravityapi.rotation;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.Direction;

/** Mutations are on the level thread; mesh workers read an immutable copy-on-write map. */
public final class RotationStorage {
    private final LevelChunk chunk;
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.block.Block> expected = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    public void expect(BlockPos pos, net.minecraft.world.level.block.Block block) { expected.put(pos.asLong(), block); }
    public boolean acceptExpected(BlockPos pos, net.minecraft.world.level.block.Block block) { return expected.remove(pos.asLong()) == block; }
    private volatile Long2ByteOpenHashMap entries = new Long2ByteOpenHashMap();
    public RotationStorage(LevelChunk chunk) { this.chunk = chunk; }
    public boolean isEmpty() { return entries.isEmpty(); }
    /** The current immutable map; callers must not modify it. */
    public Long2ByteOpenHashMap snapshot() { return entries; }
    public CubeRotation get(BlockPos pos) {
        Long2ByteOpenHashMap map = entries;
        if (map.isEmpty() || !map.containsKey(pos.asLong())) return null;
        return CubeRotation.byIndex(map.get(pos.asLong()));
    }
    public void set(BlockPos pos, CubeRotation rotation, boolean sync) {
        if (rotation != null && rotation.isIdentity()) rotation = null;
        if (java.util.Objects.equals(get(pos), rotation)) return;
        Long2ByteOpenHashMap copy = new Long2ByteOpenHashMap(entries);
        if (rotation == null) copy.remove(pos.asLong()); else copy.put(pos.asLong(), (byte) rotation.index());
        entries = copy;
        if (!copy.isEmpty()) ((RotationLevel) chunk.getLevel()).gravityapi$markRotations();
        if (rotation == null) expected.remove(pos.asLong());
        chunk.getLevel().getChunkSource().getLightEngine().checkBlock(pos);
        chunk.setUnsaved(true);
        if (sync && !chunk.getLevel().isClientSide) RotationSync.sendChange(chunk, pos, rotation);
    }
    public CompoundTag serializeNBT() {
        Long2ByteOpenHashMap map = entries;
        long[] positions = map.keySet().toLongArray();
        byte[] rotations = new byte[positions.length];
        for (int i = 0; i < positions.length; i++) rotations[i] = map.get(positions[i]);
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("Positions", positions); tag.putByteArray("Rotations", rotations);
        return tag;
    }
    public void deserializeNBT(CompoundTag tag) {
        long[] positions = tag.getLongArray("Positions"); byte[] rotations = tag.getByteArray("Rotations");
        Long2ByteOpenHashMap map = new Long2ByteOpenHashMap();
        for (int i = 0; i < Math.min(positions.length, rotations.length); i++) {
            BlockPos pos = BlockPos.of(positions[i]); CubeRotation r = CubeRotation.byIndex(rotations[i]);
            if (r != null && !r.isIdentity() && (pos.getX() >> 4) == chunk.getPos().x && (pos.getZ() >> 4) == chunk.getPos().z
                    && !chunk.getLevel().isOutsideBuildHeight(pos)) map.put(positions[i], rotations[i]);
        }
        entries = map;
        expected.clear();
        if (!map.isEmpty()) ((RotationLevel) chunk.getLevel()).gravityapi$markRotations();
    }
    public void invalidate() { expected.clear(); }
}
