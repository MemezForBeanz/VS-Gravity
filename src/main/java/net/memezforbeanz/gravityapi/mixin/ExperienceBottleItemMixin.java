package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;

@Mixin(ExperienceBottleItem.class)
public abstract class ExperienceBottleItemMixin extends Item {
    
    public ExperienceBottleItemMixin(Properties settings) {
        super(settings);
    }

}



