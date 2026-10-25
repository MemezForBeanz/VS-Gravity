package net.memezforbeanz.gravityapi.capabilities;

import net.memezforbeanz.gravityapi.compat.vs2.ShipGravityCapabilityImpl;
import org.jetbrains.annotations.Nullable;

public interface GravityEntity {
    @Nullable GravityCapabilityImpl gravityapi$gravity();
    @Nullable ShipGravityCapabilityImpl gravityapi$shipGravity();
}
