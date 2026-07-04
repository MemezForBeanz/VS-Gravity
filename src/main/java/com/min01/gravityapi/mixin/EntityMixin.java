package com.min01.gravityapi.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.min01.gravityapi.api.GravityChangerAPI;
import com.min01.gravityapi.api.GravityDirection;
import com.min01.gravityapi.capabilities.GravityCapabilities;
import com.min01.gravityapi.capabilities.IGravityCapability;
import com.min01.gravityapi.compat.vs2.ShipCollisionDebug;
import com.min01.gravityapi.compat.vs2.VS2Helper;
import com.min01.gravityapi.config.GravityConfig;
import com.min01.gravityapi.util.RotationUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow
    private Vec3 position;
    
    @Shadow
    private EntityDimensions dimensions;
    
    @Shadow
    private float eyeHeight;
    
    @Shadow
    public double xo;
    
    @Shadow
    public double yo;
    
    @Shadow
    public double zo;
    
    @Shadow
    public abstract double getX();
    
    @Shadow
    public abstract Vec3 getEyePosition();
    
    @Shadow
    public abstract double getY();
    
    @Shadow
    public abstract double getZ();
    
    @Shadow
    public Level level;
    
    @Shadow
    public abstract int getBlockX();
    
    @Shadow
    public abstract int getBlockZ();
    
    @Shadow
    public boolean noPhysics;
    
    @Shadow
    public abstract Vec3 getDeltaMovement();
    
    @Shadow
    public abstract boolean isVehicle();
    
    @Shadow
    public abstract AABB getBoundingBox();
    
    @Shadow
    public static Vec3 collideWithShapes(Vec3 movement, AABB entityBoundingBox, List<VoxelShape> collisions) {
        return null;
    }
    
    @Shadow
    public abstract Vec3 position();
    
    
    @Shadow
    public abstract boolean isPassengerOfSameVehicle(Entity entity);
    
    @Shadow
    public abstract void push(double deltaX, double deltaY, double deltaZ);
    
    @Shadow
    protected abstract void onBelowWorld();
    
    @Shadow
    public abstract double getEyeY();
    
    @Shadow
    public abstract float getViewYRot(float tickDelta);
    
    @Shadow
    public abstract float getYRot();
    
    @Shadow
    public abstract float getXRot();
    
    @Shadow
    @Final
    protected RandomSource random;
    
    @Shadow
    public float fallDistance;
    
	@Inject(method = "tick", at = @At("HEAD"))
	private void tick(CallbackInfo ci)
	{
		Entity entity = Entity.class.cast(this);
		entity.getCapability(GravityCapabilities.GRAVITY).ifPresent(IGravityCapability::tick);
	}

	/**
	 * Explicit ground-stick for VS2 ship gravity. After a move, if the entity is resting on the
	 * ship's deck (probed in ship-local space), clamp the downward (gravity-direction) velocity and
	 * force onGround. Without this, vanilla's onGround detection fails on tilted ship geometry once
	 * the entity sinks even slightly, so gravity accumulates unbounded and eventually tunnels the
	 * entity through the deck / slides it off the edge.
	 */
	@Inject(method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("RETURN"))
	private void inject_shipGroundStick(net.minecraft.world.entity.MoverType type, Vec3 movement, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		// STRICT check: ground-stick + depenetration must only run with ship gravity actually
		// active. Under the loose check, a normal-gravity player brushing a ship hull would get
		// shoved out along ship-up every tick (vibration/forcefield).
		if (!GravityChangerAPI.isShipGravityPhysics(self)) {
			return;
		}
		GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(self);
		Vec3 worldDown = gravityDirection.vecPlayerToWorld(new Vec3(0.0D, -1.0D, 0.0D));
		// deltaMovement is in the local (gravity) frame for arbitrary gravity: -Y is "down".
		// Skip while moving upward (jumping) so we don't cancel jumps.
		Vec3 dm = self.getDeltaMovement();
		if (dm.y <= 0.0D && VS2Helper.isSupportedByShipDeck(self, worldDown, 0.2D)) {
			if (dm.y < 0.0D) {
				self.setDeltaMovement(dm.x, 0.0D, dm.z);
			}
			self.setOnGround(true);
			self.fallDistance = 0.0F;

			// Depenetration: velocity clamping alone never recovers the slow per-tick sinking into
			// the deck (residual un-cancelled movement, or the ship shifting under an entity VS2 no
			// longer drags). Once the feet pass the bottom of the deck layer, the probe above stops
			// seeing the deck and gravity runs away. Measure how deep the feet are inside the deck
			// (in ship space) and push back out along ship-up, capped per tick so the correction is
			// smooth rather than a teleport.
			double penetration = VS2Helper.getShipDeckPenetration(self, 0.5D);
			if (penetration > 1.0E-3D) {
				Vec3 shipUp = VS2Helper.getShipUpInWorld(self);
				if (shipUp != null) {
					double correction = Math.min(penetration, 0.25D);
					self.setPos(self.position().add(shipUp.scale(correction)));
					if (ShipCollisionDebug.shouldLog(self) && self instanceof Player) {
						ShipCollisionDebug.log("[groundstick] {} depenetrate {} (of {}) along shipUp={}",
								self.level().isClientSide() ? "C" : "S",
								String.format("%.4f", correction), String.format("%.4f", penetration), shipUp);
					}
				}
			}

			if (ShipCollisionDebug.shouldLog(self) && self instanceof Player) {
				ShipCollisionDebug.log("[groundstick] {} supported -> onGround=true, clamped dMove={}",
						self.level().isClientSide() ? "C" : "S", self.getDeltaMovement());
			}
		}
	}
    
    @WrapOperation(method = "Lnet/minecraft/world/entity/Entity;makeBoundingBox()Lnet/minecraft/world/phys/AABB;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityDimensions;makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"))
    private AABB wrapOperation_canChangeIntoPose_getBoundingBox(EntityDimensions dimensions, Vec3 pos, Operation<AABB> original) {
    	Entity self = (Entity) (Object) this;

    	// Skip hitbox transformation for arbitrary gravity (VS ships) - only transform for cardinal gravity
    	// For VS ships, the hitbox stays normal but the camera rotates
    	// The isArbitraryGravity check includes the cached inGravityGeneratorField flag
    	boolean isArbitrary = GravityChangerAPI.isArbitraryGravity(self);
    	Direction gravityDirection = GravityChangerAPI.getGravityDirection(self);

    	// Always skip transformation and return original for arbitrary gravity OR if gravity is DOWN
    	// This ensures the hitbox is NEVER modified for VS ship gravity
    	if (isArbitrary || gravityDirection == Direction.DOWN) {
    		return original.call(dimensions, pos);
    	}

    	// Only transform for non-DOWN cardinal gravity (not arbitrary)

    	AABB box = dimensions.makeBoundingBox(0, 0, 0);
    	AABB transformedBox = RotationUtil.boxPlayerToWorld(box, gravityDirection);
    	if (gravityDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
    		transformedBox = transformedBox.move(0.0D, -1.0E-6D, 0.0D);
    	}
    	return transformedBox.move(pos);
    }
    
    @Inject(method = "getBoundingBoxForPose", at = @At("RETURN"), cancellable = true)
    private void getBoundingBoxForPose(Pose pose, CallbackInfoReturnable<AABB> cir)
    {
        Entity self = (Entity) (Object) this;

        // Skip hitbox transformation for arbitrary gravity (VS ships)
        // The isArbitraryGravity check includes the cached inGravityGeneratorField flag
        if (GravityChangerAPI.isArbitraryGravity(self)) return;

        Direction gravityDirection = GravityChangerAPI.getGravityDirection(self);
        if (gravityDirection == Direction.DOWN) return;


    	AABB aabb = cir.getReturnValue();
    	if(gravityDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE)
    	{
    		aabb = aabb.move(0.0D, -1.0E-6D, 0.0D);
    	}
    	cir.setReturnValue(RotationUtil.boxPlayerToWorld(aabb, gravityDirection));
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;calculateViewVector(FF)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void inject_getRotationVector(CallbackInfoReturnable<Vec3> cir) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
        if (gravityDirection.equals(GravityDirection.DOWN)) return;

        cir.setReturnValue(RotationUtil.vecPlayerToWorld(cir.getReturnValue(), gravityDirection));
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getBlockPosBelowThatAffectsMyMovement()Lnet/minecraft/core/BlockPos;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getVelocityAffectingPos(CallbackInfoReturnable<BlockPos> cir) {
        // Use the full gravity vector (works for arbitrary/VS ship gravity too, where the cardinal
        // direction is kept as DOWN for camera-only gravity). This makes friction/speed be read from
        // the block actually beneath the entity's feet in the gravity direction (e.g. the ship deck),
        // rather than whatever happens to be straight down in world space.
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
        if (gravityDirection.equals(GravityDirection.DOWN)) return;

        cir.setReturnValue(BlockPos.containing(this.position.add(gravityDirection.getVector().scale(0.5000001D))));
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getEyePosition()Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getEyePos(CallbackInfoReturnable<Vec3> cir) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
        if (gravityDirection.equals(GravityDirection.DOWN)) return;

        cir.setReturnValue(RotationUtil.vecPlayerToWorld(new Vec3(0.0D, this.eyeHeight, 0.0D), gravityDirection).add(this.position));
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getCameraPosVec(float tickDelta, CallbackInfoReturnable<Vec3> cir) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
        if (gravityDirection.equals(GravityDirection.DOWN)) return;

        Vec3 vec3d = RotationUtil.vecPlayerToWorld(new Vec3(0.0D, this.eyeHeight, 0.0D), gravityDirection);

        double d = Mth.lerp((double) tickDelta, this.xo, this.getX()) + vec3d.x;
        double e = Mth.lerp((double) tickDelta, this.yo, this.getY()) + vec3d.y;
        double f = Mth.lerp((double) tickDelta, this.zo, this.getZ()) + vec3d.z;
        cir.setReturnValue(new Vec3(d, e, f));
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getLightLevelDependentMagicValue()F",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getBrightnessAtFEyes(CallbackInfoReturnable<Float> cir) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) return;
        
        cir.setReturnValue(this.level.hasChunkAt(this.getBlockX(), this.getBlockZ()) ? this.level.getLightLevelDependentMagicValue(BlockPos.containing(this.getEyePosition())) : 0.0F);
    }
    
    // transform move vector from local to world (the velocity is local)
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"),
        ordinal = 0,
        argsOnly = true
    )
    private Vec3 modify_move_Vec3d_0_0(Vec3 vec3d) {
        // For arbitrary gravity (VS ships), transform movement to apply gravity in ship's down direction
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
            Vec3 world = gravityDirection.vecPlayerToWorld(vec3d);
            Entity self = (Entity) (Object) this;
            if (self instanceof Player && ShipCollisionDebug.shouldLog(self)) {
                com.min01.gravityapi.capabilities.GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponent(self);
                ShipCollisionDebug.log("[move] {} og={} posY={} dMove={} | localMove={} -> worldMove={} (arb={} inField={})",
                        self.level().isClientSide() ? "C" : "S", self.onGround(),
                        String.format("%.4f", self.getY()), self.getDeltaMovement(), vec3d, world,
                        cap.getArbitraryGravityDirection() != null, cap.isInGravityGeneratorField());
            }
            return world;
        }

        // For cardinal gravity, use standard transformation
        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return vec3d;
        }

        if(!((Entity) (Object) this instanceof Player)) {
            return RotationUtil.vecEntityToWorld(vec3d, gravityDirection);
        }

        return RotationUtil.vecPlayerToWorld(vec3d, gravityDirection);
    }
    
    // looks like not useful
//    @ModifyArg(
//        method = "move",
//        at = @At(
//            value = "INVOKE",
//            target = "Lnet/minecraft/world/phys/Vec3;multiply(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
//            ordinal = 0
//        ),
//        index = 0
//    )
//    private Vec3 modify_move_multiply_0(Vec3 vec3d) {
//        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
//        if (gravityDirection == Direction.DOWN) {
//            return vec3d;
//        }
//
//        return RotationUtil.maskPlayerToWorld(vec3d, gravityDirection);
//    }
    
    // transform the argument vector back to local coordinate
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V",
            ordinal = 0
        ),
        ordinal = 0,
        argsOnly = true
    )
    private Vec3 modify_move_Vec3d_0_1(Vec3 vec3d) {
        // For arbitrary gravity (VS ships), transform back to local coords
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
            return gravityDirection.vecWorldToPlayer(vec3d);
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return vec3d;
        }

        return RotationUtil.vecWorldToPlayer(vec3d, gravityDirection);
    }
    
    // transform the local variable (result from collide()) to local coordinate
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V",
            ordinal = 0
        ),
        ordinal = 1
    )
    private Vec3 modify_move_Vec3d_1(Vec3 vec3d) {
        // For arbitrary gravity (VS ships), transform collide result to local coords
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
            return gravityDirection.vecWorldToPlayer(vec3d);
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return vec3d;
        }

        return RotationUtil.vecWorldToPlayer(vec3d, gravityDirection);
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getOnPosLegacy()Lnet/minecraft/core/BlockPos;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getLandingPos(CallbackInfoReturnable<BlockPos> cir) {
        // For arbitrary gravity (VS ships), use arbitrary direction for ground detection
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
            BlockPos blockPos = BlockPos.containing(gravityDirection.vecPlayerToWorld(new Vec3(0.0D, -0.20000000298023224D, 0.0D)).add(this.position));
            cir.setReturnValue(blockPos);
            return;
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) return;

        BlockPos blockPos = BlockPos.containing(RotationUtil.vecPlayerToWorld(new Vec3(0.0D, -0.20000000298023224D, 0.0D), gravityDirection).add(this.position));
        cir.setReturnValue(blockPos);
    }

    /**
     * Take over the whole of {@code Entity.collide} for entities with active ship gravity.
     *
     * <p>Vanilla's step-up logic builds its probe sweeps from world-axis vectors
     * ({@code (move.x, maxUpStep, move.z)}, {@code (0, maxUpStep, 0)}, and a final settle sweep
     * {@code (0, -stepY + move.y, 0)}) and compares candidates by world-horizontal distance. On a
     * tilted ship those vectors are diagonal in ship space, so the "stepped" candidate can beat the
     * correct wall-stop and carry the entity through blocks it walked into, and the settle sweep
     * adds an uncancelled displacement every tick the entity presses against a wall (violent
     * ship-down acceleration at hull sides). Running the entire collide - main sweep, step-up and
     * settle - in ship-local space keeps every probe axis-aligned with the deck.
     *
     * <p>Trade-off: entity-vs-entity collisions (boats, shulkers) are skipped for these entities.
     */
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void inject_collide_shipGravity(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (!GravityChangerAPI.isShipGravityPhysics(self)) {
            return;
        }

        // Two-stage resolution: first against the ship's blocks in shipyard space (where they are
        // axis-aligned), then against world terrain in world space. World-space getBlockCollisions
        // never contains ship shapes (VS2 applies those only via its polygon path, which we skip
        // for these entities), so the two stages are disjoint and each geometry is swept in the
        // frame where it is axis-aligned. Without the terrain stage, an in-field entity standing
        // on ordinary ground near a ship had NO terrain collision at all (clipping), and with
        // plain world sweeps the tangential remainder of tilted gravity made it slide forever.
        Vec3 afterShip = collideOnShipFull(self, movement);
        Vec3 result = collideOnTerrain(self, afterShip != null ? afterShip : movement);

        if (ShipCollisionDebug.shouldLog(self) && self instanceof Player) {
            ShipCollisionDebug.log("[collideFull] {} worldMove={} -> ship={} -> result={}",
                    self.level().isClientSide() ? "C" : "S", movement,
                    afterShip != null ? afterShip : "(no ship)", result);
        }
        cir.setReturnValue(result);
    }

    /**
     * Full ship-local replacement for {@code Entity.collide}: main sweep, vanilla-equivalent
     * step-up and settle, all in shipyard coordinates where the ship's blocks are axis-aligned.
     *
     * @return the world-space collision-adjusted movement, or {@code null} if the entity is not on
     *         a resolvable ship (caller should then resolve against terrain only)
     */
    @Unique
    private static Vec3 collideOnShipFull(Entity entity, Vec3 worldMovement) {
        AABB worldBox = entity.getBoundingBox();
        VS2Helper.ShipCollisionContext ctx = VS2Helper.getShipCollisionContext(entity, worldMovement, worldBox);
        if (ctx == null) {
            return null;
        }

        Vec3 shipMove = ctx.shipMovement;
        AABB shipBox = ctx.shipBox;
        double stepUp = entity.maxUpStep();

        // One shape query covering the main sweep and all step probes (vanilla reuses a single
        // list the same way). Queried with a null entity so VS2 doesn't re-inject world-space
        // tilted shapes at shipyard coordinates.
        AABB queryBox = shipBox.expandTowards(shipMove.x, shipMove.y, shipMove.z)
                .expandTowards(0.0D, stepUp, 0.0D)
                .inflate(1.0E-7D);
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : entity.level().getBlockCollisions(null, queryBox)) {
            if (!shape.isEmpty()) {
                shapes.add(shape);
            }
        }
        if (shapes.isEmpty()) {
            // Nothing of the ship near the entity - let the terrain stage handle the movement.
            return ctx.shipDirToWorld(shipMove);
        }

        Vec3 adjusted = shipMove.lengthSqr() == 0.0D ? shipMove : collideWithShapes(shipMove, shipBox, shapes);
        boolean collidedX = shipMove.x != adjusted.x;
        boolean collidedY = shipMove.y != adjusted.y;
        boolean collidedZ = shipMove.z != adjusted.z;
        boolean grounded = entity.onGround() || (collidedY && shipMove.y < 0.0D);
        if (stepUp > 0.0D && grounded && (collidedX || collidedZ)) {
            Vec3 stepped = collideWithShapes(new Vec3(shipMove.x, stepUp, shipMove.z), shipBox, shapes);
            Vec3 upOnly = collideWithShapes(new Vec3(0.0D, stepUp, 0.0D),
                    shipBox.expandTowards(shipMove.x, 0.0D, shipMove.z), shapes);
            if (upOnly.y < stepUp) {
                Vec3 horizontal = collideWithShapes(new Vec3(shipMove.x, 0.0D, shipMove.z),
                        shipBox.move(upOnly), shapes).add(upOnly);
                if (horizontal.horizontalDistanceSqr() > stepped.horizontalDistanceSqr()) {
                    stepped = horizontal;
                }
            }
            if (stepped.horizontalDistanceSqr() > adjusted.horizontalDistanceSqr()) {
                adjusted = stepped.add(collideWithShapes(new Vec3(0.0D, -stepped.y + shipMove.y, 0.0D),
                        shipBox.move(stepped), shapes));
            }
        }

        Vec3 world = ctx.shipDirToWorld(adjusted);
        return world != null ? world : worldMovement;
    }

    /**
     * World-space collision against ordinary terrain for entities under arbitrary gravity, with
     * slope-standing semantics.
     *
     * <p>Terrain blocks are world-axis-aligned, so the sweeps themselves are plain vanilla - the
     * problem is the gravity vector: tilted gravity swept with axis separation gets its world-Y
     * stopped by the ground while the world-horizontal remainder passes on, sliding the entity
     * indefinitely. Instead, the movement is decomposed into its component along gravity and the
     * rest (walking/jumping). The walking part is resolved with vanilla semantics including
     * world-axis step-up (correct for terrain). The gravity part is treated as a ray: if it hits
     * the ground and the gravity tilt from world-up is within {@code maxTerrainStandAngle}, the
     * ray is cut at the contact point - the entity STANDS on the terrain "slope" with no residual
     * drift. Steeper than the limit, the vanilla deflection is kept and the entity naturally
     * slides off.
     */
    @Unique
    private static Vec3 collideOnTerrain(Entity entity, Vec3 worldMovement) {
        AABB box = entity.getBoundingBox();
        double stepUp = entity.maxUpStep();

        AABB queryBox = box.expandTowards(worldMovement.x, worldMovement.y, worldMovement.z)
                .expandTowards(0.0D, stepUp, 0.0D)
                .inflate(1.0E-7D);
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : entity.level().getBlockCollisions(entity, queryBox)) {
            if (!shape.isEmpty()) {
                shapes.add(shape);
            }
        }
        // Entity collision boxes (boats, shulkers) are world-aligned, so they belong to this stage.
        shapes.addAll(entity.level().getEntityCollisions(entity, queryBox));
        if (shapes.isEmpty()) {
            return worldMovement;
        }

        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        Vec3 gravity = gravityDirection.getVector();
        if (gravity.lengthSqr() < 1.0E-9D) {
            gravity = new Vec3(0.0D, -1.0D, 0.0D);
        }
        gravity = gravity.normalize();

        // Split off the movement component pulling along gravity (falling); the rest is
        // walking/jumping input.
        double along = worldMovement.dot(gravity);
        Vec3 gravityPart = along > 1.0E-9D ? gravity.scale(along) : Vec3.ZERO;
        Vec3 walkPart = worldMovement.subtract(gravityPart);

        // Walking part: vanilla sweep + step-up, all in world axes (terrain is world-aligned).
        Vec3 rest = walkPart.lengthSqr() == 0.0D ? walkPart : collideWithShapes(walkPart, box, shapes);
        boolean collidedX = walkPart.x != rest.x;
        boolean collidedY = walkPart.y != rest.y;
        boolean collidedZ = walkPart.z != rest.z;
        boolean grounded = entity.onGround() || (collidedY && walkPart.y < 0.0D);
        if (stepUp > 0.0D && grounded && (collidedX || collidedZ)) {
            Vec3 stepped = collideWithShapes(new Vec3(walkPart.x, stepUp, walkPart.z), box, shapes);
            Vec3 upOnly = collideWithShapes(new Vec3(0.0D, stepUp, 0.0D),
                    box.expandTowards(walkPart.x, 0.0D, walkPart.z), shapes);
            if (upOnly.y < stepUp) {
                Vec3 horizontal = collideWithShapes(new Vec3(walkPart.x, 0.0D, walkPart.z),
                        box.move(upOnly), shapes).add(upOnly);
                if (horizontal.horizontalDistanceSqr() > stepped.horizontalDistanceSqr()) {
                    stepped = horizontal;
                }
            }
            if (stepped.horizontalDistanceSqr() > rest.horizontalDistanceSqr()) {
                rest = stepped.add(collideWithShapes(new Vec3(0.0D, -stepped.y + walkPart.y, 0.0D),
                        box.move(stepped), shapes));
            }
        }

        // Gravity part, swept from the walked-to position.
        AABB movedBox = box.move(rest);
        Vec3 gravityAdjusted = gravityPart.lengthSqr() == 0.0D
                ? gravityPart : collideWithShapes(gravityPart, movedBox, shapes);
        if (gravityPart.y != 0.0D && gravityAdjusted.y != gravityPart.y) {
            // The gravity ray hit the ground. Within the standing limit, cut the whole ray at the
            // contact fraction instead of letting the world-horizontal remainder slide on.
            double cosTilt = -gravity.y; // 1.0 = straight down, 0.0 = fully sideways
            double maxAngle = GravityConfig.maxTerrainStandAngle.get();
            if (cosTilt >= Math.cos(Math.toRadians(maxAngle))) {
                double contactFraction = Mth.clamp(gravityAdjusted.y / gravityPart.y, 0.0D, 1.0D);
                Vec3 cut = gravityPart.scale(contactFraction);
                gravityAdjusted = cut.lengthSqr() == 0.0D ? Vec3.ZERO : collideWithShapes(cut, movedBox, shapes);
            }
        }

        return rest.add(gravityAdjusted);
    }

    // transform the argument to local coordinate
    @ModifyVariable(
        method = "collide",
        at = @At(
            value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            ordinal = 0
        ),
        ordinal = 0
    )
    private Vec3 modify_adjustMovementForCollisions_Vec3d_0(Vec3 vec3d) {
        // For arbitrary gravity (VS ships), skip collision transformation entirely
        // VS2 handles collision for entities on ships through its own system
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            return vec3d;
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return vec3d;
        }

        return RotationUtil.vecWorldToPlayer(vec3d, gravityDirection);
    }

    // transform the result to world coordinate
    // the input to Entity.collideBoundingBox will be in local coord
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void inject_adjustMovementForCollisions(CallbackInfoReturnable<Vec3> cir) {
        // For arbitrary gravity (VS ships), skip collision transformation entirely
        // VS2 handles collision for entities on ships through its own system
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            return;
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) return;

        cir.setReturnValue(RotationUtil.vecPlayerToWorld(cir.getReturnValue(), gravityDirection));
    }
    
    // the argument was transformed to local coord,
    // but bounding box stretch needs world coord
    @WrapOperation(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;expandTowards(DDD)Lnet/minecraft/world/phys/AABB;"
            )
    )
    private AABB redirect_adjustMovementForCollisions_stretch_1(AABB instance, double x, double y, double z, Operation<AABB> original) {
        // For arbitrary gravity (VS ships), skip - let VS2 handle it
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            return original.call(instance, x, y, z);
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return original.call(instance, x, y, z);
        }
        Vec3 rotate = RotationUtil.vecPlayerToWorld(new Vec3(x, y, z), gravityDirection);

        return original.call(instance, rotate.x, rotate.y, rotate.z);
    }
    
    // the argument was transformed to local coord,
    // but bounding box move needs world coord
    @ModifyArg(
            method = "collide",
            at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/phys/AABB;move(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"
            )
        )
    private Vec3 redirect_adjustMovementForCollisions_offset_0(Vec3 rotate) {
        // For arbitrary gravity (VS ships), skip - let VS2 handle it
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            return rotate;
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return rotate;
        }
    	return RotationUtil.vecPlayerToWorld(rotate, gravityDirection);
    }
    
    // Entity.collideBoundingBox is inputed with local coord, transform it to world coord
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        ordinal = 0,
        argsOnly = true
    )
    private static Vec3 modify_adjustMovementForCollisions_Vec3d_0(Vec3 vec3d, Entity entity) {
        if (entity == null) {
            return vec3d;
        }
        
        // For arbitrary gravity (VS ships), skip - let VS2 handle collision
        if (GravityChangerAPI.isArbitraryGravity(entity)) {
            return vec3d;
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return vec3d;
        }

        return RotationUtil.vecPlayerToWorld(vec3d, gravityDirection);
    }
    
    // transform back to local coord
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void inject_adjustMovementForCollisions(Entity entity, Vec3 movement, AABB entityBoundingBox, Level world, List<VoxelShape> collisions, CallbackInfoReturnable<Vec3> cir) {
        if (entity == null) return;
        
        // For arbitrary gravity (VS ships), skip - let VS2 handle collision
        if (GravityChangerAPI.isArbitraryGravity(entity)) {
            return;
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) return;

        cir.setReturnValue(RotationUtil.vecWorldToPlayer(cir.getReturnValue(), gravityDirection));
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideWithShapes(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
            ordinal = 0
        )
    )
    private static Vec3 redirect_adjustMovementForCollisions_adjustMovementForCollisions_0(Vec3 movement, AABB entityBoundingBox, List<VoxelShape> collisions, Entity entity) {
        // Check entity null FIRST to avoid NPE
        if (entity == null) {
            return collideWithShapes(movement, entityBoundingBox, collisions);
        }

        if (entity instanceof Player && ShipCollisionDebug.shouldLog(entity)) {
            ShipCollisionDebug.log("[collide] ENTER player isArbitrary={} gravNearestDir={} onGround={} worldMove={}",
                    GravityChangerAPI.isArbitraryGravity(entity), GravityChangerAPI.getGravityDirection(entity),
                    entity.onGround(), movement);
        }

        // For active ship gravity, resolve collision in the ship's local space.
        // The movement vector is already in world space here, but the surrounding ship geometry is
        // tilted in world space, so a normal axis-separated sweep makes the (world-aligned) hitbox
        // slide along / clip through the ground. By transforming the box + movement into the ship's
        // shipyard coordinates - where the ship's blocks are axis-aligned and gravity points
        // straight down - we can run the standard sweep correctly and convert the result back.
        // STRICT check: entities merely near a ship (loose isArbitraryGravity) keep the stock
        // vanilla+VS2 collision path below instead of our ship-local resolution.
        if (GravityChangerAPI.isShipGravityPhysics(entity)) {
            Vec3 shipResult = collideOnShip(entity, movement, entityBoundingBox);
            if (shipResult != null) {
                if (ShipCollisionDebug.shouldLog(entity)) {
                    ShipCollisionDebug.log("[collide] ARBITRARY+onShip worldMove={} -> shipResult={} (dY {} -> {})",
                            movement, shipResult, movement.y, shipResult.y);
                }
                return shipResult;
            }
            // Not actually on a ship (or transform unavailable): fall back to plain world collision.
            if (ShipCollisionDebug.shouldLog(entity)) {
                ShipCollisionDebug.log("[collide] ARBITRARY but collideOnShip==null -> FALLBACK world collision, worldMove={}",
                        movement);
            }
            return collideWithShapes(movement, entityBoundingBox, collisions);
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return collideWithShapes(movement, entityBoundingBox, collisions);
        }

        // Use cardinal direction for collision system
        Vec3 playerMovement = RotationUtil.vecWorldToPlayer(movement, gravityDirection);
        double playerMovementX = playerMovement.x;
        double playerMovementY = playerMovement.y;
        double playerMovementZ = playerMovement.z;
        Direction directionX = RotationUtil.dirPlayerToWorld(Direction.EAST, gravityDirection);
        Direction directionY = RotationUtil.dirPlayerToWorld(Direction.UP, gravityDirection);
        Direction directionZ = RotationUtil.dirPlayerToWorld(Direction.SOUTH, gravityDirection);
        if (playerMovementY != 0.0D) {
            playerMovementY = Shapes.collide(directionY.getAxis(), entityBoundingBox, collisions, playerMovementY * directionY.getAxisDirection().getStep()) * directionY.getAxisDirection().getStep();
            if (playerMovementY != 0.0D) {
                entityBoundingBox = entityBoundingBox.move(RotationUtil.vecPlayerToWorld(new Vec3(0.0D, playerMovementY, 0.0D), gravityDirection));
            }
        }
        
        boolean isZLargerThanX = Math.abs(playerMovementX) < Math.abs(playerMovementZ);
        if (isZLargerThanX && playerMovementZ != 0.0D) {
            playerMovementZ = Shapes.collide(directionZ.getAxis(), entityBoundingBox, collisions, playerMovementZ * directionZ.getAxisDirection().getStep()) * directionZ.getAxisDirection().getStep();
            if (playerMovementZ != 0.0D) {
                entityBoundingBox = entityBoundingBox.move(RotationUtil.vecPlayerToWorld(new Vec3(0.0D, 0.0D, playerMovementZ), gravityDirection));
            }
        }
        
        if (playerMovementX != 0.0D) {
            playerMovementX = Shapes.collide(directionX.getAxis(), entityBoundingBox, collisions, playerMovementX * directionX.getAxisDirection().getStep()) * directionX.getAxisDirection().getStep();
            if (!isZLargerThanX && playerMovementX != 0.0D) {
                entityBoundingBox = entityBoundingBox.move(RotationUtil.vecPlayerToWorld(new Vec3(playerMovementX, 0.0D, 0.0D), gravityDirection));
            }
        }
        
        if (!isZLargerThanX && playerMovementZ != 0.0D) {
            playerMovementZ = Shapes.collide(directionZ.getAxis(), entityBoundingBox, collisions, playerMovementZ * directionZ.getAxisDirection().getStep()) * directionZ.getAxisDirection().getStep();
        }
        
        return RotationUtil.vecPlayerToWorld(new Vec3(playerMovementX, playerMovementY, playerMovementZ), gravityDirection);
    }

    /**
     * Resolve a movement collision for an entity standing on a VS2 ship, in the ship's local space.
     *
     * <p>The ship's blocks are queried directly in shipyard coordinates (where they are axis-aligned),
     * the movement and bounding box are transformed into that same space, the standard axis-separated
     * sweep is run (gravity is straight down there), and the adjusted movement is converted back to
     * world space.
     *
     * @return the world-space collision-adjusted movement, or {@code null} if the entity is not on a
     *         resolvable ship (caller should fall back to normal collision)
     */
    @Unique
    private static Vec3 collideOnShip(Entity entity, Vec3 worldMovement, AABB worldBox) {
        VS2Helper.ShipCollisionContext ctx = VS2Helper.getShipCollisionContext(entity, worldMovement, worldBox);
        if (ctx == null) {
            if (ShipCollisionDebug.shouldLog(entity)) {
                ShipCollisionDebug.log("[collideOnShip] ctx==null (no ship / transform unavailable)");
            }
            return null;
        }

        AABB expanded = ctx.shipBox.expandTowards(ctx.shipMovement.x, ctx.shipMovement.y, ctx.shipMovement.z);
        List<VoxelShape> shipShapes = new ArrayList<>();
        // Query with a null entity: at shipyard coordinates the ship's blocks exist as ordinary,
        // axis-aligned blocks. Passing the (on-ship) entity could make VS2 re-inject the ship's
        // world-space (tilted) collision shapes, mixing coordinate spaces - we only want the raw
        // shipyard blocks here.
        for (VoxelShape shape : entity.level().getBlockCollisions(null, expanded)) {
            if (!shape.isEmpty()) {
                shipShapes.add(shape);
            }
        }

        Vec3 shipAdjusted = collideWithShapes(ctx.shipMovement, ctx.shipBox, shipShapes);
        Vec3 worldResult = ctx.shipDirToWorld(shipAdjusted);
        if (ShipCollisionDebug.shouldLog(entity)) {
            ShipCollisionDebug.log("[collideOnShip] shipBox={} expanded={} shapesFound={} | shipMove={} -> shipAdjusted={} (dY stopped: {}) -> worldResult={}",
                    ctx.shipBox, expanded, shipShapes.size(), ctx.shipMovement, shipAdjusted,
                    ctx.shipMovement.y != shipAdjusted.y, worldResult);
        }
        return worldResult;
    }

    @WrapOperation(
        method = "isInWall",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;ofSize(Lnet/minecraft/world/phys/Vec3;DDD)Lnet/minecraft/world/phys/AABB;",
            ordinal = 0
        )
    )
    private AABB modify_isInsideWall_of_0(Vec3 vec3, double x, double y, double z, Operation<AABB> original) {
        // Skip for arbitrary gravity (VS ships)
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            return original.call(vec3, x, y, z);
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return original.call(vec3, x, y, z);
        }
        Vec3 rotate = RotationUtil.vecPlayerToWorld(new Vec3(x, y, z), gravityDirection);
        return original.call(vec3, rotate.x, rotate.y, rotate.z);
    }
    
    @ModifyArg(
        method = "getDirection",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/Direction;fromYRot(D)Lnet/minecraft/core/Direction;"
        )
    )
    private double redirect_getHorizontalFacing_getYaw_0(double rotation) {
        Entity this_ = (Entity) (Object) this;
        
        // Skip for arbitrary gravity (VS ships)
        if (GravityChangerAPI.isArbitraryGravity(this_)) {
            return rotation;
        }
        
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this_);
        if (gravityDirection == Direction.DOWN) {
            return rotation;
        }

        return RotationUtil.rotPlayerToWorld((float) rotation, this.getXRot(), gravityDirection).x;
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;spawnSprintParticle()V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_spawnSprintingParticles(CallbackInfo ci) {
        // Skip for arbitrary gravity (VS ships)
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) return;

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) return;

        ci.cancel();
        
        Vec3 floorPos = this.position().subtract(RotationUtil.vecPlayerToWorld(new Vec3(0.0D, 0.20000000298023224D, 0.0D), gravityDirection));

        BlockPos blockPos = BlockPos.containing(floorPos);
        BlockState blockState = this.level.getBlockState(blockPos);
        if (blockState.getRenderShape() != RenderShape.INVISIBLE) {
            Vec3 particlePos = this.position().add(RotationUtil.vecPlayerToWorld(new Vec3((this.random.nextDouble() - 0.5D) * (double) this.dimensions.width, 0.1D, (this.random.nextDouble() - 0.5D) * (double) this.dimensions.width), gravityDirection));
            Vec3 playerVelocity = this.getDeltaMovement();
            Vec3 particleVelocity = RotationUtil.vecPlayerToWorld(new Vec3(playerVelocity.x * -4.0D, 1.5D, playerVelocity.z * -4.0D), gravityDirection);
            this.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, blockState), particlePos.x, particlePos.y, particlePos.z, particleVelocity.x, particleVelocity.y, particleVelocity.z);
        }
    }
    
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;push(Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_pushAwayFrom(Entity entity, CallbackInfo ci) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
        GravityDirection otherGravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);

        if (gravityDirection.equals(GravityDirection.DOWN) && otherGravityDirection.equals(GravityDirection.DOWN)) return;

        ci.cancel();
        
        if (!this.isPassengerOfSameVehicle(entity)) {
            if (!entity.noPhysics && !this.noPhysics) {
                Vec3 entityOffset = entity.getBoundingBox().getCenter().subtract(this.getBoundingBox().getCenter());
                
                {
                    Vec3 playerEntityOffset = RotationUtil.vecWorldToPlayer(entityOffset, gravityDirection);
                    double dx = playerEntityOffset.x;
                    double dz = playerEntityOffset.z;
                    double f = Mth.absMax(dx, dz);
                    if (f >= 0.009999999776482582D) {
                        f = Math.sqrt(f);
                        dx /= f;
                        dz /= f;
                        double g = 1.0D / f;
                        if (g > 1.0D) {
                            g = 1.0D;
                        }
                        
                        dx *= g;
                        dz *= g;
                        dx *= 0.05000000074505806D;
                        dz *= 0.05000000074505806D;
                        if (!this.isVehicle()) {
                            this.push(-dx, 0.0D, -dz);
                        }
                    }
                }
                
                {
                    Vec3 entityEntityOffset = RotationUtil.vecWorldToPlayer(entityOffset, otherGravityDirection);
                    double dx = entityEntityOffset.x;
                    double dz = entityEntityOffset.z;
                    double f = Mth.absMax(dx, dz);
                    if (f >= 0.009999999776482582D) {
                        f = Math.sqrt(f);
                        dx /= f;
                        dz /= f;
                        double g = 1.0D / f;
                        if (g > 1.0D) {
                            g = 1.0D;
                        }
                        
                        dx *= g;
                        dz *= g;
                        dx *= 0.05000000074505806D;
                        dz *= 0.05000000074505806D;
                        if (!entity.isVehicle()) {
                            entity.push(dx, 0.0D, dz);
                        }
                    }
                }
            }
        }
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;checkBelowWorld()V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_attemptTickInVoid(CallbackInfo ci) {
        Entity this_ = (Entity) (Object) this;
    
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(this_);
        Direction nearestDir = gravityDirection.getNearestDirection();
        if (GravityConfig.voidDamageAboveWorld.get() &&
            this.getY() > (double) (this.level.getMaxBuildHeight() + 256) &&
            nearestDir == Direction.UP
        ) {
            this.onBelowWorld();
            ci.cancel();
            return;
        }
        
        if (GravityConfig.voidDamageOnHorizontalFallTooFar.get() &&
            nearestDir.getAxis() != Direction.Axis.Y &&
            fallDistance > 1024
            // TODO also handle reverse gravity strength
        ) {
            this.onBelowWorld();
            ci.cancel();
            return;
        }
    }
    
    @WrapOperation(
        method = "isFree(DDD)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;move(DDD)Lnet/minecraft/world/phys/AABB;",
            ordinal = 0
        )
    )
    private AABB redirect_doesNotCollide_offset_0(AABB instance, double x, double y, double z, Operation<AABB> original) {
        Vec3 rotate = new Vec3(x, y, z);
        rotate = RotationUtil.vecPlayerToWorld(rotate, GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this));
        return original.call(instance, rotate.x, rotate.y, rotate.z);
    }
    
    
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;updateFluidOnEyes()V",
        at = @At(
            value = "STORE"
        ),
        ordinal = 0
    )
    private double submergedInWaterEyeFix(double d) {
        d = this.getEyePosition().y();
        return d;
    }
    
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;updateFluidOnEyes()V",
        at = @At(
            value = "STORE"
        ),
        ordinal = 0
    )
    private BlockPos submergedInWaterPosFix(BlockPos blockpos) {
        blockpos = BlockPos.containing(this.getEyePosition());
        return blockpos;
    }
    
    
}