package com.min01.gravityapi.compat.vs2;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;

import javax.annotation.Nonnull;

/**
 * Capability registration and attachment for ship-relative gravity.
 */
public class ShipGravityCapabilities {
    
    public static final Capability<IShipGravityCapability> SHIP_GRAVITY = CapabilityManager.get(new CapabilityToken<>() {});
    
    /**
     * Attach the ship gravity capability to entities
     */
    public static void attachEntityCapability(AttachCapabilitiesEvent<Entity> event) {
        // Only attach if VS2 is loaded
        if (!VS2Integration.isVS2Loaded()) {
            return;
        }
        
        event.addCapability(IShipGravityCapability.ID, new ICapabilitySerializable<CompoundTag>() {
            private final LazyOptional<IShipGravityCapability> instance = LazyOptional.of(() -> {
                ShipGravityCapabilityImpl impl = new ShipGravityCapabilityImpl();
                impl.setEntity(event.getObject());
                return impl;
            });
            
            @Nonnull
            @Override
            public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing) {
                return SHIP_GRAVITY.orEmpty(capability, this.instance.cast());
            }
            
            @Override
            public CompoundTag serializeNBT() {
                return this.instance.orElseThrow(NullPointerException::new).serializeNBT();
            }
            
            @Override
            public void deserializeNBT(CompoundTag nbt) {
                this.instance.orElseThrow(NullPointerException::new).deserializeNBT(nbt);
            }
        });
    }
}

