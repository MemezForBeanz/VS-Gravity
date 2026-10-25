package net.memezforbeanz.gravityapi.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl;
import net.memezforbeanz.gravityapi.util.GCUtil;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;

public class UpdateGravitySyncStatePacket 
{
	private final UUID entityUUID;
	
	public UpdateGravitySyncStatePacket(UUID entityUUID) 
	{
		this.entityUUID = entityUUID;
	}

	public UpdateGravitySyncStatePacket(FriendlyByteBuf buf)
	{
		this.entityUUID = buf.readUUID();
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeUUID(this.entityUUID);
	}
	
	public static class Handler 
	{
		public static boolean onMessage(UpdateGravitySyncStatePacket message, Supplier<dev.architectury.networking.NetworkManager.PacketContext> ctx) 
		{
			ctx.get().queue(() ->
			{
				if(ctx.get().getEnvironment() == dev.architectury.utils.Env.SERVER)
				{
					Entity entity = GCUtil.getEntityByUUID(ctx.get().getPlayer().level(), message.entityUUID);
					if (entity == null) return;
                    GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponent(entity);
					cap.needsSync = false;
					cap.noAnimation = false;
				}
			});

			
			return true;
		}
	}
}
