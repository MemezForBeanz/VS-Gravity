package net.memezforbeanz.gravityapi.compat.planets;

import earth.terrarium.adastra.api.events.AdAstraEvents;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;

/**
 * Stands Ad Astra's planet gravity down for entities under a gravity strength override,
 * through its public event API (no mixins). Ad Astra applies planet gravity at the head of
 * {@code LivingEntity.travel} as a world-Y velocity correction (or, at ~0 g, replaces travel
 * with its zero-g movement) — on top of our own gravity, and in world space even when the
 * entity's gravity is rotated. Under an override our strength is the only gravity felt:
 * <ul>
 *     <li>the gravity / zero-gravity tick listeners veto Ad Astra's travel handling — this
 *     also covers the client player, whose gravity Ad Astra reads from synced planet data
 *     rather than {@code getGravity};</li>
 *     <li>the entity gravity listener reports Earth-normal 1.0, so its fall damage scaling
 *     and item/minecart/projectile corrections become no-ops.</li>
 * </ul>
 * Only loaded when Ad Astra is present.
 */
public final class AdAstraCompat {

    private AdAstraCompat() {
    }

    public static void register() {
        AdAstraEvents.GravityTickEvent.register((level, entity, travelVector, pos) ->
                !GravityChangerAPI.isGravityStrengthOverridden(entity));
        AdAstraEvents.ZeroGravityTickEvent.register((level, entity, travelVector, pos) ->
                !GravityChangerAPI.isGravityStrengthOverridden(entity));
        AdAstraEvents.EntityGravityEvent.register((entity, gravity) ->
                GravityChangerAPI.isGravityStrengthOverridden(entity) ? 1.0f : gravity);
    }
}
