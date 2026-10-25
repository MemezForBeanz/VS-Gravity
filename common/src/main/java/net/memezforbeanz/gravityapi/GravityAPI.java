package net.memezforbeanz.gravityapi;

import dev.architectury.platform.Platform;
import net.memezforbeanz.gravityapi.compat.planets.AdAstraCompat;
import net.memezforbeanz.gravityapi.event.CommonEvents;
import net.memezforbeanz.gravityapi.init.GravityBlocks;
import net.memezforbeanz.gravityapi.init.GravityCreativeTabs;
import net.memezforbeanz.gravityapi.init.GravityItems;
import net.memezforbeanz.gravityapi.init.GravityMobEffects;
import net.memezforbeanz.gravityapi.network.GravityNetwork;

/** Loader-neutral setup; each loader's entrypoint registers the configs, then calls {@link #init()}. */
public class GravityAPI
{
	public static final String MODID = "gravityapi";

	public static void init()
	{
		// Blocks before block entities and items: on Fabric each register() call resolves its suppliers at once
		GravityBlocks.BLOCKS.register();
		GravityBlocks.BLOCK_ENTITIES.register();
		GravityItems.ITEMS.register();
		GravityMobEffects.EFFECTS.register();
		GravityMobEffects.POTIONS.register();
		GravityCreativeTabs.CREATIVE_MODE_TAB.register();

		GravityNetwork.registerMessages();
		CommonEvents.register();

		// Planet gravity compat: strength overrides replace Ad Astra's planet gravity
		// (Cosmic Horizons is handled by CosmosGravityProcedureMixin)
		if (Platform.isModLoaded("ad_astra"))
		{
			AdAstraCompat.register();
		}
	}
}
