package net.memezforbeanz.gravityapi.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.memezforbeanz.gravityapi.fabric.client.FabricRotatedModel;
import net.memezforbeanz.gravityapi.rotation.RotatedModel;

public final class GravityAPIFabricClient implements ClientModInitializer
{
	@Override
	public void onInitializeClient()
	{
		RotatedModel.factory = FabricRotatedModel::new;
	}
}
