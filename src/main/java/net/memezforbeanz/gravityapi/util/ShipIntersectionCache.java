package net.memezforbeanz.gravityapi.util;

/**
 * Per-entity, per-tick cache of the VS2 ship-intersection query result. Implemented on every
 * entity by {@code EntityMixin}.
 *
 * <p>The underlying query ({@code getShipsIntersecting}, a world-AABB scan over loaded ships) is
 * hit from the collision pipeline and hitbox checks several times per entity per tick; the ship an
 * entity overlaps cannot meaningfully change mid-tick, so one query per game tick is enough. The
 * cached value is the raw VS2 ship object (typed {@code Object} so this interface never links VS2
 * classes), and {@code null} results are cached too - the timestamp alone marks validity.
 */
public interface ShipIntersectionCache {

    Object gravityapi$getCachedShip();

    long gravityapi$getShipCacheTime();

    void gravityapi$setCachedShip(long gameTime, Object ship);
}
