package net.memezforbeanz.gravityapi.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.memezforbeanz.gravityapi.mixin.LevelEntitiesAccessor;

public class GCUtil {
	
	public static final Map<Integer, Entity> ENTITY_MAP = new HashMap<>();
	public static final Map<Integer, Entity> ENTITY_MAP2 = new HashMap<>();
	
	/** Client only: runs the consumer with the client level, if one is loaded. */
	public static void getClientLevel(Consumer<Level> consumer)
	{
		Level level = ClientLevelHolder.level();
		if (level != null)
		{
			consumer.accept(level);
		}
	}
	
	public static Iterable<Entity> getAllEntities(Level level)
	{
		return ((LevelEntitiesAccessor) level).gc_getEntities().getAll();
	}
	
	public static Entity getEntityByUUID(Level level, UUID uuid)
	{
		return ((LevelEntitiesAccessor) level).gc_getEntities().get(uuid);
	}
	
    public static MutableComponent getLinkText(String link) {
        return Component.literal(link).withStyle(
            style -> style.withClickEvent(new ClickEvent(
                ClickEvent.Action.OPEN_URL, link
            )).withUnderlined(true)
        );
    }
    
    public static MutableComponent getDirectionText(Direction gravityDirection) {
        return Component.translatable("direction." + gravityDirection.getName());
    }
    
    public static double distanceToRange(double value, double rangeStart, double rangeEnd) {
        if (value < rangeStart) {
            return rangeStart - value;
        }
        
        if (value > rangeEnd) {
            return value - rangeEnd;
        }
        
        return 0;
    }
    
    public static boolean isClientPlayer(Entity entity) {
        if (entity.level().isClientSide()) {
            return entity instanceof LocalPlayer;
        }
        return false;
    }
    
    public static boolean isRemotePlayer(Entity entity) {
        if (entity.level().isClientSide()) {
            return entity instanceof RemotePlayer;
        }
        return false;
    }
}
