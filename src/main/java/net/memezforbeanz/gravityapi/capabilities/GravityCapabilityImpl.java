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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.PacketDistributor;

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
    
    // the base gravity direction
    Direction baseGravityDirection = Direction.DOWN;
    
    // the base gravity strength
    double baseGravityStrength = 1.0;
    
    @Nullable RotationParameters currentRotationParameters = RotationParameters.getDefault();
    
    // Only used on client, not synchronized.
    @Nullable
    public RotationAnimation animation;
    
    public Entity entity;
    
    private Direction currGravityDirection = Direction.DOWN;
    private double currGravityStrength = 1.0;
    private double currentEffectPriority = Double.MIN_VALUE;
    
    // Arbitrary gravity direction (for VS ship angles)
    // This allows gravity in any direction, not just the 6 cardinal directions
    @Nullable
    private GravityDirection arbitraryGravityDirection = null;

    @Nullable
    private GravityDirection prevArbitraryGravityDirection = null;

    // Flag to track if gravity is from a camera-only source (VS ship gravity generator)
    // When true, hitbox should NOT be transformed regardless of whether direction is cardinal
    private boolean isCameraOnlyGravity = false;

    // Cached flag set during tick to indicate entity is in a gravity generator field
    // This is checked during hitbox calculations as a reliable fallback
    private boolean inGravityGeneratorField = false;

    /**
     * Check if the current gravity is from a VS ship/camera-only source.
     * When true, hitbox should NOT be transformed (only camera rotates).
     * This returns true for VS ship gravity even if the direction happens to be cardinal.
     */
    public boolean isArbitraryGravity() {
        // Return true if:
        // 1. Entity is in a gravity generator field (most reliable check), OR
        // 2. This is camera-only gravity (VS ship gravity generator), OR
        // 3. We have a non-cardinal arbitrary direction, OR
        // 4. Entity is currently on a VS ship (fallback check for sync timing issues)
        if (inGravityGeneratorField) {
            return true;
        }
        if (isCameraOnlyGravity) {
            return true;
        }
        if (arbitraryGravityDirection != null && !arbitraryGravityDirection.isCardinal()) {
            return true;
        }
        // Fallback: if entity is on a VS ship, treat as arbitrary to prevent hitbox issues
        // This catches cases where the gravity effect hasn't synced yet
        if (entity != null && VS2Integration.isVS2Loaded() && VS2Integration.isOnShip(entity)) {
            return true;
        }
        return false;
    }

    /**
     * STRICT check: does this entity actually have ship-relative gravity physics active?
     *
     * <p>Unlike {@link #isArbitraryGravity()}, this does NOT include the "entity is merely near a
     * VS ship" proximity fallback. The loose check is right for hitbox/camera decisions (where a
     * false positive is harmless), but the collision pipeline (ship-local collision, ground-stick,
     * depenetration, skipping VS2's own ship collision) must only engage when gravity is genuinely
     * ship-relative - otherwise a normal-gravity player bumping into a ship gets VS2's collision
     * disabled and our ship-up depenetration shoves them away from the hull (vibration/forcefield).
     */
    public boolean isShipGravityPhysics() {
        // A *cardinal* arbitrary direction (e.g. a planet-style field generator pulling toward a
        // block in the normal world) must NOT engage the ship pipeline: those entities get the
        // classic cardinal gravity physics (transformed hitbox, the cardinal collide mixins).
        // Genuinely ship-relative gravity is camera-only, in a registry generator field, or
        // non-cardinal (a ship-rotated vector).
        return inGravityGeneratorField || isCameraOnlyGravity
                || (arbitraryGravityDirection != null && !arbitraryGravityDirection.isCardinal());
    }

    /**
     * Check if entity is currently in a gravity generator field
     */
    public boolean isInGravityGeneratorField() {
        return inGravityGeneratorField;
    }

    /**
     * Set whether entity is in a gravity generator field (called during tick)
     */
    public void setInGravityGeneratorField(boolean inField) {
        this.inGravityGeneratorField = inField;
    }

    private boolean isFiringUpdateEvent = false;

    private @Nullable GravityCapabilityImpl.GravityDirEffect delayApplyDirEffect = null;
    private double delayApplyStrengthEffect = 1.0;

    // Effect linger: when a per-tick gravity effect (generator field, plate, anchor) misses a
    // tick — boundary jitter at a small field's edge, tick-order races, sync gaps — hold the
    // last effect for a few ticks instead of snapping back to base gravity. Without this,
    // every dropout is a full cardinal flip (hitbox rebuild, setPos, velocity transform) that
    // reverses one tick later: items twitch at field edges and player movement desyncs.
    private int ticksSinceLastEffect = 1000;
    private Direction lingerDirection = Direction.DOWN;
    private @Nullable GravityDirection lingerArbitrary = null;
    private boolean lingerCameraOnly = false;
    private @Nullable RotationParameters lingerRotationParameters = null;
    
    // only used on server side
    public boolean needsSync = false;
    
    public boolean noAnimation = false;
    
	@Override
	public void setEntity(Entity entity) 
	{
		this.entity = entity;
        if (entity.level.isClientSide()) {
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
        
        // Tick ship gravity if VS2 is loaded
        tickShipGravity();

        updateGravityStatus(true);

        // AFTER the update (so it also corrects effects that were delay-applied inside it, whose
        // vectors were computed from last tick's ship transform), re-anchor ship-relative gravity
        // to the ship's current rotation - otherwise "down" lags a spinning ship by at least one
        // tick (more under effect linger) and the collision frame diverges from the geometry.
        reanchorArbitraryToShipRotation();

        applyGravityChange();
        
        if (!entity.level.isClientSide()) {
            if (needsSync) {
                sendSyncPacketToOtherPlayers();
            }
        }
    }

    /**
     * Rotate the stored ship-relative (arbitrary non-cardinal) gravity vectors by the rotation the
     * supporting ship underwent this tick. Stored directions are world-space snapshots: the
     * applying block entity computed them from the ship transform of the tick it ran in, and the
     * effect linger can hold them for several more ticks. On a rotating ship every such snapshot
     * points where "down" USED to be - the entity then collides in a frame that no longer matches
     * the ship geometry, which is the clip-through/lagging-gravity bug on spinning ships.
     */
    private void reanchorArbitraryToShipRotation() {
        if (!VS2Integration.isVS2Loaded()) {
            return;
        }
        // Camera-only (registry) effects are computed live inside this tick's update from the
        // ship's CURRENT transform - only rotate those when the linger is holding a stale copy.
        // Per-entity effects (bridge fields, plating) are delay-applied from last tick's block
        // tick and are always one transform behind.
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
            // The linger slot usually aliases the live direction; rotate it exactly once.
            lingerArbitrary = (lingerArbitrary == arbitraryGravityDirection && rotatedArbitrary != null)
                    ? rotatedArbitrary
                    : rotatedBy(lingerArbitrary, delta);
        }
        // currGravityDirection is deliberately left alone: the effect-application path manages it
        // (camera-only ship gravity keeps it DOWN so the hitbox stays untransformed), and the next
        // fresh effect updates it through the normal angle-limiting/sync path if the nearest
        // cardinal genuinely changes.
        if (rotatedArbitrary != null) {
            arbitraryGravityDirection = rotatedArbitrary;
        }
    }

    private static GravityDirection rotatedBy(GravityDirection direction, org.joml.Quaterniond rotation) {
        net.minecraft.world.phys.Vec3 v = direction.getVector();
        org.joml.Vector3d rotated = rotation.transform(new org.joml.Vector3d(v.x, v.y, v.z));
        return GravityDirection.shipRelative(new net.minecraft.world.phys.Vec3(rotated.x, rotated.y, rotated.z));
    }

    /**
     * Tick the ship gravity capability if VS2 is present
     */
    private void tickShipGravity() {
        if (!VS2Integration.isVS2Loaded()) {
            return;
        }

        entity.getCapability(ShipGravityCapabilities.SHIP_GRAVITY).ifPresent(shipGravity -> {
            shipGravity.tick();
        });
    }

    /**
     * A gravity plate's bid for this entity's gravity this tick (AmethystGravity-reattracted
     * model): plates never apply gravity directly; each nearby plate face adds a candidate,
     * and {@link #resolvePlatingCandidates()} picks one per tick with hysteresis. This is
     * what makes wall-walking stable - competing plates at a corner can't fight each other,
     * and the currently-held direction wins ties instead of flip-flopping.
     */
    public record PlatingCandidate(Vec3 worldDirection, boolean shipFrame, double volume, BlockPos source) {}

    public static final double PLATING_EFFECT_PRIORITY = 1000;

    private final List<PlatingCandidate> platingCandidates = new ArrayList<>();
    /** World-frame direction the plate resolution chose last tick (hysteresis anchor). */
    @Nullable
    private Vec3 stickyPlatingDirection = null;

    // Client-authoritative plating for players: the client's plate detection runs against
    // its own (authoritative) position, so the client reports its resolved choice to the
    // server instead of the server detecting independently 1-2 ticks behind - independent
    // detection made both sides flip at different times and fight over position.
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

    /**
     * Anticheat bound for a client plating report, and per-tick staleness check: the source
     * block must still be a plate and the player must plausibly be within its reach.
     */
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
        // Server side: players are excluded from server plate detection; inject the
        // verified client report so the server flips in step with the client
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
            // Hysteresis: if the direction we already hold is still among the best
            // candidates, keep it rather than switching to whichever plate iterated first
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
        applyGravityDirectionEffect(direction, params, PLATING_EFFECT_PRIORITY);
    }

    public void updateGravityStatus(boolean sendPacketIfNecessary) {
        // Always check if entity is in a gravity generator field (for hitbox calculations)
        // This needs to run even for entities that accept server sync.
        // Computed once here and reused below - the registry walk does a block-entity lookup and
        // (on ships) a transform per generator, so querying twice per entity per tick adds up.
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
            // Plate block entities still tick client-side and add candidates for these
            // entities; drop them so the list doesn't grow unbounded
            platingCandidates.clear();
            return;
        }
        
        Direction oldGravityDirection = currGravityDirection;
        double oldGravityStrength = currGravityStrength;
        
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            currGravityDirection = GravityChangerAPI.getGravityDirection(vehicle);
            currGravityStrength = GravityChangerAPI.getGravityStrength(vehicle);
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

                // Check for gravity generator fields (applies to all entities)
                GravityGeneratorRegistry.GravityFieldResult fieldResult = registryFieldResult;

                // Update the cached flag for hitbox calculations
                inGravityGeneratorField = (fieldResult != null);

                if (fieldResult != null) {
                    // Use camera-only gravity for VS ships
                    // This keeps currGravityDirection as DOWN so hitbox/physics stay normal
                    // Only the camera and arbitrary direction are affected
                    this.applyCameraOnlyGravityEffect(
                            fieldResult.gravityDirection(),
                            fieldResult.parameters(),
                            fieldResult.priority()
                    );
                }

                // Gravity plating: resolve this tick's plate candidates into (at most) one effect
                resolvePlatingCandidates();

                if (!(entity instanceof LivingEntity livingEntity)) {
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
                    // Check if this is a camera-only effect (from VS ship gravity generator)
                    if (delayApplyDirEffect.isCameraOnly()) {
                        // Camera-only effect - keep direction as DOWN, only set arbitrary
                        applyCameraOnlyGravityEffect(
                            delayApplyDirEffect.arbitraryDirection(),
                            delayApplyDirEffect.rotationParameters(),
                            delayApplyDirEffect.priority()
                        );
                    } else if (delayApplyDirEffect.arbitraryDirection() != null) {
                        // Full gravity effect with arbitrary direction
                        applyGravityDirectionEffect(
                            delayApplyDirEffect.arbitraryDirection(),
                            delayApplyDirEffect.rotationParameters(),
                            delayApplyDirEffect.priority()
                        );
                    } else {
                        // Use cardinal direction
                        applyGravityDirectionEffect(
                            delayApplyDirEffect.direction(),
                            delayApplyDirEffect.rotationParameters(), delayApplyDirEffect.priority()
                        );
                    }
                    delayApplyDirEffect = null;
                }
                currGravityStrength *= delayApplyStrengthEffect;
                delayApplyStrengthEffect = 1.0;
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
                // Clear arbitrary gravity direction when no effect is active
                arbitraryGravityDirection = null;
                // Reset camera-only flag
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
		if(!this.entity.level.isClientSide)
		{

			GravityNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> this.entity),
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
		// The local client player computes its current gravity every tick from local
		// effects (plates, fields) - it is the prediction authority for its own feel.
		// The server computes the same thing 1-2 ticks apart; letting its packet stomp
		// curr/arbitrary here caused a flip-fight around every transition (the "server
		// desync"). Only remote entities take the server's current state verbatim.
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
        // Delegate to the GravityDirection version using cardinal direction
        applyGravityDirectionEffect(GravityDirection.fromDirection(direction), rotationParameters, priority);
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
        // Apply angle limiting
        GravityDirection limitedGravityDirection = applyAngleLimiting(gravityDirection);

        if (isFiringUpdateEvent) {
            if (priority > currentEffectPriority) {
                currentEffectPriority = priority;
                // EXPLICITLY set currGravityDirection to DOWN - this ensures hitbox stays normal!
                // The arbitrary direction is only used for camera rotation
                currGravityDirection = Direction.DOWN;
                arbitraryGravityDirection = limitedGravityDirection;
                // Mark this as camera-only gravity so hitbox stays untransformed
                isCameraOnlyGravity = true;

                if (rotationParameters != null) {
                    currentRotationParameters = rotationParameters;
                }
            }
        }
        else {
            if (delayApplyDirEffect == null || priority > delayApplyDirEffect.priority()) {
                // Store with DOWN as the cardinal direction and mark as camera-only
                delayApplyDirEffect = new GravityDirEffect(
                    Direction.DOWN, rotationParameters, priority, limitedGravityDirection, true
                );
            }
        }
    }

    /**
     * Apply an arbitrary gravity direction effect (supports any angle, not just cardinal directions)
     * This DOES affect physics - use applyCameraOnlyGravityEffect for VS ships.
     * Includes angle limiting to prevent sudden large gravity changes
     */
    public void applyGravityDirectionEffect(
        @NotNull GravityDirection gravityDirection,
        @Nullable RotationParameters rotationParameters,
        double priority
    ) {
        // Apply angle limiting if we have a previous direction
        GravityDirection limitedGravityDirection = applyAngleLimiting(gravityDirection);

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

    /**
     * Apply angle limiting to prevent sudden large gravity direction changes.
     * If the angle between current and target gravity is too large, interpolate toward it.
     */
    private GravityDirection applyAngleLimiting(GravityDirection targetDirection) {
        if (!GravityConfig.smoothArbitraryGravity.get()) {
            return targetDirection;
        }

        // Get current direction to compare against
        GravityDirection currentDirection = getCurrentGravityDirection();
        if (currentDirection == null || currentDirection.equals(GravityDirection.DOWN) && targetDirection.equals(GravityDirection.DOWN)) {
            return targetDirection;
        }

        // Cardinal-to-cardinal changes snap like classic GravityChanger: the RotationAnimation
        // smooths the camera, and physics must flip in one tick. Interpolating here would emit
        // non-cardinal intermediates (untransformed hitbox, ship pipeline) for a plain
        // wall-to-wall transition, and the >90-degree guard below would swallow 180-degree flips
        // outright - a planet-style field could never pull an entity on its far side.
        if (currentDirection.isCardinal() && targetDirection.isCardinal()) {
            return targetDirection;
        }

        // Calculate angle between current and target
        Vec3 currentVec = currentDirection.getVector().normalize();
        Vec3 targetVec = targetDirection.getVector().normalize();

        double dot = currentVec.dot(targetVec);
        // Clamp to avoid NaN from acos
        dot = Math.max(-1.0, Math.min(1.0, dot));
        double angleRadians = Math.acos(dot);
        double angleDegrees = Math.toDegrees(angleRadians);

        double maxAngle = GravityConfig.maxGravityChangeAngle.get();

        // If angle is within limit, use target directly
        if (angleDegrees <= maxAngle) {
            return targetDirection;
        }

        // Interpolate toward target by maxAngle. Never swallow large changes outright:
        // with effect linger holding the previous direction between plate handoffs, a
        // >90-degree guard would permanently block big transitions (plating on the far
        // side of a rotating ship could never take over).
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
            // Vectors are nearly parallel, use linear interpolation
            return v1.scale(1 - t).add(v2.scale(t)).normalize();
        }

        if (theta > Math.PI - 0.001) {
            // Antiparallel: sin(theta) ~ 0, the slerp formula divides by zero. Rotate v1
            // around any perpendicular axis instead so 180-degree flips still progress.
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
        
        // Skip bounding box update for arbitrary gravity (VS ships)
        // The hitbox should stay unchanged when affected by gravity generators
        if (!isArbitraryGravity()) {
            // update bounding box only for non-arbitrary (cardinal) gravity
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
        
        Vec3 relativeRotationCenter = getLocalRotationCenter(
            entity, oldGravity, newGravity, rotationParameters
        );
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
        
        adjustEntityPosition(oldGravity, newGravity, entity.getBoundingBox());
        
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
    
    // Adjust position to avoid suffocation in blocks when changing gravity
    private void adjustEntityPosition(Direction oldGravity, Direction newGravity, AABB entityBoundingBox) {
        if (!GravityConfig.adjustPositionAfterChangingGravity.get()) {
            return;
        }
        
        if (entity instanceof AreaEffectCloud || entity instanceof AbstractArrow || entity instanceof EndCrystal
                || entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            // Items and orbs are small enough not to suffocate; nudging them on every flip
            // makes them visibly pop around at field boundaries
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
            if (entity instanceof Player) {
                //LOGGER.info("Adjusting player position {} {}", positionAdjustmentOffset, entity);
            }
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
    
    /**
     * Get the arbitrary gravity direction (supports any angle).
     * Returns null if gravity is a cardinal direction.
     */
    @Nullable
    public GravityDirection getArbitraryGravityDirection() {
        return arbitraryGravityDirection;
    }

    /**
     * Get the current gravity as a GravityDirection object (supports any angle).
     * This is the preferred method for getting gravity direction when supporting VS ships.
     */
    /**
     * Last arbitrary (ship-aligned) gravity direction seen while the entity was inside a gravity
     * generator field. Used to keep physics gravity stable - see {@link #getCurrentGravityDirection()}.
     */
    private GravityDirection lastFieldArbitrary = null;

    public GravityDirection getCurrentGravityDirection() {
        if (arbitraryGravityDirection != null) {
            // Remember the good value while we're genuinely in a field so we can ride out transient
            // nulls below.
            if (inGravityGeneratorField) {
                lastFieldArbitrary = arbitraryGravityDirection;
            }
            return arbitraryGravityDirection;
        }
        // Robustness: while standing in a gravity-generator field, arbitraryGravityDirection can be
        // transiently null between/within ticks (effect re-evaluation and client/server sync races).
        // If physics reads world-DOWN for even a single move sub-step, that world-down vector - run
        // through the ship-local collision on a tilted ship - leaves an uncancelled ship-horizontal
        // component and the entity slides off the deck. Keep returning the last known ship-aligned
        // direction for as long as we remain in the field so every move sub-step is consistent.
        if (inGravityGeneratorField && lastFieldArbitrary != null) {
            return lastFieldArbitrary;
        }
        // Truly out of any field - forget the cached direction and use the cardinal gravity.
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
    
    @OnlyIn(Dist.CLIENT)
    public RotationAnimation getRotationAnimation() {
        return animation;
    }
    
    @Override
    public void applyGravityChange() {
        if (currentRotationParameters == null) {
            currentRotationParameters = RotationParameters.getDefault();
        }
        
        // Check for cardinal direction changes (legacy behavior)
        boolean cardinalChanged = prevGravityDirection != currGravityDirection;

        // Check for arbitrary gravity changes (for smooth VS ship support)
        boolean arbitraryChanged = hasArbitraryGravityChanged();

        // Check if we're transitioning from arbitrary gravity back to normal
        boolean leavingArbitraryGravity = (prevArbitraryGravityDirection != null && arbitraryGravityDirection == null);

        if (cardinalChanged) {
            // Cardinal direction changed - use the standard animation system
            applyGravityDirectionChange(
                prevGravityDirection, currGravityDirection,
                currentRotationParameters, false
            );
            prevGravityDirection = currGravityDirection;
            // The cardinal animation targets the nearest cardinal; if the real gravity is
            // non-cardinal (ship), retarget from the in-flight rotation to the true
            // direction so rendering never snaps when the cardinal animation finishes.
            // Skip when the arbitrary vector coincides with the cardinal (axis-aligned
            // ship): the end rotation is identical and retargeting would only cut the
            // full-length animation down to the short retarget duration.
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
            // Transitioning from arbitrary gravity back to normal/cardinal gravity
            // Apply a gravity change to reset the player orientation
            Direction targetDir = currGravityDirection;
            Direction sourceDir = prevArbitraryGravityDirection.getNearestDirection();
            if (sourceDir != targetDir) {
                applyGravityDirectionChange(
                    sourceDir, targetDir,
                    currentRotationParameters, false
                );
            }
            // NOTE: Do NOT update bounding box here - for VS ships returning to normal,
            // the hitbox was never changed in the first place
            entity.fallDistance = 0;
        } else if (arbitraryChanged && arbitraryGravityDirection != null) {
            // Only the arbitrary direction changed (ship is rotating within the same cardinal direction)
            // Apply smooth arbitrary gravity change without full position adjustment
            applyArbitraryGravityChange();
        }
        
        // Track arbitrary gravity changes for smooth interpolation
        prevArbitraryGravityDirection = arbitraryGravityDirection;

        if (Math.abs(currGravityStrength - prevGravityStrength) > 0.0001) {
            prevGravityStrength = currGravityStrength;
        }
    }
    
    /**
     * Check if the arbitrary gravity direction has changed significantly
     */
    private boolean hasArbitraryGravityChanged() {
        if (arbitraryGravityDirection == null && prevArbitraryGravityDirection == null) {
            return false;
        }
        if (arbitraryGravityDirection == null || prevArbitraryGravityDirection == null) {
            return true;
        }
        // Compare vectors - if they're different enough, consider it changed
        Vec3 curr = arbitraryGravityDirection.getVector();
        Vec3 prev = prevArbitraryGravityDirection.getVector();
        return curr.distanceToSqr(prev) > 0.0001;
    }

    /**
     * Apply smooth arbitrary gravity changes for VS ship rotation.
     * This handles the case where the ship rotates but stays within the same cardinal direction.
     */
    private void applyArbitraryGravityChange() {
        if (!canChangeGravity() || arbitraryGravityDirection == null) {
            return;
        }

        // NOTE: Do NOT update bounding box for arbitrary gravity changes!
        // For VS ships, the hitbox stays world-aligned - only the camera and movement direction change.
        // Updating the bounding box causes hitbox size/offset issues.

        // Reset fall distance to prevent fall damage during smooth rotation
        entity.fallDistance = 0;

        // For smooth arbitrary gravity, we need to adjust velocity to match the new gravity frame
        // BUT: use cardinal directions for velocity transformation since physics uses cardinal
        if (prevArbitraryGravityDirection != null && !prevArbitraryGravityDirection.equals(arbitraryGravityDirection)) {
            Direction prevNearest = prevArbitraryGravityDirection.getNearestDirection();
            Direction currNearest = arbitraryGravityDirection.getNearestDirection();

            // Only transform velocity if the cardinal direction changed
            if (prevNearest != currNearest) {
                Vec3 currentVelocity = entity.getDeltaMovement();

                // Transform velocity from old gravity frame to world, then to new gravity frame
                Vec3 worldVelocity = RotationUtil.vecPlayerToWorld(currentVelocity, prevNearest);
                Vec3 newFrameVelocity = RotationUtil.vecWorldToPlayer(worldVelocity, currNearest);

                entity.setDeltaMovement(newFrameVelocity);
            }
        }

        // Start smooth rotation animation on client if applicable
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

    /**
     * Not needed in normal cases.
     * Only used in {@link GravityChangerAPI#instantlySetClientBaseGravityDirection(Entity, Direction)}
     * Used by ImmPtl.
     */
    public void forceApplyGravityChange() {
        prevGravityDirection = currGravityDirection;
        prevGravityStrength = currGravityStrength;
    }
    
    private static record GravityDirEffect(
        @NotNull Direction direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        @Nullable GravityDirection arbitraryDirection,
        boolean isCameraOnly
    ) {
        /**
         * Constructor for cardinal direction only (backward compatibility)
         */
        public GravityDirEffect(Direction direction, RotationParameters rotationParameters, double priority) {
            this(direction, rotationParameters, priority, null, false);
        }

        /**
         * Constructor with arbitrary direction but not camera-only
         */
        public GravityDirEffect(Direction direction, RotationParameters rotationParameters, double priority, GravityDirection arbitraryDirection) {
            this(direction, rotationParameters, priority, arbitraryDirection, false);
        }
    }
}
