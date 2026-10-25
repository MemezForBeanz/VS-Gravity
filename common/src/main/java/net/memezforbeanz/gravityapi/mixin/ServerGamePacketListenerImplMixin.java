package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    private static double gravitychanger$onPlayerMove_playerMovementY;
    
    @Shadow
    public ServerPlayer player;
    
    @Shadow
    private static double clampHorizontal(double d) {return 0;}
    
    ;
    
    @Shadow
    private static double clampVertical(double d) {return 0;}
    
    ;
    
    @Shadow
    private double lastGoodX;
    
    @Shadow
    private double lastGoodY;
    
    @Shadow
    private double lastGoodZ;

    @ModifyArg(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
        )
    )
    private Vec3 modify_onPlayerMove_move_1(Vec3 vec3d) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.player);
        if (gravityDirection == Direction.DOWN) {
            return vec3d;
        }

        return RotationUtil.vecWorldToPlayer(vec3d, gravityDirection);
    }

    /**
     * Relax the "moved wrongly" check around gravity flips and under non-down/ship gravity, where
     * client and server briefly disagree on the gravity frame and the teleport-back rubber-bands.
     * Normal DOWN gravity keeps the vanilla threshold.
     */
    @ModifyConstant(
        method = "handleMovePlayer",
        constant = @Constant(doubleValue = 0.0625D)
    )
    private double gravitychanger$relaxMovedWronglyCheck(double original) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.player);
        if (gravityDirection != Direction.DOWN) {
            return Double.MAX_VALUE;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponent(this.player);
        if (comp != null) {
            if (comp.isShipGravityPhysics()) {
                return Double.MAX_VALUE;
            }
            long sinceFlip = this.player.level().getGameTime() - comp.lastGravityChangeGameTime;
            if (sinceFlip >= 0 && sinceFlip < 20) {
                return Double.MAX_VALUE;
            }
        }
        return original;
    }

    @ModifyArg(
        method = "handleMoveVehicle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
        ),
        index = 1
    )
    private Vec3 modify_onVehicleMove_move_0(Vec3 vec3d) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.player);
        if (gravityDirection == Direction.DOWN) {
            return vec3d;
        }
        
        return RotationUtil.vecWorldToPlayer(vec3d, gravityDirection);
    }

    @WrapOperation(
        method = "noBlocksAround",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;expandTowards(DDD)Lnet/minecraft/world/phys/AABB;"
        )
    )
    private AABB modify_onVehicleMove_move_0(AABB instance, double x, double y, double z, Operation<AABB> original) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.player);
        Vec3 argVec = new Vec3(x, y, z);
        argVec = RotationUtil.vecWorldToPlayer(argVec, gravityDirection);
        return original.call(instance, argVec.x, argVec.y, argVec.z);
    }
    
}
