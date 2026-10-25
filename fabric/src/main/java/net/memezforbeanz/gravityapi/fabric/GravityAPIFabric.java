package net.memezforbeanz.gravityapi.fabric;

import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import net.fabricmc.api.ModInitializer;
import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.memezforbeanz.gravityapi.config.GravityServerConfig;
import net.minecraftforge.fml.config.ModConfig;

public final class GravityAPIFabric implements ModInitializer
{
	@Override
	public void onInitialize()
	{
		// Forge Config API Port: same specs, files and SERVER-config sync as on Forge
		ForgeConfigRegistry.INSTANCE.register(GravityAPI.MODID, ModConfig.Type.COMMON, GravityConfig.CONFIG_SPEC, "gravity-api.toml");
		ForgeConfigRegistry.INSTANCE.register(GravityAPI.MODID, ModConfig.Type.SERVER, GravityServerConfig.CONFIG_SPEC, "gravity-api-server.toml");

		GravityAPI.init();
	}
}
