package net.memezforbeanz.gravityapi.rotation;
/** Fast negative test. Monotonic for the lifetime of a level; empty chunks remain independently cheap. */
public interface RotationLevel {
    boolean gravityapi$hasRotations();
    void gravityapi$markRotations();
}
