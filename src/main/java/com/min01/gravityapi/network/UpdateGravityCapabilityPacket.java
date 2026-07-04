package com.min01.gravityapi.network;

import java.util.UUID;
import java.util.function.Supplier;

import com.min01.gravityapi.api.GravityDirection;
import com.min01.gravityapi.capabilities.GravityCapabilities;
import com.min01.gravityapi.util.GCUtil;

import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

public class UpdateGravityCapabilityPacket 
{
	private final UUID entityUUID;
	private final boolean noAnimation;
	private final Direction baseGravityDirection;
	private final Direction currentGravityDirection;
	private final double baseGravityStrength;
	private final double currentGravityStrength;
	// Arbitrary gravity vector for VS ship angles (null if using cardinal direction)
	@Nullable
	private final Vec3 arbitraryGravityVector;
	// Flag to indicate this is from a camera-only source (VS ship)
	private final boolean isCameraOnly;

	public UpdateGravityCapabilityPacket(boolean noAnimation, UUID entityUUID, Direction baseGravityDirection, Direction currentGravityDirection, double baseGravityStrength, double currentGravityStrength) 
	{
		this(noAnimation, entityUUID, baseGravityDirection, currentGravityDirection, baseGravityStrength, currentGravityStrength, null, false);
	}

	public UpdateGravityCapabilityPacket(boolean noAnimation, UUID entityUUID, Direction baseGravityDirection, Direction currentGravityDirection, double baseGravityStrength, double currentGravityStrength, @Nullable GravityDirection arbitraryGravity)
	{
		this(noAnimation, entityUUID, baseGravityDirection, currentGravityDirection, baseGravityStrength, currentGravityStrength, arbitraryGravity, false);
	}

	public UpdateGravityCapabilityPacket(boolean noAnimation, UUID entityUUID, Direction baseGravityDirection, Direction currentGravityDirection, double baseGravityStrength, double currentGravityStrength, @Nullable GravityDirection arbitraryGravity, boolean isCameraOnly)
	{
		this.noAnimation = noAnimation;
		this.entityUUID = entityUUID;
		this.baseGravityDirection = baseGravityDirection;
		this.currentGravityDirection = currentGravityDirection;
		this.baseGravityStrength = baseGravityStrength;
		this.currentGravityStrength = currentGravityStrength;
		// Always send arbitrary direction if present (for camera-only effects even when cardinal)
		this.arbitraryGravityVector = (arbitraryGravity != null) ? arbitraryGravity.getVector() : null;
		this.isCameraOnly = isCameraOnly;
	}

	public UpdateGravityCapabilityPacket(FriendlyByteBuf buf)
	{
		this.noAnimation = buf.readBoolean();
		this.entityUUID = buf.readUUID();
		this.baseGravityDirection = buf.readEnum(Direction.class);
		this.currentGravityDirection = buf.readEnum(Direction.class);
		this.baseGravityStrength = buf.readDouble();
		this.currentGravityStrength = buf.readDouble();
		// Read arbitrary gravity vector
		boolean hasArbitrary = buf.readBoolean();
		if (hasArbitrary) {
			this.arbitraryGravityVector = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		} else {
			this.arbitraryGravityVector = null;
		}
		// Read camera-only flag
		this.isCameraOnly = buf.readBoolean();
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeBoolean(this.noAnimation);
		buf.writeUUID(this.entityUUID);
		buf.writeEnum(this.baseGravityDirection);
		buf.writeEnum(this.currentGravityDirection);
		buf.writeDouble(this.baseGravityStrength);
		buf.writeDouble(this.currentGravityStrength);
		// Write arbitrary gravity vector
		buf.writeBoolean(this.arbitraryGravityVector != null);
		if (this.arbitraryGravityVector != null) {
			buf.writeDouble(this.arbitraryGravityVector.x);
			buf.writeDouble(this.arbitraryGravityVector.y);
			buf.writeDouble(this.arbitraryGravityVector.z);
		}
		// Write camera-only flag
		buf.writeBoolean(this.isCameraOnly);
	}
	
	public static class Handler 
	{
		public static boolean onMessage(UpdateGravityCapabilityPacket message, Supplier<NetworkEvent.Context> ctx) 
		{
			ctx.get().enqueueWork(() ->
			{
				if(ctx.get().getDirection().getReceptionSide().isClient())
				{
					GCUtil.getClientLevel(level -> 
					{
						Entity entity = GCUtil.getEntityByUUID(level, message.entityUUID);
						entity.getCapability(GravityCapabilities.GRAVITY).ifPresent(cap -> 
						{
							// Convert arbitrary vector to GravityDirection if present
							GravityDirection arbitraryDir = null;
							if (message.arbitraryGravityVector != null) {
								arbitraryDir = GravityDirection.fromVector(message.arbitraryGravityVector);
							}
							cap.sync(message.noAnimation, message.baseGravityDirection, message.currentGravityDirection, message.baseGravityStrength, message.currentGravityStrength, arbitraryDir, message.isCameraOnly);
						});
					});
				}
			});

			ctx.get().setPacketHandled(true);
			return true;
		}
	}
}
