package net.memezforbeanz.gravityapi.capabilities;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import net.memezforbeanz.gravityapi.EntityTags;
import net.memezforbeanz.gravityapi.RotationAnimation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.api.RotationParameters;
import net.memezforbeanz.gravityapi.compat.vs2.ShipGravityCapabilities;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.memezforbeanz.gravityapi.api.generator.GravityGeneratorRegistry;
import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.memezforbeanz.gravityapi.init.GravityMobEffects;
import net.memezforbeanz.gravityapi.item.GravityAnchorItem;
import net.memezforbeanz.gravityapi.mixin.EntityAccessor;
import net.memezforbeanz.gravityapi.mob_effect.GravityDirectionMobEffect;
import net.memezforbeanz.gravityapi.network.GravityNetwork;
import net.memezforbeanz.gravityapi.network.UpdateGravityCapabilityPacket;
import net.memezforbeanz.gravityapi.network.UpdateGravitySyncStatePacket;
import net.memezforbeanz.gravityapi.util.GCUtil;
import net.memezforbeanz.gravityapi.util.RotationUtil;
import net.memezforbeanz.gravityapi.util.QuaternionUtil;
import net.memezforbeanz.gravityapi.util.FlyingMobs;
import net.memezforbeanz.gravityapi.api.GravityFields;
import net.minecraft.world.phys.Vec2;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The gravity is determined by the follows:
 * 1. base gravity
 * 2. gravity modifier, can override base gravity (determined from modifier events)
 * 3. gravity effects, can override modified gravity
 * The result of applying 1 and 2 is called modified gravity and is synced.
 * The result of 3 is current gravity and is not synced.
 * The gravity effect should be applied both on client and server, except for remote players.
 * (The client player's gravity attributes are separately computed.
 * Other client entities' are synced from server.)
 */
public class GravityCapabilityImpl implements IGravityCapability {
    public boolean initialized = false;
    
    // not synchronized
    private Direction prevGravityDirection = Direction.DOWN;
    private double prevGravityStrength = 1.0;
    
    Direction baseGravityDirection = Direction.DOWN;
    
    double baseGravityStrength = 1.0;
    
    @Nullable RotationParameters currentRotationParameters = RotationParameters.getDefault();
    
    // Only used on client, not synchronized.
    @Nullable
    public RotationAnimation animation;
    
    public Entity entity;
    
    private Direction currGravityDirection = Direction.DOWN;
    private double currGravityStrength = 1.0;
    private double currentEffectPriority = Double.MIN_VALUE;
    
    // Arbitrary (non-cardinal) gravity, for VS ships
    @Nullable
    private GravityDirection arbitraryGravityDirection = null;

    @Nullable
    private GravityDirection prevArbitraryGravityDirection = null;

    // Gravity from a camera-only source (VS ship gravity generator): the hitbox is never transformed
    private boolean isCameraOnlyGravity = false;

    // Set each tick while the entity is in a gravity generator field
    private boolean inGravityGeneratorField = false;

    /**
     * Whether gravity comes from a VS ship / camera-only source, even when the direction is cardinal.
     * The hitbox is then not transformed, only the camera rotates.
     */
    public boolean isArbitraryGravity() {
        if (inGravityGeneratorField) {
            return true;
        }
        if (isCameraOnlyGravity) {
            return true;
        }
        if (arbitraryGravityDirection != null && !arbitraryGravityDirection.isCardinal()) {
            return true;
        }
        // Entity on a VS ship whose gravity effect hasn't synced yet
        if (entity != null && VS2Integration.isVS2Loaded() && VS2Integration.isOnShip(entity)) {
            return true;
        }
        return false;
    }

    /**
     * Strict check: whether ship-relative gravity physics is active.
     * <p>Unlike {@link #isArbitraryGravity()}, proximity to a ship doesn't count. The ship collision
     * pipeline must only run for genuinely ship-relative gravity, or normal-gravity entities
     * touching a hull get pushed away from it.
     */
    public boolean isShipGravityPhysics() {
        // A cardinal arbitrary direction (a planet field off-ship) uses the classic cardinal
        // physics, not the ship pipeline
        return inGravityGeneratorField || isCameraOnlyGravity
                || (arbitraryGravityDirection != null && !arbitraryGravityDirection.isCardinal());
    }

    public boolean isInGravityGeneratorField() {
        return inGravityGeneratorField;
    }

    public void setInGravityGeneratorField(boolean inField) {
        this.inGravityGeneratorField = inField;
    }

    private boolean isFiringUpdateEvent = false;

    // Field free fall (see applyFieldGravityEffect): pending values take hold if the field's effect wins the tick
    private @Nullable Vec3 pendingFreeFallPull = null;
    private @Nullable Vec3 pendingFreeFallFace = null;
    private double pendingFreeFallPriority = Double.NEGATIVE_INFINITY;
    private boolean pendingAirborneFlip = false;
    private double pendingAirborneFlipPriority = Double.NEGATIVE_INFINITY;
    /** World-space pull of the field holding this entity in free fall this tick, or null. */
    private @Nullable Vec3 freeFallPull = null;
    /** World-space face the free fall is heading for (the tilt's target), or null for the pull's. */
    private @Nullable Vec3 freeFallFace = null;
    /** Field gravity strength the free fall accelerates with (in-frame gravity is off meanwhile). */
    private double freeFallStrength = 0;
    /** This tick's cardinal flip happens in mid-air: pivot on the hitbox centre, not the feet. */
    private boolean airborneFlip = false;
    // Visual tilt toward the free fall's target face, in the player frame; slerped from last tick's value
    private final Quaternionf visualTilt = new Quaternionf();
    private final Quaternionf prevVisualTilt = new Quaternionf();

    private @Nullable GravityCapabilityImpl.GravityDirEffect delayApplyDirEffect = null;
    private double delayApplyStrengthEffect = 1.0;

    // Effect linger: hold the last per-tick effect for a few ticks when it misses one (boundary
    // jitter, tick order, sync gaps) instead of flipping back and forth
    private int ticksSinceLastEffect = 1000;
    private Direction lingerDirection = Direction.DOWN;
    private @Nullable GravityDirection lingerArbitrary = null;
    private boolean lingerCameraOnly = false;
    private @Nullable RotationParameters lingerRotationParameters = null;

    // Absolute strength override (a field's target gravity): replaces the strength, planet mod
    // gravity included (see compat.planets). Re-applied every tick, highest priority wins.
    private double pendingStrengthOverride = Double.NaN;
    private double pendingStrengthOverridePriority = Double.NEGATIVE_INFINITY;
    private double strengthOverride = Double.NaN;
    private int ticksSinceStrengthOverride = 1000;
    
    // only used on server side
    public boolean needsSync = false;
    
    public boolean noAnimation = false;
    
	@Override
	public void setEntity(Entity entity) 
	{
		this.entity = entity;
        if (entity.level().isClientSide()) {
            animation = new RotationAnimation();
        }
        else {
            animation = null;
        }
	}
    
    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag.contains("baseGravityDirection")) {
            baseGravityDirection = Direction.byName(tag.getString("baseGravityDirection"));
        }
        else {
            baseGravityDirection = Direction.DOWN;
        }
        
        if (tag.contains("baseGravityStrength")) {
            baseGravityStrength = tag.getDouble("baseGravityStrength");
        }
        else {
            baseGravityStrength = 1.0;
        }
        
        // the current gravity is serialized to avoid unnecessary gravity rotation when entering world
        // do not deserialize it when for client player when not initializing
        if (!initialized || shouldAcceptServerSync()) {
            if (tag.contains("currentGravityDirection")) {
                currGravityDirection = Direction.byName(tag.getString("currentGravityDirection"));
            }
            else {
                currGravityDirection = Direction.DOWN;
            }
            
            if (tag.contains("currentGravityStrength")) {
                currGravityStrength = tag.getDouble("currentGravityStrength");
            }
            else {
                currGravityStrength = 1.0;
            }
        }
        
        if (!initialized) {
            prevGravityDirection = currGravityDirection;
            prevGravityStrength = currGravityStrength;
            initialized = true;
            this.needsSync = true;
            this.noAnimation = true;
            applyGravityDirectionChange(
                prevGravityDirection, currGravityDirection, currentRotationParameters, true
            );
        }
    }
    
    private boolean shouldAcceptServerSync() {
        return entity.level().isClientSide() && !GCUtil.isClientPlayer(entity);
    }
    
    @Override
    public CompoundTag serializeNBT() {
		CompoundTag tag = new CompoundTag();
        tag.putString("baseGravityDirection", baseGravityDirection.getName());
        tag.putString("currentGravityDirection", currGravityDirection.getName());
        
        tag.putDouble("baseGravityStrength", baseGravityStrength);
        tag.putDouble("currentGravityStrength", currGravityStrength);
		return tag;
    }
    
    @Override
    public void tick() {
        if (!canChangeGravity()) {
            return;
        }
        
        tickShipGravity();

        updateGravityStatus(true);

        // After the update, so delay-applied effects are corrected too: re-anchor ship-relative
        // gravity to the ship's current rotation
        reanchorArbitraryToShipRotation();

        applyGravityChange();

        tickFieldFreeFall();
        
        if (!entity.level().isClientSide()) {
            if (needsSync) {
                sendSyncPacketToOtherPlayers();
            }
        }
    }

    /**
     * Rotate stored ship-relative gravity by this tick's ship rotation. Unanchored directions are
     * world-space snapshots and would otherwise lag a rotating ship.
     */
    private void reanchorArbitraryToShipRotation() {
        if (!VS2Integration.isVS2Loaded()) {
            return;
        }
        // Anchored directions re-resolve exactly from the ship's current transform
        boolean resolvedAnchored = false;
        if (arbitraryGravityDirection != null && arbitraryGravityDirection.isShipAnchored()) {
            GravityDirection resolved = resolveAnchored(arbitraryGravityDirection);
            if (lingerArbitrary == arbitraryGravityDirection) {
                lingerArbitrary = resolved;
            }
            arbitraryGravityDirection = resolved;
            resolvedAnchored = true;
        }
        if (lingerArbitrary != null && lingerArbitrary.isShipAnchored() && lingerArbitrary != arbitraryGravityDirection) {
            lingerArbitrary = resolveAnchored(lingerArbitrary);
        }
        if (resolvedAnchored) {
            return;
        }
        // Camera-only effects were computed from this tick's transform; only a lingering copy is stale.
        // Per-entity effects are always one tick behind.
        if (isCameraOnlyGravity && ticksSinceLastEffect == 0) {
            return;
        }
        boolean hasArbitrary = arbitraryGravityDirection != null && !arbitraryGravityDirection.isCardinal();
        boolean hasLinger = lingerArbitrary != null && !lingerArbitrary.isCardinal();
        if (!hasArbitrary && !hasLinger) {
            return;
        }
        org.joml.Quaterniond delta =
                net.memezforbeanz.gravityapi.compat.vs2.VS2Helper.getShipRotationDeltaThisTick(entity);
        if (delta == null) {
            return;
        }
        GravityDirection rotatedArbitrary = hasArbitrary ? rotatedBy(arbitraryGravityDirection, delta) : null;
        if (hasLinger) {
            // The linger slot usually aliases the live direction: rotate it once
            lingerArbitrary = (lingerArbitrary == arbitraryGravityDirection && rotatedArbitrary != null)
                    ? rotatedArbitrary
                    : rotatedBy(lingerArbitrary, delta);
        }
        // currGravityDirection is managed by the effect path (camera-only gravity keeps it DOWN)
        if (rotatedArbitrary != null) {
            arbitraryGravityDirection = rotatedArbitrary;
        }
    }

    private static GravityDirection resolveAnchored(GravityDirection direction) {
        org.joml.Quaterniond shipToWorld = net.memezforbeanz.gravityapi.compat.vs2.VS2Helper.anchoredShipToWorld(direction, false);
        return shipToWorld != null ? direction.withShipRotation(shipToWorld) : direction;
    }

    private static GravityDirection rotatedBy(GravityDirection direction, org.joml.Quaterniond rotation) {
        net.minecraft.world.phys.Vec3 v = direction.getVector();
        org.joml.Vector3d rotated = rotation.transform(new org.joml.Vector3d(v.x, v.y, v.z));
        return GravityDirection.shipRelative(new net.minecraft.world.phys.Vec3(rotated.x, rotated.y, rotated.z));
    }

    private void tickShipGravity() {
        if (!VS2Integration.isVS2Loaded()) {
            return;
        }

        var shipGravity = ShipGravityCapabilities.get(entity);
        if (shipGravity != null) shipGravity.tick();
    }

    /**
     * A gravity plate's bid for this entity's gravity this tick. Each nearby plate face adds a
     * candidate and {@link #resolvePlatingCandidates()} picks one, preferring the held direction.
     */
    public record PlatingCandidate(Vec3 worldDirection, boolean shipFrame, double volume, BlockPos source) {}

    public static final double PLATING_EFFECT_PRIORITY = 1000;

    private final List<PlatingCandidate> platingCandidates = new ArrayList<>();
    /** World-frame direction the plate resolution chose last tick (hysteresis anchor). */
    @Nullable
    private Vec3 stickyPlatingDirection = null;

    // Client-authoritative plating for players: the client reports its choice so both sides flip
    // together
    @Nullable
    private Vec3 lastReportedPlatingDirection = null;
    @Nullable
    private BlockPos lastReportedPlatingSource = null;
    private long lastPlatingReportTime = -1000;
    /** Server side: the sender's most recent verified plating report. */
    @Nullable
    private PlatingCandidate clientReportedPlating = null;
    private long clientPlatingReportGameTime = -1000;
    /** Game time of the last cardinal gravity flip (used to relax server move validation). */
    public long lastGravityChangeGameTime = -1000;

    public void addPlatingCandidate(Vec3 worldDirection, boolean shipFrame, double volume, BlockPos source) {
        platingCandidates.add(new PlatingCandidate(worldDirection.normalize(), shipFrame, volume, source));
    }

    public void setClientReportedPlating(@Nullable PlatingCandidate candidate) {
        clientReportedPlating = candidate;
        clientPlatingReportGameTime = entity.level().getGameTime();
    }

    /** Sanity check for a client plating report: the source must still be a plate within reach. */
    public boolean isPlatingReportValid(PlatingCandidate candidate) {
        if (!(entity.level().getBlockEntity(candidate.source()) instanceof
                net.memezforbeanz.gravityapi.plating.GravityPlatingBlockEntity)) {
            return false;
        }
        Vec3 plateCenter = Vec3.atCenterOf(candidate.source());
        if (candidate.shipFrame()) {
            plateCenter = VS2Integration.isVS2Loaded()
                ? net.memezforbeanz.gravityapi.compat.vs2.VS2Helper.shipToWorldPosition(
                    entity.level(), candidate.source(), plateCenter)
                : null;
            if (plateCenter == null) {
                return false;
            }
        }
        double allowed = Math.min(Math.max(candidate.volume(), 0.0), 64.0) + 6.0;
        return entity.position().distanceToSqr(plateCenter) <= allowed * allowed;
    }

    private void maybeSendPlatingReport(@Nullable PlatingCandidate chosen) {
        if (!entity.level().isClientSide() || !GCUtil.isClientPlayer(entity)) {
            return;
        }
        long now = entity.level().getGameTime();
        boolean changed;
        if (chosen == null) {
            changed = lastReportedPlatingDirection != null;
        } else {
            changed = lastReportedPlatingDirection == null
                || lastReportedPlatingDirection.dot(chosen.worldDirection()) < 0.999
                || !chosen.source().equals(lastReportedPlatingSource);
        }
        // Refresh periodically while holding a choice; the server expires stale reports
        boolean refresh = chosen != null && now - lastPlatingReportTime >= 20;
        if (!changed && !refresh) {
            return;
        }
        lastReportedPlatingDirection = chosen == null ? null : chosen.worldDirection();
        lastReportedPlatingSource = chosen == null ? null : chosen.source();
        lastPlatingReportTime = now;
        GravityNetwork.sendToServer(new net.memezforbeanz.gravityapi.network.UpdatePlatingGravityPacket(chosen));
    }

    private void resolvePlatingCandidates() {
        // Players are excluded from server plate detection; use their verified client report
        if (!entity.level().isClientSide() && entity instanceof net.minecraft.server.level.ServerPlayer
                && clientReportedPlating != null) {
            if (entity.level().getGameTime() - clientPlatingReportGameTime <= 40
                    && isPlatingReportValid(clientReportedPlating)) {
                platingCandidates.add(clientReportedPlating);
            } else {
                clientReportedPlating = null;
            }
        }

        if (platingCandidates.isEmpty()) {
            stickyPlatingDirection = null;
            maybeSendPlatingReport(null);
            return;
        }
        List<PlatingCandidate> candidates = new ArrayList<>(platingCandidates);
        platingCandidates.clear();

        boolean flying = entity instanceof Player player && player.getAbilities().flying;
        if (entity.isSpectator() || flying) {
            stickyPlatingDirection = null;
            maybeSendPlatingReport(null);
            return;
        }

        // Smallest volume = most specific plate tier wins (matches AmethystGravity)
        double lowestVolume = Double.MAX_VALUE;
        for (PlatingCandidate c : candidates) {
            lowestVolume = Math.min(lowestVolume, c.volume());
        }
        PlatingCandidate chosen = null;
        for (PlatingCandidate c : candidates) {
            if (c.volume() > lowestVolume + 1.0e-9) {
                continue;
            }
            if (chosen == null) {
                chosen = c;
            }
            // Hysteresis: keep the held direction while it's among the best candidates
            if (stickyPlatingDirection != null && c.worldDirection().dot(stickyPlatingDirection) > 0.999) {
                chosen = c;
                break;
            }
        }
        stickyPlatingDirection = chosen.worldDirection();
        maybeSendPlatingReport(chosen);

        boolean fallFlying = entity instanceof LivingEntity living && living.isFallFlying();
        RotationParameters params = new RotationParameters(
            entity.onGround(), !fallFlying, RotationParameters.getDefault().rotationTimeMS()
        );
        GravityDirection direction = chosen.shipFrame()
            ? GravityDirection.shipRelative(chosen.worldDirection())
            : GravityDirection.fromVector(chosen.worldDirection());
        // Plate candidates come from last tick's block ticks: anchor with that tick's ship transform
        applyGravityDirectionEffect(anchorToShip(direction, true), params, PLATING_EFFECT_PRIORITY);
    }

    public void updateGravityStatus(boolean sendPacketIfNecessary) {
        // Needed for hitbox calculations even for entities that accept server sync; computed once
        // and reused below
        GravityGeneratorRegistry.GravityFieldResult registryFieldResult =
                GravityGeneratorRegistry.getGravityFieldEffect(entity);
        if (VS2Integration.isVS2Loaded()) {
            inGravityGeneratorField = (registryFieldResult != null);
        }

        // for the remote players and non-player entities,
        // their effect data is not synchronized to the client
        // (possibly for making it harder to cheat for hacked clients)
        // then we don't calculate its gravity in normal way in client
        if (shouldAcceptServerSync()) {
            // Plate block entities still tick client-side for these entities; drop their candidates
            platingCandidates.clear();
            clearPendingFieldState();
            freeFallPull = null;
            return;
        }
        
        Direction oldGravityDirection = currGravityDirection;
        double oldGravityStrength = currGravityStrength;
        
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            currGravityDirection = GravityChangerAPI.getGravityDirection(vehicle);
            currGravityStrength = GravityChangerAPI.getGravityStrength(vehicle);
            // Riding: no field free fall
            clearPendingFieldState();
            freeFallPull = null;
        }
        else {
            currGravityDirection = baseGravityDirection;
            currGravityStrength = baseGravityStrength;
            currGravityStrength *= GravityConfig.gravityStrengthMultiplier.get();
            // the rotation parameters is not being reset here
            // the rotation parameter is kept when an effect vanishes
            currentEffectPriority = Double.MIN_VALUE;
            
            isFiringUpdateEvent = true;
            try {
                for (ItemStack handSlot : entity.getHandSlots()) {
                    Item item = handSlot.getItem();
                    if (item instanceof GravityAnchorItem anchorItem) {
                        this.applyGravityDirectionEffect(
                            anchorItem.direction,
                            null, 1000000
                        );
                    }
                }

                GravityGeneratorRegistry.GravityFieldResult fieldResult = registryFieldResult;

                inGravityGeneratorField = (fieldResult != null);

                if (fieldResult != null) {
                    // VS ship generator: camera-only gravity, physics stays DOWN
                    this.applyCameraOnlyGravityEffect(
                            fieldResult.gravityDirection(),
                            fieldResult.parameters(),
                            fieldResult.priority()
                    );
                }

                // Gravity plating: resolve this tick's plate candidates into (at most) one effect
                resolvePlatingCandidates();

                // Before the non-living early-out: items and minecarts feel the target gravity too
                resolveStrengthOverride();

                if (!(entity instanceof LivingEntity livingEntity)) {
                    // Non-living entities never free-fall
                    clearPendingFieldState();
                    return;
                }
                
                for (GravityDirectionMobEffect dirEffect : GravityDirectionMobEffect.EFFECT_MAP.values()) {
                    MobEffectInstance effectInstance = livingEntity.getEffect(dirEffect);
                    if (effectInstance != null) {
                        int amplifier = effectInstance.getAmplifier();
                        
                        this.applyGravityDirectionEffect(
                            dirEffect.gravityDirection,
                            null,
                            amplifier + 1.0
                        );
                    }
                }
                if (entity instanceof LivingEntity living) {
                    if (living.hasEffect(GravityMobEffects.INVERT.get())) {
                        this.applyGravityDirectionEffect(
                        		this.getCurrGravityDirection().getOpposite(),
                            null, 5
                        );
                    }
                }
                if (entity instanceof LivingEntity living) {
                	GravityMobEffects.INCREASE.get().apply(living, this);
                    GravityMobEffects.DECREASE.get().apply(living, this);
                    GravityMobEffects.REVERSE.get().apply(living, this);
                }


                if (delayApplyDirEffect != null) {
                    if (delayApplyDirEffect.isCameraOnly()) {
                        // Camera-only: direction stays DOWN, only the arbitrary direction is set
                        applyCameraOnlyGravityEffect(
                            delayApplyDirEffect.arbitraryDirection(),
                            delayApplyDirEffect.rotationParameters(),
                            delayApplyDirEffect.priority()
                        );
                    } else if (delayApplyDirEffect.arbitraryDirection() != null) {
                        // Already limited (or deliberately not) when issued
                        applyGravityDirectionEffect(
                            delayApplyDirEffect.arbitraryDirection(),
                            delayApplyDirEffect.rotationParameters(),
                            delayApplyDirEffect.priority(),
                            false
                        );
                    } else {
                        applyGravityDirectionEffect(
                            delayApplyDirEffect.direction(),
                            delayApplyDirEffect.rotationParameters(), delayApplyDirEffect.priority()
                        );
                    }
                    delayApplyDirEffect = null;
                }
                currGravityStrength *= delayApplyStrengthEffect;
                delayApplyStrengthEffect = 1.0;
                resolveFieldFreeFall();
            }
            finally {
                isFiringUpdateEvent = false;
            }
            
            if (currentEffectPriority != Double.MIN_VALUE) {
                // An effect is active: remember it so a brief dropout can be ridden out
                ticksSinceLastEffect = 0;
                lingerDirection = currGravityDirection;
                lingerArbitrary = arbitraryGravityDirection;
                lingerCameraOnly = isCameraOnlyGravity;
                lingerRotationParameters = currentRotationParameters;
            } else if (ticksSinceLastEffect < GravityConfig.effectLingerTicks.get()) {
                // No effect this tick, but one was active moments ago: hold it
                ticksSinceLastEffect++;
                currGravityDirection = lingerDirection;
                arbitraryGravityDirection = lingerArbitrary;
                isCameraOnlyGravity = lingerCameraOnly;
                if (lingerRotationParameters != null) {
                    currentRotationParameters = lingerRotationParameters;
                }
            } else {
                // if no effect is applied, reset the rotation parameters and arbitrary gravity
                if (ticksSinceLastEffect < 1000) {
                    ticksSinceLastEffect++;
                }
                currentRotationParameters = RotationParameters.getDefault();
                arbitraryGravityDirection = null;
                isCameraOnlyGravity = false;
                lingerArbitrary = null;
                lingerRotationParameters = null;
            }
        }
        
        if (entity instanceof net.minecraft.world.entity.player.Player
                && net.memezforbeanz.gravityapi.compat.vs2.ShipCollisionDebug.shouldLogVerbose(entity)) {
            net.memezforbeanz.gravityapi.compat.vs2.ShipCollisionDebug.log(
                    "[updateStatus] side={} acceptSync={} inField={} effectPriority={} cameraOnly={} currCardinal={} arbitrary={}",
                    entity.level().isClientSide() ? "CLIENT" : "SERVER", shouldAcceptServerSync(),
                    inGravityGeneratorField, currentEffectPriority, isCameraOnlyGravity,
                    currGravityDirection, arbitraryGravityDirection);
        }

        if (sendPacketIfNecessary) {
            boolean cardinalChanged = oldGravityDirection != currGravityDirection;
            boolean strengthChanged = Math.abs(oldGravityStrength - currGravityStrength) > 0.0001;
            // Also sync when arbitrary gravity or camera-only flag changes
            boolean arbitraryChanged = hasArbitraryGravityChanged() || isCameraOnlyGravity;

            if (cardinalChanged || strengthChanged || arbitraryChanged || needsSync) {
                sendSyncPacketToOtherPlayers();
                needsSync = false;
            }
        }
    }
    
    private void sendSyncPacketToOtherPlayers() 
    {
		if(!this.entity.level().isClientSide)
		{

			GravityNetwork.sendTrackingEntity(this.entity,
				new UpdateGravityCapabilityPacket(this.noAnimation, this.entity.getUUID(), baseGravityDirection, currGravityDirection, baseGravityStrength, currGravityStrength, arbitraryGravityDirection, isCameraOnlyGravity));
		}
    }
	
	@Override
	public void sync(boolean noAnimation, Direction baseGravityDirection, Direction currentGravityDirection, double baseGravityStrength, double currentGravityStrength, @Nullable GravityDirection arbitraryGravity)
    {
		sync(noAnimation, baseGravityDirection, currentGravityDirection, baseGravityStrength, currentGravityStrength, arbitraryGravity, false);
    }

    @Override
	public void sync(boolean noAnimation, Direction baseGravityDirection, Direction currentGravityDirection, double baseGravityStrength, double currentGravityStrength, @Nullable GravityDirection arbitraryGravity, boolean isCameraOnly)
    {
		this.baseGravityDirection = baseGravityDirection;
		this.baseGravityStrength = baseGravityStrength;
		// The local player computes its own current gravity from local effects; taking the server's
		// (a tick or two apart) caused flip-fights. Only remote entities take server state.
		if (shouldAcceptServerSync()) {
			this.currGravityDirection = currentGravityDirection;
			this.currGravityStrength = currentGravityStrength;
			this.arbitraryGravityDirection = arbitraryGravity;
			this.isCameraOnlyGravity = isCameraOnly;
		}
		if(noAnimation)
		{
			GravityChangerAPI.instantlySetClientBaseGravityDirection(this.entity, baseGravityDirection);
		}
		GravityNetwork.sendToServer(new UpdateGravitySyncStatePacket(this.entity.getUUID()));
    }
    
    public void applyGravityDirectionEffect(
        @NotNull Direction direction,
        @Nullable RotationParameters rotationParameters,
        double priority
    ) {
        applyGravityDirectionEffect(GravityDirection.fromDirection(direction), rotationParameters, priority);
    }

    /**
     * Apply a gravity field's pull, Mario Galaxy style. Physics gravity is always a face direction
     * (a world cardinal, or a ship-relative face); the true pull is only a force and a visual tilt:
     * <ul>
     *     <li>grounded or {@code nearSurface}: gravity is {@code landingFace};</li>
     *     <li>otherwise the current frame is held, in-frame gravity is off, and {@code pull}
     *     accelerates the entity while its view tilts toward the face (see {@link #getVisualTilt}).</li>
     * </ul>
     *
     * @param pull        the field's true pull in world space
     * @param landingFace the world-space face to stand on; null to use {@code pull}'s
     * @param shipFrame   the field is on a VS ship: faces are ship-relative, never cardinal
     */
    public void applyFieldGravityEffect(
        @NotNull Vec3 pull,
        @Nullable Vec3 landingFace,
        boolean nearSurface,
        boolean shipFrame,
        @Nullable RotationParameters rotationParameters,
        double priority
    ) {
        if (pull.lengthSqr() < 1.0E-8) {
            return;
        }
        if (FlyingMobs.isFlying(entity)) {
            // Flying mobs keep world-frame physics (vanilla flight AI) and only lean into the
            // field's pull on screen (see tickFieldFreeFall); landed, they take faces as usual
            return;
        }
        Vec3 face = landingFace != null && landingFace.lengthSqr() > 1.0E-8 ? landingFace.normalize() : pull.normalize();
        boolean grounded = entity.onGround();
        // Swimming, climbing and riding keep vanilla's in-frame gravity
        boolean supported = grounded || entity.isInWater() || entity.isInLava() || entity.isPassenger()
            || (entity instanceof LivingEntity living && living.onClimbable());
        // Creative flight and elytra steer in the frame's own axes, which a tilted view would
        // contradict: they take the face directly
        boolean selfPropelled = (entity instanceof Player player && player.getAbilities().flying)
            || (entity instanceof LivingEntity living && living.isFallFlying());
        supported |= selfPropelled;
        if (supported || nearSurface || !GravityConfig.fieldFreeFall.get() || !(entity instanceof LivingEntity)) {
            GravityDirection direction = shipFrame
                ? GravityDirection.shipRelative(face)
                : GravityDirection.fromDirection(Direction.getNearest(face.x, face.y, face.z));
            // Only landing out of a free fall pivots on the hitbox centre and keeps world momentum;
            // other field flips are the upstream flip about the feet
            if (freeFallPull != null && priority > pendingAirborneFlipPriority) {
                pendingAirborneFlip = true;
                pendingAirborneFlipPriority = priority;
            }
            // Field faces are exact: no angle-limited steps (a 180° flip included)
            applyGravityDirectionEffect(direction, rotationParameters, priority, false);
            return;
        }
        // Free fall: hold the current frame
        GravityDirection hold = arbitraryGravityDirection != null && !arbitraryGravityDirection.isCardinal()
            ? arbitraryGravityDirection
            : GravityDirection.fromDirection(currGravityDirection);
        if (pendingFreeFallPull == null || priority > pendingFreeFallPriority) {
            pendingFreeFallPull = pull.normalize();
            pendingFreeFallFace = face;
            pendingFreeFallPriority = priority;
        }
        applyGravityDirectionEffect(hold, rotationParameters, priority);
    }

    /** After this tick's effects resolved: start free fall / mid-air flip if a field's effect won. */
    private void resolveFieldFreeFall() {
        freeFallPull = null;
        airborneFlip = false;
        if (pendingFreeFallPull != null && pendingFreeFallPriority == currentEffectPriority) {
            freeFallPull = pendingFreeFallPull;
            freeFallFace = pendingFreeFallFace;
            freeFallStrength = currGravityStrength;
            // The pull replaces in-frame gravity (see tickFieldFreeFall)
            currGravityStrength = 0;
        } else if (pendingAirborneFlip && pendingAirborneFlipPriority == currentEffectPriority) {
            airborneFlip = true;
        }
        clearPendingFieldState();
    }

    private void clearPendingFieldState() {
        pendingFreeFallPull = null;
        pendingFreeFallFace = null;
        pendingFreeFallPriority = Double.NEGATIVE_INFINITY;
        pendingAirborneFlip = false;
        pendingAirborneFlipPriority = Double.NEGATIVE_INFINITY;
    }

    /** Vanilla's per-tick gravity acceleration for living entities, at 1.0 strength. */
    private static final double LIVING_GRAVITY = 0.08;

    /** Share of the remaining tilt turned per tick (capped by the configured rate): eases in. */
    private static final float TILT_APPROACH = 0.15f;
    /** Tilt turned back per tick when a free fall ends without a flip (about half a second). */
    private static final float TILT_RELEASE_RADIANS = (float) Math.toRadians(18);

    /** This tick's free-fall acceleration (world space), waiting for travel. */
    @Nullable
    private Vec3 pendingFreeFallAcceleration;

    /**
     * Add this tick's free-fall acceleration. Called as travel starts, after vanilla zeroes
     * velocity components below 0.003, like vanilla gravity is.
     */
    public void applyFreeFallAcceleration() {
        if (pendingFreeFallAcceleration != null) {
            entity.setDeltaMovement(entity.getDeltaMovement()
                .add(getCurrentGravityDirection().vecWorldToPlayer(pendingFreeFallAcceleration)));
            pendingFreeFallAcceleration = null;
        }
    }

    /** Free-fall acceleration along the field's pull, and the visual tilt toward the target face. */
    private void tickFieldFreeFall() {
        airborneFlip = false;
        pendingFreeFallAcceleration = null;
        prevVisualTilt.set(visualTilt);
        if (freeFallPull != null) {
            boolean flying = entity instanceof Player player && player.getAbilities().flying;
            if (!flying && !entity.isNoGravity() && freeFallStrength > 0) {
                // Added at travel (see applyFreeFallAcceleration): here, aiStep's small-velocity
                // cutoff would erase a weak pull every tick and leave the entity hanging
                pendingFreeFallAcceleration = freeFallPull.scale(LIVING_GRAVITY * freeFallStrength);
            }
            // Aim at the landing face so the landing flip has little left to turn
            Vec3 target = getCurrentGravityDirection()
                .vecWorldToPlayer(freeFallFace != null ? freeFallFace : freeFallPull).normalize();
            Quaternionf targetTilt = new Quaternionf().rotationTo(0, -1, 0, (float) target.x, (float) target.y, (float) target.z);
            float maxStep = (float) (Math.PI / 2 / (GravityConfig.fieldTiltSeconds.get() * 20.0));
            turnToward(visualTilt, targetTilt, maxStep, TILT_APPROACH);
        } else if (FlyingMobs.isFlying(entity)) {
            // Read from the field samplers on both sides, so remote clients lean the model too
            Vec3 field = GravityFields.fieldAcceleration(entity.level(), entity.getBoundingBox().getCenter());
            Quaternionf targetTilt = new Quaternionf();
            if (field != null && field.lengthSqr() > 1.0E-8) {
                Vec3 target = getCurrentGravityDirection().vecWorldToPlayer(field).normalize();
                targetTilt.rotationTo(0, -1, 0, (float) target.x, (float) target.y, (float) target.z);
            }
            float maxStep = (float) (Math.PI / 2 / (GravityConfig.fieldTiltSeconds.get() * 20.0));
            turnToward(visualTilt, targetTilt, maxStep, TILT_APPROACH);
        } else {
            turnToward(visualTilt, new Quaternionf(), TILT_RELEASE_RADIANS, 1.0f);
        }
    }

    /** Turn {@code current} toward {@code target}: {@code share} of the way, at most {@code maxStep} radians. */
    private static void turnToward(Quaternionf current, Quaternionf target, float maxStep, float share) {
        float dot = Math.abs(current.x * target.x + current.y * target.y + current.z * target.z + current.w * target.w);
        float angle = 2.0f * (float) Math.acos(Math.min(dot, 1.0f));
        if (angle < 1.0E-4f) {
            current.set(target);
            return;
        }
        float step = Math.min(angle * share, maxStep);
        // Never crawl the last few degrees
        step = Math.max(step, Math.min(angle, (float) Math.toRadians(0.5)));
        current.slerp(target, Math.min(step / angle, 1.0f)).normalize();
    }

    private static boolean isIdentity(Quaternionf q) {
        return Math.abs(q.w) > 1.0f - 1.0E-6f;
    }

    /**
     * The free fall's tilt, or null when there is none. A player-frame rotation
     * ({@code worldToPlayer' = tilt^-1 * worldToPlayer}), slerped between the last two ticks.
     */
    @Nullable
    public Quaternionf getVisualTilt(float partialTick) {
        if (isIdentity(visualTilt) && isIdentity(prevVisualTilt)) {
            return null;
        }
        return new Quaternionf(prevVisualTilt).slerp(visualTilt, Mth.clamp(partialTick, 0, 1));
    }

    /** Return and clear the tilt, for a starting rotation animation to take over. */
    @Nullable
    public Quaternionf consumeVisualTilt() {
        Quaternionf tilt = isIdentity(visualTilt) ? null : new Quaternionf(visualTilt);
        visualTilt.identity();
        prevVisualTilt.identity();
        return tilt;
    }

    /**
     * Apply a camera-only gravity effect for VS ships.
     * This rotates the camera to match the ship orientation but does NOT affect physics.
     * The player's movement, collisions, and hitbox remain normal (world-aligned).
     *
     * @param gravityDirection The arbitrary gravity direction for camera rotation
     * @param rotationParameters Optional rotation animation parameters
     * @param priority The priority of this effect
     */
    public void applyCameraOnlyGravityEffect(
        @NotNull GravityDirection gravityDirection,
        @Nullable RotationParameters rotationParameters,
        double priority
    ) {
        GravityDirection limitedGravityDirection = anchorToShip(applyAngleLimiting(gravityDirection), false);

        if (isFiringUpdateEvent) {
            if (priority > currentEffectPriority) {
                currentEffectPriority = priority;
                // DOWN keeps the hitbox untransformed; the arbitrary direction only drives the camera
                currGravityDirection = Direction.DOWN;
                arbitraryGravityDirection = limitedGravityDirection;
                isCameraOnlyGravity = true;

                if (rotationParameters != null) {
                    currentRotationParameters = rotationParameters;
                }
            }
        }
        else {
            if (delayApplyDirEffect == null || priority > delayApplyDirEffect.priority()) {
                delayApplyDirEffect = new GravityDirEffect(
                    Direction.DOWN, rotationParameters, priority, limitedGravityDirection, true
                );
            }
        }
    }

    /**
     * Apply a gravity direction effect (any angle), with angle limiting.
     * This affects physics; use applyCameraOnlyGravityEffect for camera-only ship gravity.
     */
    public void applyGravityDirectionEffect(
        @NotNull GravityDirection gravityDirection,
        @Nullable RotationParameters rotationParameters,
        double priority
    ) {
        applyGravityDirectionEffect(gravityDirection, rotationParameters, priority, true);
    }

    private void applyGravityDirectionEffect(
        @NotNull GravityDirection gravityDirection,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean limitAngle
    ) {
        // Ship-relative directions are anchored at issue time, when the ship transform matches the
        // one the block entity used. Delayed effects store the anchored, limited direction.
        GravityDirection limitedGravityDirection = anchorToShip(
            limitAngle ? applyAngleLimiting(gravityDirection) : gravityDirection, false);

        if (isFiringUpdateEvent) {
            if (priority > currentEffectPriority) {
                currentEffectPriority = priority;
                currGravityDirection = limitedGravityDirection.getNearestDirection();
                arbitraryGravityDirection = limitedGravityDirection;

                if (rotationParameters != null) {
                    currentRotationParameters = rotationParameters;
                }
            }
        }
        else {
            // When not firing event, store it on delayApplyEffect.
            // The effect could come from another entity ticking,
            // but there is no guarantee for ticking order between entities.
            // (the ticking order does not change according to EntityTickList)
            if (delayApplyDirEffect == null || priority > delayApplyDirEffect.priority()) {
                delayApplyDirEffect = new GravityDirEffect(
                    limitedGravityDirection.getNearestDirection(), rotationParameters, priority, limitedGravityDirection
                );
            }
        }
    }

    /** Anchor a ship-relative direction to the entity's ship. Cardinal, anchored and off-ship directions are returned as is. */
    private GravityDirection anchorToShip(GravityDirection direction, boolean prevTick) {
        if (direction.isCardinal() || direction.isShipAnchored() || !VS2Integration.isVS2Loaded()) {
            return direction;
        }
        GravityDirection anchored =
                net.memezforbeanz.gravityapi.compat.vs2.VS2Helper.anchorToEntityShip(entity, direction, prevTick);
        return anchored != null ? anchored : direction;
    }

    /** Limit how far gravity turns per change: larger changes are interpolated by the configured maximum. */
    private GravityDirection applyAngleLimiting(GravityDirection targetDirection) {
        if (!GravityConfig.smoothArbitraryGravity.get()) {
            return targetDirection;
        }

        GravityDirection currentDirection = getCurrentGravityDirection();
        if (currentDirection == null || currentDirection.equals(GravityDirection.DOWN) && targetDirection.equals(GravityDirection.DOWN)) {
            return targetDirection;
        }

        // Cardinal-to-cardinal changes snap (the animation smooths the camera); 180° flips must
        // not be blocked
        if (currentDirection.isCardinal() && targetDirection.isCardinal()) {
            return targetDirection;
        }

        Vec3 currentVec = currentDirection.getVector().normalize();
        Vec3 targetVec = targetDirection.getVector().normalize();

        double dot = currentVec.dot(targetVec);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        double angleRadians = Math.acos(dot);
        double angleDegrees = Math.toDegrees(angleRadians);

        double maxAngle = GravityConfig.maxGravityChangeAngle.get();

        if (angleDegrees <= maxAngle) {
            return targetDirection;
        }

        // Never swallow a large change outright: step toward it
        double t = maxAngle / angleDegrees;
        Vec3 interpolated = slerp(currentVec, targetVec, t);

        return GravityDirection.fromVector(interpolated);
    }

    /**
     * Spherical linear interpolation between two unit vectors
     */
    private Vec3 slerp(Vec3 v1, Vec3 v2, double t) {
        double dot = v1.dot(v2);
        dot = Math.max(-1.0, Math.min(1.0, dot));

        double theta = Math.acos(dot);
        if (theta < 0.001) {
            return v1.scale(1 - t).add(v2.scale(t)).normalize();
        }

        if (theta > Math.PI - 0.001) {
            // Antiparallel: rotate about any perpendicular axis
            Vec3 axis = Math.abs(v1.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            Vec3 perp = v1.cross(axis).normalize();
            double angle = t * theta;
            return v1.scale(Math.cos(angle)).add(perp.scale(Math.sin(angle))).normalize();
        }

        double sinTheta = Math.sin(theta);
        double a = Math.sin((1 - t) * theta) / sinTheta;
        double b = Math.sin(t * theta) / sinTheta;

        return v1.scale(a).add(v2.scale(b)).normalize();
    }

    /**
     * Override this entity's gravity strength (1.0 = overworld gravity) for the next tick,
     * replacing base strength, the config multiplier and any planet mod's gravity. Call
     * every tick while it should hold; the highest-priority call per tick wins.
     */
    public void applyGravityStrengthOverride(double strength, double priority) {
        if (priority > pendingStrengthOverridePriority) {
            pendingStrengthOverride = Math.max(strength, 0.0);
            pendingStrengthOverridePriority = priority;
        }
    }

    /** Whether a strength override currently holds (planet mods must not add their gravity). */
    public boolean isStrengthOverridden() {
        return !Double.isNaN(strengthOverride);
    }

    /** Resolves this tick's pending override (or the lingering one) into the strength. */
    private void resolveStrengthOverride() {
        if (!Double.isNaN(pendingStrengthOverride)) {
            strengthOverride = pendingStrengthOverride;
            ticksSinceStrengthOverride = 0;
        } else if (ticksSinceStrengthOverride < GravityConfig.effectLingerTicks.get()) {
            ticksSinceStrengthOverride++;
        } else {
            strengthOverride = Double.NaN;
        }
        pendingStrengthOverride = Double.NaN;
        pendingStrengthOverridePriority = Double.NEGATIVE_INFINITY;
        if (!Double.isNaN(strengthOverride)) {
            currGravityStrength = strengthOverride;
        }
    }

    public void applyGravityStrengthEffect(
        double strengthMultiplier
    ) {
        if (isFiringUpdateEvent) {
            currGravityStrength *= strengthMultiplier;
        }
        else {
            delayApplyStrengthEffect *= strengthMultiplier;
        }
    }
    
    public void applyGravityDirectionChange(
        Direction oldGravity, Direction newGravity,
        RotationParameters rotationParameters, boolean isInitialization
    ) {
        if (!canChangeGravity()) {
            return;
        }
        
        // Arbitrary (ship) gravity keeps the hitbox unchanged
        if (!isArbitraryGravity()) {
            entity.setBoundingBox(((EntityAccessor) entity).gc_makeBoundingBox());
        }

        // A weird thing is that,
        // using `entity.setPos(entity.position())` to a painting on client side
        // make the painting move wrongly, because Painting overrides `trackingPosition()`.
        // No entity other than Painting overrides that method.
        // It seems to be legacy code from early versions of Minecraft.
        
        if (isInitialization) {
            return;
        }

        entity.fallDistance = 0;
        lastGravityChangeGameTime = entity.level().getGameTime();

        long timeMs = entity.level().getGameTime() * 50;
        
        // A mid-air flip out of a field free fall pivots on the hitbox centre
        Vec3 relativeRotationCenter = airborneFlip
            ? new Vec3(0, entity.getDimensions(entity.getPose()).height / 2, 0)
            : getLocalRotationCenter(entity, oldGravity, newGravity, rotationParameters);
        Vec3 oldPos = entity.position();
        Vec3 oldLastTickPos = new Vec3(entity.xOld, entity.yOld, entity.zOld);
        Vec3 rotationCenter = oldPos.add(RotationUtil.vecPlayerToWorld(relativeRotationCenter, oldGravity));
        Vec3 newPos = rotationCenter.subtract(RotationUtil.vecPlayerToWorld(relativeRotationCenter, newGravity));
        Vec3 posTranslation = newPos.subtract(oldPos);
        Vec3 newLastTickPos = oldLastTickPos.add(posTranslation);
        
        entity.setPos(newPos);
        entity.xo = newLastTickPos.x;
        entity.yo = newLastTickPos.y;
        entity.zo = newLastTickPos.z;
        entity.xOld = newLastTickPos.x;
        entity.yOld = newLastTickPos.y;
        entity.zOld = newLastTickPos.z;
        
        // In mid-air, back out along the new up
        adjustEntityPosition(airborneFlip ? newGravity : oldGravity, newGravity, entity.getBoundingBox());

        if (!entity.level().isClientSide()) {
            // The client folds the tilt into its flip animation; the server just drops it
            consumeVisualTilt();
        }
        if (entity.level().isClientSide()) {
            Validate.notNull(animation, "gravity animation is null");
            
            int rotationTimeMS = rotationParameters.rotationTimeMS();
            
            animation.startRotationAnimation(
                newGravity, oldGravity,
                rotationTimeMS,
                entity, timeMs, rotationParameters.rotateView(),
                relativeRotationCenter
            );
        }
        
        if (airborneFlip) {
            // Landing out of a free fall keeps world momentum (the position delta is zero here for the local player)
            entity.setDeltaMovement(reframeKeepingWorldMomentum(entity.getDeltaMovement(), oldGravity, newGravity));
            return;
        }
        Vec3 realWorldVelocity = getRealWorldVelocity(entity, oldGravity);
        if (rotationParameters.rotateVelocity()) {
            // Rotate velocity with gravity, this will cause things to appear to take a sharp turn
            Vector3f worldSpaceVec = realWorldVelocity.toVector3f();
            worldSpaceVec.rotate(RotationUtil.getRotationBetween(oldGravity, newGravity));
            entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(new Vec3(worldSpaceVec), newGravity));
        }
        else {
            // Velocity will be conserved relative to the world, will result in more natural motion
            entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(realWorldVelocity, newGravity));
        }
    }
    
    // getVelocity() does not return the actual velocity. It returns the velocity plus acceleration.
    // Even if the entity is standing still, getVelocity() will still give a downwards vector.
    // The real velocity is this tick position subtract last tick position
    private static Vec3 getRealWorldVelocity(Entity entity, Direction prevGravityDirection) {
        if (entity.isControlledByLocalInstance()) {
            return new Vec3(
                entity.getX() - entity.xo,
                entity.getY() - entity.yo,
                entity.getZ() - entity.zo
            );
        }
        
        return RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), prevGravityDirection);
    }
    
    @NotNull
    private static Vec3 getLocalRotationCenter(
        Entity entity,
        Direction oldGravity, Direction newGravity, RotationParameters rotationParameters
    ) {
        if (entity instanceof EndCrystal) {
            //In the middle of the block below
            return new Vec3(0, -0.5, 0);
        }
        
        EntityDimensions dimensions = entity.getDimensions(entity.getPose());
        if (newGravity.getOpposite() == oldGravity) {
            // In the center of the hit-box
            return new Vec3(0, dimensions.height / 2, 0);
        }
        else {
            return Vec3.ZERO;
        }
    }
    
    /** A player-frame velocity re-expressed in the new frame, world direction kept, using the exact ship-relative frames. */
    private Vec3 reframeKeepingWorldMomentum(Vec3 velocity, Direction oldGravity, Direction newGravity) {
        GravityDirection from = prevArbitraryGravityDirection != null
                && prevArbitraryGravityDirection.getNearestDirection() == oldGravity
            ? prevArbitraryGravityDirection : GravityDirection.fromDirection(oldGravity);
        GravityDirection to = arbitraryGravityDirection != null
                && arbitraryGravityDirection.getNearestDirection() == newGravity
            ? arbitraryGravityDirection : GravityDirection.fromDirection(newGravity);
        return to.vecWorldToPlayer(from.vecPlayerToWorld(velocity));
    }

    // Adjust position to avoid suffocation in blocks when changing gravity
    private void adjustEntityPosition(Direction oldGravity, Direction newGravity, AABB entityBoundingBox) {
        if (!GravityConfig.adjustPositionAfterChangingGravity.get()) {
            return;
        }
        
        if (entity instanceof AreaEffectCloud || entity instanceof AbstractArrow || entity instanceof EndCrystal
                || entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            // Items and orbs don't suffocate; nudging them makes them pop around at field edges
            return;
        }
        
        // for example, if gravity changed from down to north, move up
        // if gravity changed from down to up, also move up
        Direction movingDirection = oldGravity.getOpposite();
        
        Iterable<VoxelShape> collisions = entity.level().getCollisions(
            entity,
            entityBoundingBox.inflate(-0.01) // shrink to avoid floating point error
        );
        AABB totalCollisionBox = null;
        for (VoxelShape collision : collisions) {
            if (!collision.isEmpty()) {
                AABB boundingBox = collision.bounds();
                if (totalCollisionBox == null) {
                    totalCollisionBox = boundingBox;
                }
                else {
                    totalCollisionBox = totalCollisionBox.minmax(boundingBox);
                }
            }
        }
        
        if (totalCollisionBox != null) {
            Vec3 positionAdjustmentOffset = getPositionAdjustmentOffset(
                entityBoundingBox, totalCollisionBox, movingDirection
            );
            entity.setPos(entity.position().add(positionAdjustmentOffset));
        }
    }
    
    private static Vec3 getPositionAdjustmentOffset(
        AABB entityBoundingBox, AABB nearbyCollisionUnion, Direction movingDirection
    ) {
        Direction.Axis axis = movingDirection.getAxis();
        double offset = 0;
        if (movingDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            double pushing = nearbyCollisionUnion.max(axis);
            double pushed = entityBoundingBox.min(axis);
            if (pushing > pushed) {
                offset = pushing - pushed;
            }
        }
        else {
            double pushing = nearbyCollisionUnion.min(axis);
            double pushed = entityBoundingBox.max(axis);
            if (pushing < pushed) {
                offset = pushed - pushing;
            }
        }
        
        return new Vec3(movingDirection.step()).scale(offset);
    }
    
    public double getBaseGravityStrength() {
        return baseGravityStrength;
    }
    
    public void setBaseGravityStrength(double strength) {
        if (!canChangeGravity()) {
            return;
        }
        
        baseGravityStrength = strength;
        needsSync = true;
    }
    
    public Direction getCurrGravityDirection() {
        return currGravityDirection;
    }
    
    /** The arbitrary gravity direction, or null for cardinal gravity. */
    @Nullable
    public GravityDirection getArbitraryGravityDirection() {
        return arbitraryGravityDirection;
    }

    /** The current gravity, including arbitrary (ship) directions. */
    /** Last ship-aligned direction seen inside a generator field (see {@link #getCurrentGravityDirection()}). */
    private GravityDirection lastFieldArbitrary = null;

    public GravityDirection getCurrentGravityDirection() {
        if (arbitraryGravityDirection != null) {
            if (inGravityGeneratorField) {
                lastFieldArbitrary = arbitraryGravityDirection;
            }
            return arbitraryGravityDirection;
        }
        // In a generator field arbitraryGravityDirection can be null for a moment (sync races); a
        // single world-DOWN move step on a tilted ship slides the entity off the deck, so keep the
        // last ship-aligned direction while in the field
        if (inGravityGeneratorField && lastFieldArbitrary != null) {
            return lastFieldArbitrary;
        }
        lastFieldArbitrary = null;
        return GravityDirection.fromDirection(currGravityDirection);
    }

    public double getCurrGravityStrength() {
        return currGravityStrength;
    }

    /** Diagnostic accessor: whether the active gravity effect is the camera-only (VS field) variant. */
    public boolean isCameraOnlyGravity() {
        return isCameraOnlyGravity;
    }
    
    private boolean canChangeGravity() {
        return EntityTags.canChangeGravity(entity);
    }
    
    public Direction getPrevGravityDirection() {
        return prevGravityDirection;
    }
    
    public Direction getBaseGravityDirection() {
        return baseGravityDirection;
    }
    
    public void setBaseGravityDirection(Direction gravityDirection) {
        if (!canChangeGravity()) {
            return;
        }
        
        if (baseGravityDirection != gravityDirection) {
            baseGravityDirection = gravityDirection;
            needsSync = true;
            
            // update gravity immediately
            // avoid having wrong info from getGravityDirection()
            updateGravityStatus(false); // will this cause issue?
        }
    }
    
    public void reset() {
        baseGravityDirection = Direction.DOWN;
        baseGravityStrength = 1.0;
        needsSync = true;
    }
    
    public RotationAnimation getRotationAnimation() {
        return animation;
    }
    
    @Override
    public void applyGravityChange() {
        if (currentRotationParameters == null) {
            currentRotationParameters = RotationParameters.getDefault();
        }
        
        boolean cardinalChanged = prevGravityDirection != currGravityDirection;

        boolean arbitraryChanged = hasArbitraryGravityChanged();

        boolean leavingArbitraryGravity = (prevArbitraryGravityDirection != null && arbitraryGravityDirection == null);

        // Same direction in the same ship frame: only the ship moved, and the frame moved with it.
        // Nothing to transition.
        boolean shipMotionOnly = arbitraryGravityDirection != null
                && arbitraryGravityDirection.isSameShipFrameAs(prevArbitraryGravityDirection);

        // Both directions anchored to a ship: the ship-frame flip, never the cardinal or generic paths
        boolean shipFrameChange = !shipMotionOnly && arbitraryGravityDirection != null
                && prevArbitraryGravityDirection != null
                && arbitraryGravityDirection.isShipAnchored() && prevArbitraryGravityDirection.isShipAnchored();

        if (shipMotionOnly) {
            prevGravityDirection = currGravityDirection;
            entity.fallDistance = 0;
        } else if (shipFrameChange) {
            prevGravityDirection = currGravityDirection;
            applyShipFrameChange(prevArbitraryGravityDirection, arbitraryGravityDirection, currentRotationParameters);
        } else if (cardinalChanged) {
            // Cardinal direction changed - use the standard animation system
            applyGravityDirectionChange(
                prevGravityDirection, currGravityDirection,
                currentRotationParameters, false
            );
            prevGravityDirection = currGravityDirection;
            // Non-cardinal gravity: retarget the cardinal animation to the true direction (unless they
            // coincide, where it would only shorten the animation)
            if (entity.level().isClientSide() && animation != null
                    && arbitraryGravityDirection != null && !arbitraryGravityDirection.isCardinal()
                    && arbitraryGravityDirection.getVector().distanceToSqr(
                        Vec3.atLowerCornerOf(currGravityDirection.getNormal())) > 1.0e-6) {
                long timeMs = entity.level().getGameTime() * 50;
                GravityDirection from = prevArbitraryGravityDirection != null
                    ? prevArbitraryGravityDirection
                    : GravityDirection.fromDirection(currGravityDirection);
                animation.startArbitraryRotationAnimation(
                    arbitraryGravityDirection, from,
                    currentRotationParameters.rotationTimeMS(),
                    entity, timeMs
                );
            }
        } else if (leavingArbitraryGravity) {
            // Back from arbitrary gravity to cardinal
            Direction targetDir = currGravityDirection;
            Direction sourceDir = prevArbitraryGravityDirection.getNearestDirection();
            if (sourceDir != targetDir) {
                applyGravityDirectionChange(
                    sourceDir, targetDir,
                    currentRotationParameters, false
                );
            }
            // The ship hitbox was never transformed
            entity.fallDistance = 0;
        } else if (arbitraryChanged && arbitraryGravityDirection != null) {
            // Only the arbitrary direction changed (within the same cardinal)
            applyArbitraryGravityChange();
        }
        
        prevArbitraryGravityDirection = arbitraryGravityDirection;

        if (Math.abs(currGravityStrength - prevGravityStrength) > 0.0001) {
            prevGravityStrength = currGravityStrength;
        }
    }
    
    private boolean hasArbitraryGravityChanged() {
        if (arbitraryGravityDirection == null && prevArbitraryGravityDirection == null) {
            return false;
        }
        if (arbitraryGravityDirection == null || prevArbitraryGravityDirection == null) {
            return true;
        }
        Vec3 curr = arbitraryGravityDirection.getVector();
        Vec3 prev = prevArbitraryGravityDirection.getVector();
        return curr.distanceToSqr(prev) > 0.0001;
    }

    /** Smooth arbitrary gravity change (a VS ship rotating within the same cardinal). */
    private void applyArbitraryGravityChange() {
        if (!canChangeGravity() || arbitraryGravityDirection == null) {
            return;
        }

        // No bounding box update: on VS ships the hitbox stays world-aligned

        entity.fallDistance = 0;

        if (prevArbitraryGravityDirection != null && !prevArbitraryGravityDirection.equals(arbitraryGravityDirection)) {
            Direction prevNearest = prevArbitraryGravityDirection.getNearestDirection();
            Direction currNearest = arbitraryGravityDirection.getNearestDirection();

            // Re-frame velocity only when the nearest cardinal changed
            if (prevNearest != currNearest) {
                Vec3 currentVelocity = entity.getDeltaMovement();

                Vec3 worldVelocity = RotationUtil.vecPlayerToWorld(currentVelocity, prevNearest);
                Vec3 newFrameVelocity = RotationUtil.vecWorldToPlayer(worldVelocity, currNearest);

                entity.setDeltaMovement(newFrameVelocity);
            }
        }

        if (entity.level().isClientSide() && animation != null && prevArbitraryGravityDirection != null) {
            long timeMs = entity.level().getGameTime() * 50;
            animation.startArbitraryRotationAnimation(
                arbitraryGravityDirection,
                prevArbitraryGravityDirection,
                currentRotationParameters != null ? currentRotationParameters.rotationTimeMS() : 100,
                entity,
                timeMs
            );
        }
    }

    /** Below this cosine (about 30°) a ship-frame change is a face change, not drift. */
    private static final double SHIP_FACE_CHANGE_DOT = 0.87;

    /**
     * A change between two ship-anchored directions, whose frames are locked to the ship (see
     * {@link GravityDirection#shipAnchored}): upstream's flip, done once in the ship's frame.
     * Pivot about the feet (the hitbox centre for 180° or a free-fall landing), turn velocity and
     * view with gravity (a free-fall landing keeps world momentum), one animation.
     * Small changes only re-express velocity and animate.
     */
    private void applyShipFrameChange(GravityDirection from, GravityDirection to, RotationParameters parameters) {
        if (!canChangeGravity()) {
            return;
        }
        entity.fallDistance = 0;
        double dot = from.getVector().dot(to.getVector());
        long timeMs = entity.level().getGameTime() * 50;
        if (dot >= SHIP_FACE_CHANGE_DOT) {
            entity.setDeltaMovement(to.vecWorldToPlayer(from.vecPlayerToWorld(entity.getDeltaMovement())));
            if (entity.level().isClientSide() && animation != null) {
                animation.startArbitraryRotationAnimation(to, from, parameters.rotationTimeMS(), entity, timeMs);
            }
            return;
        }
        lastGravityChangeGameTime = entity.level().getGameTime();

        boolean opposite = dot < -0.999;
        Vec3 relativeRotationCenter = airborneFlip || opposite
            ? new Vec3(0, entity.getDimensions(entity.getPose()).height / 2, 0)
            : Vec3.ZERO;
        if (!relativeRotationCenter.equals(Vec3.ZERO)) {
            Vec3 rotationCenter = entity.position().add(from.vecPlayerToWorld(relativeRotationCenter));
            Vec3 posTranslation = rotationCenter.subtract(to.vecPlayerToWorld(relativeRotationCenter))
                .subtract(entity.position());
            entity.setPos(entity.position().add(posTranslation));
            entity.xo += posTranslation.x;
            entity.yo += posTranslation.y;
            entity.zo += posTranslation.z;
            entity.xOld += posTranslation.x;
            entity.yOld += posTranslation.y;
            entity.zOld += posTranslation.z;
        }

        // Old down onto new; 180°: about the entity's left-right axis
        Quaternionf turn = opposite
            ? new Quaternionf().fromAxisAngleRad(from.vecPlayerToWorld(new Vec3(1, 0, 0)).toVector3f(), (float) Math.PI)
            : QuaternionUtil.getRotationBetween(from.getVector(), to.getVector());

        // deltaMovement is ship-relative (the dragger carries the ship's motion), in the gravity frame
        Vec3 worldVelocity = from.vecPlayerToWorld(entity.getDeltaMovement());
        if (!airborneFlip && parameters.rotateVelocity()) {
            worldVelocity = QuaternionUtil.rotate(worldVelocity, turn);
        }
        entity.setDeltaMovement(to.vecWorldToPlayer(worldVelocity));

        Quaternionf oldView = QuaternionUtil.getViewRotation(entity.getXRot(), entity.getYRot());
        Vec3 look = from.vecPlayerToWorld(RotationUtil.rotToVec(entity.getYRot(), entity.getXRot()));
        if (parameters.rotateView()) {
            look = opposite ? look.scale(-1) : QuaternionUtil.rotate(look, turn);
        }
        Vec2 yawPitch = RotationUtil.vecToRot(to.vecWorldToPlayer(look));
        float deltaYaw = Mth.wrapDegrees(yawPitch.x - entity.getYRot());
        float deltaPitch = yawPitch.y - entity.getXRot();
        entity.setYRot(entity.getYRot() + deltaYaw);
        entity.setXRot(entity.getXRot() + deltaPitch);
        entity.yRotO += deltaYaw;
        entity.xRotO += deltaPitch;
        if (entity instanceof LivingEntity living) {
            living.yBodyRot += deltaYaw;
            living.yBodyRotO += deltaYaw;
            living.yHeadRot += deltaYaw;
            living.yHeadRotO += deltaYaw;
        }

        if (!entity.level().isClientSide()) {
            // The client folds the tilt into its flip animation; the server just drops it
            consumeVisualTilt();
        } else if (animation != null) {
            animation.startShipFaceAnimation(to, from, oldView, parameters.rotationTimeMS(), entity, timeMs,
                relativeRotationCenter);
        }
    }

    /**
     * Not needed in normal cases.
     * Only used in {@link GravityChangerAPI#instantlySetClientBaseGravityDirection(Entity, Direction)}
     * Used by ImmPtl.
     */
    public void forceApplyGravityChange() {
        prevGravityDirection = currGravityDirection;
        prevGravityStrength = currGravityStrength;
        prevArbitraryGravityDirection = arbitraryGravityDirection;
        // Rebuild the hitbox for the new frame, without the flip
        applyGravityDirectionChange(currGravityDirection, currGravityDirection, currentRotationParameters, true);
    }

    /**
     * Resolve the effects applied so far this tick right away and take the result as the
     * current frame, with no flip (no pivot, push-out or animation): for an entity that is just
     * spawning, so it appears already standing in its field. Server only.
     */
    public void settleNow() {
        if (entity.level().isClientSide() || !canChangeGravity()) {
            return;
        }
        updateGravityStatus(false);
        reanchorArbitraryToShipRotation();
        forceApplyGravityChange();
        consumeVisualTilt();
        needsSync = true;
    }

    /** Send the full gravity state to one player, applied there without animation (they just started tracking this entity). */
    public void sendStateTo(net.minecraft.server.level.ServerPlayer player) {
        if (entity.level().isClientSide()) {
            return;
        }
        GravityNetwork.sendNonLocal(
            new UpdateGravityCapabilityPacket(true, entity.getUUID(), baseGravityDirection, currGravityDirection,
                baseGravityStrength, currGravityStrength, arbitraryGravityDirection, isCameraOnlyGravity), player);
    }
    
    private static record GravityDirEffect(
        @NotNull Direction direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        @Nullable GravityDirection arbitraryDirection,
        boolean isCameraOnly
    ) {
        public GravityDirEffect(Direction direction, RotationParameters rotationParameters, double priority) {
            this(direction, rotationParameters, priority, null, false);
        }

        public GravityDirEffect(Direction direction, RotationParameters rotationParameters, double priority, GravityDirection arbitraryDirection) {
            this(direction, rotationParameters, priority, arbitraryDirection, false);
        }
    }
}
