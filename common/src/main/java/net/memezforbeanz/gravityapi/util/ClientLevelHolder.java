package net.memezforbeanz.gravityapi.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/** Client-only: kept out of GCUtil so the dedicated server never verifies a reference to client classes. */
final class ClientLevelHolder
{
	static Level level()
	{
		return Minecraft.getInstance().level;
	}
}
