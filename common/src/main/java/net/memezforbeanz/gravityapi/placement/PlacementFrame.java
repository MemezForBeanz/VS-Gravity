package net.memezforbeanz.gravityapi.placement;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

/** A nested, exception-safe placement scope; captured before VS2 temporarily changes the player. */
public final class PlacementFrame {
    private static final ThreadLocal<PlacementFrame> CURRENT = new ThreadLocal<>();
    // Fast path for the hot entity getters: skip the ThreadLocal unless some placement is selecting.
    private static final java.util.concurrent.atomic.AtomicInteger INPUT_SCOPES = new java.util.concurrent.atomic.AtomicInteger();
    private final Quaternionf playerToWorld;
    private final net.minecraft.world.entity.Entity player;
    private Boolean localInputs;
    public final float yaw;
    public final float pitch;
    private boolean resolved;
    public CubeRotation rotation;
    public CubeRotation layerRotation;
    public net.minecraft.world.level.block.state.BlockState fallback;
    public boolean rejectedByCanPlace;
    public Vec3 gridLook;
    public Vec3 gridHeading;

    private PlacementFrame(net.minecraft.world.entity.Entity player, GravityDirection gravity, float yaw, float pitch) {
        this.player = player;
        this.playerToWorld = gravity.getPlayerToWorldRotation();
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static PlacementFrame current() { return CURRENT.get(); }
    public static void restore(PlacementFrame previous) {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    public static void begin(BlockPlaceContext context) {
        CURRENT.remove();
        if (context.getPlayer() == null || !net.memezforbeanz.gravityapi.config.GravityServerConfig.omniPlacement()) return;
        GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(context.getPlayer());
        Quaterniond anchor = VS2Helper.anchoredShipToWorld(gravity, false);
        if (anchor != null) gravity = gravity.withShipRotation(anchor);
        CURRENT.set(new PlacementFrame(context.getPlayer(), gravity, context.getPlayer().getYRot(), context.getPlayer().getXRot()));
    }

    /** Read-only getter view, restricted to synchronous state selection for this one player. */
    public static PlacementFrame inputs(Object entity) {
        if (INPUT_SCOPES.get() == 0) return null;
        PlacementFrame frame = current();
        return frame != null && frame.localInputs != null && frame.player == entity ? frame : null;
    }

    public <T> T withInputs(boolean local, java.util.function.Supplier<T> selection) {
        Boolean previous = localInputs;
        localInputs = local;
        INPUT_SCOPES.incrementAndGet();
        try {
            return selection.get();
        } finally {
            INPUT_SCOPES.decrementAndGet();
            localInputs = previous;
        }
    }

    public Vec3 inputLook() { return localInputs ? Vec3.directionFromRotation(pitch, yaw) : gridLook; }
    public float inputYaw() {
        if (localInputs) return yaw;
        return (float) Math.toDegrees(Math.atan2(-gridLook.x, gridLook.z));
    }
    public float inputPitch() {
        if (localInputs) return pitch;
        return (float) -Math.toDegrees(Math.atan2(gridLook.y, Math.sqrt(gridLook.horizontalDistanceSqr())));
    }
    public net.minecraft.core.Direction inputHorizontal() {
        if (localInputs) return net.minecraft.core.Direction.fromYRot(yaw);
        return Math.abs(gridHeading.x) > Math.abs(gridHeading.z)
                ? (gridHeading.x > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
                : (gridHeading.z > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
    }

    public void resolve(BlockPlaceContext context) {
        if (resolved) return;
        resolved = true;
        Quaternionf frame = new Quaternionf(playerToWorld);
        Object ship = VS2Helper.getShipAtBlock(context.getLevel(), context.getClickedPos());
        if (ship != null) {
            Quaterniond target = VS2Helper.getPlacementShipRotation(ship);
            if (target == null) {
                // An unreadable target must not accidentally be treated as terrain.
                gridLook = Vec3.directionFromRotation(pitch, yaw);
                gridHeading = Vec3.directionFromRotation(0, yaw);
                return;
            }
            frame = new Quaternionf(target).conjugate().mul(frame);
        }
        frame.normalize();
        rotation = CubeRotation.nearest(frame);
        gridLook = new Vec3(frame.transform(Vec3.directionFromRotation(pitch, yaw).toVector3f()));
        gridHeading = new Vec3(frame.transform(Vec3.directionFromRotation(0, yaw).toVector3f()));
        if (gridHeading.horizontalDistanceSqr() < 1.0E-8) {
            gridHeading = new Vec3(frame.transform(Vec3.directionFromRotation(0, yaw + 90).toVector3f()));
        }
    }
}
