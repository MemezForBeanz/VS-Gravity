package net.memezforbeanz.gravityapi.rotation;

/** Direct capability reference avoids LazyOptional allocation in shape/render hooks. */
public interface RotationChunk {
    RotationStorage gravityapi$rotations();
    void gravityapi$rotations(RotationStorage storage);
}
