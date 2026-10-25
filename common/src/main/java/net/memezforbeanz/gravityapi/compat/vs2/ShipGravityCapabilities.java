package net.memezforbeanz.gravityapi.compat.vs2;

import net.minecraft.world.entity.Entity;
import net.memezforbeanz.gravityapi.capabilities.GravityEntity;
import org.jetbrains.annotations.Nullable;

public final class ShipGravityCapabilities {
    private ShipGravityCapabilities() {}
    public static @Nullable ShipGravityCapabilityImpl get(Entity entity) {
        return ((GravityEntity) entity).gravityapi$shipGravity();
    }
}
