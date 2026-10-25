package net.memezforbeanz.gravityapi.util;

/**
 * Per-entity, per-tick cache of the VS2 ship-intersection query, implemented on every entity by
 * {@code EntityMixin}. Holds the raw ship ({@code Object}, so VS2 isn't linked); null results are
 * cached too.
 */
public interface ShipIntersectionCache {

    Object gravityapi$getCachedShip();

    long gravityapi$getShipCacheTime();

    void gravityapi$setCachedShip(long gameTime, Object ship);
}
