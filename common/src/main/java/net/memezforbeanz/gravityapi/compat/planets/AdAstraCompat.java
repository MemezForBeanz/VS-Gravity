package net.memezforbeanz.gravityapi.compat.planets;

import earth.terrarium.adastra.api.events.AdAstraEvents;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;

/**
 * Stands Ad Astra's planet gravity down under a gravity strength override, through its event API.
 * The tick listeners veto its travel handling (including zero-g movement); the entity gravity
 * listener reports 1.0 so its other corrections are no-ops. Only loaded with Ad Astra.
 */
public final class AdAstraCompat {

    private AdAstraCompat() {
    }

    public static void register() {
        AdAstraEvents.GravityTickEvent.register((level, entity, travelVector, pos) ->
                !GravityChangerAPI.isPlanetGravitySuppressed(entity));
        AdAstraEvents.ZeroGravityTickEvent.register((level, entity, travelVector, pos) ->
                !GravityChangerAPI.isPlanetGravitySuppressed(entity));
        AdAstraEvents.EntityGravityEvent.register((entity, gravity) ->
                GravityChangerAPI.isPlanetGravitySuppressed(entity) ? 1.0f : gravity);
    }
}
