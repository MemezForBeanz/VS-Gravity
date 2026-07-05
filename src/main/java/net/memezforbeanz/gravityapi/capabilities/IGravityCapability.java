package net.memezforbeanz.gravityapi.capabilities;

import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;
import net.minecraftforge.common.util.INBTSerializable;
import org.jetbrains.annotations.Nullable;

@AutoRegisterCapability
public interface IGravityCapability extends INBTSerializable<CompoundTag>
{
	ResourceLocation ID = new ResourceLocation(GravityAPI.MODID, "gravity");

	void setEntity(Entity entity);
	
	void tick();
	
	void applyGravityChange();
	
	/**
	 * Sync gravity state from server to client
	 * @param noAnimation Whether to skip rotation animation
	 * @param baseGravityDirection The base gravity direction
	 * @param currentGravityDirection The current gravity direction (cardinal)
	 * @param baseGravityStrength The base gravity strength
	 * @param currentGravityStrength The current gravity strength
	 * @param arbitraryGravity The arbitrary gravity direction for VS ship angles (may be null)
	 */
	void sync(boolean noAnimation, Direction baseGravityDirection, Direction currentGravityDirection, double baseGravityStrength, double currentGravityStrength, @Nullable GravityDirection arbitraryGravity);

	/**
	 * Sync gravity state from server to client with camera-only flag
	 */
	void sync(boolean noAnimation, Direction baseGravityDirection, Direction currentGravityDirection, double baseGravityStrength, double currentGravityStrength, @Nullable GravityDirection arbitraryGravity, boolean isCameraOnly);

	/**
	 * Sync gravity state from server to client (backward compatible, no arbitrary direction)
	 */
	default void sync(boolean noAnimation, Direction baseGravityDirection, Direction currentGravityDirection, double baseGravityStrength, double currentGravityStrength) {
		sync(noAnimation, baseGravityDirection, currentGravityDirection, baseGravityStrength, currentGravityStrength, null);
	}
}
