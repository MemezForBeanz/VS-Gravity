package net.memezforbeanz.gravityapi.rotation;

import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;

/** Read-only local neighbour coordinates for canSurvive. Concrete Level casts are not supported. */
public record LocalNeighbourView(LevelReader level, BlockPos origin, CubeRotation rotation) implements InvocationHandler {
    public BlockPos world(BlockPos pos) {
        Vec3 offset = rotation.apply(Vec3.atLowerCornerOf(pos.subtract(origin)));
        return origin.offset((int) offset.x, (int) offset.y, (int) offset.z);
    }
    public LevelReader proxy() { return (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(), new Class<?>[]{LevelReader.class}, this); }
    public static LocalNeighbourView of(Object object) {
        return object != null && Proxy.isProxyClass(object.getClass()) && Proxy.getInvocationHandler(object) instanceof LocalNeighbourView view ? view : null;
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object[] mapped = args == null ? null : args.clone();
        if (mapped != null) for (int i = 0; i < mapped.length; i++) if (mapped[i] instanceof BlockPos pos) mapped[i] = world(pos);
        try { return method.invoke(level, mapped); } catch (InvocationTargetException exception) { throw exception.getCause(); }
    }
}
