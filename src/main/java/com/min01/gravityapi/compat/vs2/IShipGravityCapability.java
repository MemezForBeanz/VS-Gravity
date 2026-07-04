package com.min01.gravityapi.compat.vs2;

import com.min01.gravityapi.GravityAPI;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;
import net.minecraftforge.common.util.INBTSerializable;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for ship-relative gravity capability.
 * This capability stores gravity settings relative to a Valkyrien Skies ship.
 */
@AutoRegisterCapability
public interface IShipGravityCapability extends INBTSerializable<CompoundTag> {

    ResourceLocation ID = new ResourceLocation(GravityAPI.MODID, "ship_gravity");

    /**
     * Get the ship ID this gravity is relative to, or null if using world gravity
     */
    @Nullable
    Long getShipId();

    /**
     * Set the ship ID for ship-relative gravity
     * @param shipId The ship ID, or null to use world gravity
     */
    void setShipId(@Nullable Long shipId);

    /**
     * Get the gravity direction relative to the ship (or world if not on a ship)
     */
    Direction getShipRelativeGravityDirection();

    /**
     * Set the gravity direction relative to the ship
     */
    void setShipRelativeGravityDirection(Direction direction);

    /**
     * Check if ship-relative gravity is enabled
     */
    boolean isShipGravityEnabled();

    /**
     * Enable or disable ship-relative gravity
     */
    void setShipGravityEnabled(boolean enabled);

    /**
     * Check if the player should automatically align gravity to the ship they step on
     */
    boolean isAutoAlignEnabled();

    /**
     * Enable or disable automatic gravity alignment when stepping on ships
     */
    void setAutoAlignEnabled(boolean enabled);

    /**
     * Tick update for ship gravity
     */
    void tick();
}

