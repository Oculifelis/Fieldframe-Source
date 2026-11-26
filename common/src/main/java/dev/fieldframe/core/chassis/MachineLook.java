package dev.fieldframe.core.chassis;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a working module looks and sounds on the client: a lit faceplate overlay, a particle and an occasional sound.
 * Built-in modules get defaults by kind; any module can set its own through presentation hints
 * ({@code work_sound}: a sound event id, {@code work_particle}: a simple particle type id, {@code glow_texture}:
 * a block-atlas texture; an empty string switches that part off). Purely cosmetic and client side.
 */
public final class MachineLook {
    private MachineLook() {
    }

    /** Sound events Fieldframe registers (path under the {@code fieldframe} namespace); sounds.json maps them. */
    public static final List<String> SOUND_EVENTS = List.of(
            "machine.install", "machine.remove", "machine.upgrade", "machine.tier_up",
            "module.heat", "module.engine", "module.grind", "module.saw", "module.press", "module.fluid", "module.brew",
            "module.charge", "module.enchant", "module.grow", "module.water", "module.crystal", "module.chill",
            "module.turbine", "module.wind", "module.storm", "module.blast", "module.cell");

    private record Kind(String sound, String particle) {
    }

    private static final Kind HEAT = new Kind("fieldframe:module.heat", "minecraft:smoke");
    private static final Kind ENGINE = new Kind("fieldframe:module.engine", "minecraft:smoke");
    private static final Kind CRYSTAL = new Kind("fieldframe:module.crystal", "minecraft:electric_spark");
    private static final Kind GRIND = new Kind("fieldframe:module.grind", "minecraft:crit");
    private static final Kind SAW = new Kind("fieldframe:module.saw", "minecraft:crit");
    private static final Kind PRESS = new Kind("fieldframe:module.press", "minecraft:poof");
    private static final Kind FLUID = new Kind("fieldframe:module.fluid", "minecraft:splash");
    private static final Kind BREW = new Kind("fieldframe:module.brew", "minecraft:effect");
    private static final Kind CHARGE = new Kind("fieldframe:module.charge", "minecraft:electric_spark");
    private static final Kind ENCHANT = new Kind("fieldframe:module.enchant", "minecraft:enchant");
    private static final Kind GROW = new Kind("fieldframe:module.grow", "minecraft:happy_villager");
    private static final Kind WATER = new Kind("fieldframe:module.water", "minecraft:splash");
    private static final Kind CHILL = new Kind("fieldframe:module.chill", "minecraft:snowflake");
    private static final Kind TURBINE = new Kind("fieldframe:module.turbine", "minecraft:cloud");
    private static final Kind WIND = new Kind("fieldframe:module.wind", "");
    private static final Kind SPARK = new Kind("", "minecraft:electric_spark");
    private static final Kind STORM = new Kind("fieldframe:module.storm", "minecraft:electric_spark");
    private static final Kind BLAST = new Kind("fieldframe:module.blast", "minecraft:smoke");
    private static final Kind CELL = new Kind("fieldframe:module.cell", "minecraft:bubble_pop");
    private static final Kind QUIET = new Kind("", "");

    private static final Map<String, Kind> BUILTIN = new HashMap<>();

    static {
        for (String p : List.of("heater", "magma_crucible", "pyrolyzer", "alloy_smelter", "refinery", "stone_extruder")) {
            BUILTIN.put(p, HEAT);
        }
        for (String p : List.of("fuel_engine", "fluid_engine", "food_engine")) {
            BUILTIN.put(p, ENGINE);
        }
        for (String p : List.of("gem_engine", "redstone_engine", "arcane_engine")) {
            BUILTIN.put(p, CRYSTAL);
        }
        BUILTIN.put("crusher", GRIND);
        BUILTIN.put("sawmill", SAW);
        BUILTIN.put("press", PRESS);
        BUILTIN.put("recipe_assembler", PRESS);
        for (String p : List.of("mixer", "washer", "centrifuge", "fluid_transposer")) {
            BUILTIN.put(p, FLUID);
        }
        BUILTIN.put("brewer", BREW);
        BUILTIN.put("item_charger", CHARGE);
        BUILTIN.put("armor_infuser", CHARGE);
        BUILTIN.put("aura_chamber", ENCHANT);
        BUILTIN.put("enchanter", ENCHANT);
        BUILTIN.put("insolator", GROW);
        BUILTIN.put("crop_tender", GROW);
        BUILTIN.put("waterwheel_interface", WATER);
        BUILTIN.put("water_collector", WATER);
        BUILTIN.put("glacial_precipitator", CHILL);
        BUILTIN.put("blast_chiller", CHILL);
        BUILTIN.put("boiler", HEAT);
        BUILTIN.put("steam_turbine", TURBINE);
        BUILTIN.put("condenser", FLUID);
        BUILTIN.put("hot_junction", SPARK);
        BUILTIN.put("wind_rotor", WIND);
        BUILTIN.put("storm_coil", STORM);
        BUILTIN.put("blast_damper", BLAST);
        BUILTIN.put("galvanic_cell", CELL);
        BUILTIN.put("geothermal_probe", HEAT);
        BUILTIN.put("bio_digester", FLUID);
    }

    private static Kind kind(ModuleDefinition def) {
        if (def == null) {
            return QUIET;
        }
        FfId id = def.id();
        return id.namespace().equals("fieldframe") ? BUILTIN.getOrDefault(id.path(), QUIET) : QUIET;
    }

    /** Sound event id played now and then while the module works; "" for none. */
    public static String sound(ModuleDefinition def) {
        if (def != null && def.presentation().has("work_sound")) {
            return def.presentation().getString("work_sound", "");
        }
        return kind(def).sound();
    }

    /** Simple particle type id puffed from the face while the module works; "" for none. */
    public static String particle(ModuleDefinition def) {
        if (def != null && def.presentation().has("work_particle")) {
            return def.presentation().getString("work_particle", "");
        }
        return kind(def).particle();
    }

    /** Block-atlas texture drawn full-bright over the faceplate while the module works; "" for none. */
    public static String glowTexture(ModuleDefinition def) {
        if (def == null) {
            return "";
        }
        if (def.presentation().has("glow_texture")) {
            return def.presentation().getString("glow_texture", "");
        }
        return def.id().namespace() + ":block/module_glow/" + def.id().path();
    }
}
