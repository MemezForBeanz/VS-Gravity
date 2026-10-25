package net.memezforbeanz.gravityapi.rotation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "gravityapi")
public final class RotationEvents {
    @SubscribeEvent public static void attach(AttachCapabilitiesEvent<LevelChunk> event) {
        LevelChunk chunk = event.getObject();
        RotationStorage storage = new RotationStorage(chunk);
        ((RotationChunk) chunk).gravityapi$rotations(storage);
        event.addCapability(new ResourceLocation("gravityapi", "rotations"), storage);
        event.addListener(storage::invalidate);
    }
    @SubscribeEvent public static void watch(ChunkWatchEvent.Watch event) {
        RotationSync.sendFull(event.getChunk(), event.getPlayer());
    }
}
