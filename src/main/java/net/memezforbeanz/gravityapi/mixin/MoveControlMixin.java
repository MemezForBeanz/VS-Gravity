package net.memezforbeanz.gravityapi.mixin;


import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalDoubleRef;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Makes vanilla steering gravity-aware: MOVE_TO computes the target delta in world space while
 * yaw and travel input are in the gravity frame, so it is rotated into the local frame before
 * the yaw math.
 */
@Mixin(MoveControl.class)
public abstract class MoveControlMixin {

    @Shadow
    @Final
    protected Mob mob;

    /**
     * MOVE_TO steers toward a world-space delta while yaw and travel input are in the gravity frame:
     * turn the delta into the mob's frame (ship-locked on a ship) for the yaw and the later jump
     * check. Wraps the atan2 itself: its arguments are already loaded when it is invoked.
     */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;atan2(DD)D"))
    private double gravityapi$localMoveToYaw(double z, double x, Operation<Double> original,
            @Local(ordinal = 0) LocalDoubleRef dx,
            @Local(ordinal = 1) LocalDoubleRef dz,
            @Local(ordinal = 2) LocalDoubleRef dy) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(this.mob);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return original.call(z, x);
        }
        Vec3 local = gravityDirection.vecWorldToPlayer(new Vec3(dx.get(), dy.get(), dz.get()));
        dx.set(local.x);
        dy.set(local.y);
        dz.set(local.z);
        return original.call(local.z, local.x);
    }

    /** The partial-block hop check compares world Y, meaningless off DOWN gravity: only the step-height condition drives jumps there. */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
        )
    )
    private VoxelShape gravityapi$skipWorldSpaceFeetShape(BlockState state, BlockGetter level, BlockPos pos, Operation<VoxelShape> original) {
        if (!GravityChangerAPI.getGravityDirectionVec(this.mob).equals(GravityDirection.DOWN)) {
            return Shapes.empty();
        }

        return original.call(state, level, pos);
    }

    /** STRAFE walkability probes world coordinates with a local offset: skip it off DOWN gravity. */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/control/MoveControl;isWalkable(FF)Z"
        )
    )
    private boolean gravityapi$skipWorldSpaceWalkableCheck(MoveControl self, float relX, float relZ, Operation<Boolean> original) {
        if (!GravityChangerAPI.getGravityDirectionVec(this.mob).equals(GravityDirection.DOWN)) {
            return true;
        }

        return original.call(self, relX, relZ);
    }
}
