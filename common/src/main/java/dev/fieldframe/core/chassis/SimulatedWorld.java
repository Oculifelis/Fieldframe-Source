package dev.fieldframe.core.chassis;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.process.RecipeSource;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A headless, side-effect-free {@link ChassisWorld} with explicit synthetic neighbours. Used by unit tests and
 * by the manual's schematic test bench; it never touches a live world.
 */
public final class SimulatedWorld implements ChassisWorld {
    public long time;
    public boolean day = true;
    public boolean rain;
    public boolean thunder;
    /** Maximum damage by item id (items listed here wear out; their damage is item data "damage"). */
    public final Map<FfId, Integer> maxDamage = new HashMap<>();
    /** Tag Unifier table: item id to the id of each namespace's variant (namespace -> id). */
    public final Map<FfId, Map<String, FfId>> unify = new HashMap<>();
    /** Moon phase (0 full .. 4 new .. 7). */
    public int moon;
    /** Where the chassis is; null models a platform without a position. */
    public dev.fieldframe.api.world.WorldPos pos = new dev.fieldframe.api.world.WorldPos("minecraft:overworld", 0, 64, 0);
    public final Map<LocalFace, ItemStore> items = new EnumMap<>(LocalFace.class);
    public final Map<LocalFace, FluidStore> fluids = new EnumMap<>(LocalFace.class);
    public final Map<LocalFace, EnergyStore> energy = new EnumMap<>(LocalFace.class);
    public final Map<LocalFace, Integer> redstone = new EnumMap<>(LocalFace.class);
    public final Set<LocalFace> unloaded = new HashSet<>();
    public final Set<LocalFace> sky = new HashSet<>();
    public final Map<FfId, Integer> fuels = new HashMap<>();
    public final Map<FfId, ItemVariant> fuelRemainders = new HashMap<>();
    public final Map<FfId, Set<FfId>> itemTags = new HashMap<>();
    public final Map<FfId, Set<FfId>> fluidTags = new HashMap<>();
    public final Map<FfId, Set<FfId>> blockTags = new HashMap<>();
    /** Brewing: "base|ingredient" ids to the result. */
    public final Map<String, ItemVariant> brewing = new HashMap<>();
    /** Items an enchanter accepts; the result carries componentKey "enchanted:<level>". */
    public final Set<FfId> enchantable = new HashSet<>();
    public final Map<FfId, Integer> foods = new HashMap<>();
    public final Map<FfId, Integer> enchantLevels = new HashMap<>();
    /** RF capacity of chargeable items; charge is kept in the componentKey as "rf=<n>". */
    public final Map<FfId, Long> chargeCapacity = new HashMap<>();
    public final Map<FfId, List<ProcessRecipe>> recipes = new HashMap<>();
    public long recipeGeneration = 1;
    /** World actions; set to null to model a platform without world access. */
    public SimulatedActions actions = new SimulatedActions();
    /** Shaped crafting table for the Recipe Assembler: 9 ids joined by "," ("_" for empty) -> result. */
    public final Map<String, CraftResult> crafting = new HashMap<>();

    public SimulatedWorld addRecipe(ProcessRecipe r) {
        recipes.computeIfAbsent(r.family(), k -> new ArrayList<>()).add(r);
        recipeGeneration++;
        return this;
    }

    @Override
    public dev.fieldframe.api.world.WorldActions actions() {
        return actions;
    }

    @Override
    public CraftResult craft(List<ItemVariant> grid) {
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            ItemVariant v = i < grid.size() ? grid.get(i) : null;
            k.append(i == 0 ? "" : ",").append(v == null ? "_" : v.id().toString());
        }
        return crafting.get(k.toString());
    }

    /** Item data is encoded into the component key as {@code key:json} (one key per item is enough for tests). */
    @Override
    public dev.fieldframe.api.data.FfData itemData(ItemVariant v, String key) {
        String c = v.componentKey();
        return c.startsWith(key + ":") ? dev.fieldframe.api.data.FfJson.parse(c.substring(key.length() + 1)) : null;
    }

    @Override
    public ItemVariant withItemData(ItemVariant v, String key, dev.fieldframe.api.data.FfData data) {
        return new ItemVariant.Simple(v.id(), key + ":" + dev.fieldframe.api.data.FfJson.toJsonString(data), v.maxStackSize());
    }

    @Override
    public long gameTime() {
        return time;
    }

    @Override
    public boolean isNeighborLoaded(LocalFace face) {
        return !unloaded.contains(face);
    }

    @Override
    public ItemStore neighborItems(LocalFace face) {
        return unloaded.contains(face) ? null : items.get(face);
    }

    @Override
    public FluidStore neighborFluids(LocalFace face) {
        return unloaded.contains(face) ? null : fluids.get(face);
    }

    @Override
    public EnergyStore neighborEnergy(LocalFace face) {
        return unloaded.contains(face) ? null : energy.get(face);
    }

    @Override
    public int redstoneInput(LocalFace face) {
        return redstone.getOrDefault(face, 0);
    }

    @Override
    public TagLookup tags() {
        return new TagLookup() {
            @Override
            public boolean itemInTag(ItemVariant item, FfId tag) {
                Set<FfId> members = itemTags.get(tag);
                return members != null && members.contains(item.id());
            }

            @Override
            public boolean fluidInTag(FluidVariant fluid, FfId tag) {
                Set<FfId> members = fluidTags.get(tag);
                return members != null && members.contains(fluid.id());
            }

            @Override
            public boolean blockInTag(FfId block, FfId tag) {
                Set<FfId> members = blockTags.get(tag);
                return members != null && members.contains(block);
            }
        };
    }

    @Override
    public ItemVariant brew(ItemVariant base, ItemVariant ingredient) {
        return brewing.get(base.id() + "|" + ingredient.id());
    }

    @Override
    public ItemVariant enchant(ItemVariant item, int level, long seed) {
        return enchantable.contains(item.id()) && item.componentKey().isEmpty()
                ? new ItemVariant.Simple(item.id(), "enchanted:" + level, item.maxStackSize()) : null;
    }

    @Override
    public int foodValue(ItemVariant v) {
        return foods.getOrDefault(v.id(), 0);
    }

    @Override
    public int enchantmentLevels(ItemVariant v) {
        return enchantLevels.getOrDefault(v.id(), 0);
    }

    @Override
    public ChargeResult charge(ItemVariant v, long maxRf) {
        Long cap = chargeCapacity.get(v.id());
        if (cap == null) {
            return null;
        }
        long stored = v.componentKey().startsWith("rf=") ? Long.parseLong(v.componentKey().substring(3)) : 0;
        long accepted = Math.max(0, Math.min(maxRf, cap - stored));
        long now = stored + accepted;
        return new ChargeResult(new ItemVariant.Simple(v.id(), "rf=" + now, v.maxStackSize()), accepted, now >= cap);
    }

    @Override
    public RecipeSource recipes() {
        return new RecipeSource() {
            @Override
            public List<ProcessRecipe> recipes(FfId family) {
                return recipes.getOrDefault(family, List.of());
            }

            @Override
            public long generation() {
                return recipeGeneration;
            }
        };
    }

    @Override
    public int fuelBurnTicks(ItemVariant v) {
        return fuels.getOrDefault(v.id(), 0);
    }

    @Override
    public ItemVariant fuelRemainder(ItemVariant v) {
        return fuelRemainders.get(v.id());
    }

    @Override
    public boolean canSeeSky(LocalFace face) {
        return sky.contains(face);
    }

    @Override
    public boolean isDaytime() {
        return day;
    }

    @Override
    public boolean isRaining() {
        return rain;
    }

    @Override
    public boolean isThundering() {
        return thunder;
    }

    @Override
    public int moonPhase() {
        return moon;
    }

    @Override
    public int[] durability(ItemVariant v) {
        Integer max = v == null ? null : maxDamage.get(v.id());
        if (max == null) {
            return null;
        }
        dev.fieldframe.api.data.FfData d = itemData(v, "damage");
        return new int[]{d == null ? 0 : d.getInt("value", 0), max};
    }

    @Override
    public ItemVariant withDamage(ItemVariant v, int damage) {
        if (durability(v) == null) {
            return null;
        }
        if (damage <= 0) {
            return ItemVariant.Simple.of(v.id().toString());
        }
        return withItemData(v, "damage", new dev.fieldframe.api.data.FfData().putInt("value", damage));
    }

    @Override
    public ItemVariant unified(ItemVariant v, List<String> preferred) {
        Map<String, FfId> m = v == null ? null : unify.get(v.id());
        if (m == null) {
            return null;
        }
        for (String ns : preferred) {
            if (m.containsKey(ns)) {
                return ItemVariant.Simple.of(m.get(ns).toString());
            }
        }
        return null;
    }

    @Override
    public dev.fieldframe.api.world.WorldPos position() {
        return pos;
    }

    @Override
    public int skyDarkening() {
        return day ? 0 : 11;
    }
}
