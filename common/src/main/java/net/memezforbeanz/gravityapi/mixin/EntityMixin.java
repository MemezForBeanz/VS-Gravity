package net.memezforbeanz.gravityapi.mixin;

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
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl;
import net.memezforbeanz.gravityapi.compat.vs2.ShipCollisionDebug;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.memezforbeanz.gravityapi.util.RotationUtil;
import net.memezforbeanz.gravityapi.util.ShipIntersectionCache;

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
public abstract class EntityMixin implements ShipIntersectionCache {

    @Unique
    private Object gravityapi$cachedShip;

    @Unique
    private long gravityapi$shipCacheTime = Long.MIN_VALUE;

    @Override
    public Object gravityapi$getCachedShip() {
        return this.gravityapi$cachedShip;
    }

    @Override
    public long gravityapi$getShipCacheTime() {
        return this.gravityapi$shipCacheTime;
    }

    @Override
    public void gravityapi$setCachedShip(long gameTime, Object ship) {
        this.gravityapi$shipCacheTime = gameTime;
        this.gravityapi$cachedShip = ship;
    }

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
		GravityCapabilityImpl gravity = GravityChangerAPI.getGravityComponentEarly(Entity.class.cast(this));
		if (gravity != null)
		{
			gravity.tick();
		}
	}

	/**
	 * Ground-stick for ship gravity: after a move, an entity resting on the deck (probed in
	 * ship space) has its downward velocity clamped and onGround forced. Vanilla's onGround fails
	 * on tilted ship geometry once the entity sinks slightly.
	 */
	@Inject(method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", at = @At("RETURN"))
	private void inject_shipGroundStick(net.minecraft.world.entity.MoverType type, Vec3 movement, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		// Strict check: a normal-gravity player brushing a hull must not be shoved along ship-up
		if (!GravityChangerAPI.isShipGravityPhysics(self)) {
			// Ship-locked gravity lined up with the world axes (an unrotated planetoid) takes the
			// cardinal physics, but VS2 must still carry the entity with the ship: its own standing
			// check only knows world-down
			if (GravityChangerAPI.getGravityDirectionVec(self).isShipAnchored()) {
				VS2Helper.markStandingOnShip(self);
			}
			return;
		}

		// Keep VS2's EntityDragger tracking the entity on every move, not only when the deck probe
		// hits (VS2 refreshes this in the collision code we skip)
		boolean dragged = VS2Helper.markStandingOnShip(self);

		GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(self);
		Vec3 worldDown = gravityDirection.vecPlayerToWorld(new Vec3(0.0D, -1.0D, 0.0D));
		// deltaMovement is in the gravity frame; skip while rising so jumps aren't cancelled
		Vec3 dm = self.getDeltaMovement();
		if (ShipCollisionDebug.shouldLog(self) && self instanceof Player) {
			ShipCollisionDebug.log("[tick] {} t={} og={} supported={} pen={} dMove={} pos={} gravity={} anchored={} local={} strength={} override={} noGrav={} yaw={}",
					self.level().isClientSide() ? "C" : "S", self.level().getGameTime(), self.onGround(),
					VS2Helper.isSupportedByShipDeck(self, worldDown, 0.2D),
					String.format("%.4f", VS2Helper.getShipDeckPenetration(self, 0.5D)), dm, self.position(), gravityDirection,
					gravityDirection.isShipAnchored(), gravityDirection.getShipLocalVector(),
					String.format("%.4f", GravityChangerAPI.getGravityStrength(self)),
					GravityChangerAPI.isGravityStrengthOverridden(self), self.isNoGravity(),
					String.format("%.2f", self.getYRot()));
		}
		if (dm.y <= 0.0D && VS2Helper.isSupportedByShipDeck(self, worldDown, 0.2D)) {
			if (dm.y < 0.0D) {
				self.setDeltaMovement(dm.x, 0.0D, dm.z);
			}
			self.setOnGround(true);
			self.fallDistance = 0.0F;

			// Depenetration: push feet that sank into the deck back out along ship-up, capped per tick
			// at the probe depth
			double penetration = VS2Helper.getShipDeckPenetration(self, 0.5D);
			if (penetration > 1.0E-6D) {
				// The entity's up in the ship frame: under the hull that is ship-down
				Vec3 shipUp = VS2Helper.getShipLocalUpInWorld(self);
				if (shipUp != null) {
					double correction = Math.min(penetration, 0.5D);
					self.setPos(self.position().add(shipUp.scale(correction)));
					if (ShipCollisionDebug.shouldLog(self) && self instanceof Player) {
						ShipCollisionDebug.log("[groundstick] {} depenetrate {} (of {}) along shipUp={}",
								self.level().isClientSide() ? "C" : "S",
								String.format("%.4f", correction), String.format("%.4f", penetration), shipUp);
					}
				}
			}

			if (ShipCollisionDebug.shouldLog(self) && self instanceof Player) {
				ShipCollisionDebug.log("[groundstick] {} supported -> onGround=true, clamped dMove={} dragged={}",
						self.level().isClientSide() ? "C" : "S", self.getDeltaMovement(), dragged);
			}
		}
	}
    
    @WrapOperation(method = "Lnet/minecraft/world/entity/Entity;makeBoundingBox()Lnet/minecraft/world/phys/AABB;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityDimensions;makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"))
    private AABB wrapOperation_canChangeIntoPose_getBoundingBox(EntityDimensions dimensions, Vec3 pos, Operation<AABB> original) {
    	Entity self = (Entity) (Object) this;

    	// Arbitrary (VS ship) gravity and DOWN keep the vanilla hitbox; only other cardinals transform it
    	boolean isArbitrary = GravityChangerAPI.isArbitraryGravity(self);
    	Direction gravityDirection = GravityChangerAPI.getGravityDirection(self);

    	if (isArbitrary || gravityDirection == Direction.DOWN) {
    		return original.call(dimensions, pos);
    	}

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

        // Arbitrary (VS ship) gravity keeps the vanilla hitbox
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
        Entity self = (Entity) (Object) this;
        if (net.memezforbeanz.gravityapi.placement.PlacementFrame.inputs(self) != null) return;
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(self);
        Vec3 view = cir.getReturnValue();
        // A free fall's tilt turns the view; the crosshair follows it (world = playerToWorld(tilt * v))
        org.joml.Quaternionf tilt = self instanceof Player ? GravityChangerAPI.getVisualTilt(self, 1.0f) : null;
        if (tilt != null) {
            org.joml.Vector3f tilted = tilt.transform(new org.joml.Vector3f((float) view.x, (float) view.y, (float) view.z));
            view = new Vec3(tilted.x, tilted.y, tilted.z);
        }
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            if (tilt != null) {
                cir.setReturnValue(view);
            }
            return;
        }

        cir.setReturnValue(RotationUtil.vecPlayerToWorld(view, gravityDirection));
    }

    // Movement input in a field free fall follows the tilted view, like the view vector
    @WrapOperation(method = "moveRelative", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wrap_moveRelative_tiltInput(Vec3 relative, float speed, float yaw, Operation<Vec3> original) {
        Vec3 input = original.call(relative, speed, yaw);
        // Players only: a mob's tilt (a flying mob leaning into a field) is cosmetic
        org.joml.Quaternionf tilt = (Object) this instanceof Player
            ? GravityChangerAPI.getVisualTilt((Entity) (Object) this, 1.0f) : null;
        if (tilt == null) {
            return input;
        }
        org.joml.Vector3f tilted = tilt.transform(new org.joml.Vector3f((float) input.x, (float) input.y, (float) input.z));
        return new Vec3(tilted.x, tilted.y, tilted.z);
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getBlockPosBelowThatAffectsMyMovement()Lnet/minecraft/core/BlockPos;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getVelocityAffectingPos(CallbackInfoReturnable<BlockPos> cir) {
        // Read friction/speed from the block beneath the feet along the full gravity vector (on a
        // ship, probed in shipyard space, where the deck blocks are)
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
        if (gravityDirection.equals(GravityDirection.DOWN)) return;

        Vec3 probe = this.position.add(gravityDirection.getVector().scale(0.5000001D));
        if (VS2Integration.isVS2Loaded()) {
            Vec3 shipProbe = VS2Helper.worldToShipPosition((Entity) (Object) this, probe);
            if (shipProbe != null) {
                probe = shipProbe;
            }
        }
        cir.setReturnValue(BlockPos.containing(probe));
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
        // Arbitrary (VS ship) gravity: move along the ship's down
        if (GravityChangerAPI.isArbitraryGravity((Entity) (Object) this)) {
            GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec((Entity) (Object) this);
            Vec3 world = gravityDirection.vecPlayerToWorld(vec3d);
            Entity self = (Entity) (Object) this;
            if (self instanceof Player && ShipCollisionDebug.shouldLog(self)) {
                net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponent(self);
                ShipCollisionDebug.log("[move] {} og={} posY={} dMove={} | localMove={} -> worldMove={} (arb={} inField={})",
                        self.level().isClientSide() ? "C" : "S", self.onGround(),
                        String.format("%.4f", self.getY()), self.getDeltaMovement(), vec3d, world,
                        cap.getArbitraryGravityDirection() != null, cap.isInGravityGeneratorField());
            }
            return world;
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return vec3d;
        }

        if(!((Entity) (Object) this instanceof Player)) {
            return RotationUtil.vecEntityToWorld(vec3d, gravityDirection);
        }

        return RotationUtil.vecPlayerToWorld(vec3d, gravityDirection);
    }

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
        // Arbitrary gravity: back to local coords
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
        // Arbitrary gravity: collide result to local coords
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
        // Arbitrary gravity: ground detection along the arbitrary direction
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
     * Run all of {@code Entity.collide} (main sweep, step-up and settle) in ship space for entities
     * with active ship gravity. Vanilla's world-axis step-up probes are diagonal on a tilted ship and
     * carried entities through walls.
     * <p>Entity-vs-entity collisions (boats, shulkers) are skipped for these entities.
     */
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void inject_collide_shipGravity(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        if (!GravityChangerAPI.isShipGravityPhysics(self)) {
            return;
        }

        // Ship blocks first (shipyard space), then world terrain (world space). The two never overlap:
        // world block collisions don't contain ship shapes.
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
     * Ship-space replacement for {@code Entity.collide}: main sweep, step-up and settle.
     *
     * @return the world-space adjusted movement, or {@code null} if the entity isn't on a resolvable
     *         ship (terrain only then)
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
        // Step-up runs along the entity's local up in ship space; "horizontal" is perpendicular to it
        Direction.Axis upAxis = ctx.up.getAxis();
        Vec3 upUnit = Vec3.atLowerCornerOf(ctx.up.getNormal());
        Vec3 stepVector = upUnit.scale(stepUp);

        // One shape query for all sweeps; null entity so VS2 doesn't add its world-space shapes
        AABB queryBox = shipBox.expandTowards(shipMove.x, shipMove.y, shipMove.z)
                .expandTowards(stepVector.x, stepVector.y, stepVector.z)
                .inflate(1.0E-7D);
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : entity.level().getBlockCollisions(null, queryBox)) {
            if (!shape.isEmpty()) {
                shapes.add(shape);
            }
        }
        if (shapes.isEmpty()) {
            return ctx.shipDirToWorld(shipMove);
        }

        // Shapes.collide ignores shapes the box already overlaps: lift feet that start inside the deck
        // clear first (VS2's own collision does the same). Not while rising: the lift would register
        // as a vertical collision and zero the jump.
        double moveUp = shipMove.dot(upUnit);
        double lift = moveUp > 0.0D ? 0.0D
                : deckOverlapAlongUp(shipBox, shapes, upAxis, ctx.up.getAxisDirection().getStep(), stepUp * 0.5D);
        Vec3 liftVector = Vec3.ZERO;
        if (lift > 0.0D) {
            liftVector = upUnit.scale(lift);
            shipBox = shipBox.move(liftVector);
        }
        // Same for walls: push the body out sideways before sweeping, or it keeps sinking in
        Vec3 pushVector = wallPushOut(shipBox, shapes, upAxis);
        if (pushVector.lengthSqr() > 0.0D) {
            shipBox = shipBox.move(pushVector);
            if (ShipCollisionDebug.shouldLog(entity) && entity instanceof Player) {
                ShipCollisionDebug.log("[wallpush] {} pushed {} out of ship wall",
                        entity.level().isClientSide() ? "C" : "S", pushVector);
            }
        }
        liftVector = liftVector.add(pushVector);

        Vec3 adjusted = shipMove.lengthSqr() == 0.0D ? shipMove : collideWithShapes(shipMove, shipBox, shapes);
        Vec3 lateral = shipMove.subtract(upUnit.scale(moveUp));
        boolean collidedUp = shipMove.get(upAxis) != adjusted.get(upAxis);
        boolean collidedLateral = false;
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != upAxis && shipMove.get(axis) != adjusted.get(axis)) {
                collidedLateral = true;
            }
        }
        boolean grounded = entity.onGround() || (collidedUp && moveUp < 0.0D);
        if (stepUp > 0.0D && grounded && collidedLateral) {
            Vec3 stepped = collideWithShapes(lateral.add(stepVector), shipBox, shapes);
            Vec3 upOnly = collideWithShapes(stepVector, shipBox.expandTowards(lateral.x, lateral.y, lateral.z), shapes);
            if (upOnly.dot(upUnit) < stepUp) {
                Vec3 horizontal = collideWithShapes(lateral, shipBox.move(upOnly), shapes).add(upOnly);
                if (lateralDistanceSqr(horizontal, upUnit) > lateralDistanceSqr(stepped, upUnit)) {
                    stepped = horizontal;
                }
            }
            if (lateralDistanceSqr(stepped, upUnit) > lateralDistanceSqr(adjusted, upUnit)) {
                adjusted = stepped.add(collideWithShapes(upUnit.scale(moveUp - stepped.dot(upUnit)),
                        shipBox.move(stepped), shapes));
            }
        }

        Vec3 world = ctx.shipDirToWorld(adjusted.add(liftVector));
        return world != null ? world : worldMovement;
    }

    /** How far the box's base sits inside ship shapes beneath it along up, up to {@code maxLift}. Walls and shapes above the feet don't count. */
    @Unique
    private static double deckOverlapAlongUp(AABB box, List<VoxelShape> shapes, Direction.Axis upAxis,
                                             int upSign, double maxLift) {
        double feet = upSign > 0 ? box.min(upAxis) : box.max(upAxis);
        double lift = 0.0D;
        for (VoxelShape shape : shapes) {
            for (AABB part : shape.toAabbs()) {
                boolean footprintOverlap = true;
                for (Direction.Axis axis : Direction.Axis.values()) {
                    if (axis != upAxis && (part.max(axis) <= box.min(axis) + 1.0E-7D
                            || part.min(axis) >= box.max(axis) - 1.0E-7D)) {
                        footprintOverlap = false;
                        break;
                    }
                }
                if (!footprintOverlap) {
                    continue;
                }
                double surface = upSign > 0 ? part.max(upAxis) : part.min(upAxis);
                double base = upSign > 0 ? part.min(upAxis) : part.max(upAxis);
                double height = (surface - feet) * upSign;
                if ((base - feet) * upSign < 0.0D && height > 1.0E-7D && height <= maxLift) {
                    lift = Math.max(lift, height);
                }
            }
        }
        return lift;
    }

    /** Overlap below this is contact round-off (the sweeps stop at 1e-7). */
    @Unique
    private static final double WALL_PUSH_EPSILON = 1.0E-4D;
    /** Most a wall pushes per tick: smooth, and never through a thin wall. */
    @Unique
    private static final double MAX_WALL_PUSH = 0.25D;

    /**
     * Sideways (perpendicular to {@code upAxis}) push out of overlapped ship shapes: the shallowest
     * exit per shape, the deepest per axis, zero where walls push both ways. Capped at
     * {@link #MAX_WALL_PUSH}. Floors and ceilings are left to the lift.
     */
    @Unique
    private static Vec3 wallPushOut(AABB box, List<VoxelShape> shapes, Direction.Axis upAxis) {
        double[] pos = new double[3];
        double[] neg = new double[3];
        for (VoxelShape shape : shapes) {
            for (AABB part : shape.toAabbs()) {
                double bestDepth = Double.MAX_VALUE;
                int bestAxis = -1;
                int bestSign = 0;
                boolean overlapping = true;
                double upDepth = Double.MAX_VALUE;
                for (Direction.Axis axis : Direction.Axis.values()) {
                    double intoPositive = part.max(axis) - box.min(axis); // push + to clear it
                    double intoNegative = box.max(axis) - part.min(axis); // push - to clear it
                    if (intoPositive <= WALL_PUSH_EPSILON || intoNegative <= WALL_PUSH_EPSILON) {
                        overlapping = false;
                        break;
                    }
                    if (axis == upAxis) {
                        upDepth = Math.min(intoPositive, intoNegative);
                        continue;
                    }
                    if (intoPositive < bestDepth) {
                        bestDepth = intoPositive;
                        bestAxis = axis.ordinal();
                        bestSign = 1;
                    }
                    if (intoNegative < bestDepth) {
                        bestDepth = intoNegative;
                        bestAxis = axis.ordinal();
                        bestSign = -1;
                    }
                }
                // Shallowest along up: a floor or ceiling, handled by the lift and depenetration
                if (!overlapping || bestAxis < 0 || upDepth <= bestDepth) {
                    continue;
                }
                if (bestSign > 0) {
                    pos[bestAxis] = Math.max(pos[bestAxis], bestDepth);
                } else {
                    neg[bestAxis] = Math.max(neg[bestAxis], bestDepth);
                }
            }
        }
        double[] push = new double[3];
        for (int i = 0; i < 3; i++) {
            if (pos[i] > 0.0D && neg[i] > 0.0D) {
                continue; // walls on both sides: no way out along this axis
            }
            push[i] = Mth.clamp(pos[i] - neg[i], -MAX_WALL_PUSH, MAX_WALL_PUSH);
        }
        return new Vec3(push[0], push[1], push[2]);
    }

    /** Squared length of {@code v} perpendicular to the unit vector {@code up}. */
    @Unique
    private static double lateralDistanceSqr(Vec3 v, Vec3 up) {
        double along = v.dot(up);
        return v.lengthSqr() - along * along;
    }

    /**
     * Terrain collision under arbitrary gravity, with slope standing. The walking part is a vanilla
     * sweep with step-up; the gravity part is a ray, cut at the contact when the gravity is within
     * {@code maxTerrainStandAngle} of world-down (else it deflects and slides like vanilla).
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

        // Gravity part (falling) and the rest (walking/jumping)
        double along = worldMovement.dot(gravity);
        Vec3 gravityPart = along > 1.0E-9D ? gravity.scale(along) : Vec3.ZERO;
        Vec3 walkPart = worldMovement.subtract(gravityPart);

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

        AABB movedBox = box.move(rest);
        Vec3 gravityAdjusted = gravityPart.lengthSqr() == 0.0D
                ? gravityPart : collideWithShapes(gravityPart, movedBox, shapes);
        if (gravityPart.y != 0.0D && gravityAdjusted.y != gravityPart.y) {
            // Within the standing limit, stop the whole ray at the contact
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
        // Arbitrary gravity: no transformation (ship collision handles it)
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
        // Arbitrary gravity: no transformation (ship collision handles it)
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
        // Arbitrary gravity: skipped
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
        // Arbitrary gravity: skipped
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
        
        // Arbitrary gravity: skipped
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
        
        // Arbitrary gravity: skipped
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
        if (entity == null) {
            return collideWithShapes(movement, entityBoundingBox, collisions);
        }

        if (entity instanceof Player && ShipCollisionDebug.shouldLog(entity)) {
            ShipCollisionDebug.log("[collide] ENTER player isArbitrary={} gravNearestDir={} onGround={} worldMove={}",
                    GravityChangerAPI.isArbitraryGravity(entity), GravityChangerAPI.getGravityDirection(entity),
                    entity.onGround(), movement);
        }

        // Active ship gravity: collide in ship space, where the ship's blocks are axis-aligned.
        // Strict check: entities merely near a ship keep vanilla + VS2 collision.
        if (GravityChangerAPI.isShipGravityPhysics(entity)) {
            Vec3 shipResult = collideOnShip(entity, movement, entityBoundingBox);
            if (shipResult != null) {
                if (ShipCollisionDebug.shouldLog(entity)) {
                    ShipCollisionDebug.log("[collide] ARBITRARY+onShip worldMove={} -> shipResult={} (dY {} -> {})",
                            movement, shipResult, movement.y, shipResult.y);
                }
                return shipResult;
            }
            // Not on a ship (or no transform): plain world collision
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
     * Collide an entity on a VS2 ship in ship space: blocks queried in shipyard coordinates, the
     * box and movement transformed there, swept, and converted back.
     *
     * @return the world-space adjusted movement, or {@code null} if not on a resolvable ship
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
        // Null entity: only the raw shipyard blocks, not VS2's world-space shapes
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