package net.memezforbeanz.gravityapi;

import net.memezforbeanz.gravityapi.capabilities.GravityCapabilities;
import net.memezforbeanz.gravityapi.compat.planets.AdAstraCompat;
import net.memezforbeanz.gravityapi.compat.vs2.ShipGravityCapabilities;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.memezforbeanz.gravityapi.init.GravityBlocks;
import net.memezforbeanz.gravityapi.init.GravityCreativeTabs;
import net.memezforbeanz.gravityapi.init.GravityItems;
import net.memezforbeanz.gravityapi.init.GravityMobEffects;
import net.memezforbeanz.gravityapi.network.GravityNetwork;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(GravityAPI.MODID)
public class GravityAPI
{
	public static final String MODID = "gravityapi";
	
	public GravityAPI() 
	{
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		ModLoadingContext ctx = ModLoadingContext.get();
		
		GravityItems.ITEMS.register(bus);
		GravityBlocks.BLOCKS.register(bus);
		GravityBlocks.BLOCK_ENTITIES.register(bus);
		GravityMobEffects.EFFECTS.register(bus);
		GravityMobEffects.POTIONS.register(bus);
		GravityCreativeTabs.CREATIVE_MODE_TAB.register(bus);

		GravityNetwork.registerMessages();
		MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, GravityCapabilities::attachEntityCapability);

		if (VS2Integration.isVS2Loaded()) {
			MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, ShipGravityCapabilities::attachEntityCapability);
		}

		ctx.registerConfig(Type.COMMON, GravityConfig.CONFIG_SPEC, "gravity-api.toml");
		ctx.registerConfig(Type.SERVER, net.memezforbeanz.gravityapi.config.GravityServerConfig.CONFIG_SPEC, "gravity-api-server.toml");
		// Planet gravity compat: strength overrides replace Ad Astra's planet gravity
		// (Cosmic Horizons is handled by CosmosGravityProcedureMixin)
		if (ModList.get().isLoaded("ad_astra")) {
			AdAstraCompat.register();
		}
	}
}
