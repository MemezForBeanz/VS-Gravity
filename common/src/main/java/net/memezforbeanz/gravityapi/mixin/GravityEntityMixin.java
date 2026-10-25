package net.memezforbeanz.gravityapi.mixin;

import net.memezforbeanz.gravityapi.capabilities.*;
import net.memezforbeanz.gravityapi.compat.vs2.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Entity.class)
public abstract class GravityEntityMixin implements GravityEntity {
    @Unique private GravityCapabilityImpl gravityapi$gravity;
    @Unique private ShipGravityCapabilityImpl gravityapi$shipGravity;
    @Unique private boolean gravityapi$ready;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void gravityapi$ready(CallbackInfo ci) { gravityapi$ready = true; }

    public GravityCapabilityImpl gravityapi$gravity() {
        if (!gravityapi$ready) return null;
        if (gravityapi$gravity == null) {
            gravityapi$gravity = new GravityCapabilityImpl();
            gravityapi$gravity.setEntity((Entity) (Object) this);
        }
        return gravityapi$gravity;
    }
    public ShipGravityCapabilityImpl gravityapi$shipGravity() {
        if (!gravityapi$ready || !VS2Integration.isVS2Loaded()) return null;
        if (gravityapi$shipGravity == null) {
            gravityapi$shipGravity = new ShipGravityCapabilityImpl();
            gravityapi$shipGravity.setEntity((Entity) (Object) this);
        }
        return gravityapi$shipGravity;
    }
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void gravityapi$save(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        tag.put("gravityapi:gravity", gravityapi$gravity().serializeNBT());
        var ship = gravityapi$shipGravity();
        if (ship != null) tag.put("gravityapi:ship_gravity", ship.serializeNBT());
    }
    @Inject(method = "load", at = @At("RETURN"))
    private void gravityapi$load(CompoundTag tag, CallbackInfo ci) {
        CompoundTag gravity = gravityapi$data(tag, "gravityapi:gravity");
        if (gravity != null) gravityapi$gravity().deserializeNBT(gravity);
        CompoundTag ship = gravityapi$data(tag, "gravityapi:ship_gravity");
        if (ship != null && gravityapi$shipGravity() != null) gravityapi$shipGravity().deserializeNBT(ship);
    }
    @Unique private static CompoundTag gravityapi$data(CompoundTag tag, String key) {
        if (tag.contains(key, 10)) return tag.getCompound(key);
        CompoundTag legacy = tag.getCompound("ForgeCaps");
        return legacy.contains(key, 10) ? legacy.getCompound(key) : null;
    }
}
