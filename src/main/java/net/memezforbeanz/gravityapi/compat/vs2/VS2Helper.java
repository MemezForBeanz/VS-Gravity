package net.memezforbeanz.gravityapi.compat.vs2;

import net.memezforbeanz.gravityapi.util.ShipIntersectionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper class that directly interacts with VS2 API using reflection.
 * This class avoids direct imports of VS2 classes to prevent ClassNotFoundException
 * when VS2 is not present.
 */
public class VS2Helper {

    private static final Logger LOGGER = LoggerFactory.getLogger(VS2Helper.class);
    private static Method getShipManagingPosMethod = null;
    private static Method getShipsIntersectingMethod = null;
    private static boolean initAttempted = false;

    // Reflection is unavoidable here (no compile-time VS2 linkage), but string-based
    // getMethod/getConstructor/forName lookups are not - they dominated the cost when these
    // helpers ran per entity per tick (and per collide sub-step). Cache every lookup; a cached
    // Method.invoke JITs to near-direct-call cost.
    private static final Map<Class<?>, Map<String, Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static volatile Class<?> vector3dClassCached = null;
    private static volatile Constructor<?> vector3dCtorCached = null;

    private static Method cachedMethod(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        Map<String, Method> byName = METHOD_CACHE.computeIfAbsent(cls, k -> new ConcurrentHashMap<>());
        Method m = byName.get(name);
        if (m == null) {
            m = cls.getMethod(name, params);
            byName.put(name, m);
        }
        return m;
    }

    private static Class<?> vector3dClass() throws ClassNotFoundException {
        Class<?> cls = vector3dClassCached;
        if (cls == null) {
            cls = Class.forName("org.joml.Vector3d");
            vector3dClassCached = cls;
        }
        return cls;
    }

    private static Object newVector3d(double x, double y, double z) throws ReflectiveOperationException {
        Constructor<?> ctor = vector3dCtorCached;
        if (ctor == null) {
            ctor = vector3dClass().getConstructor(double.class, double.class, double.class);
            vector3dCtorCached = ctor;
        }
        return ctor.newInstance(x, y, z);
    }

    /**
     * Initialize reflection methods
     */
    private static void init() {
        if (initAttempted) return;
        initAttempted = true;

        try {
            Class<?> vsGameUtilsClass = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            // getShipManagingPos resolves a ship from a SHIPYARD block position (it checks
            // isChunkInShipyard). It is only valid for ship-local coordinates (e.g. a block that is
            // part of a ship's structure), NOT for an entity's world position.
            getShipManagingPosMethod = vsGameUtilsClass.getMethod("getShipManagingPos", Level.class, BlockPos.class);
            // getShipsIntersecting resolves ships from a WORLD-space AABB (it queries each ship's
            // world AABB). This is what we need to find the ship an entity is physically standing on,
            // since the entity's position is in world space, not shipyard space.
            getShipsIntersectingMethod = vsGameUtilsClass.getMethod("getShipsIntersecting", Level.class, AABB.class);
            LOGGER.info("[VS2Helper] Successfully initialized VS2 integration - found getShipManagingPos and getShipsIntersecting methods");
        } catch (Exception e) {
            // VS2 not available or API changed
            LOGGER.warn("[VS2Helper] Failed to initialize VS2 integration: {}", e.getMessage());
            getShipManagingPosMethod = null;
            getShipsIntersectingMethod = null;
        }
    }

    /**
     * Get the ship ID that an entity is standing on
     */
    @Nullable
    public static Long getShipMountedOn(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship != null) {
            try {
                Method getIdMethod = cachedMethod(ship.getClass(), "getId");
                return (Long) getIdMethod.invoke(ship);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Get the ship that an entity is standing on / inside, resolved from the entity's WORLD-space
     * bounding box.
     *
     * <p>We deliberately use {@code getShipsIntersecting} (a world-AABB query) rather than
     * {@code getShipManagingPos}: the latter only resolves ships from shipyard coordinates, so it
     * returns {@code null} for an entity standing on a ship out in the world - which previously made
     * ship-local collision silently fall back to (sliding) world-space collision.
     */
    @Nullable
    public static Object getShipEntityIsOn(Entity entity) {
        init();
        if (getShipsIntersectingMethod == null) {
            return null;
        }

        Level level = entity.level();

        // The scan below is hit from the collision pipeline and hitbox checks several times per
        // entity per tick; the supporting ship can't meaningfully change mid-tick, so serve repeat
        // queries within the same game tick from the entity's cache (null results included).
        long gameTime = level.getGameTime();
        ShipIntersectionCache cache = entity instanceof ShipIntersectionCache c ? c : null;
        if (cache != null && cache.gravityapi$getShipCacheTime() == gameTime) {
            return cache.gravityapi$getCachedShip();
        }

        // Inflate slightly so the supporting ship is still found in the instant the entity's feet are
        // a hair above the deck (e.g. just before/while settling onto it).
        AABB worldBox = entity.getBoundingBox().inflate(0.5D);

        try {
            Object ships = getShipsIntersectingMethod.invoke(null, level, worldBox);
            if (!(ships instanceof Iterable<?> iterable)) {
                if (ShipCollisionDebug.shouldLog(entity)) {
                    ShipCollisionDebug.log("[detect] getShipsIntersecting returned non-iterable: {}", ships);
                }
                if (cache != null) {
                    cache.gravityapi$setCachedShip(gameTime, null);
                }
                return null;
            }

            // Pick the ship whose world AABB is closest (by center) to the entity, so that when an
            // entity overlaps more than one ship we resolve collision against the nearest one.
            Object best = null;
            double bestDistSq = Double.MAX_VALUE;
            int count = 0;
            Vec3 entityPos = entity.position();
            for (Object ship : iterable) {
                if (ship == null) {
                    continue;
                }
                count++;
                double distSq = worldAabbCenterDistanceSq(ship, entityPos);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = ship;
                }
            }
            // Only log for players (or any entity that actually found a ship) to avoid mob spam.
            if (ShipCollisionDebug.VERBOSE && ((ShipCollisionDebug.shouldLogPlayer(entity)) || (best != null && ShipCollisionDebug.shouldLog(entity)))) {
                ShipCollisionDebug.log("[detect] entity={} pos={} box={} -> {} intersecting ship(s), chosen={}",
                        entity.getName().getString(), entityPos, worldBox, count,
                        best == null ? "none" : ("id=" + safeShipId(best)));
            }
            if (cache != null) {
                cache.gravityapi$setCachedShip(gameTime, best);
            }
            return best;
        } catch (Exception e) {
            if (ShipCollisionDebug.shouldLog(entity)) {
                ShipCollisionDebug.log("[detect] getShipsIntersecting threw: {}", e.toString());
            }
            if (cache != null) {
                cache.gravityapi$setCachedShip(gameTime, null);
            }
            return null;
        }
    }

    /** Best-effort ship id for logging. */
    private static Object safeShipId(Object ship) {
        try {
            return cachedMethod(ship.getClass(), "getId").invoke(ship);
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Squared distance from a ship's world-AABB center to the given world position. Returns
     * {@link Double#MAX_VALUE} if the AABB can't be read (so such ships are never preferred, but a
     * single intersecting ship is still returned by the caller's "closest" loop).
     */
    private static double worldAabbCenterDistanceSq(Object ship, Vec3 worldPos) {
        try {
            Method getWorldAABBMethod = cachedMethod(ship.getClass(), "getWorldAABB");
            Object aabb = getWorldAABBMethod.invoke(ship);
            if (aabb == null) {
                return Double.MAX_VALUE;
            }
            double cx = ((Double) cachedMethod(aabb.getClass(), "minX").invoke(aabb)
                    + (Double) cachedMethod(aabb.getClass(), "maxX").invoke(aabb)) * 0.5D;
            double cy = ((Double) cachedMethod(aabb.getClass(), "minY").invoke(aabb)
                    + (Double) cachedMethod(aabb.getClass(), "maxY").invoke(aabb)) * 0.5D;
            double cz = ((Double) cachedMethod(aabb.getClass(), "minZ").invoke(aabb)
                    + (Double) cachedMethod(aabb.getClass(), "maxZ").invoke(aabb)) * 0.5D;
            double dx = cx - worldPos.x;
            double dy = cy - worldPos.y;
            double dz = cz - worldPos.z;
            return dx * dx + dy * dy + dz * dz;
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }

    /**
     * Rotation the supporting ship underwent this game tick, as the world-frame delta quaternion
     * from the ship's previous-tick transform to its current transform - the same
     * prevTickTransform -> transform delta VS2's own EntityDragger uses to carry entities along.
     * Returns {@code null} when the entity isn't on a ship, the delta is negligible, or the
     * transforms can't be read.
     *
     * <p>Used to re-anchor stored world-space ship-gravity directions each tick: an effect vector
     * computed by a block entity last tick (or held by the effect linger) is expressed in a stale
     * ship orientation; rotating it by this delta re-expresses it in the ship's current one, so
     * "down" tracks a spinning ship instead of lagging it.
     */
    @Nullable
    public static org.joml.Quaterniond getShipRotationDeltaThisTick(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship == null) {
            return null;
        }
        try {
            Object transform = cachedMethod(ship.getClass(), "getTransform").invoke(ship);
            Object prevTransform;
            try {
                prevTransform = cachedMethod(ship.getClass(), "getPrevTickTransform").invoke(ship);
            } catch (NoSuchMethodException e) {
                // Older VS2 core API name
                prevTransform = cachedMethod(ship.getClass(), "getPrevTickShipTransform").invoke(ship);
            }
            if (transform == null || prevTransform == null) {
                return null;
            }
            org.joml.Quaterniond curr = readShipToWorldRotation(transform);
            org.joml.Quaterniond prev = readShipToWorldRotation(prevTransform);
            if (curr == null || prev == null) {
                return null;
            }
            // delta * prev == curr  ->  delta = curr * prev^-1 (conjugate: unit quaternions)
            org.joml.Quaterniond delta = curr.mul(prev.conjugate(), new org.joml.Quaterniond());
            // Identity (parked ship) - skip the downstream work. cos(theta/2) ~ 1.
            if (Math.abs(delta.w) > 0.99999999) {
                return null;
            }
            return delta.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static org.joml.Quaterniond readShipToWorldRotation(Object transform) throws Exception {
        Object quaternion = cachedMethod(transform.getClass(), "getShipToWorldRotation").invoke(transform);
        if (quaternion == null) {
            return null;
        }
        return new org.joml.Quaterniond(
                (Double) cachedMethod(quaternion.getClass(), "x").invoke(quaternion),
                (Double) cachedMethod(quaternion.getClass(), "y").invoke(quaternion),
                (Double) cachedMethod(quaternion.getClass(), "z").invoke(quaternion),
                (Double) cachedMethod(quaternion.getClass(), "w").invoke(quaternion));
    }

    /**
     * Get the ship's rotation as a double array [x, y, z, w], or null if not on a ship
     */
    @Nullable
    public static double[] getShipRotation(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship != null) {
            try {
                Method getTransformMethod = cachedMethod(ship.getClass(), "getTransform");
                Object transform = getTransformMethod.invoke(ship);
                Method getRotationMethod = cachedMethod(transform.getClass(), "getShipToWorldRotation");
                Object quaternion = getRotationMethod.invoke(transform);

                // Extract x, y, z, w from the quaternion using reflection
                Method xMethod = cachedMethod(quaternion.getClass(), "x");
                Method yMethod = cachedMethod(quaternion.getClass(), "y");
                Method zMethod = cachedMethod(quaternion.getClass(), "z");
                Method wMethod = cachedMethod(quaternion.getClass(), "w");

                double x = (Double) xMethod.invoke(quaternion);
                double y = (Double) yMethod.invoke(quaternion);
                double z = (Double) zMethod.invoke(quaternion);
                double w = (Double) wMethod.invoke(quaternion);

                return new double[]{x, y, z, w};
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Transform a world-space direction to ship-local space
     */
    @Nullable
    public static Vec3 worldToShipDirection(Entity entity, Vec3 worldDir) {
        Object matrix = getWorldToShipMatrix(entity);
        if (matrix == null) {
            return null;
        }

        try {
            // Create a new Vector3d with the direction
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(worldDir.x, worldDir.y, worldDir.z);

            // Call transformDirection on the matrix
            Method transformMethod = cachedMethod(matrix.getClass(), "transformDirection", vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            // Extract x, y, z from result
            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transform a ship-local direction to world space
     */
    @Nullable
    public static Vec3 shipToWorldDirection(Entity entity, Vec3 shipDir) {
        Object matrix = getShipToWorldMatrix(entity);
        if (matrix == null) {
            return null;
        }

        try {
            // Create a new Vector3d with the direction
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(shipDir.x, shipDir.y, shipDir.z);

            // Call transformDirection on the matrix
            Method transformMethod = cachedMethod(matrix.getClass(), "transformDirection", vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            // Extract x, y, z from result
            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the "up" direction of the ship in world space
     */
    @Nullable
    public static Vec3 getShipUpDirection(Entity entity) {
        return shipToWorldDirection(entity, new Vec3(0, 1, 0));
    }

    /**
     * Transform a position from world space to ship-local space
     */
    @Nullable
    public static Vec3 worldToShipPosition(Entity entity, Vec3 worldPos) {
        Object matrix = getWorldToShipMatrix(entity);
        if (matrix == null) {
            return null;
        }

        try {
            // Create a new Vector3d with the position
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(worldPos.x, worldPos.y, worldPos.z);

            // Call transformPosition on the matrix
            Method transformMethod = cachedMethod(matrix.getClass(), "transformPosition", vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            // Extract x, y, z from result
            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transform a position from ship-local space to world space
     */
    @Nullable
    public static Vec3 shipToWorldPosition(Entity entity, Vec3 shipPos) {
        Object matrix = getShipToWorldMatrix(entity);
        if (matrix == null) {
            return null;
        }

        try {
            // Create a new Vector3d with the position
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(shipPos.x, shipPos.y, shipPos.z);

            // Call transformPosition on the matrix
            Method transformMethod = cachedMethod(matrix.getClass(), "transformPosition", vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            // Extract x, y, z from result
            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the ship-to-world transformation matrix
     */
    @Nullable
    private static Object getShipToWorldMatrix(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship != null) {
            try {
                Method getTransformMethod = cachedMethod(ship.getClass(), "getTransform");
                Object transform = getTransformMethod.invoke(ship);
                Method getMatrixMethod = cachedMethod(transform.getClass(), "getShipToWorld");
                return getMatrixMethod.invoke(transform);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Get the world-to-ship transformation matrix
     */
    @Nullable
    private static Object getWorldToShipMatrix(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship != null) {
            try {
                Method getTransformMethod = cachedMethod(ship.getClass(), "getTransform");
                Object transform = getTransformMethod.invoke(ship);
                Method getMatrixMethod = cachedMethod(transform.getClass(), "getWorldToShip");
                return getMatrixMethod.invoke(transform);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Get ship from a specific block position
     */
    @Nullable
    public static Object getShipAtBlock(Level level, BlockPos pos) {
        init();
        if (getShipManagingPosMethod == null) {
            LOGGER.debug("[VS2Helper] getShipAtBlock: getShipManagingPosMethod is null");
            return null;
        }

        try {
            Object result = getShipManagingPosMethod.invoke(null, level, pos);
            if (result != null) {
                LOGGER.debug("[VS2Helper] getShipAtBlock: Found ship at {} - class: {}", pos, result.getClass().getName());
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("[VS2Helper] getShipAtBlock: Exception getting ship at {}: {}", pos, e.getMessage());
            return null;
        }
    }

    /**
     * Check if a block position is within a ship
     */
    public static boolean isBlockOnShip(Level level, BlockPos pos) {
        return getShipAtBlock(level, pos) != null;
    }

    /**
     * Get the ship ID from a block position
     */
    @Nullable
    public static Long getShipIdAtBlock(Level level, BlockPos pos) {
        Object ship = getShipAtBlock(level, pos);
        if (ship != null) {
            try {
                Method getIdMethod = cachedMethod(ship.getClass(), "getId");
                return (Long) getIdMethod.invoke(ship);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Transform a ship-local position to world position using a block's ship context
     */
    @Nullable
    public static Vec3 shipToWorldPosition(Level level, BlockPos blockOnShip, Vec3 shipPos) {
        Object ship = getShipAtBlock(level, blockOnShip);
        if (ship == null) {
            LOGGER.debug("[VS2Helper] shipToWorldPosition: No ship at block {}", blockOnShip);
            return null;
        }

        Object matrix = getShipToWorldMatrixFromShip(ship);
        if (matrix == null) {
            LOGGER.warn("[VS2Helper] shipToWorldPosition: Could not get ship-to-world matrix from ship");
            return null;
        }

        try {
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(shipPos.x, shipPos.y, shipPos.z);

            Method transformMethod = cachedMethod(matrix.getClass(), "transformPosition", vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            LOGGER.debug("[VS2Helper] shipToWorldPosition: {} -> ({}, {}, {})", shipPos, x, y, z);
            return new Vec3(x, y, z);
        } catch (Exception e) {
            LOGGER.error("[VS2Helper] shipToWorldPosition: Exception transforming position: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Transform a ship-local direction to world direction using a block's ship context
     */
    @Nullable
    public static Vec3 shipToWorldDirectionFromBlock(Level level, BlockPos blockOnShip, Vec3 shipDir) {
        Object ship = getShipAtBlock(level, blockOnShip);
        if (ship == null) {
            return null;
        }

        Object matrix = getShipToWorldMatrixFromShip(ship);
        if (matrix == null) {
            return null;
        }

        try {
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(shipDir.x, shipDir.y, shipDir.z);

            Method transformMethod = cachedMethod(matrix.getClass(), "transformDirection", vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transform a world position to ship-local position using a block's ship context
     */
    @Nullable
    public static Vec3 worldToShipPosition(Level level, BlockPos blockOnShip, Vec3 worldPos) {
        Object ship = getShipAtBlock(level, blockOnShip);
        if (ship == null) {
            return null;
        }

        Object matrix = getWorldToShipMatrixFromShip(ship);
        if (matrix == null) {
            return null;
        }

        try {
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(worldPos.x, worldPos.y, worldPos.z);

            Method transformMethod = cachedMethod(matrix.getClass(), "transformPosition", vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transform a world direction to a ship-local direction using a block's ship context
     */
    @Nullable
    public static Vec3 worldToShipDirectionFromBlock(Level level, BlockPos blockOnShip, Vec3 worldDir) {
        Object ship = getShipAtBlock(level, blockOnShip);
        if (ship == null) {
            return null;
        }

        Object matrix = getWorldToShipMatrixFromShip(ship);
        if (matrix == null) {
            return null;
        }

        try {
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(worldDir.x, worldDir.y, worldDir.z);

            Method transformMethod = cachedMethod(matrix.getClass(), "transformDirection", vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transform a shipyard-space AABB to the world-space AABB enclosing it (corner sweep).
     * Returns null when the block is not on a ship or the transform is unavailable.
     */
    @Nullable
    public static AABB shipBoxToWorldBox(Level level, BlockPos blockOnShip, AABB shipBox) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3(
                    (i & 1) == 0 ? shipBox.minX : shipBox.maxX,
                    (i & 2) == 0 ? shipBox.minY : shipBox.maxY,
                    (i & 4) == 0 ? shipBox.minZ : shipBox.maxZ);
            Vec3 world = shipToWorldPosition(level, blockOnShip, corner);
            if (world == null) {
                return null;
            }
            minX = Math.min(minX, world.x);
            minY = Math.min(minY, world.y);
            minZ = Math.min(minZ, world.z);
            maxX = Math.max(maxX, world.x);
            maxY = Math.max(maxY, world.y);
            maxZ = Math.max(maxZ, world.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Transform a world-space AABB to the shipyard-space AABB enclosing it (corner sweep).
     * Returns null when the block is not on a ship or the transform is unavailable.
     */
    @Nullable
    public static AABB worldBoxToShipBox(Level level, BlockPos blockOnShip, AABB worldBox) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3(
                    (i & 1) == 0 ? worldBox.minX : worldBox.maxX,
                    (i & 2) == 0 ? worldBox.minY : worldBox.maxY,
                    (i & 4) == 0 ? worldBox.minZ : worldBox.maxZ);
            Vec3 ship = worldToShipPosition(level, blockOnShip, corner);
            if (ship == null) {
                return null;
            }
            minX = Math.min(minX, ship.x);
            minY = Math.min(minY, ship.y);
            minZ = Math.min(minZ, ship.z);
            maxX = Math.max(maxX, ship.x);
            maxY = Math.max(maxY, ship.y);
            maxZ = Math.max(maxZ, ship.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Get the ship-to-world transformation matrix from a ship object
     */
    @Nullable
    private static Object getShipToWorldMatrixFromShip(Object ship) {
        if (ship == null) {
            return null;
        }

        try {
            Method getTransformMethod = cachedMethod(ship.getClass(), "getTransform");
            Object transform = getTransformMethod.invoke(ship);
            if (transform == null) {
                LOGGER.warn("[VS2Helper] getShipToWorldMatrixFromShip: getTransform() returned null");
                return null;
            }
            Method getMatrixMethod = cachedMethod(transform.getClass(), "getShipToWorld");
            Object matrix = getMatrixMethod.invoke(transform);
            if (matrix == null) {
                LOGGER.warn("[VS2Helper] getShipToWorldMatrixFromShip: getShipToWorld() returned null");
            }
            return matrix;
        } catch (Exception e) {
            LOGGER.error("[VS2Helper] getShipToWorldMatrixFromShip: Exception: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the world-to-ship transformation matrix from a ship object
     */
    @Nullable
    private static Object getWorldToShipMatrixFromShip(Object ship) {
        if (ship == null) {
            return null;
        }

        try {
            Method getTransformMethod = cachedMethod(ship.getClass(), "getTransform");
            Object transform = getTransformMethod.invoke(ship);
            Method getMatrixMethod = cachedMethod(transform.getClass(), "getWorldToShip");
            return getMatrixMethod.invoke(transform);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transform a position vector using a VS2 (org.joml.Matrix4dc) transform matrix.
     */
    @Nullable
    private static Vec3 transformPosition(Object matrix, Vec3 pos) {
        return transform(matrix, pos, "transformPosition");
    }

    /**
     * Transform a direction vector using a VS2 (org.joml.Matrix4dc) transform matrix.
     */
    @Nullable
    private static Vec3 transformDirection(Object matrix, Vec3 dir) {
        return transform(matrix, dir, "transformDirection");
    }

    @Nullable
    private static Vec3 transform(Object matrix, Vec3 vec, String methodName) {
        if (matrix == null) {
            return null;
        }
        try {
            Class<?> vector3dClass = vector3dClass();
            Object vector = newVector3d(vec.x, vec.y, vec.z);

            Method transformMethod = cachedMethod(matrix.getClass(), methodName, vector3dClass);
            Object result = transformMethod.invoke(matrix, vector);

            Method xMethod = cachedMethod(result.getClass(), "x");
            Method yMethod = cachedMethod(result.getClass(), "y");
            Method zMethod = cachedMethod(result.getClass(), "z");

            double x = (Double) xMethod.invoke(result);
            double y = (Double) yMethod.invoke(result);
            double z = (Double) zMethod.invoke(result);

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build a ship-local collision context for an entity that is standing on (or inside the
     * region of) a VS2 ship.
     *
     * <p>The returned context expresses the entity's bounding box and intended movement in the
     * ship's local "shipyard" coordinate space, where the ship's own blocks are axis-aligned and
     * gravity (from a gravity generator) points straight down (-Y). Running Minecraft's standard
     * axis-separated collision in this space and then converting the result back to world space
     * (via {@link ShipCollisionContext#shipDirToWorld(Vec3)}) produces correct collision against
     * arbitrarily-rotated ship geometry, instead of the world-axis sliding/clipping that happens
     * when an axis-aligned box is swept against tilted shapes.
     *
     * @return the ship-local collision context, or {@code null} if the entity is not on a ship or
     *         the ship transform could not be resolved (caller should fall back to normal collision)
     */
    @Nullable
    public static ShipCollisionContext getShipCollisionContext(Entity entity, Vec3 worldMovement, AABB worldBox) {
        Object ship = getShipEntityIsOn(entity);
        if (ship == null) {
            return null;
        }

        Object worldToShip = getWorldToShipMatrixFromShip(ship);
        Object shipToWorld = getShipToWorldMatrixFromShip(ship);
        if (worldToShip == null || shipToWorld == null) {
            if (ShipCollisionDebug.shouldLogVerbose(entity)) {
                ShipCollisionDebug.log("[ctx] ship found (id={}) but matrices null: worldToShip={} shipToWorld={}",
                        safeShipId(ship), worldToShip, shipToWorld);
            }
            return null;
        }

        // Feet-center of the entity's world bounding box.
        double feetX = (worldBox.minX + worldBox.maxX) * 0.5D;
        double feetY = worldBox.minY;
        double feetZ = (worldBox.minZ + worldBox.maxZ) * 0.5D;

        Vec3 shipFeet = transformPosition(worldToShip, new Vec3(feetX, feetY, feetZ));
        Vec3 shipMovement = transformDirection(worldToShip, worldMovement);
        if (shipFeet == null || shipMovement == null) {
            if (ShipCollisionDebug.shouldLogVerbose(entity)) {
                ShipCollisionDebug.log("[ctx] transform failed: shipFeet={} shipMovement={}", shipFeet, shipMovement);
            }
            return null;
        }

        if (ShipCollisionDebug.shouldLogVerbose(entity)) {
            ShipCollisionDebug.log("[ctx] worldFeet=({}, {}, {}) -> shipFeet={} | worldMove={} -> shipMove={}",
                    feetX, feetY, feetZ, shipFeet, worldMovement, shipMovement);
        }

        // Reconstruct an axis-aligned box in ship space from the entity's dimensions. We deliberately
        // do NOT rotate the world AABB (that would produce an inflated, oriented box); instead we
        // rebuild it around the ship-space feet position so it stays a tight, ship-aligned box.
        double halfWidth = worldBox.getXsize() * 0.5D;
        double halfDepth = worldBox.getZsize() * 0.5D;
        double height = worldBox.getYsize();
        AABB shipBox = new AABB(
                shipFeet.x - halfWidth, shipFeet.y, shipFeet.z - halfDepth,
                shipFeet.x + halfWidth, shipFeet.y + height, shipFeet.z + halfDepth
        );

        return new ShipCollisionContext(shipBox, shipMovement, shipToWorld);
    }

    /**
     * Check whether the entity is resting on (directly supported by) a VS2 ship's blocks in the
     * given world-space gravity direction.
     *
     * <p>This probes a thin slab just below the entity's feet, transformed into the ship's shipyard
     * coordinates where its blocks are axis-aligned. It is used as an explicit ground check for
     * ship gravity, because vanilla's onGround detection (which relies on the downward collision
     * sweep stopping) becomes unreliable once an entity has sunk slightly into/below tilted ship
     * geometry - leading to runaway downward velocity accumulation.
     *
     * @param worldDownDir the (un-normalized is fine) world-space direction gravity pulls the entity
     * @param probeDistance how far below the feet to look, in blocks (e.g. 0.2)
     * @return true if a solid ship block is within {@code probeDistance} below the feet
     */
    public static boolean isSupportedByShipDeck(Entity entity, Vec3 worldDownDir, double probeDistance) {
        Object ship = getShipEntityIsOn(entity);
        if (ship == null) {
            return false;
        }
        Object worldToShip = getWorldToShipMatrixFromShip(ship);
        if (worldToShip == null) {
            return false;
        }

        AABB box = entity.getBoundingBox();
        double feetX = (box.minX + box.maxX) * 0.5D;
        double feetY = box.minY;
        double feetZ = (box.minZ + box.maxZ) * 0.5D;

        Vec3 shipFeet = transformPosition(worldToShip, new Vec3(feetX, feetY, feetZ));
        Vec3 shipDown = transformDirection(worldToShip, worldDownDir);
        if (shipFeet == null || shipDown == null) {
            return false;
        }
        if (shipDown.lengthSqr() < 1.0E-9) {
            return false;
        }
        shipDown = shipDown.normalize();

        // A thin slab spanning the entity footprint, from just above the feet down by probeDistance
        // along ship-down. A small upward margin (0.02) catches the case where the feet are resting
        // exactly on the block surface.
        double halfWidth = box.getXsize() * 0.5D;
        double halfDepth = box.getZsize() * 0.5D;
        Vec3 probeEnd = shipFeet.add(shipDown.scale(probeDistance));
        double margin = 0.02D;
        AABB slab = new AABB(
                Math.min(shipFeet.x, probeEnd.x) - halfWidth, Math.min(shipFeet.y, probeEnd.y),
                Math.min(shipFeet.z, probeEnd.z) - halfDepth,
                Math.max(shipFeet.x, probeEnd.x) + halfWidth, Math.max(shipFeet.y, probeEnd.y) + margin,
                Math.max(shipFeet.z, probeEnd.z) + halfDepth
        );

        for (VoxelShape shape : entity.level().getBlockCollisions(null, slab)) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far (along ship-up, in blocks) the entity's feet have sunk below the surface of the ship
     * block(s) they are standing in.
     *
     * <p>Even with ship-local collision cancelling gravity each tick, an entity standing on a tilted
     * ship slowly sinks into the deck (residual un-cancelled movement per tick, and/or the ship
     * physically shifting under an entity that VS2 no longer drags because our collision path
     * bypasses its world-space shapes). Velocity clamping alone never recovers that penetration, so
     * once the feet pass the bottom of the deck layer the ground probe stops seeing it and gravity
     * runs away. This measures the penetration so the caller can push the entity back out.
     *
     * <p>Only shapes whose top is within {@code maxDepth} above the feet are considered, so walls
     * beside the entity (whose tops are a full block or more higher) don't register as "deck".
     *
     * @param maxDepth the deepest penetration to look for / report, in blocks (e.g. 0.5)
     * @return the penetration depth in [0, maxDepth], or 0 if not on a ship / not penetrating
     */
    public static double getShipDeckPenetration(Entity entity, double maxDepth) {
        Object ship = getShipEntityIsOn(entity);
        if (ship == null) {
            return 0.0D;
        }
        Object worldToShip = getWorldToShipMatrixFromShip(ship);
        if (worldToShip == null) {
            return 0.0D;
        }

        AABB box = entity.getBoundingBox();
        double feetX = (box.minX + box.maxX) * 0.5D;
        double feetY = box.minY;
        double feetZ = (box.minZ + box.maxZ) * 0.5D;

        Vec3 shipFeet = transformPosition(worldToShip, new Vec3(feetX, feetY, feetZ));
        if (shipFeet == null) {
            return 0.0D;
        }

        // Footprint shrunk slightly so blocks the entity is merely standing beside don't count.
        double halfWidth = Math.max(0.05D, box.getXsize() * 0.5D - 0.1D);
        double halfDepth = Math.max(0.05D, box.getZsize() * 0.5D - 0.1D);
        AABB column = new AABB(
                shipFeet.x - halfWidth, shipFeet.y, shipFeet.z - halfDepth,
                shipFeet.x + halfWidth, shipFeet.y + maxDepth, shipFeet.z + halfDepth
        );

        double penetration = 0.0D;
        for (VoxelShape shape : entity.level().getBlockCollisions(null, column)) {
            if (shape.isEmpty()) {
                continue;
            }
            double top = shape.bounds().maxY;
            // Ignore shapes that continue above the search window - those are walls, not the deck
            // surface the feet have sunk into.
            if (top > shipFeet.y + maxDepth + 1.0E-4D) {
                continue;
            }
            penetration = Math.max(penetration, top - shipFeet.y);
        }
        return Math.min(Math.max(penetration, 0.0D), maxDepth);
    }

    // Reflection handles for VS2's entity-dragging bookkeeping, resolved lazily on first use.
    private static Method getDraggingInformationMethod = null;
    private static Method setLastShipStoodOnMethod = null;
    private static boolean draggingInitAttempted = false;

    /**
     * Keep VS2's entity dragging alive for an entity standing on a ship.
     *
     * <p>VS2's {@code EntityDragger} runs every tick and carries entities along with the motion of
     * the ship they stand on (position AND yaw, from the prev-tick-to-current transform delta) —
     * but only for entities whose {@code EntityDraggingInformation.lastShipStoodOn} was refreshed
     * within the last 25 ticks. Stock VS2 refreshes that field exclusively inside
     * {@code EntityShipCollisionUtils.adjustEntityMovementForShipCollisions}, which we cancel at
     * HEAD for ship-gravity entities (their collision runs through our ship-local path instead).
     * Without this call those entities are never dragged at all: a moving/rotating ship slides out
     * from under (or into) them every tick, which is exactly the clipping/glitching seen on fast
     * ships. Setting the field is enough — VS2's setter also resets {@code ticksSinceStoodOnShip}.
     *
     * @return true if the entity is on a ship and the dragging info was updated
     */
    public static boolean markStandingOnShip(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship == null) {
            return false;
        }
        try {
            if (!draggingInitAttempted) {
                draggingInitAttempted = true;
                Class<?> providerClass = Class.forName(
                        "org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider");
                getDraggingInformationMethod = providerClass.getMethod("getDraggingInformation");
                Class<?> infoClass = Class.forName(
                        "org.valkyrienskies.mod.common.util.EntityDraggingInformation");
                setLastShipStoodOnMethod = infoClass.getMethod("setLastShipStoodOn", Long.class);
                LOGGER.info("[VS2Helper] Initialized VS2 entity-dragging integration");
            }
            if (getDraggingInformationMethod == null || setLastShipStoodOnMethod == null) {
                return false;
            }
            Long shipId = (Long) cachedMethod(ship.getClass(), "getId").invoke(ship);
            Object draggingInfo = getDraggingInformationMethod.invoke(entity);
            setLastShipStoodOnMethod.invoke(draggingInfo, shipId);
            return true;
        } catch (Exception e) {
            if (ShipCollisionDebug.shouldLog(entity)) {
                ShipCollisionDebug.log("[drag] markStandingOnShip failed: {}", e.toString());
            }
            return false;
        }
    }

    /**
     * The world-space direction of "ship up" (ship-local +Y), or {@code null} if the entity is not
     * on a resolvable ship.
     */
    @Nullable
    public static Vec3 getShipUpInWorld(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship == null) {
            return null;
        }
        Object shipToWorld = getShipToWorldMatrixFromShip(ship);
        if (shipToWorld == null) {
            return null;
        }
        Vec3 up = transformDirection(shipToWorld, new Vec3(0.0D, 1.0D, 0.0D));
        if (up == null || up.lengthSqr() < 1.0E-9D) {
            return null;
        }
        return up.normalize();
    }

    /**
     * Holds the ship-local inputs for a collision sweep and the matrix needed to convert the
     * resulting movement back into world space.
     */
    public static final class ShipCollisionContext {
        public final AABB shipBox;
        public final Vec3 shipMovement;
        private final Object shipToWorldMatrix;

        private ShipCollisionContext(AABB shipBox, Vec3 shipMovement, Object shipToWorldMatrix) {
            this.shipBox = shipBox;
            this.shipMovement = shipMovement;
            this.shipToWorldMatrix = shipToWorldMatrix;
        }

        /**
         * Convert a ship-local direction (e.g. the collision-adjusted movement) back to world space.
         *
         * @return the world-space vector, or {@code null} if the transform failed
         */
        @Nullable
        public Vec3 shipDirToWorld(Vec3 shipDir) {
            return transformDirection(shipToWorldMatrix, shipDir);
        }
    }
}

