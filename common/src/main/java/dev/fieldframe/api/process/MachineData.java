package dev.fieldframe.api.process;

import dev.fieldframe.api.id.FfId;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Machine data besides recipes: engine fuels, Aura Chamber effects and recipe removals. Three ways to change them,
 * applied in this order on every data load (server start and {@code /reload}):
 * <ol>
 *   <li>datapacks: {@code data/<ns>/fieldframe/fuels/*.json}, {@code fieldframe/aura/*.json},
 *       {@code fieldframe/recipe_filters/*.json} (formats in docs/configuration.md);</li>
 *   <li>Java contributors registered here (once, e.g. in the mod constructor);</li>
 *   <li>KubeJS server scripts: {@code FieldframeEvents.fuels}, {@code .auras}, {@code .recipeFilter}.</li>
 * </ol>
 * Each later step can add, replace (same key or id) or remove entries from the earlier ones, including Fieldframe's
 * own data and the Metallurgy recipes it reads. Contributors run on the server only; clients receive the result.
 */
public final class MachineData {
    /** One engine fuel: {@code key} is an item or fluid id or a {@code #tag}; RF per item, or per mB for fluids. */
    public record Fuel(FfId engine, String key, boolean fluid, long rf) {
    }

    /** One Aura Chamber recipe: {@code count} of {@code ingredient} (id or {@code #tag}) gives the effect for {@code duration} ticks. */
    public record Aura(FfId id, String ingredient, int count, FfId effect, int duration, int amplifier) {
        public Aura {
            if (count < 1 || count > 64 || duration < 20 || amplifier < 0 || amplifier > 9) {
                throw new IllegalArgumentException("Invalid aura " + id);
            }
        }
    }

    public interface FuelSink {
        /** Adds or replaces the fuel of {@code fluidOrTag} in {@code engine}. */
        void fluid(FfId engine, String fluidOrTag, long rfPerMb);

        void item(FfId engine, String itemOrTag, long rf);

        /** Removes the entry of this engine and key (as written: id or #tag). */
        void remove(FfId engine, String key);

        /** Removes every fuel of an engine (to start its table from scratch). */
        void clear(FfId engine);
    }

    public interface AuraSink {
        /** Adds, or replaces the entry with the same id. */
        void add(Aura aura);

        /** Removes an entry by id, e.g. {@code fieldframe:metallurgy_adamantine}. */
        void remove(FfId id);
    }

    /** Temperatures in kelvin for heat generators (see core/material/Temperatures); keys are ids or {@code #tags}. */
    public interface TemperatureSink {
        void block(String blockOrTag, int kelvin);

        /** Only while the block is lit (furnaces, campfires); wins over the unlit entry. */
        void litBlock(String blockOrTag, int kelvin);

        void fluid(String fluidOrTag, int kelvin);

        /** Removes the block, lit-block and fluid entries for this key. */
        void remove(String key);
    }

    /** Electrode potentials in volts for the Galvanic Cell (see core/material/Electrodes); keys are item ids or {@code #tags}. */
    public interface ElectrodeSink {
        void item(String itemOrTag, double volts);

        void remove(String key);
    }

    /**
     * Animals for the Treadmill (see core/material/TreadmillMobs): a fixed {@code rfPerTick}, or {@code rfPerSpeed}
     * times the animal's movement speed (horses); {@code food} is an item id or {@code #tag}.
     */
    public interface TreadmillSink {
        void mob(String entity, double rfPerTick, double rfPerSpeed, String food);

        void remove(String entity);
    }

    /** The Tag Unifier's preferred mods (see core/material/Unification): put a namespace first, or drop it. */
    public interface UnificationSink {
        void prefer(String namespace);

        void avoid(String namespace);
    }

    /**
     * Fluid armor effects (see core/flora/FluidArmorEffects): wearing at least {@code minMb} of {@code fluidOrTag}
     * (summed over the worn pieces) gives the mob effect {@code effect} at {@code amplifier}, one level more per
     * {@code mbPerLevel} above that (0 = fixed) up to {@code maxAmplifier}. {@code fieldframe:cleanse} removes harmful
     * effects instead. Several effects may share a fluid.
     */
    public interface FluidArmorSink {
        void effect(String fluidOrTag, FfId effect, int amplifier, long minMb, long mbPerLevel, int maxAmplifier);

        /** Removes every effect of this key (as written: id or #tag). */
        void remove(String fluidOrTag);
    }

    public interface RecipeFilterSink {
        /**
         * Removes the recipe with this id from every Fieldframe machine, or every recipe matching a pattern ending
         * in {@code *}, e.g. {@code fieldframe:alloying/metallurgy/*}. Works for Fieldframe's recipes, smelting used
         * by the Heater, and the Metallurgy recipes read on NeoForge.
         */
        void remove(String idOrPattern);

        /** Removes every recipe of a process family, e.g. {@code fieldframe:heating}. */
        void removeFamily(FfId family);
    }

    private static final List<Consumer<FuelSink>> FUEL_CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<AuraSink>> AURA_CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<RecipeFilterSink>> FILTER_CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<TemperatureSink>> TEMPERATURE_CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<ElectrodeSink>> ELECTRODE_CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<TreadmillSink>> TREADMILL_CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<UnificationSink>> UNIFICATION_CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<FluidArmorSink>> FLUID_ARMOR_CONTRIBUTORS = new CopyOnWriteArrayList<>();

    private static volatile List<Fuel> fuels = List.of();
    private static volatile List<Aura> auras = List.of();

    private MachineData() {
    }

    public static void addFuelContributor(Consumer<FuelSink> c) {
        FUEL_CONTRIBUTORS.add(c);
    }

    public static void addAuraContributor(Consumer<AuraSink> c) {
        AURA_CONTRIBUTORS.add(c);
    }

    public static void addRecipeFilterContributor(Consumer<RecipeFilterSink> c) {
        FILTER_CONTRIBUTORS.add(c);
    }

    /** Adds to the temperature table after the datapack files ({@code data/<ns>/fieldframe/temperatures/*.json}). */
    public static void addTemperatureContributor(Consumer<TemperatureSink> c) {
        TEMPERATURE_CONTRIBUTORS.add(c);
    }

    public static List<Consumer<TemperatureSink>> temperatureContributors() {
        return List.copyOf(TEMPERATURE_CONTRIBUTORS);
    }

    /** Adds to the electrode table after the datapack files ({@code data/<ns>/fieldframe/electrodes/*.json}). */
    public static void addElectrodeContributor(Consumer<ElectrodeSink> c) {
        ELECTRODE_CONTRIBUTORS.add(c);
    }

    public static List<Consumer<ElectrodeSink>> electrodeContributors() {
        return List.copyOf(ELECTRODE_CONTRIBUTORS);
    }

    /** Adds to the Treadmill's animals after the datapack files ({@code data/<ns>/fieldframe/treadmill/*.json}). */
    public static void addTreadmillContributor(Consumer<TreadmillSink> c) {
        TREADMILL_CONTRIBUTORS.add(c);
    }

    public static List<Consumer<TreadmillSink>> treadmillContributors() {
        return List.copyOf(TREADMILL_CONTRIBUTORS);
    }

    /** Changes the Tag Unifier's preferred mods (default: Metallurgy, vanilla, Fieldframe). */
    public static void addUnificationContributor(Consumer<UnificationSink> c) {
        UNIFICATION_CONTRIBUTORS.add(c);
    }

    public static List<Consumer<UnificationSink>> unificationContributors() {
        return List.copyOf(UNIFICATION_CONTRIBUTORS);
    }

    /** Adds to the fluid armor effects after the datapack files ({@code data/<ns>/fieldframe/fluid_armor_effects/*.json}). */
    public static void addFluidArmorContributor(Consumer<FluidArmorSink> c) {
        FLUID_ARMOR_CONTRIBUTORS.add(c);
    }

    public static List<Consumer<FluidArmorSink>> fluidArmorContributors() {
        return List.copyOf(FLUID_ARMOR_CONTRIBUTORS);
    }

    public static List<Consumer<FuelSink>> fuelContributors() {
        return List.copyOf(FUEL_CONTRIBUTORS);
    }

    public static List<Consumer<AuraSink>> auraContributors() {
        return List.copyOf(AURA_CONTRIBUTORS);
    }

    public static List<Consumer<RecipeFilterSink>> recipeFilterContributors() {
        return List.copyOf(FILTER_CONTRIBUTORS);
    }

    /** The effective fuels table (read-only; on clients, what the server sent). */
    public static List<Fuel> fuels() {
        return fuels;
    }

    /** The effective Aura Chamber table, Metallurgy's chamber recipes included. */
    public static List<Aura> auras() {
        return auras;
    }

    /** Fieldframe internal: publishes the tables after each load or sync. */
    public static void publish(List<Fuel> f, List<Aura> a) {
        if (f != null) {
            fuels = List.copyOf(f);
        }
        if (a != null) {
            auras = List.copyOf(a);
        }
    }

    /** Tests only. */
    public static void resetContributorsForTests() {
        FUEL_CONTRIBUTORS.clear();
        TEMPERATURE_CONTRIBUTORS.clear();
        ELECTRODE_CONTRIBUTORS.clear();
        TREADMILL_CONTRIBUTORS.clear();
        UNIFICATION_CONTRIBUTORS.clear();
        AURA_CONTRIBUTORS.clear();
        FILTER_CONTRIBUTORS.clear();
    }
}
