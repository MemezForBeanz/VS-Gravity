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
 * Makes the vanilla steering layer gravity-aware.
 *
 * <p>A mob's yaw and its travel() input are in the gravity-local frame, but MOVE_TO steering
 * computes the wanted-position delta in world space - so on a wall or ceiling the mob walks in a
 * direction rotated away from where its AI is steering. The squared distance is rotation-invariant,
 * so rotating the delta into the local frame right before the yaw math fixes the steering yaw AND
 * the step-height jump check without otherwise touching vanilla control flow.
 */
@Mixin(MoveControl.class)
public abstract class MoveControlMixin {

    @Shadow
    @Final
    protected Mob mob;

    @Inject(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;atan2(DD)D")
    )
    private void gravityapi$rotateMoveToDelta(CallbackInfo ci,
            @Local(ordinal = 0) LocalDoubleRef dx,
            @Local(ordinal = 1) LocalDoubleRef dz,
            @Local(ordinal = 2) LocalDoubleRef dy) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.mob);
        if (gravityDirection == Direction.DOWN) {
            return;
        }

        Vec3 local = RotationUtil.vecWorldToPlayer(dx.get(), dy.get(), dz.get(), gravityDirection);
        dx.set(local.x);
        dy.set(local.y);
        dz.set(local.z);
    }

    /**
     * The partial-block hop check samples the collision shape at the mob's world block position and
     * compares against world Y - meaningless off DOWN gravity and a source of jump spam. Only the
     * (now frame-correct) step-height condition should drive jumps there.
     */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
        )
    )
    private VoxelShape gravityapi$skipWorldSpaceFeetShape(BlockState state, BlockGetter level, BlockPos pos, Operation<VoxelShape> original) {
        if (GravityChangerAPI.getGravityDirection(this.mob) != Direction.DOWN) {
            return Shapes.empty();
        }

        return original.call(state, level, pos);
    }

    /**
     * STRAFE walkability probes world coordinates with a local-frame offset, so strafing mobs
     * (skeletons, pillagers) get their strafe direction randomly reset off DOWN gravity.
     */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/control/MoveControl;isWalkable(FF)Z"
        )
    )
    private boolean gravityapi$skipWorldSpaceWalkableCheck(MoveControl self, float relX, float relZ, Operation<Boolean> original) {
        if (GravityChangerAPI.getGravityDirection(this.mob) != Direction.DOWN) {
            return true;
        }

        return original.call(self, relX, relZ);
    }
}
