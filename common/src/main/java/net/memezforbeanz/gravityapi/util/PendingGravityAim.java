package net.memezforbeanz.gravityapi.util;

/** A mob's fireball waiting to be aimed through gravity once its shooter has placed it. */
public interface PendingGravityAim {
    /** Aim now if still pending (no-op otherwise). */
    void gravityapi$aimNow();
}
