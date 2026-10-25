package net.memezforbeanz.gravityapi.compat.vs2;

import net.memezforbeanz.gravityapi.util.ShipIntersectionCache;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

/** VS2 access through reflection, so VS2 stays an optional dependency. */
public class VS2Helper {

    private static final Logger LOGGER = LoggerFactory.getLogger(VS2Helper.class);
    private static Method getShipManagingPosMethod = null;
    private static Method getShipsIntersectingMethod = null;
    private static boolean initAttempted = false;

    // Reflection lookups are cached: these helpers run per entity per tick
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

    private static void init() {
        if (initAttempted) return;
        initAttempted = true;

        try {
            Class<?> vsGameUtilsClass = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            // getShipManagingPos takes shipyard positions (blocks of a ship)
            getShipManagingPosMethod = vsGameUtilsClass.getMethod("getShipManagingPos", Level.class, BlockPos.class);
            // getShipsIntersecting takes a world AABB (entities standing on a ship)
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
     * The ship an entity is standing on or inside, from its world-space bounding box
     * ({@code getShipsIntersecting}; {@code getShipManagingPos} only takes shipyard positions).
     */
    @Nullable
    public static Object getShipEntityIsOn(Entity entity) {
        init();
        if (getShipsIntersectingMethod == null) {
            return null;
        }

        Level level = entity.level();

        // Cached per game tick: hit several times per entity per tick from collision and hitbox checks
        long gameTime = level.getGameTime();
        ShipIntersectionCache cache = entity instanceof ShipIntersectionCache c ? c : null;
        if (cache != null && cache.gravityapi$getShipCacheTime() == gameTime) {
            return cache.gravityapi$getCachedShip();
        }

        // Inflated so a ship is still found with the feet a hair above the deck
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

            // Overlapping several ships: the one whose AABB centre is nearest
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

    /** A ship's transforms between world and shipyard space. */
    public record ShipFrame(Object worldToShip, Object shipToWorld) {
        @Nullable
        public Vec3 toShip(Vec3 worldPos) {
            return transformPosition(worldToShip, worldPos);
        }

        @Nullable
        public Vec3 toWorld(Vec3 shipPos) {
            return transformPosition(shipToWorld, shipPos);
        }

        @Nullable
        public Vec3 directionToWorld(Vec3 shipDir) {
            return transformDirection(shipToWorld, shipDir);
        }

        @Nullable
        public Vec3 directionToShip(Vec3 worldDir) {
            return transformDirection(worldToShip, worldDir);
        }
    }

    /** A ship's current frame, or null if its transform can't be read. */
    @Nullable
    public static ShipFrame frameOf(@Nullable Object ship) {
        Object worldToShip = getWorldToShipMatrixFromShip(ship);
        Object shipToWorld = getShipToWorldMatrixFromShip(ship);
        return worldToShip != null && shipToWorld != null ? new ShipFrame(worldToShip, shipToWorld) : null;
    }

    /** The frames of the ships whose world bounds intersect {@code worldBox}. */
    public static java.util.List<ShipFrame> shipFramesIntersecting(Level level, AABB worldBox) {
        init();
        java.util.List<ShipFrame> frames = new java.util.ArrayList<>();
        if (getShipsIntersectingMethod == null) {
            return frames;
        }
        try {
            Object ships = getShipsIntersectingMethod.invoke(null, level, worldBox);
            if (ships instanceof Iterable<?> iterable) {
                for (Object ship : iterable) {
                    Object worldToShip = getWorldToShipMatrixFromShip(ship);
                    Object shipToWorld = getShipToWorldMatrixFromShip(ship);
                    if (worldToShip != null && shipToWorld != null) {
                        frames.add(new ShipFrame(worldToShip, shipToWorld));
                    }
                }
            }
        } catch (Exception e) {
            // No ships, then
        }
        return frames;
    }

    /** Best-effort ship id for logging. */
    private static Object safeShipId(Object ship) {
        try {
            return cachedMethod(ship.getClass(), "getId").invoke(ship);
        } catch (Exception e) {
            return "?";
        }
    }

    /** Squared distance from a ship's world-AABB centre to a position; {@link Double#MAX_VALUE} if unreadable. */
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
     * The supporting ship's rotation this tick (prev-tick to current transform, as VS2's
     * EntityDragger uses), or {@code null} off-ship, when negligible or unreadable. Re-anchors
     * stored world-space ship gravity to the ship's current orientation.
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
            // delta = curr * prev^-1
            org.joml.Quaterniond delta = curr.mul(prev.conjugate(), new org.joml.Quaterniond());
            // Parked ship
            if (Math.abs(delta.w) > 0.99999999) {
                return null;
            }
            return delta.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Anchor a ship-relative direction to the entity's ship, recording it in the ship's frame.
     *
     * @param prevTick use the previous-tick transform (directions a block entity computed last tick)
     * @return the anchored direction, or {@code null} if the entity isn't on a readable ship
     */
    @Nullable
    public static GravityDirection anchorToEntityShip(Entity entity, GravityDirection direction, boolean prevTick) {
        Object ship = getShipEntityIsOn(entity);
        if (ship == null) {
            return null;
        }
        try {
            Object transform = prevTick ? prevTickTransform(ship) : cachedMethod(ship.getClass(), "getTransform").invoke(ship);
            if (transform == null) {
                return null;
            }
            org.joml.Quaterniond shipToWorld = readShipToWorldRotation(transform);
            if (shipToWorld == null) {
                return null;
            }
            Vec3 world = direction.getVector();
            org.joml.Vector3d local = shipToWorld.conjugate(new org.joml.Quaterniond())
                    .transform(new org.joml.Vector3d(world.x, world.y, world.z));
            long shipId = (Long) cachedMethod(ship.getClass(), "getId").invoke(ship);
            return GravityDirection.shipAnchored(shipToWorld, ship, shipId, new Vec3(local.x, local.y, local.z));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Rebuild a ship-anchored direction on this side from its ship id and ship-local vector (synced
     * from the other side), or null if the ship isn't loaded here.
     */
    @Nullable
    public static GravityDirection anchorToShipById(Level level, long shipId, Vec3 shipLocal) {
        try {
            Class<?> vsGameUtilsClass = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            Object shipWorld = cachedMethod(vsGameUtilsClass, "getShipObjectWorld", Level.class).invoke(null, level);
            if (shipWorld == null) {
                return null;
            }
            Object ships = cachedMethod(shipWorld.getClass(), "getAllShips").invoke(shipWorld);
            Object ship = cachedMethod(ships.getClass(), "getById", long.class).invoke(ships, shipId);
            if (ship == null) {
                return null;
            }
            Object transform = cachedMethod(ship.getClass(), "getTransform").invoke(ship);
            org.joml.Quaterniond shipToWorld = transform != null ? readShipToWorldRotation(transform) : null;
            return shipToWorld != null ? GravityDirection.shipAnchored(shipToWorld, ship, shipId, shipLocal) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The ship-to-world rotation of an anchored direction's ship, or {@code null} if unanchored or
     * unreadable.
     *
     * @param render use the client's interpolated render transform where there is one
     */
    @Nullable
    public static org.joml.Quaterniond anchoredShipToWorld(GravityDirection direction, boolean render) {
        Object ship = direction.getAnchorShip();
        if (ship == null || direction.getShipLocalVector() == null) {
            return null;
        }
        try {
            Object transform = null;
            if (render) {
                try {
                    transform = cachedMethod(ship.getClass(), "getRenderTransform").invoke(ship);
                } catch (NoSuchMethodException e) {
                    // Server ships have no render transform
                }
            }
            if (transform == null) {
                transform = cachedMethod(ship.getClass(), "getTransform").invoke(ship);
            }
            return transform != null ? readShipToWorldRotation(transform) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** An anchored direction's current world vector (see {@link #anchoredShipToWorld}), or {@code null}. */
    @Nullable
    public static Vec3 anchoredToWorld(GravityDirection direction, boolean render) {
        org.joml.Quaterniond shipToWorld = anchoredShipToWorld(direction, render);
        if (shipToWorld == null) {
            return null;
        }
        Vec3 local = direction.getShipLocalVector();
        org.joml.Vector3d world = shipToWorld.transform(new org.joml.Vector3d(local.x, local.y, local.z));
        return new Vec3(world.x, world.y, world.z);
    }

    /** World-to-player rotation to render a gravity direction with: anchored directions follow the ship's render transform. */
    public static org.joml.Quaternionf renderWorldToPlayerRotation(GravityDirection direction) {
        if (direction.isShipAnchored()) {
            org.joml.Quaterniond shipToWorld = anchoredShipToWorld(direction, true);
            if (shipToWorld != null) {
                return GravityDirection.shipLockedWorldToPlayer(direction.getShipLocalVector(), shipToWorld);
            }
        }
        return direction.getWorldToPlayerRotation();
    }

    private static Object prevTickTransform(Object ship) throws Exception {
        try {
            return cachedMethod(ship.getClass(), "getPrevTickTransform").invoke(ship);
        } catch (NoSuchMethodException e) {
            // Older VS2 core API name
            return cachedMethod(ship.getClass(), "getPrevTickShipTransform").invoke(ship);
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

    /** The ship's rotation as [x, y, z, w], or null if not on a ship */
    @Nullable
    public static double[] getShipRotation(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship != null) {
            try {
                Method getTransformMethod = cachedMethod(ship.getClass(), "getTransform");
                Object transform = getTransformMethod.invoke(ship);
                Method getRotationMethod = cachedMethod(transform.getClass(), "getShipToWorldRotation");
                Object quaternion = getRotationMethod.invoke(transform);

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

    /** World-space direction to ship-local */
    @Nullable
    public static Vec3 worldToShipDirection(Entity entity, Vec3 worldDir) {
        Object matrix = getWorldToShipMatrix(entity);
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

    /** Ship-local direction to world space */
    @Nullable
    public static Vec3 shipToWorldDirection(Entity entity, Vec3 shipDir) {
        Object matrix = getShipToWorldMatrix(entity);
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

    /** The ship's up direction in world space */
    @Nullable
    public static Vec3 getShipUpDirection(Entity entity) {
        return shipToWorldDirection(entity, new Vec3(0, 1, 0));
    }

    /** World-space position to ship-local */
    @Nullable
    public static Vec3 worldToShipPosition(Entity entity, Vec3 worldPos) {
        Object matrix = getWorldToShipMatrix(entity);
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

    /** Ship-local position to world space */
    @Nullable
    public static Vec3 shipToWorldPosition(Entity entity, Vec3 shipPos) {
        Object matrix = getShipToWorldMatrix(entity);
        if (matrix == null) {
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

            return new Vec3(x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

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

    /** The ship at a block position */
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

    /** Logical target-grid rotation, using the shared reflective VS2 compatibility path. */
    @Nullable
    public static org.joml.Quaterniond getPlacementShipRotation(Object ship) {
        try {
            Object transform = cachedMethod(ship.getClass(), "getTransform").invoke(ship);
            return transform == null ? null : readShipToWorldRotation(transform);
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether a block position is on a ship */
    public static boolean isBlockOnShip(Level level, BlockPos pos) {
        return getShipAtBlock(level, pos) != null;
    }

    /** The ship id at a block position */
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

    /** Ship-local position to world, using the ship the block is on */
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

    /** Ship-local direction to world, using the ship the block is on */
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

    /** World position to ship-local, using the ship the block is on */
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

    /** World direction to ship-local, using the ship the block is on */
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

    /** Shipyard AABB to the world AABB enclosing it; null when the block isn't on a ship. */
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

    /** World AABB to the shipyard AABB enclosing it; null when the block isn't on a ship. */
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

    @Nullable
    private static Vec3 transformPosition(Object matrix, Vec3 pos) {
        return transform(matrix, pos, "transformPosition");
    }

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
     * Ship-local collision context: the entity's box and movement in shipyard space, where the
     * ship's blocks are axis-aligned. Vanilla collision runs there and the result is converted
     * back with {@link ShipCollisionContext#shipDirToWorld(Vec3)}.
     *
     * @return the context, or {@code null} if the entity isn't on a resolvable ship
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

        // A tight ship-aligned box rebuilt from the ship-space feet, extending along the entity's
        // local up (ship -Y under the hull)
        Direction up = shipLocalUp(entity, worldToShip);
        double halfWidth = worldBox.getXsize() * 0.5D;
        double halfDepth = worldBox.getZsize() * 0.5D;
        double height = worldBox.getYsize();
        double feetAlongUp = shipFeet.get(up.getAxis());
        double headAlongUp = feetAlongUp + height * up.getAxisDirection().getStep();
        AABB shipBox = axisBox(shipFeet, up.getAxis(),
                Math.min(feetAlongUp, headAlongUp), Math.max(feetAlongUp, headAlongUp), halfWidth, halfDepth);

        return new ShipCollisionContext(shipBox, shipMovement, shipToWorld, up);
    }

    /** The entity's local up in ship space, snapped to a ship axis; {@link Direction#UP} if undeterminable. */
    private static Direction shipLocalUp(Entity entity, Object worldToShip) {
        Vec3 gravity = GravityChangerAPI.getGravityDirectionVec(entity).getVector();
        Vec3 shipGravity = transformDirection(worldToShip, gravity);
        if (shipGravity == null || shipGravity.lengthSqr() < 1.0E-9D) {
            return Direction.UP;
        }
        return Direction.getNearest(-shipGravity.x, -shipGravity.y, -shipGravity.z);
    }

    /** An axis-aligned box spanning {@code [axisMin, axisMax]} along {@code axis}, centred on {@code center} with half-sizes {@code halfA}/{@code halfB} on the other two axes. */
    private static AABB axisBox(Vec3 center, Direction.Axis axis, double axisMin, double axisMax,
                                double halfA, double halfB) {
        return switch (axis) {
            case X -> new AABB(axisMin, center.y - halfA, center.z - halfB, axisMax, center.y + halfA, center.z + halfB);
            case Y -> new AABB(center.x - halfA, axisMin, center.z - halfB, center.x + halfA, axisMax, center.z + halfB);
            case Z -> new AABB(center.x - halfA, center.y - halfB, axisMin, center.x + halfA, center.y + halfB, axisMax);
        };
    }

    /**
     * Whether the entity rests on ship blocks in the given gravity direction: a thin slab below the
     * feet, probed in shipyard space. Vanilla's onGround is unreliable once an entity sinks
     * slightly into tilted ship geometry.
     *
     * @param worldDownDir  world-space gravity direction
     * @param probeDistance how far below the feet to look, in blocks
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

        // Slab across the footprint along the ship axis nearest gravity, from just above the feet
        Direction up = Direction.getNearest(-shipDown.x, -shipDown.y, -shipDown.z);
        int upSign = up.getAxisDirection().getStep();
        double halfWidth = box.getXsize() * 0.5D;
        double halfDepth = box.getZsize() * 0.5D;
        double margin = 0.02D;
        double feetAlongUp = shipFeet.get(up.getAxis());
        double nearEdge = feetAlongUp + margin * upSign;
        double farEdge = feetAlongUp - probeDistance * upSign;
        AABB slab = axisBox(shipFeet, up.getAxis(),
                Math.min(nearEdge, farEdge), Math.max(nearEdge, farEdge), halfWidth, halfDepth);

        for (VoxelShape shape : entity.level().getBlockCollisions(null, slab)) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far the feet have sunk into the ship block they stand in, along ship-up, so the caller
     * can push the entity back out. Only shapes topping out within {@code maxDepth} count (walls
     * don't).
     *
     * @param maxDepth deepest penetration to report, in blocks
     * @return depth in [0, maxDepth], 0 if not on a ship or not penetrating
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

        // Along the entity's local up in ship space
        Direction up = shipLocalUp(entity, worldToShip);
        Direction.Axis axis = up.getAxis();
        int upSign = up.getAxisDirection().getStep();
        double feetAlongUp = shipFeet.get(axis);

        // Footprint shrunk so blocks beside the entity don't count
        double halfWidth = Math.max(0.05D, box.getXsize() * 0.5D - 0.1D);
        double halfDepth = Math.max(0.05D, box.getZsize() * 0.5D - 0.1D);
        double windowEnd = feetAlongUp + maxDepth * upSign;
        AABB column = axisBox(shipFeet, axis,
                Math.min(feetAlongUp, windowEnd), Math.max(feetAlongUp, windowEnd), halfWidth, halfDepth);

        double penetration = 0.0D;
        for (VoxelShape shape : entity.level().getBlockCollisions(null, column)) {
            if (shape.isEmpty()) {
                continue;
            }
            AABB bounds = shape.bounds();
            double surface = upSign > 0 ? bounds.max(axis) : bounds.min(axis);
            double height = (surface - feetAlongUp) * upSign;
            // Shapes reaching past the window are walls
            if (height > maxDepth + 1.0E-4D) {
                continue;
            }
            penetration = Math.max(penetration, height);
        }
        return Math.min(Math.max(penetration, 0.0D), maxDepth);
    }

    /** World-space direction of the entity's local up in the ship frame, or {@code null} off-ship. */
    @Nullable
    public static Vec3 getShipLocalUpInWorld(Entity entity) {
        Object ship = getShipEntityIsOn(entity);
        if (ship == null) {
            return null;
        }
        Object worldToShip = getWorldToShipMatrixFromShip(ship);
        Object shipToWorld = getShipToWorldMatrixFromShip(ship);
        if (worldToShip == null || shipToWorld == null) {
            return null;
        }
        Direction up = shipLocalUp(entity, worldToShip);
        Vec3 worldUp = transformDirection(shipToWorld, Vec3.atLowerCornerOf(up.getNormal()));
        if (worldUp == null || worldUp.lengthSqr() < 1.0E-9D) {
            return null;
        }
        return worldUp.normalize();
    }

    // VS2's entity-dragging bookkeeping, resolved lazily
    private static Method getDraggingInformationMethod = null;
    private static Method setLastShipStoodOnMethod = null;
    private static boolean draggingInitAttempted = false;

    /**
     * Keep VS2 dragging an entity standing on a ship. VS2 only drags entities whose
     * {@code lastShipStoodOn} was refreshed recently, and refreshes it in its collision code, which
     * ship-gravity entities skip.
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

    /** World-space ship up (ship +Y), or {@code null} off-ship. */
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

    /** A collision sweep's ship-local inputs and the transform back to world space. */
    public static final class ShipCollisionContext {
        public final AABB shipBox;
        public final Vec3 shipMovement;
        /** The entity's local up in ship space (ship axis nearest to opposite its gravity). */
        public final Direction up;
        private final Object shipToWorldMatrix;

        private ShipCollisionContext(AABB shipBox, Vec3 shipMovement, Object shipToWorldMatrix, Direction up) {
            this.shipBox = shipBox;
            this.shipMovement = shipMovement;
            this.shipToWorldMatrix = shipToWorldMatrix;
            this.up = up;
        }

        /** Ship-local direction back to world space; {@code null} if the transform failed. */
        @Nullable
        public Vec3 shipDirToWorld(Vec3 shipDir) {
            return transformDirection(shipToWorldMatrix, shipDir);
        }
    }
}

