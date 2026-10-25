package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ThrowablePotionItem;

@Mixin(value = ThrowablePotionItem.class, priority = 1001)
public abstract class ThrowablePotionItemMixin extends PotionItem {
    
    public ThrowablePotionItemMixin(Properties settings) {
        super(settings);
    }

}



