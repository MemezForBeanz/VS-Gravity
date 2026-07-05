package net.memezforbeanz.gravityapi.event;

import com.google.common.eventbus.Subscribe;
import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.command.DirectionArgumentType;
import net.memezforbeanz.gravityapi.command.LocalDirectionArgumentType;

import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, modid = GravityAPI.MODID)
public class RegisterArgumentTypes
{
	@Subscribe
	public static void onFMLCommonSetup(FMLCommonSetupEvent event)
	{
		ArgumentTypeInfos.registerByClass(DirectionArgumentType.class, SingletonArgumentInfo.contextFree(DirectionArgumentType::new));
		ArgumentTypeInfos.registerByClass(LocalDirectionArgumentType.class, SingletonArgumentInfo.contextFree(LocalDirectionArgumentType::new));
	}
}
