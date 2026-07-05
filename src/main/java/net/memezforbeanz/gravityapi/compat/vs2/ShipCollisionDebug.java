package net.memezforbeanz.gravityapi.compat.vs2;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized, throttled logging for the VS2 ship-local gravity collision path.
 *
 * <p>Toggle {@link #ENABLED} to turn the (fairly verbose) diagnostics on/off. Logging is throttled
 * per-entity by tick count so we get a readable burst once per {@link #LOG_INTERVAL_TICKS} ticks
 * instead of flooding the console every physics sub-step.
 */
public final class ShipCollisionDebug {

    public static final Logger LOG = LoggerFactory.getLogger("VSGravityCollision");

    /** Master switch for ship-collision diagnostics. */
    public static boolean ENABLED = true;

    /** Extra-verbose per-substep logs (detection, ctx transforms, status). Off by default. */
    public static boolean VERBOSE = false;

    /** How often (in ticks) a given entity is allowed to log. */
    public static final int LOG_INTERVAL_TICKS = 1;

    private ShipCollisionDebug() {
    }

    /**
     * Whether the given entity should log this tick. Gated to keep spam down; logs a burst (all of
     * a tick's collision sub-steps) once per {@link #LOG_INTERVAL_TICKS}.
     */
    public static boolean shouldLog(Entity entity) {
        return ENABLED && entity != null && (entity.tickCount % LOG_INTERVAL_TICKS == 0);
    }

    /**
     * Like {@link #shouldLog(Entity)} but only for players - used for high-frequency call sites
     * (e.g. ship detection, which runs for every gravity-capable entity) to avoid mob spam.
     */
    public static boolean shouldLogPlayer(Entity entity) {
        return entity instanceof Player && shouldLog(entity);
    }

    /** Verbose per-substep gate (detection / ctx / status). */
    public static boolean shouldLogVerbose(Entity entity) {
        return VERBOSE && shouldLog(entity);
    }

    public static void log(String format, Object... args) {
        if (ENABLED) {
            LOG.info(format, args);
        }
    }
}
