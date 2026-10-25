package net.memezforbeanz.gravityapi.network;

import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl.PlatingCandidate;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client to server: the sender's resolved gravity plating choice, or none. Players are
 * client-authoritative for plating; the server checks the source is a plate within reach
 * (AmethystGravity's client-report, server-verify scheme).
 */
public class UpdatePlatingGravityPacket
{
	private final boolean hasChoice;
	private final Vec3 direction;
	private final boolean shipFrame;
	private final double volume;
	private final BlockPos source;

	public UpdatePlatingGravityPacket(@Nullable PlatingCandidate candidate)
	{
		this.hasChoice = candidate != null;
		this.direction = candidate != null ? candidate.worldDirection() : Vec3.ZERO;
		this.shipFrame = candidate != null && candidate.shipFrame();
		this.volume = candidate != null ? candidate.volume() : 0;
		this.source = candidate != null ? candidate.source() : BlockPos.ZERO;
	}

	public UpdatePlatingGravityPacket(FriendlyByteBuf buf)
	{
		this.hasChoice = buf.readBoolean();
		if (this.hasChoice) {
			this.direction = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
			this.shipFrame = buf.readBoolean();
			this.volume = buf.readDouble();
			this.source = buf.readBlockPos();
		} else {
			this.direction = Vec3.ZERO;
			this.shipFrame = false;
			this.volume = 0;
			this.source = BlockPos.ZERO;
		}
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeBoolean(this.hasChoice);
		if (this.hasChoice) {
			buf.writeDouble(this.direction.x);
			buf.writeDouble(this.direction.y);
			buf.writeDouble(this.direction.z);
			buf.writeBoolean(this.shipFrame);
			buf.writeDouble(this.volume);
			buf.writeBlockPos(this.source);
		}
	}

	public static class Handler
	{
		public static boolean onMessage(UpdatePlatingGravityPacket message, Supplier<NetworkEvent.Context> ctx)
		{
			ctx.get().enqueueWork(() ->
			{
				if (ctx.get().getDirection().getReceptionSide().isServer())
				{
					ServerPlayer sender = ctx.get().getSender();
					if (sender == null) {
						return;
					}
					GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponent(sender);
					if (cap == null) {
						return;
					}
					if (!message.hasChoice || message.direction.lengthSqr() < 1.0e-6) {
						cap.setClientReportedPlating(null);
						return;
					}
					PlatingCandidate candidate = new PlatingCandidate(
						message.direction.normalize(), message.shipFrame,
						Math.min(Math.max(message.volume, 0.0), 64.0), message.source
					);
					cap.setClientReportedPlating(cap.isPlatingReportValid(candidate) ? candidate : null);
				}
			});

			ctx.get().setPacketHandled(true);
			return true;
		}
	}
}
