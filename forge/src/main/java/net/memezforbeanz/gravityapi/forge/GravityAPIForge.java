package net.memezforbeanz.gravityapi.forge;

import dev.architectury.platform.forge.EventBuses;
import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.memezforbeanz.gravityapi.config.GravityServerConfig;
import net.memezforbeanz.gravityapi.forge.client.ForgeRotatedModel;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(GravityAPI.MODID)
public class GravityAPIForge
{
	public GravityAPIForge()
	{
		EventBuses.registerModEventBus(GravityAPI.MODID, FMLJavaModLoadingContext.get().getModEventBus());

		ModLoadingContext ctx = ModLoadingContext.get();
		ctx.registerConfig(Type.COMMON, GravityConfig.CONFIG_SPEC, "gravity-api.toml");
		ctx.registerConfig(Type.SERVER, GravityServerConfig.CONFIG_SPEC, "gravity-api-server.toml");

		GravityAPI.init();
		if (FMLEnvironment.dist.isClient())
		{
			ForgeRotatedModel.install();
		}
	}
}
