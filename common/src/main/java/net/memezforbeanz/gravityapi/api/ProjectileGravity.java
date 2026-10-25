package net.memezforbeanz.gravityapi.api;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import net.memezforbeanz.gravityapi.mixin.ThrowableProjectileAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Projectile gravity and gravity-aware mob aiming.
 * <p>
 * Projectiles have no gravity frame: each tick they accelerate along the gravity field at their
 * position ({@link GravityFields}), so an arrow curves round a planet instead of flipping between
 * faces. Outside fields they fall along their own gravity direction, as before. Constant-velocity
 * projectiles (fireballs) keep their speed and only have their heading bent by fields.
 * <p>
 * Mobs aim by simulating the shot with the same gravity and moving the aim point by each miss
 * until the simulated shot meets the (moving) target, which finds curved "trick shots".
 */
public final class ProjectileGravity {

    /** Vanilla gravity per tick at 1 g: arrows. */
    public static final double ARROW_GRAVITY = 0.05F;
    /** Vanilla gravity per tick at 1 g: llama spit. */
    public static final double LLAMA_SPIT_GRAVITY = 0.06F;
    /** Heading bend per tick at 1 g for constant-velocity projectiles (fireballs, skulls). */
    public static final double STEERED_GRAVITY = 0.05;
    /** Air drag per tick of arrows, throwables and spit. */
    public static final double DRAG = 0.99F;

    /** The multishot angle (degrees) of the crossbow shot a mob is firing, set around its shoot call. */
    public static final ThreadLocal<Float> MULTISHOT_ANGLE = new ThreadLocal<>();

    private static final int AIM_ITERATIONS = 6;
    private static final int AIM_MAX_TICKS = 120;
    /** A simulated shot this close (blocks) to the target counts as a hit. */
    private static final double AIM_TOLERANCE = 0.25;

    private ProjectileGravity() {
    }

    /** This tick's gravity change of a projectile's velocity, for vanilla gravity {@code base} per tick at 1 g. */
    public static Vec3 acceleration(Entity projectile, double base) {
        return accelerationAt(projectile, projectile.position()).scale(base);
    }

    /**
     * A projectile's gravity at {@code pos} in g: the field there, else its own gravity direction
     * and strength. The aim simulation uses this too, so it flies the same physics.
     */
    private static Vec3 accelerationAt(Entity projectile, Vec3 pos) {
        Vec3 field = GravityFields.fieldAcceleration(projectile.level(), pos);
        if (field != null) {
            return field;
        }
        return GravityChangerAPI.getGravityDirectionVec(projectile).getVector()
                .scale(GravityChangerAPI.getGravityStrength(projectile));
    }

    /**
     * Move a projectile just spawned at the shooter's world-Y eye height ({@code getEyeY() - 0.1})
     * to its real eyes, 0.1 below them along its gravity.
     */
    public static void placeAtEyes(Entity projectile, LivingEntity shooter) {
        GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(shooter);
        if (!gravity.equals(GravityDirection.DOWN)) {
            Vec3 position = shooter.getEyePosition().add(gravity.vecPlayerToWorld(new Vec3(0, -0.1F, 0)));
            projectile.setPos(position.x, position.y, position.z);
        }
    }

    /** Vanilla gravity per tick at 1 g for a projectile, or 0 if it has none. */
    public static double baseGravity(Projectile projectile) {
        if (projectile.isNoGravity()) {
            return 0;
        }
        if (projectile instanceof AbstractArrow) {
            return ARROW_GRAVITY;
        }
        if (projectile instanceof ThrowableProjectile throwable) {
            return ((ThrowableProjectileAccessor) throwable).gravityapi$getGravity();
        }
        if (projectile instanceof LlamaSpit) {
            return LLAMA_SPIT_GRAVITY;
        }
        return 0;
    }

    /** Where mobs aim: a third of the way up the target's body, along its own up. */
    public static Vec3 aimPoint(Entity target) {
        return target.position().add(GravityChangerAPI.getGravityDirectionVec(target)
                .vecPlayerToWorld(new Vec3(0, target.getBbHeight() / 3, 0)));
    }

    /** The target's world velocity from its last move (server-side player velocity is unreliable). */
    private static Vec3 targetVelocity(Entity target) {
        return target.position().subtract(target.xOld, target.yOld, target.zOld);
    }

    private static boolean nonDownGravity(Entity entity) {
        return !GravityChangerAPI.getGravityDirectionVec(entity).equals(GravityDirection.DOWN);
    }

    /**
     * The direction a mob should shoot {@code projectile} at {@code speed} to hit its target through
     * the gravity on the way, or null to keep vanilla's aim: no mob owner or target, no gravity, or
     * plain world-down gravity along the whole shot ({@code shot} is vanilla's aim). A crossbow multishot side bolt
     * ({@link #MULTISHOT_ANGLE}) is turned off the solved shot by its angle, like vanilla does.
     */
    @Nullable
    public static Vec3 aimShot(Projectile projectile, Vec3 shot, double speed) {
        if (!(projectile.getOwner() instanceof Mob mob) || mob.getTarget() == null) {
            return null;
        }
        LivingEntity target = mob.getTarget();
        double base = baseGravity(projectile);
        if (base <= 0 || speed <= 0) {
            return null;
        }
        boolean framed = nonDownGravity(mob) || nonDownGravity(target);
        if (!framed && !GravityFields.hasFields(projectile.level())) {
            return null;
        }
        Vec3 origin = projectile.position();
        // Vanilla's own (raised) shot can cross a field the direct line misses
        boolean affected = framed || (shot.lengthSqr() > 1.0E-9
                && simulateDrag(projectile, origin, shot.normalize(), speed, base).throughField());
        Vec3 solved = solve(origin, aimPoint(target), targetVelocity(target), affected,
                direction -> simulateDrag(projectile, origin, direction, speed, base));
        if (solved == null) {
            return null;
        }
        Float multishot = MULTISHOT_ANGLE.get();
        if (multishot != null && multishot != 0) {
            GravityDirection frame = GravityChangerAPI.getGravityDirectionVec(mob);
            solved = multishotShot(solved, frame.vecPlayerToWorld(new Vec3(0, 1, 0)),
                    frame.vecPlayerToWorld(new Vec3(0, 0, 1)), multishot);
        }
        return solved;
    }

    /** {@link #aimShot} for a constant-velocity projectile accelerating by {@code power} per tick. */
    @Nullable
    public static Vec3 aimSteeredShot(Projectile projectile, Mob mob, double power, double inertia) {
        LivingEntity target = mob.getTarget();
        if (target == null || power <= 0 || !GravityFields.hasFields(projectile.level())) {
            return null;
        }
        Level level = projectile.level();
        Vec3 origin = projectile.position();
        return solve(origin, aimPoint(target), targetVelocity(target), false,
                direction -> simulateSteered(level, origin, direction, power, inertia));
    }

    /**
     * Vanilla's multishot rotation ({@code CrossbowAttackMob.getProjectileShotVector}) in the shooter's
     * frame: about its up, or its forward for a shot straight along up (vanilla's view-up fallback).
     */
    private static Vec3 multishotShot(Vec3 shot, Vec3 up, Vec3 forward, float angleDegrees) {
        Vector3f direction = shot.toVector3f().normalize();
        Vector3f side = new Vector3f(direction).cross(up.toVector3f());
        if (side.lengthSquared() <= 1.0E-7F) {
            side = new Vector3f(direction).cross(forward.toVector3f());
        }
        if (side.lengthSquared() <= 1.0E-7F) {
            return shot;
        }
        Vector3f axis = new Vector3f(direction).rotateAxis((float) Math.PI / 2F, side.x, side.y, side.z);
        return new Vec3(new Vector3f(direction).rotateAxis((float) Math.toRadians(angleDegrees), axis.x, axis.y, axis.z));
    }

    // ------------------------------------------------------------------
    // Solver
    // ------------------------------------------------------------------

    /** A simulated shot: positions per tick from tick 1, and whether it passed through a field. */
    private record Flight(Vec3[] positions, boolean throughField) {
    }

    private interface Shot {
        Flight fly(Vec3 direction);
    }

    /**
     * Aim-point correction: shoot at {@code aim}, see where the simulated shot passes the target,
     * move the aim point by the miss, repeat. Returns the best direction found, or null when the
     * first shot meets no field and {@code framed} is false (vanilla's aim then stands).
     */
    @Nullable
    private static Vec3 solve(Vec3 origin, Vec3 aim, Vec3 targetVelocity, boolean framed, Shot shot) {
        Vec3 aimAt = aim;
        Vec3 best = null;
        double bestMiss = Double.MAX_VALUE;
        for (int i = 0; i < AIM_ITERATIONS; i++) {
            Vec3 direction = aimAt.subtract(origin);
            if (direction.lengthSqr() < 1.0E-9) {
                break;
            }
            direction = direction.normalize();
            Flight flight = shot.fly(direction);
            if (i == 0 && !framed && !flight.throughField()) {
                return null;
            }
            int tick = closestTick(flight.positions(), aim, targetVelocity);
            Vec3 miss = aim.add(targetVelocity.scale(tick + 1)).subtract(flight.positions()[tick]);
            double missLength = miss.length();
            if (missLength < bestMiss) {
                bestMiss = missLength;
                best = direction;
            }
            if (missLength < AIM_TOLERANCE) {
                break;
            }
            aimAt = aimAt.add(miss);
        }
        return best;
    }

    private static int closestTick(Vec3[] positions, Vec3 aim, Vec3 targetVelocity) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int t = 0; t < positions.length; t++) {
            double distance = positions[t].distanceToSqr(aim.add(targetVelocity.scale(t + 1)));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = t;
            }
        }
        return best;
    }

    /** Arrows, throwables, spit: gravity sampled where the tick starts, then move, drag and gravity. */
    private static Flight simulateDrag(Projectile projectile, Vec3 origin, Vec3 direction, double speed, double base) {
        Vec3[] positions = new Vec3[AIM_MAX_TICKS];
        boolean throughField = false;
        Vec3 position = origin;
        Vec3 velocity = direction.scale(speed);
        for (int t = 0; t < AIM_MAX_TICKS; t++) {
            throughField |= GravityFields.fieldAcceleration(projectile.level(), position) != null;
            Vec3 gravity = accelerationAt(projectile, position).scale(base);
            position = position.add(velocity);
            positions[t] = position;
            velocity = velocity.scale(DRAG).add(gravity);
        }
        return new Flight(positions, throughField);
    }

    /** Fireballs: move, add power with inertia, then the field at the new position bends the heading. */
    private static Flight simulateSteered(Level level, Vec3 origin, Vec3 direction, double power, double inertia) {
        Vec3[] positions = new Vec3[AIM_MAX_TICKS];
        boolean throughField = false;
        Vec3 position = origin;
        Vec3 velocity = Vec3.ZERO;
        Vec3 push = direction.scale(power);
        for (int t = 0; t < AIM_MAX_TICKS; t++) {
            position = position.add(velocity);
            positions[t] = position;
            velocity = velocity.add(push).scale(inertia);
            Vec3 field = GravityFields.fieldAcceleration(level, position);
            if (field != null) {
                throughField = true;
                velocity = steer(velocity, field);
                push = velocity.normalize().scale(power);
            }
        }
        return new Flight(positions, throughField);
    }

    /** Bend {@code velocity} by the field acceleration {@code field} (in g) at constant speed. */
    public static Vec3 steer(Vec3 velocity, Vec3 field) {
        double speed = velocity.length();
        if (speed < 1.0E-6) {
            return velocity;
        }
        Vec3 bent = velocity.add(field.scale(STEERED_GRAVITY));
        return bent.lengthSqr() < 1.0E-12 ? velocity : bent.normalize().scale(speed);
    }
}
