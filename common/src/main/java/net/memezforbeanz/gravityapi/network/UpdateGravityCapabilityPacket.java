package net.memezforbeanz.gravityapi.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilities;
import net.memezforbeanz.gravityapi.util.GCUtil;

import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
	// Whether the arbitrary direction counts as cardinal: ship-relative directions on an axis-aligned ship must not re-snap to cardinal on receive
	private final boolean arbitraryIsCardinal;
	// Flag to indicate this is from a camera-only source (VS ship)
	private final boolean isCameraOnly;
	// Ship anchor of the arbitrary direction (ship-locked frame), or null: without it the receiver
	// rebuilds a frame twisted about gravity relative to the sender's, and remote mobs render turned
	@Nullable
	private final Long anchorShipId;
	@Nullable
	private final Vec3 anchorShipLocal;

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
		this.arbitraryIsCardinal = arbitraryGravity != null && arbitraryGravity.isCardinal();
		this.isCameraOnly = isCameraOnly;
		boolean anchored = arbitraryGravity != null && arbitraryGravity.isShipAnchored();
		this.anchorShipId = anchored ? arbitraryGravity.getAnchorShipId() : null;
		this.anchorShipLocal = anchored ? arbitraryGravity.getShipLocalVector() : null;
	}

	public UpdateGravityCapabilityPacket(FriendlyByteBuf buf)
	{
		this.noAnimation = buf.readBoolean();
		this.entityUUID = buf.readUUID();
		this.baseGravityDirection = buf.readEnum(Direction.class);
		this.currentGravityDirection = buf.readEnum(Direction.class);
		this.baseGravityStrength = buf.readDouble();
		this.currentGravityStrength = buf.readDouble();
		boolean hasArbitrary = buf.readBoolean();
		if (hasArbitrary) {
			this.arbitraryGravityVector = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
			this.arbitraryIsCardinal = buf.readBoolean();
		} else {
			this.arbitraryGravityVector = null;
			this.arbitraryIsCardinal = false;
		}
		this.isCameraOnly = buf.readBoolean();
		if (buf.readBoolean()) {
			this.anchorShipId = buf.readLong();
			this.anchorShipLocal = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		} else {
			this.anchorShipId = null;
			this.anchorShipLocal = null;
		}
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeBoolean(this.noAnimation);
		buf.writeUUID(this.entityUUID);
		buf.writeEnum(this.baseGravityDirection);
		buf.writeEnum(this.currentGravityDirection);
		buf.writeDouble(this.baseGravityStrength);
		buf.writeDouble(this.currentGravityStrength);
		buf.writeBoolean(this.arbitraryGravityVector != null);
		if (this.arbitraryGravityVector != null) {
			buf.writeDouble(this.arbitraryGravityVector.x);
			buf.writeDouble(this.arbitraryGravityVector.y);
			buf.writeDouble(this.arbitraryGravityVector.z);
			buf.writeBoolean(this.arbitraryIsCardinal);
		}
		buf.writeBoolean(this.isCameraOnly);
		buf.writeBoolean(this.anchorShipId != null);
		if (this.anchorShipId != null) {
			buf.writeLong(this.anchorShipId);
			buf.writeDouble(this.anchorShipLocal.x);
			buf.writeDouble(this.anchorShipLocal.y);
			buf.writeDouble(this.anchorShipLocal.z);
		}
	}
	
	public static class Handler 
	{
		public static boolean onMessage(UpdateGravityCapabilityPacket message, Supplier<dev.architectury.networking.NetworkManager.PacketContext> ctx) 
		{
			ctx.get().queue(() ->
			{
				if(ctx.get().getEnvironment() == dev.architectury.utils.Env.CLIENT)
				{
					GCUtil.getClientLevel(level -> 
					{
						Entity entity = GCUtil.getEntityByUUID(level, message.entityUUID);
						if (entity == null) {
							return;
						}
						java.util.Optional.ofNullable(net.memezforbeanz.gravityapi.api.GravityChangerAPI.getGravityComponentEarly(entity)).ifPresent(cap -> 
						{
							GravityDirection arbitraryDir = null;
							if (message.arbitraryGravityVector != null) {
								arbitraryDir = message.arbitraryIsCardinal
									? GravityDirection.fromVector(message.arbitraryGravityVector)
									: GravityDirection.shipRelative(message.arbitraryGravityVector);
							}
							if (message.anchorShipId != null && VS2Integration.isVS2Loaded()) {
								GravityDirection anchored = VS2Helper.anchorToShipById(level, message.anchorShipId, message.anchorShipLocal);
								if (anchored != null) {
									arbitraryDir = anchored;
								}
							}
							cap.sync(message.noAnimation, message.baseGravityDirection, message.currentGravityDirection, message.baseGravityStrength, message.currentGravityStrength, arbitraryDir, message.isCameraOnly);
						});
					});
				}
			});

			
			return true;
		}
	}
}
