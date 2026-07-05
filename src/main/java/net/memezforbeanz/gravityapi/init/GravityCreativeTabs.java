package net.memezforbeanz.gravityapi.init;

import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.plating.GravityPlatingBlockEntity;
import net.memezforbeanz.gravityapi.plating.GravityPlatingItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class GravityCreativeTabs 
{
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TAB = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, GravityAPI.MODID);

    public static final RegistryObject<CreativeModeTab> GRAVITY_API = CREATIVE_MODE_TAB.register("gravityapi", () -> CreativeModeTab.builder()
    		.title(Component.translatable("itemGroup.gravityapi"))
    		.icon(() -> new ItemStack(GravityItems.GRAVITY_CHANGER_UP.get()))
    		.displayItems((enabledFeatures, output) -> 
    		{
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_UP.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_DOWN.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_EAST.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_WEST.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_NORTH.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_SOUTH.get()));
                
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_UP_AOE.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_DOWN_AOE.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_EAST_AOE.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_WEST_AOE.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_NORTH_AOE.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_CHANGER_SOUTH_AOE.get()));
                
                output.accept(GravityPlatingItem.createStack(
                    new GravityPlatingBlockEntity.SideData(true, 1)
                ));
                output.accept(GravityPlatingItem.createStack(
                    new GravityPlatingBlockEntity.SideData(true, 2)
                ));
                output.accept(GravityPlatingItem.createStack(
                    new GravityPlatingBlockEntity.SideData(true, 8)
                ));
                output.accept(GravityPlatingItem.createStack(
                    new GravityPlatingBlockEntity.SideData(true, 32)
                ));
                output.accept(GravityPlatingItem.createStack(
                    new GravityPlatingBlockEntity.SideData(true, 64)
                ));
                output.accept(GravityPlatingItem.createStack(
                    new GravityPlatingBlockEntity.SideData(false, 8)
                ));
                output.accept(GravityPlatingItem.createStack(
                    new GravityPlatingBlockEntity.SideData(false, 32)
                ));
                
                output.accept(new ItemStack(GravityItems.GRAVITY_ANCHOR_UP.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_ANCHOR_DOWN.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_ANCHOR_EAST.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_ANCHOR_WEST.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_ANCHOR_NORTH.get()));
                output.accept(new ItemStack(GravityItems.GRAVITY_ANCHOR_SOUTH.get()));

                // Gravity Generator (VS2 compatible)
                output.accept(new ItemStack(GravityItems.GRAVITY_GENERATOR.get()));
    		}).build());
}
