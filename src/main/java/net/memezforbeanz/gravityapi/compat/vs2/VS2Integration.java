package net.memezforbeanz.gravityapi.compat.vs2;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Integration class for Valkyrien Skies 2 compatibility.
 * Provides methods to interact with VS2 ships and transform coordinates/rotations.
 */
public class VS2Integration {
    
    private static final String VS2_MODID = "valkyrienskies";
    private static Boolean vs2Loaded = null;
    
    /**
     * Check if Valkyrien Skies 2 is loaded
     */
    public static boolean isVS2Loaded() {
        if (vs2Loaded == null) {
            vs2Loaded = ModList.get().isLoaded(VS2_MODID);
        }
        return vs2Loaded;
    }
    
    /**
     * Get the ship ID that an entity is standing on, or null if not on a ship
     */
    @Nullable
    public static Long getShipMountedOn(Entity entity) {
        if (!isVS2Loaded()) {
            return null;
        }
        return VS2Helper.getShipMountedOn(entity);
    }
    
    /**
     * Check if an entity is currently on a Valkyrien Skies ship
     */
    public static boolean isOnShip(Entity entity) {
        return getShipMountedOn(entity) != null;
    }
    
    /**
     * Get the ship's rotation as a double array [x, y, z, w], or null if not on a ship
     */
    @Nullable
    public static double[] getShipRotation(Entity entity) {
        if (!isVS2Loaded()) {
            return null;
        }
        return VS2Helper.getShipRotation(entity);
    }
    
    /**
     * Transform a world-space direction to ship-local space
     */
    @Nullable
    public static Vec3 worldToShipDirection(Entity entity, Vec3 worldDir) {
        if (!isVS2Loaded()) {
            return null;
        }
        return VS2Helper.worldToShipDirection(entity, worldDir);
    }
    
    /**
     * Transform a ship-local direction to world space
     */
    @Nullable
    public static Vec3 shipToWorldDirection(Entity entity, Vec3 shipDir) {
        if (!isVS2Loaded()) {
            return null;
        }
        return VS2Helper.shipToWorldDirection(entity, shipDir);
    }
    
    /**
     * Get the "up" direction of the ship in world space (ship's local Y+ in world coordinates)
     */
    @Nullable
    public static Vec3 getShipUpDirection(Entity entity) {
        if (!isVS2Loaded()) {
            return null;
        }
        return VS2Helper.getShipUpDirection(entity);
    }
    
    /**
     * Transform a position from world space to ship-local space
     */
    @Nullable
    public static Vec3 worldToShipPosition(Entity entity, Vec3 worldPos) {
        if (!isVS2Loaded()) {
            return null;
        }
        return VS2Helper.worldToShipPosition(entity, worldPos);
    }
    
    /**
     * Transform a position from ship-local space to world space
     */
    @Nullable
    public static Vec3 shipToWorldPosition(Entity entity, Vec3 shipPos) {
        if (!isVS2Loaded()) {
            return null;
        }
        return VS2Helper.shipToWorldPosition(entity, shipPos);
    }
}

