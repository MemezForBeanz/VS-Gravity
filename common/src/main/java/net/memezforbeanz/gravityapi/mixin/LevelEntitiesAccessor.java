package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;

@Mixin(Level.class)
public interface LevelEntitiesAccessor {
    @Invoker("getEntities")
    LevelEntityGetter<Entity> gc_getEntities();
}
