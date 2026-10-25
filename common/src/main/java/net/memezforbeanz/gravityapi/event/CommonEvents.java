package net.memezforbeanz.gravityapi.event;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl;
import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.memezforbeanz.gravityapi.util.GCUtil;
import net.memezforbeanz.gravityapi.util.TeleportRescue;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Game event handlers. Events without an Architectury equivalent are called from mixins (see each method). */
public class CommonEvents
{
	public static void register()
	{
		EntityEvent.ADD.register((entity, level) ->
		{
			onEntityJoinLevel(entity);
			return EventResult.pass();
		});
		PlayerEvent.PLAYER_CLONE.register((original, player, wonGame) -> onPlayerClone(original, player, !wonGame));
	}

	private static void onEntityJoinLevel(Entity entity)
	{
		GCUtil.ENTITY_MAP.put(entity.getClass().hashCode(), entity);
		GCUtil.ENTITY_MAP2.put(entity.getClass().getSuperclass().hashCode(), entity);
	}

    /** A player starting to track an entity gets its gravity, applied without animation (otherwise only changes are sent). Called from ServerEntityMixin. */
    public static void onStartTracking(Entity target, ServerPlayer player)
    {
        GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponentEarly(target);
        if (cap != null)
        {
            cap.sendStateTo(player);
        }
    }

    /** Start of every living entity tick. Called from LivingEntityTickMixin. */
    public static void onLivingTick(LivingEntity entity)
    {
        TeleportRescue.tick(entity);
    }

    private static void onPlayerClone(Player original, Player player, boolean wasDeath)
    {
    	if(wasDeath && !GravityConfig.resetGravityOnRespawn.get())
    	{
        	GravityChangerAPI.setBaseGravityDirection(player, GravityChangerAPI.getBaseGravityDirection(original));
    	}
		for(Entity entity : GCUtil.getAllEntities(player.level()))
		{
			if(!entity.level().isClientSide)
			{
    			if(GravityChangerAPI.getBaseGravityDirection(entity) == Direction.DOWN)
    			{
    				continue;
    			}
    			GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponent(entity);
    			cap.initialized = false;
				cap.deserializeNBT(cap.serializeNBT());
			}
		}
    }
}
