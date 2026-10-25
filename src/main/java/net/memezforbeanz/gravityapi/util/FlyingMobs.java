package net.memezforbeanz.gravityapi.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;

/** Which mobs are flying freely (no ground under them) right now. */
public final class FlyingMobs {

    private FlyingMobs() {
    }

    /**
     * Ghasts, phantoms, vexes, allays and other flying-navigation mobs always (touching a block
     * doesn't ground them); bats, bees and parrots only while airborne. Riders are never flying.
     */
    public static boolean isFlying(Entity entity) {
        if (!(entity instanceof Mob mob) || entity.isPassenger()) {
            return false;
        }
        if (isAlwaysFlying(mob)) {
            return true;
        }
        if (entity.onGround()) {
            return false;
        }
        if (mob instanceof FlyingAnimal animal) {
            return animal.isFlying();
        }
        if (mob instanceof Bat bat) {
            return !bat.isResting();
        }
        return false;
    }

    /** Mobs with no ground mode: bumping into a block mid-flight must not give them a face. */
    private static boolean isAlwaysFlying(Mob mob) {
        if (mob instanceof FlyingMob || mob instanceof Vex) {
            return true;
        }
        // Allays and modded flyers; FlyingAnimals and bats can perch, so they're judged by state
        return !(mob instanceof FlyingAnimal) && !(mob instanceof Bat)
                && mob.getNavigation() instanceof FlyingPathNavigation;
    }
}
