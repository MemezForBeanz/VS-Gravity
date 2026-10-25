package net.memezforbeanz.gravityapi.event;

import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl;
import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.memezforbeanz.gravityapi.util.GCUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.memezforbeanz.gravityapi.util.TeleportRescue;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = GravityAPI.MODID)
public class CommonEvents
{
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
    }
    
	@SubscribeEvent
	public static void onEntityJoinLevel(EntityJoinLevelEvent event)
	{
		Entity entity = event.getEntity();
		GCUtil.ENTITY_MAP.put(entity.getClass().hashCode(), entity);
		GCUtil.ENTITY_MAP2.put(entity.getClass().getSuperclass().hashCode(), entity);
	}
	
    /** A player starting to track an entity gets its gravity, applied without animation (otherwise only changes are sent). */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponentEarly(event.getTarget());
            if (cap != null)
            {
                cap.sendStateTo(player);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event)
    {
        TeleportRescue.tick(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event)
    {
    	Player player = event.getEntity();
    	if(event.isWasDeath() && !GravityConfig.resetGravityOnRespawn.get())
    	{
        	Player original = event.getOriginal();
        	original.revive();
        	GravityChangerAPI.setBaseGravityDirection(player, GravityChangerAPI.getBaseGravityDirection(original));
    	}
		for(Entity entity : GCUtil.getAllEntities(player.level))
		{
			if(!entity.level.isClientSide)
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
