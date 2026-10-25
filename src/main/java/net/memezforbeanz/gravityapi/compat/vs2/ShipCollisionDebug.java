package net.memezforbeanz.gravityapi.compat.vs2;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Throttled logging for the ship gravity collision path, enabled by {@link #ENABLED} or the {@code debugShipCollision} config option (players only). */
public final class ShipCollisionDebug {

    public static final Logger LOG = LoggerFactory.getLogger("VSGravityCollision");

    /** Master switch for all entities. Very verbose: keep off in releases. */
    public static boolean ENABLED = false;

    /** Extra-verbose per-substep logs. */
    public static boolean VERBOSE = false;

    /** How often (in ticks) a given entity is allowed to log. */
    public static final int LOG_INTERVAL_TICKS = 1;

    private ShipCollisionDebug() {
    }

    /** Whether the entity logs this tick (once per {@link #LOG_INTERVAL_TICKS}). */
    public static boolean shouldLog(Entity entity) {
        if (entity == null || entity.tickCount % LOG_INTERVAL_TICKS != 0) {
            return false;
        }
        return ENABLED || (entity instanceof Player && configEnabled());
    }

    /** {@link #shouldLog(Entity)} for players only, for call sites that run for every entity. */
    public static boolean shouldLogPlayer(Entity entity) {
        return entity instanceof Player && shouldLog(entity);
    }

    /** Verbose per-substep gate (detection / ctx / status). */
    public static boolean shouldLogVerbose(Entity entity) {
        return VERBOSE && shouldLog(entity);
    }

    /** The {@code debugShipCollision} option, read live (config screens change it without a reload event). */
    private static boolean configEnabled() {
        try {
            return net.memezforbeanz.gravityapi.config.GravityConfig.debugShipCollision.get();
        } catch (IllegalStateException e) {
            return false; // config not loaded yet
        }
    }

    public static void log(String format, Object... args) {
        if (ENABLED || configEnabled()) {
            LOG.info(format, args);
        }
    }
}
