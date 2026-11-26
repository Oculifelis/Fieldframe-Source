package dev.fieldframe.api.world;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;

import java.util.List;

/**
 * Safe world-interaction services for one chassis, provided by the platform. All work-strategy modules
 * (breaker, placer, interaction head, crop tender, excavator, pump, collector, ejector, mover) go through this
 * interface; none touches the level directly.
 *
 * <p>Platform guarantees:
 * <ul>
 *   <li><b>Actor identity:</b> every action is performed by a per-chassis actor derived from the chassis owner
 *       (see {@link #actorName()}). Platform protection events (block break/place, right-click) are fired for that
 *       actor; a cancelled event yields {@link Outcome#PROTECTED} and changes nothing.</li>
 *   <li><b>Bounds:</b> targets are bounded by {@link WorkTarget}; unloaded positions return {@link Outcome#UNLOADED}
 *       and are never loaded.</li>
 *   <li><b>Loot:</b> drops come from the platform loot tables with the given tool; tool durability is applied.</li>
 *   <li><b>No duplication:</b> a successful break returns its drops exactly once; callers must store them.</li>
 * </ul>
 * Server policy may disable a whole action family; disabled actions return {@link Outcome#DISABLED_BY_POLICY}.
 */
public interface WorldActions {
    enum Outcome {
        OK, UNLOADED, PROTECTED, UNBREAKABLE, AIR, NOT_REPLACEABLE, NOT_PLACEABLE, WRONG_TOOL, NOTHING_TO_DO,
        BLOCKED, DISABLED_BY_POLICY;

        public boolean ok() {
            return this == OK;
        }
    }

    record Drop(ItemVariant variant, int count) {
        public Drop {
            if (variant == null || count < 1) {
                throw new IllegalArgumentException("drop");
            }
        }
    }

    /**
     * What is at a target. {@code hardness} is negative for unbreakable blocks. {@code plantItem} is the item that
     * places this block again (seed for crops), or null. {@code fluid} is set for fluid blocks, with
     * {@code fluidSource} true for source blocks.
     */
    record BlockInfo(Outcome access, FfId block, boolean air, boolean replaceable, float hardness,
                     boolean hasBlockEntity, boolean crop, boolean mature, ItemVariant plantItem,
                     FfId fluid, boolean fluidSource, boolean flowingWater) {
        public boolean unbreakable() {
            return hardness < 0;
        }

        public static BlockInfo unloaded() {
            return new BlockInfo(Outcome.UNLOADED, null, false, false, -1, false, false, false, null, null, false, false);
        }
    }

    /** Result of a break. {@code toolAfter} is the damaged tool, or null if it broke or none was used. */
    record BreakResult(Outcome outcome, List<Drop> drops, ItemVariant toolAfter) {
        public BreakResult {
            drops = drops == null ? List.of() : List.copyOf(drops);
        }

        public static BreakResult fail(Outcome o, ItemVariant tool) {
            return new BreakResult(o, List.of(), tool);
        }
    }

    /** Result of a right-click style use: the held stack afterwards plus any extra items handed back. */
    record UseResult(Outcome outcome, ItemVariant heldAfter, int heldAfterCount, List<Drop> extra) {
        public UseResult {
            extra = extra == null ? List.of() : List.copyOf(extra);
        }
    }

    record EntitySample(int count, double totalHorizontalSpeed) {
    }

    /** Result of an attack: mobs hit and killed, and the weapon afterwards (null when it broke or none was used). */
    record AttackResult(Outcome outcome, int hits, int kills, ItemVariant weaponAfter) {
    }

    /** An animal standing on a Treadmill: its entity type, whether it is a baby, and its movement speed attribute. */
    record WalkingAnimal(dev.fieldframe.api.id.FfId type, boolean baby, double speed) {
    }

    /** Display name of the acting identity, e.g. {@code [Fieldframe] Steve}. */
    String actorName();

    BlockInfo probe(WorkTarget t);

    /**
     * Temperature in kelvin of what is at the target, from the temperature table (blocks, lit furnaces and
     * campfires, fluids, then the biome's ambient temperature); -1 when the target is not loaded or unknown.
     */
    default int temperature(WorkTarget t) {
        return -1;
    }

    /**
     * Breaks the block as the actor using {@code tool} (null = empty hand). With {@code requireCorrectTool}, blocks
     * that would not drop with this tool return {@link Outcome#WRONG_TOOL} and are left in place.
     */
    BreakResult breakBlock(WorkTarget t, ItemVariant tool, boolean requireCorrectTool);

    /** Places one {@code item} at the target as the actor. On OK the caller must remove exactly one item. */
    Outcome place(WorkTarget t, ItemVariant item);

    /** Uses {@code count} x {@code held} on the target block (right click on the face toward the chassis). */
    UseResult use(WorkTarget t, ItemVariant held, int count);

    /** Removes a fluid source block at the target and returns its fluid (1000 mB), or null. */
    FluidVariant drainSource(WorkTarget t, boolean simulate);

    /** Places a source block of {@code fluid} into an air/replaceable target. */
    Outcome placeFluid(WorkTarget t, FluidVariant fluid);

    /**
     * Moves up to {@code max} items from item entities within {@code range} blocks in front of {@code face} into
     * {@code into}; entities shrink only by what was actually inserted. Items reserved for another player are skipped.
     */
    int collectItems(LocalFace face, int range, ItemStore into, int max);

    /** Spawns an item entity just outside {@code face}. BLOCKED if that space is solid. */
    Outcome dropItem(LocalFace face, ItemVariant v, int count);

    /**
     * Pushes (or pulls) living entities within {@code range} in front of {@code face}. Players only if
     * {@code includePlayers}. Returns how many entities were moved (at most {@code maxEntities}).
     */
    int moveEntities(LocalFace face, int range, double strength, boolean pull, boolean includePlayers, int maxEntities);

    /** Counts entities of a kind ("any", "player", "mob", "animal", "item") within range in front of face. */
    int countEntities(LocalFace face, int range, String kind);

    /**
     * Attacks up to {@code maxTargets} mobs within {@code range} blocks in front of {@code face} as the actor holding
     * {@code weapon} (null = empty hand), with the weapon's damage, enchantments and wear. Never players or tamed
     * animals; named mobs only when {@code named}. Drops and experience fall in the world as usual.
     */
    default AttackResult attackMobs(LocalFace face, int range, ItemVariant weapon, boolean named, int maxTargets) {
        return new AttackResult(Outcome.DISABLED_BY_POLICY, 0, 0, weapon);
    }

    /**
     * The animal in the block in front of {@code face} (a Treadmill's belt), kept walking in place there; null when
     * there is none or the platform has no entities.
     */
    default WalkingAnimal animalOnTreadmill(LocalFace face) {
        return null;
    }

    /** Samples moving living entities in front of a face (at most {@code maxEntities}). */
    EntitySample sampleMotion(LocalFace face, int range, int maxEntities);

    /** Shows a short-lived marker particle at a target (work-area preview). */
    /**
     * Rolls the vanilla fishing loot table once as this actor, fishing into the water in front of {@code face}
     * ({@code luck} as Luck of the Sea). Empty when the platform offers none.
     */
    default List<Drop> fishingLoot(LocalFace face, int luck) {
        return List.of();
    }

    /** Removes experience orbs within {@code range} blocks in front of {@code face}; returns the points taken (at most max). */
    default int collectExperience(LocalFace face, int range, int max) {
        return 0;
    }

    /**
     * Gives every player within {@code range} blocks of the chassis the effect for {@code ticks} (refreshing a shorter
     * one). Returns how many players were affected; 0 when the effect is unknown or the platform offers none.
     */
    default int applyEffect(int range, FfId effect, int ticks, int amplifier) {
        return 0;
    }

    default void mark(WorkTarget t) {
    }
}
