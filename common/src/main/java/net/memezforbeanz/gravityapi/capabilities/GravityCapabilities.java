package net.memezforbeanz.gravityapi.capabilities;

import net.minecraft.world.entity.Entity;

public final class GravityCapabilities {
    private GravityCapabilities() {}
    public static GravityCapabilityImpl get(Entity entity) { return ((GravityEntity) entity).gravityapi$gravity(); }
}
