package net.memezforbeanz.gravityapi;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.bawnorton.mixinsquared.MixinSquaredBootstrap;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;

public class GravityMixinPlugin implements IMixinConfigPlugin
{
    @Override
    public void onLoad(String mixinPackage)
    {
        MixinExtrasBootstrap.init();
        MixinSquaredBootstrap.init();
    }

	@Override
	public String getRefMapperConfig()
	{
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) 
	{
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		return switch (name)
		{
			case "ACEntityMixin" -> isModLoaded("alexscaves");
			case "MutantEndermanMixin" -> isModLoaded("mutantmonsters");
			case "CosmosGravityProcedureMixin" -> isModLoaded("cosmos");
			case "VS2EntityDraggerMixin", "VS2EntityShipCollisionUtilsMixin", "VS2RotationRelocationMixin" -> isModLoaded("valkyrienskies");
			default -> !name.startsWith("Embeddium") || isModLoaded("embeddium");
		};
	}

	/** Mixin plugins run before either loader's mod list API is usable, so ask the loading-time list directly. */
	private static boolean isModLoaded(String modId)
	{
		try
		{
			Class<?> fabric = Class.forName("net.fabricmc.loader.api.FabricLoader");
			Object loader = fabric.getMethod("getInstance").invoke(null);
			return (boolean) fabric.getMethod("isModLoaded", String.class).invoke(loader, modId);
		}
		catch (ClassNotFoundException e)
		{
			// Forge
		}
		catch (ReflectiveOperationException e)
		{
			return false;
		}
		try
		{
			Class<?> list = Class.forName("net.minecraftforge.fml.loading.LoadingModList");
			Object mods = list.getMethod("get").invoke(null);
			return list.getMethod("getModFileById", String.class).invoke(mods, modId) != null;
		}
		catch (ReflectiveOperationException e)
		{
			return false;
		}
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets)
	{
		
	}

	@Override
	public List<String> getMixins()
	{
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) 
	{
		
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
	{
		
	}
}
