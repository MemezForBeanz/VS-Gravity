package net.memezforbeanz.gravityapi.config;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server-authoritative options. Forge syncs SERVER configs to clients, so both sides agree. */
public class GravityServerConfig
{
	public static final ForgeConfigSpec CONFIG_SPEC;

	public static ForgeConfigSpec.BooleanValue omniPlacement;
	public static ForgeConfigSpec.BooleanValue rotationLayer;

	static
	{
		Pair<GravityServerConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(GravityServerConfig::new);
		CONFIG_SPEC = pair.getRight();
	}

	public GravityServerConfig(ForgeConfigSpec.Builder config)
	{
		rotationLayer = config.comment("allow new full cube-frame block rotations; existing rotations remain active").define("rotationLayer", true);
		omniPlacement = config.comment("orient supported blocks in the logical player gravity frame").define("omniPlacement", true);
	}

	public static boolean rotationLayer() {
		try { return rotationLayer.get(); } catch (IllegalStateException notLoaded) { return true; }
	}

	public static boolean omniPlacement()
	{
		try {
			return omniPlacement.get();
		} catch (IllegalStateException notLoaded) {
			return true;
		}
	}
}
