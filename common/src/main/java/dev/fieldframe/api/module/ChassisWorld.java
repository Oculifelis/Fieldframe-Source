package dev.fieldframe.api.module;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.process.RecipeSource;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

/**
 * The platform-provided view of the world around one chassis, in chassis-local faces. Only immediately
 * adjacent, loaded blocks are reachable. Implementations must never force-load chunks and must wrap
 * third-party handlers so that reported amounts are clamped to what was requested.
 *
 * <p>A headless implementation ({@code SimulatedWorld}) backs unit tests and the manual test bench.
 */
public interface ChassisWorld {
    long gameTime();

    /** False when the neighbouring block's chunk is not loaded; ports report NEIGHBOR_UNLOADED. */
    boolean isNeighborLoaded(LocalFace face);

    /** The adjacent inventory as seen through its face toward this chassis, or null. */
    ItemStore neighborItems(LocalFace face);

    FluidStore neighborFluids(LocalFace face);

    EnergyStore neighborEnergy(LocalFace face);

    /** Redstone power 0..15 received on this face. */
    int redstoneInput(LocalFace face);

    TagLookup tags();

    RecipeSource recipes();

    /** Burn time in vanilla furnace ticks, 0 if not a fuel. */
    int fuelBurnTicks(ItemVariant v);

    /** Container remainder after burning one item (e.g. empty bucket), or null. */
    default ItemVariant fuelRemainder(ItemVariant v) {
        return null;
    }

    /** Whether the block in front of the given face has an unobstructed view of the sky. */
    boolean canSeeSky(LocalFace face);

    boolean isDaytime();

    boolean isRaining();

    /** Whether it is thundering here (wind generators). Default: never. */
    default boolean isThundering() {
        return false;
    }

    int skyDarkening();

    /** {damage, maximum damage} of an item that wears out (tools, armour), or null when it has no durability. */
    default int[] durability(ItemVariant v) {
        return null;
    }

    /** The same item with {@code damage} (0 is like new), or null where the platform cannot change it. */
    default ItemVariant withDamage(ItemVariant v, int damage) {
        return null;
    }

    /**
     * The variant of the same material from the first preferred mod (namespace) for the item's common tag
     * ({@code c:ingots/copper}, nuggets, dusts, plates, gears, gems, raw materials, storage blocks), or null when the
     * item is in no such tag, carries extra data, or no preferred mod has one.
     */
    default ItemVariant unified(ItemVariant v, java.util.List<String> preferredNamespaces) {
        return null;
    }

    /** Moon phase in the game's order: 0 full moon, 4 new moon, 7 waxing gibbous; -1 where the platform has none. */
    default int moonPhase() {
        return -1;
    }

    /** The chassis position and dimension, or null where the platform has none (the manual test bench). */
    default dev.fieldframe.api.world.WorldPos position() {
        return null;
    }

    /**
     * World-interaction services (breaking, placing, using, fluids, entities) performed as this chassis' actor,
     * or null where the platform offers none (the manual test bench). World modules report NO_WORLD_ACCESS then.
     */
    default dev.fieldframe.api.world.WorldActions actions() {
        return null;
    }

    /**
     * Crafts one result from a 3x3 grid (row-major, nulls for empty) using the platform crafting recipes.
     * Pure lookup: no side effects. Null when nothing matches or unsupported.
     */
    default CraftResult craft(java.util.List<dev.fieldframe.api.resource.ItemVariant> grid) {
        return null;
    }

    /**
     * Reads Fieldframe data stored on an item under {@code key} (NBT on 1.20.1, a custom-data component on 1.21+),
     * or null. Used by modules that modify items in place (Armor Infuser).
     */
    default dev.fieldframe.api.data.FfData itemData(ItemVariant v, String key) {
        return null;
    }

    /** Returns {@code v} with {@code data} stored under {@code key}, or null when unsupported. */
    default ItemVariant withItemData(ItemVariant v, String key, dev.fieldframe.api.data.FfData data) {
        return null;
    }

    /**
     * The platform item variant for a registry id, or null when no such item exists. Modules that create items or
     * fluids from ids (data tables, configs) must go through these so the result is a real platform variant.
     */
    default ItemVariant itemVariant(dev.fieldframe.api.id.FfId id) {
        return ItemVariant.Simple.of(id.toString());
    }

    /** The platform fluid variant for a registry id, or null when no such fluid exists. */
    default dev.fieldframe.api.resource.FluidVariant fluidVariant(dev.fieldframe.api.id.FfId id) {
        return dev.fieldframe.api.resource.FluidVariant.Simple.of(id.toString());
    }

    /**
     * Brewing-stand result of {@code ingredient} applied to {@code base} (a potion), or null when it does not brew.
     * Pure lookup.
     */
    default ItemVariant brew(ItemVariant base, ItemVariant ingredient) {
        return null;
    }

    /**
     * {@code item} enchanted like an enchanting table at {@code level} with the given seed (a book becomes an
     * enchanted book), or null when it cannot be enchanted. Pure lookup.
     */
    default ItemVariant enchant(ItemVariant item, int level, long seed) {
        return null;
    }

    /** Hunger points restored by eating the item, 0 when it is not food. */
    default int foodValue(ItemVariant v) {
        return 0;
    }

    /** Sum of enchantment levels on the item (stored enchantments of a book included), 0 when none. */
    default int enchantmentLevels(ItemVariant v) {
        return 0;
    }

    /**
     * Charges an RF-holding item with up to {@code maxRf}. Null when the item holds no energy; otherwise the
     * charged variant, the amount accepted and whether it is now full. Pure: nothing outside the result changes.
     */
    default ChargeResult charge(ItemVariant v, long maxRf) {
        return null;
    }

    record ChargeResult(ItemVariant result, long accepted, boolean full) {
    }

    /** One crafting result plus the per-slot remainders (e.g. empty buckets), all non-null lists. */
    record CraftResult(ItemVariant result, int count, java.util.List<ItemVariant> remainders) {
    }

    /** Localized display name for status/tooltips; adapters may return the id string. */
    default String displayName(ItemVariant v) {
        return v.id().toString();
    }
}
