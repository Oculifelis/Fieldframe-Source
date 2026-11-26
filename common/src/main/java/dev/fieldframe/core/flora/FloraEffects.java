package dev.fieldframe.core.flora;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Decides FLORA effects from the interaction matrix; which pair gives which effect comes from {@link FloraPairs} (data). Pure functions: the platform supplies context and applies the
 * returned actions. Probabilities and formulas follow FLORA's ArmorEffectsManager; deliberate changes (no world
 * damage, reduced self-combustion) are listed in docs/features/flora.md.
 */
public final class FloraEffects {
    private FloraEffects() {
    }

    /** Explosion strength for Redstone-Glowstone self-combustion (original 20, with block damage). */
    public static final float SELF_COMBUSTION_STRENGTH = 4f;
    /** Radius (horizontal) and height used for pulse and pacify targets (original 20 x 3 x 20 box). */
    public static final int TARGET_RANGE = 20;
    public static final int TARGET_HEIGHT = 3;

    public enum Potion {
        NIGHT_VISION, SPEED, SLOWNESS, HASTE, JUMP_BOOST
    }

    public enum PulseKind {
        /** Coal-Redstone: 3 fire damage. */
        DAMAGE,
        /** Pyrotheum-Redstone: ignite 10 s. */
        IGNITE,
        /** Cryotheum-Redstone: Slowness IV, 10 s. */
        SLOW,
        /** Mana-Redstone: cure effects. */
        ANTIDOTE,
        /** Ender-Redstone: random teleport ±5, +5 up. */
        TELEPORT
    }

    public sealed interface Action {
    }

    public record ApplyPotion(Potion potion, int amplifier, int ticks) implements Action {
    }

    public record CureEffects() implements Action {
    }

    public record RefillAir() implements Action {
    }

    public record Food(int delta, float saturation) implements Action {
    }

    public record ColdDamage(float amount) implements Action {
    }

    public record Pulse(PulseKind kind) implements Action {
    }

    public record Pacify() implements Action {
    }

    /** Set the max-health offset (relative to the base, via a removable modifier); 0 clears it. */
    public record MaxHealthOffset(double offset) implements Action {
    }

    public record SelfCombustion(float strength) implements Action {
    }

    private static int level(float intensity) {
        return Math.max(0, (int) Math.log10(Math.max(1, intensity)));
    }

    /** Effects decided once per player tick (server side). */
    public static List<Action> tick(float[][] mx, long gameTime, boolean coldBiome, RandomGenerator rng) {
        List<Action> out = new ArrayList<>();
        float i;
        if ((i = FloraPairs.intensity(mx, "night_vision")) > 0 && rng.nextInt(100) < i) {
            out.add(new ApplyPotion(Potion.NIGHT_VISION, 0, 220));
        }
        if ((i = FloraPairs.intensity(mx, "max_health_flux")) > 0) {
            if (gameTime % 600 == 0) {
                out.add(new MaxHealthOffset(Math.max(-16, i * 0.005 * rng.nextGaussian())));
            }
        } else {
            out.add(new MaxHealthOffset(0));
        }
        if ((i = FloraPairs.intensity(mx, "antidote")) > 0 && rng.nextInt(2500) < i) {
            out.add(new CureEffects());
        }
        if ((i = FloraPairs.intensity(mx, "water_breathing")) > 0 && rng.nextInt(10000) < i) {
            out.add(new RefillAir());
        }
        if ((i = FloraPairs.intensity(mx, "self_combustion")) > 0 && rng.nextInt(720000) < i) {
            out.add(new SelfCombustion(SELF_COMBUSTION_STRENGTH));
        }
        if ((i = FloraPairs.intensity(mx, "hunger_gain")) > 0 && rng.nextInt(200000) < i) {
            out.add(new Food(4, 0.3f)); // an apple's worth
        }
        if ((i = FloraPairs.intensity(mx, "cold_damage")) > 0 && coldBiome && rng.nextInt(1000) < i) {
            out.add(new ColdDamage(1f));
        }
        pulse(out, mx, "antidote_pulse", PulseKind.ANTIDOTE, rng);
        pulse(out, mx, "damage_pulse", PulseKind.DAMAGE, rng);
        pulse(out, mx, "ignite_pulse", PulseKind.IGNITE, rng);
        pulse(out, mx, "slowness_pulse", PulseKind.SLOW, rng);
        pulse(out, mx, "teleport_pulse", PulseKind.TELEPORT, rng);
        if ((i = FloraPairs.intensity(mx, "hunger_loss")) > 0 && rng.nextInt(50000) < i) {
            out.add(new Food(-1, 0));
        }
        if ((i = FloraPairs.intensity(mx, "speed")) > 0) {
            out.add(new ApplyPotion(Potion.SPEED, level(i), 2));
        }
        if ((i = FloraPairs.intensity(mx, "slowness")) > 0) {
            out.add(new ApplyPotion(Potion.SLOWNESS, level(i), 2));
        }
        if ((i = FloraPairs.intensity(mx, "mining_speed")) > 0) {
            out.add(new ApplyPotion(Potion.HASTE, level(i), 2));
        }
        if ((i = FloraPairs.intensity(mx, "long_jump")) > 0) {
            out.add(new ApplyPotion(Potion.JUMP_BOOST, level(i), 2));
        }
        if ((i = FloraPairs.intensity(mx, "pacify")) > 0 && rng.nextInt(500) < i) {
            out.add(new Pacify());
        }
        return out;
    }

    private static void pulse(List<Action> out, float[][] mx, String key, PulseKind kind, RandomGenerator rng) {
        float i = FloraPairs.intensity(mx, key);
        if (i > 0 && rng.nextInt(5000) < i) {
            out.add(new Pulse(kind));
        }
    }

    /** What happens when the wearer is about to take damage. */
    public record HurtResult(boolean cancel, int teleportSelfRange, int teleportAttackerRange, int fireImmunityTicksAdded) {
        static final HurtResult NONE = new HurtResult(false, 0, 0, 0);
    }

    /**
     * @param fireCooldown current per-player fire protection cooldown (ticks); protection applies while it is below
     *                     {@code intensity * 10} and each use adds 40 ticks (original rule).
     */
    public static HurtResult onWearerHurt(float[][] mx, float amount, float health, boolean attackerIsMob, boolean fireDamage, long fireCooldown) {
        float i;
        if ((i = FloraPairs.intensity(mx, "escape_teleport")) > 0 && health - amount <= 4) {
            return new HurtResult(true, Math.max(1, (int) (3 * i)), 0, 0);
        }
        if ((i = FloraPairs.intensity(mx, "teleport_attacker")) > 0 && attackerIsMob) {
            return new HurtResult(true, 0, Math.max(1, (int) i), 0);
        }
        if ((i = FloraPairs.intensity(mx, "fire_protection")) > 0 && fireDamage && fireCooldown < i * 10) {
            return new HurtResult(true, 0, 0, 40);
        }
        return HurtResult.NONE;
    }

    /** Wearer hits something: seconds of fire to set, and explosion strength if the target already burns (0 = none). */
    public record AttackResult(int igniteSeconds, float explosionIfBurning) {
    }

    public static AttackResult onWearerAttack(float[][] mx) {
        float ig = FloraPairs.intensity(mx, "ignite_enemies");
        float ex = FloraPairs.intensity(mx, "explode_burning");
        return new AttackResult(ig > 0 ? Math.max(1, (int) ig) : 0, ex > 0 ? (float) Math.sqrt(ex) : 0);
    }

    /** Modified fall distance (Pyrotheum-Pyrotheum increases it, Ender-Pyrotheum divides it). */
    public static float onWearerFall(float[][] mx, float distance) {
        float i;
        float d = distance;
        if ((i = FloraPairs.intensity(mx, "fall_damage_up")) > 0 && d > 2) {
            d += (float) Math.sqrt(i);
        }
        if ((i = FloraPairs.intensity(mx, "fall_damage_down")) > 0) {
            d /= Math.max(1, i);
        }
        return d;
    }

    /** Blink distance (Ender-Ender), 0 if unavailable. */
    public static int blinkRange(float[][] mx) {
        return (int) Math.min(64, FloraPairs.intensity(mx, "blink"));
    }

    /** Maximum depth for sinking through the floor while sneaking (Ender-Glowstone), 0 if unavailable. */
    public static int sinkDepth(float[][] mx) {
        return (int) Math.min(64, FloraPairs.intensity(mx, "sink"));
    }

    /** Fireball cooldown in ticks (Coal-Coal), or -1 if unavailable. */
    public static int fireballCooldown(float[][] mx) {
        float i = FloraPairs.intensity(mx, "fireball");
        return i > 0 ? (int) Math.max(20, 600 - i) : -1;
    }
}
