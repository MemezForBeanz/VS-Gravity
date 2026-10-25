package net.memezforbeanz.gravityapi.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.api.GravityFields;
import net.memezforbeanz.gravityapi.api.RotationParameters;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Teleporting mobs (endermen) stranded in open space teleport to the nearest ground they can
 * stand on, world or ship. Their own teleports only look for ground straight down the world
 * column, which in space or a sideways field often lands them in the void.
 * <p>
 * Stranded: airborne for {@value #STRANDED_TICKS} ticks with no surface within
 * {@value #SURFACE_REACH} blocks along gravity, the field's pull or their motion, or airborne for
 * {@value #MAX_AIRBORNE_TICKS} ticks whatever lies ahead.
 */
public final class TeleportRescue {

    /** Mobs that rescue themselves. */
    public static final TagKey<EntityType<?>> RESCUED = TagKey.create(Registries.ENTITY_TYPE,
            new ResourceLocation(GravityAPI.MODID, "space_rescue_teleporters"));

    private static final int STRANDED_TICKS = 20;
    private static final int MAX_AIRBORNE_TICKS = 200;
    private static final int CHECK_INTERVAL = 10;
    /** Wait after a search that found nowhere to stand. */
    private static final int RETRY_TICKS = 100;
    private static final double SURFACE_REACH = 32;
    private static final int SEARCH_RADIUS = 32;
    /** Below this a gravity or field pull doesn't count as carrying the mob anywhere. */
    private static final double MIN_PULL = 0.01;
    /** A face can be stood on when its normal is within about 60° of the local up. */
    private static final double MIN_UP_DOT = 0.5;
    private static final double MIN_SPEED = 0.05;

    private static final class State {
        int airborne;
        int cooldown;
    }

    private static final Map<LivingEntity, State> STATES = new WeakHashMap<>();

    private TeleportRescue() {
    }

    public static void tick(LivingEntity entity) {
        Level level = entity.level();
        if (level.isClientSide() || !entity.getType().is(RESCUED)) {
            return;
        }
        if (entity.onGround() || entity.isInWater() || entity.isInLava() || entity.isPassenger()
                || (entity instanceof Mob mob && mob.isLeashed()) || !entity.isAlive()) {
            STATES.remove(entity);
            return;
        }
        State state = STATES.computeIfAbsent(entity, e -> new State());
        state.airborne++;
        if (state.cooldown > 0) {
            state.cooldown--;
            return;
        }
        if (state.airborne < STRANDED_TICKS || state.airborne % CHECK_INTERVAL != 0) {
            return;
        }
        if (state.airborne < MAX_AIRBORNE_TICKS && headingForSurface(entity)) {
            return;
        }
        if (rescue(entity)) {
            STATES.remove(entity);
        } else {
            state.cooldown = RETRY_TICKS;
        }
    }

    /** Whether gravity, the field's pull or the mob's own motion is carrying it to a surface. */
    private static boolean headingForSurface(LivingEntity entity) {
        Level level = entity.level();
        Vec3 center = entity.getBoundingBox().getCenter();
        List<Vec3> directions = new ArrayList<>(3);
        if (!entity.isNoGravity() && GravityChangerAPI.getGravityStrength(entity) > MIN_PULL) {
            directions.add(GravityChangerAPI.getGravityDirectionVec(entity).getVector());
        }
        Vec3 field = GravityFields.fieldAcceleration(level, center);
        if (field != null && field.length() > MIN_PULL) {
            directions.add(field);
        }
        Vec3 velocity = GravityChangerAPI.getWorldVelocity(entity);
        if (velocity.length() > MIN_SPEED) {
            directions.add(velocity);
        }
        for (Vec3 direction : directions) {
            if (surfaceAlong(entity, center, center.add(direction.normalize().scale(SURFACE_REACH)))) {
                return true;
            }
        }
        return false;
    }

    /** A solid block on the segment, in the world or on a ship crossing it. */
    private static boolean surfaceAlong(LivingEntity entity, Vec3 from, Vec3 to) {
        Level level = entity.level();
        if (clips(level, entity, from, to)) {
            return true;
        }
        if (VS2Integration.isVS2Loaded()) {
            for (VS2Helper.ShipFrame ship : VS2Helper.shipFramesIntersecting(level, new AABB(from, to).inflate(1))) {
                Vec3 shipFrom = ship.toShip(from);
                Vec3 shipTo = ship.toShip(to);
                if (shipFrom != null && shipTo != null && clips(level, entity, shipFrom, shipTo)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean clips(Level level, LivingEntity entity, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
                .getType() != HitResult.Type.MISS;
    }

    /** A place to stand: the feet position and the face's outward normal, both in world space. */
    private record Spot(Vec3 feet, Vec3 up, @Nullable VS2Helper.ShipFrame ship, double distance) {
    }

    private static boolean rescue(LivingEntity entity) {
        Level level = entity.level();
        Vec3 center = entity.getBoundingBox().getCenter();
        Spot best = findSpot(entity, center, null, null);
        if (VS2Integration.isVS2Loaded()) {
            AABB reach = AABB.ofSize(center, 0, 0, 0).inflate(SEARCH_RADIUS);
            for (VS2Helper.ShipFrame ship : VS2Helper.shipFramesIntersecting(level, reach)) {
                Vec3 local = ship.toShip(center);
                if (local != null) {
                    best = findSpot(entity, local, ship, best);
                }
            }
        }
        if (best == null) {
            return false;
        }
        teleport(entity, best);
        return true;
    }

    /**
     * The nearest standable face around {@code localCenter} (shipyard space on a ship), or
     * {@code best} if none is nearer. Standable: the face points within {@link #MIN_UP_DOT} of up
     * (against the gravity there) and the body fits in front of it.
     */
    @Nullable
    private static Spot findSpot(LivingEntity entity, Vec3 localCenter, @Nullable VS2Helper.ShipFrame ship,
                                 @Nullable Spot best) {
        Level level = entity.level();
        int bodyBlocks = Math.max(1, (int) Math.ceil(entity.getBbHeight()));
        double bestDistance = best != null ? best.distance() : Double.MAX_VALUE;
        Spot found = best;
        BlockPos origin = BlockPos.containing(localCenter);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos clearance = new BlockPos.MutableBlockPos();

        // Sections nearest first, so the search stops once no closer face can exist
        List<SectionPos> sections = new ArrayList<>();
        SectionPos.betweenClosedStream(
                SectionPos.blockToSectionCoord(origin.getX() - SEARCH_RADIUS),
                SectionPos.blockToSectionCoord(Math.max(origin.getY() - SEARCH_RADIUS, level.getMinBuildHeight())),
                SectionPos.blockToSectionCoord(origin.getZ() - SEARCH_RADIUS),
                SectionPos.blockToSectionCoord(origin.getX() + SEARCH_RADIUS),
                SectionPos.blockToSectionCoord(Math.min(origin.getY() + SEARCH_RADIUS, level.getMaxBuildHeight() - 1)),
                SectionPos.blockToSectionCoord(origin.getZ() + SEARCH_RADIUS)).forEach(sections::add);
        sections.sort(Comparator.comparingDouble(section -> sectionDistance(section, localCenter)));

        for (SectionPos section : sections) {
            if (sectionDistance(section, localCenter) >= bestDistance) {
                break;
            }
            // Never load chunks: only search what's there
            if (!level.hasChunk(section.x(), section.z())) {
                continue;
            }
            LevelChunk chunk = level.getChunk(section.x(), section.z());
            int index = level.getSectionIndexFromSectionY(section.y());
            if (index < 0 || index >= chunk.getSections().length) {
                continue;
            }
            LevelChunkSection blocks = chunk.getSection(index);
            if (blocks.hasOnlyAir()) {
                continue;
            }
            for (int dx = 0; dx < 16; dx++) {
                for (int dy = 0; dy < 16; dy++) {
                    for (int dz = 0; dz < 16; dz++) {
                        BlockState state = blocks.getBlockState(dx, dy, dz);
                        if (state.isAir()) {
                            continue;
                        }
                        pos.set(section.minBlockX() + dx, section.minBlockY() + dy, section.minBlockZ() + dz);
                        if (Vec3.atCenterOf(pos).distanceTo(localCenter) - 1 >= bestDistance) {
                            continue;
                        }
                        VoxelShape shape = state.getCollisionShape(level, pos);
                        if (shape.isEmpty() || !insideBlock(shape)) {
                            continue;
                        }
                        for (Direction face : Direction.values()) {
                            // Feet go on the block boundary, so the shape must fill that face (no fences, walls, posts)
                            if (!Block.isFaceFull(shape, face)) {
                                continue;
                            }
                            Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
                            Vec3 localFeet = Vec3.atCenterOf(pos).add(normal.scale(0.5));
                            double distance = localFeet.distanceTo(localCenter);
                            if (distance >= bestDistance || !clear(level, clearance, pos, face, bodyBlocks)) {
                                continue;
                            }
                            Vec3 feet = ship != null ? ship.toWorld(localFeet) : localFeet;
                            Vec3 up = ship != null ? ship.directionToWorld(normal) : normal;
                            if (feet == null || up == null) {
                                continue;
                            }
                            // Standable against the gravity just in front of the face
                            Vec3 gravity = GravityFields.accelerationAt(level, feet.add(up.scale(0.5)));
                            if (gravity.lengthSqr() > 1.0E-8 && up.dot(gravity.normalize()) > -MIN_UP_DOT) {
                                continue;
                            }
                            bestDistance = distance;
                            found = new Spot(feet, up.normalize(), ship, distance);
                        }
                    }
                }
            }
        }
        return found;
    }

    /** Farthest a random teleport looks along gravity from its target for somewhere to land. */
    private static final int LANDING_SCAN = 48;

    /**
     * Whether a teleporting mob needs {@link #teleportNear}: vanilla's landing search drops straight
     * down in world Y over world blocks, so under other gravity, on a ship or in a field it lands
     * far off in the world (or nowhere) instead of on the ground around it.
     */
    public static boolean needsGravityTeleport(LivingEntity entity) {
        if (entity.level().isClientSide()) {
            return false;
        }
        if (!GravityChangerAPI.getGravityDirectionVec(entity).equals(GravityDirection.DOWN)) {
            return true;
        }
        if (VS2Integration.isVS2Loaded() && VS2Helper.getShipEntityIsOn(entity) != null) {
            return true;
        }
        return GravityFields.fieldAcceleration(entity.level(), entity.getBoundingBox().getCenter()) != null;
    }

    /**
     * A random teleport toward {@code (x, y, z)} that lands along the gravity there: in the world and
     * on every ship near the target, the first standable face within {@value #LANDING_SCAN} blocks
     * of it, nearest wins. Returns whether the mob teleported.
     */
    public static boolean teleportNear(LivingEntity entity, double x, double y, double z) {
        Level level = entity.level();
        Vec3 target = new Vec3(x, y, z);
        Vec3 gravity = GravityFields.accelerationAt(level, target);
        Vec3 down = gravity.lengthSqr() > 1.0E-8 ? gravity.normalize() : GravityChangerAPI.getGravityDirectionVec(entity).getVector();
        Spot best = landAlong(entity, target, down, null, null);
        if (VS2Integration.isVS2Loaded()) {
            for (VS2Helper.ShipFrame ship : VS2Helper.shipFramesIntersecting(level, AABB.ofSize(target, 0, 0, 0).inflate(LANDING_SCAN))) {
                best = landAlong(entity, target, down, ship, best);
            }
        }
        if (best == null) {
            return false;
        }
        teleport(entity, best);
        return true;
    }

    /** The first standable face along {@code worldDown} from {@code worldTarget} in one space, or {@code best} if that's nearer. */
    @Nullable
    private static Spot landAlong(LivingEntity entity, Vec3 worldTarget, Vec3 worldDown, @Nullable VS2Helper.ShipFrame ship, @Nullable Spot best) {
        Level level = entity.level();
        Vec3 localTarget = ship != null ? ship.toShip(worldTarget) : worldTarget;
        Vec3 localDown = ship != null ? ship.directionToShip(worldDown) : worldDown;
        if (localTarget == null || localDown == null || localDown.lengthSqr() < 1.0E-8) {
            return best;
        }
        Direction down = Direction.getNearest(localDown.x, localDown.y, localDown.z);
        Direction face = down.getOpposite();
        int bodyBlocks = Math.max(1, (int) Math.ceil(entity.getBbHeight()));
        BlockPos.MutableBlockPos pos = BlockPos.containing(localTarget).mutable();
        BlockPos.MutableBlockPos clearance = new BlockPos.MutableBlockPos();
        boolean passedAir = false;
        for (int i = 0; i < LANDING_SCAN; i++, pos.move(down)) {
            if (!level.hasChunkAt(pos)) {
                return best;
            }
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                passedAir = true;
                continue;
            }
            if (!passedAir) {
                continue;
            }
            // Same rules as a rescue landing: a full face, nothing sticking out, room for the body, dry
            if (!insideBlock(shape) || !Block.isFaceFull(shape, face) || !clear(level, clearance, pos, face, bodyBlocks)
                    || !level.getFluidState(pos.relative(face)).isEmpty()) {
                return best;
            }
            Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
            Vec3 localFeet = Vec3.atCenterOf(pos).add(normal.scale(0.5));
            double distance = localFeet.distanceTo(localTarget);
            if (best != null && distance >= best.distance()) {
                return best;
            }
            Vec3 feet = ship != null ? ship.toWorld(localFeet) : localFeet;
            Vec3 up = ship != null ? ship.directionToWorld(normal) : normal;
            if (feet == null || up == null) {
                return best;
            }
            Vec3 there = GravityFields.accelerationAt(level, feet.add(up.scale(0.5)));
            if (there.lengthSqr() > 1.0E-8 && up.normalize().dot(there.normalize()) > -MIN_UP_DOT) {
                return best;
            }
            return new Spot(feet, up.normalize(), ship, distance);
        }
        return best;
    }

    /** Whether the body fits in front of {@code face}: that many blocks with no collision. */
    private static boolean clear(Level level, BlockPos.MutableBlockPos cursor, BlockPos block, Direction face, int blocks) {
        for (int i = 1; i <= blocks; i++) {
            cursor.setWithOffset(block, face.getStepX() * i, face.getStepY() * i, face.getStepZ() * i);
            if (!level.hasChunkAt(cursor) || !level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Whether a collision shape stays within its own block (fences and walls reach above it). */
    private static boolean insideBlock(VoxelShape shape) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (shape.min(axis) < 0 || shape.max(axis) > 1) {
                return false;
            }
        }
        return true;
    }

    /** Distance from {@code point} to the nearest point of a section. */
    private static double sectionDistance(SectionPos section, Vec3 point) {
        double x = Math.max(Math.max(section.minBlockX() - point.x, point.x - (section.maxBlockX() + 1)), 0);
        double y = Math.max(Math.max(section.minBlockY() - point.y, point.y - (section.maxBlockY() + 1)), 0);
        double z = Math.max(Math.max(section.minBlockZ() - point.z, point.z - (section.maxBlockZ() + 1)), 0);
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Vanilla's teleport effects, then stand the mob on the face: gravity set to it at once, so it isn't in the block. */
    private static void teleport(LivingEntity entity, Spot spot) {
        Level level = entity.level();
        Vec3 from = entity.position();
        level.gameEvent(GameEvent.TELEPORT, from, GameEvent.Context.of(entity));
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, entity.getSoundSource(), 1.0F, 1.0F);

        Vec3 down = spot.up().scale(-1);
        GravityDirection frame = spot.ship() != null
                ? GravityDirection.shipRelative(down)
                : GravityDirection.fromDirection(Direction.getNearest(down.x, down.y, down.z));
        Vec3 feet = spot.feet().add(spot.up().scale(0.01));
        entity.teleportTo(feet.x, feet.y, feet.z);
        GravityChangerAPI.getGravityComponent(entity).applyGravityDirectionEffect(frame, RotationParameters.getDefault(), 0);
        GravityChangerAPI.settleGravityNow(entity);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0;
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
        }

        level.broadcastEntityEvent(entity, (byte) 46);
        entity.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
    }
}
