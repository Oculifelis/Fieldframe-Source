package dev.fieldframe.core.modules;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.UpgradeDefinition;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ConfigField;
import dev.fieldframe.api.module.Exposure;
import dev.fieldframe.api.module.ModuleCategory;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.ModuleDefinition.Stage;
import dev.fieldframe.api.module.TickPhase;
import dev.fieldframe.api.module.TunableSpec;
import dev.fieldframe.api.process.ProcessFamilies;
import dev.fieldframe.core.modules.world.DeviceBehaviors;
import dev.fieldframe.core.modules.world.DiggingBehaviors;
import dev.fieldframe.core.modules.world.FieldBehaviors;
import dev.fieldframe.core.modules.world.GeneratorBehaviors;
import dev.fieldframe.core.modules.world.HandBehaviors;

/**
 * Registers Fieldframe's own modules and upgrades through the same public {@link FieldframeRegistry} API that
 * addons and KubeJS use. Values here are provisional defaults; see docs/balance.md.
 */
public final class BuiltinModules {
    private BuiltinModules() {
    }

    static {
        dev.fieldframe.api.module.Archetypes.install(BuiltinModules::processorBuilder);
    }

    public static final FfId ITEM_PORT = FfId.ff("item_port");
    public static final FfId FLUID_PORT = FfId.ff("fluid_port");
    public static final FfId POWER_TERMINAL = FfId.ff("power_terminal");
    public static final FfId INVENTORY_PANEL = FfId.ff("inventory_access_panel");
    public static final FfId TANK_PANEL = FfId.ff("tank_access_panel");
    public static final FfId HEATER = FfId.ff("heater");
    public static final FfId CRUSHER = FfId.ff("crusher");
    public static final FfId PRESS = FfId.ff("press");
    public static final FfId MIXER = FfId.ff("mixer");
    public static final FfId WASHER = FfId.ff("washer");
    public static final FfId FUEL_ENGINE = FfId.ff("fuel_engine");
    public static final FfId SOLAR = FfId.ff("solar_collector");
    public static final FfId SIGNAL_TERMINAL = FfId.ff("signal_terminal");
    public static final FfId LOGIC_PLATE = FfId.ff("logic_plate");
    public static final FfId MEMORY_PLATE = FfId.ff("memory_plate");
    public static final FfId TIMER_COUNTER = FfId.ff("timer_counter_plate");
    public static final FfId RESOURCE_SENSOR = FfId.ff("resource_sensor");
    public static final FfId ENVIRONMENT_SENSOR = FfId.ff("environment_sensor");
    public static final FfId INDICATOR = FfId.ff("indicator_panel");
    public static final FfId CONTROLLER = FfId.ff("controller_plate");
    public static final FfId BIO_DIGESTER = FfId.ff("bio_digester");
    public static final FfId SERVICE_PORT = FfId.ff("combined_service_port");
    public static final FfId DROP_COLLECTOR = FfId.ff("drop_collector");
    public static final FfId ITEM_EJECTOR = FfId.ff("item_ejector");
    public static final FfId FLUID_PUMP = FfId.ff("world_fluid_pump");
    public static final FfId RECIPE_ASSEMBLER = FfId.ff("recipe_assembler");
    public static final FfId WATERWHEEL = FfId.ff("waterwheel_interface");
    public static final FfId MOTION_HARVESTER = FfId.ff("motion_harvester");
    public static final FfId BLOCK_BREAKER = FfId.ff("block_breaker");
    public static final FfId BLOCK_PLACER = FfId.ff("block_placer");
    public static final FfId INTERACTION_HEAD = FfId.ff("interaction_head");
    public static final FfId CROP_TENDER = FfId.ff("crop_tender");
    public static final FfId ENTITY_MOVER = FfId.ff("entity_mover");
    public static final FfId EXCAVATOR = FfId.ff("hydraulic_excavator");
    public static final FfId ARMOR_INFUSER = FfId.ff("armor_infuser");

    // Thermal-style machines (docs/thermal_parity.md).
    public static final FfId SAWMILL = FfId.ff("sawmill");
    public static final FfId INSOLATOR = FfId.ff("insolator");
    public static final FfId CENTRIFUGE = FfId.ff("centrifuge");
    public static final FfId MAGMA_CRUCIBLE = FfId.ff("magma_crucible");
    public static final FfId FLUID_TRANSPOSER = FfId.ff("fluid_transposer");
    public static final FfId BLAST_CHILLER = FfId.ff("blast_chiller");
    public static final FfId REFINERY = FfId.ff("refinery");
    public static final FfId PYROLYZER = FfId.ff("pyrolyzer");
    public static final FfId ALLOY_SMELTER = FfId.ff("alloy_smelter");
    public static final FfId PRECIPITATOR = FfId.ff("glacial_precipitator");
    public static final FfId FLUID_ENGINE = FfId.ff("fluid_engine");
    public static final FfId GEM_ENGINE = FfId.ff("gem_engine");
    public static final FfId REDSTONE_ENGINE = FfId.ff("redstone_engine");
    public static final FfId FOOD_ENGINE = FfId.ff("food_engine");
    public static final FfId ARCANE_ENGINE = FfId.ff("arcane_engine");
    public static final FfId ITEM_CHARGER = FfId.ff("item_charger");
    public static final FfId BREWER = FfId.ff("brewer");
    public static final FfId ENCHANTER = FfId.ff("enchanter");
    public static final FfId STONE_EXTRUDER = FfId.ff("stone_extruder");
    public static final FfId NULLIFIER = FfId.ff("nullifier");
    public static final FfId WATER_COLLECTOR = FfId.ff("water_collector");
    public static final FfId FISHER = FfId.ff("fisher");
    public static final FfId TREE_TAPPER = FfId.ff("tree_tapper");
    public static final FfId XP_COLLECTOR = FfId.ff("xp_collector");
    public static final FfId AURA_CHAMBER = FfId.ff("aura_chamber");

    public static synchronized void registerAll() {
        if (FieldframeRegistry.module(ITEM_PORT) != null) {
            return;
        }
        String src = "fieldframe (built-in)";

        // ---- Handling ----
        FieldframeRegistry.registerModule(ModuleDefinition.builder(ITEM_PORT)
                .category(ModuleCategory.HANDLING).phase(TickPhase.IMPORT).stage(Stage.WORKSHOP)
                .exposes(Exposure.ITEM)
                .binding(BindingSpec.item("lane", 0))
                .binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.choice("mode", "passive_both", "passive_in", "passive_out", "passive_both", "import", "export"))
                .config(ConfigField.integer("batch", 1, 64, 8))
                .config(ConfigField.itemFilter("filter"))
                .behavior(d -> new ItemPortBehavior())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(FLUID_PORT)
                .category(ModuleCategory.HANDLING).phase(TickPhase.IMPORT).stage(Stage.AUTOMATION)
                .exposes(Exposure.FLUID)
                .binding(BindingSpec.fluid("tank", 0))
                .binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.choice("mode", "passive_both", "passive_in", "passive_out", "passive_both", "import", "export"))
                .config(ConfigField.integer("rate", 1, 4000, 250))
                .config(ConfigField.fluidFilter("filter"))
                .behavior(d -> new FluidPortBehavior())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(POWER_TERMINAL)
                .category(ModuleCategory.HANDLING).phase(TickPhase.EXPORT).stage(Stage.WORKSHOP)
                .exposes(Exposure.ENERGY)
                .binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.choice("mode", "receive", "receive", "provide", "push"))
                .tunable(TunableSpec.of("push_rf_per_tick", 1, 100_000, 256, "RF/t"))
                .behavior(d -> new PowerTerminalBehavior())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(INVENTORY_PANEL)
                .category(ModuleCategory.HANDLING).phase(TickPhase.CONTROL).stage(Stage.WORKSHOP)
                .exposes(Exposure.PLAYER_ACCESS)
                .config(ConfigField.bool("a", true)).config(ConfigField.bool("b", true)).config(ConfigField.bool("c", true))
                .behavior(d -> new AccessPanelBehavior(false))
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(TANK_PANEL)
                .category(ModuleCategory.HANDLING).phase(TickPhase.CONTROL).stage(Stage.AUTOMATION)
                .exposes(Exposure.PLAYER_ACCESS)
                .config(ConfigField.bool("a", true)).config(ConfigField.bool("b", true)).config(ConfigField.bool("c", true))
                .behavior(d -> new AccessPanelBehavior(true))
                .build(), src);

        // ---- Processing: one engine, several families ----
        processor(HEATER, ProcessFamilies.HEATING, Stage.WORKSHOP, false, false, src);
        processor(CRUSHER, ProcessFamilies.CRUSHING, Stage.AUTOMATION, false, false, src);
        processor(PRESS, ProcessFamilies.PRESSING, Stage.AUTOMATION, false, false, src);
        processor(MIXER, ProcessFamilies.MIXING, Stage.AUTOMATION, true, true, src);
        processor(WASHER, ProcessFamilies.WASHING, Stage.AUTOMATION, true, true, src);

        // Thermal-style processors: same engine, recipe families from data (type fieldframe:process + family).
        processor(SAWMILL, ProcessFamilies.SAWING, Stage.AUTOMATION, false, false, src);
        processor(INSOLATOR, ProcessFamilies.GROWING, Stage.AUTOMATION, true, false, src);
        // Tanks optional but connected by default where the family's recipes use fluids (a first machine just works).
        processorWithTanks(CENTRIFUGE, ProcessFamilies.SEPARATING, Stage.AUTOMATION, BindingSpec.IN, -1, src);
        processor(MAGMA_CRUCIBLE, ProcessFamilies.MELTING, Stage.AUTOMATION, false, true, src);
        processorWithTanks(FLUID_TRANSPOSER, ProcessFamilies.TRANSPOSING, Stage.AUTOMATION, BindingSpec.IN, BindingSpec.OUT, src);
        processor(BLAST_CHILLER, ProcessFamilies.CHILLING, Stage.AUTOMATION, true, false, src);
        processor(REFINERY, ProcessFamilies.REFINING, Stage.FIELD, true, true, src);
        processorWithTanks(PYROLYZER, ProcessFamilies.PYROLYSIS, Stage.AUTOMATION, -1, BindingSpec.OUT, src);
        processor(ALLOY_SMELTER, ProcessFamilies.ALLOYING, Stage.AUTOMATION, false, false, src);
        processor(PRECIPITATOR, ProcessFamilies.PRECIPITATING, Stage.AUTOMATION, true, false, src);
        processorWithTanks(BIO_DIGESTER, ProcessFamilies.DIGESTION, Stage.AUTOMATION, BindingSpec.IN, BindingSpec.OUT, src);
        SmallModules.register(src);

        // ---- Generation ----
        FieldframeRegistry.registerModule(ModuleDefinition.builder(FUEL_ENGINE)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.WORKSHOP)
                .binding(BindingSpec.item("fuel", BindingSpec.SUPPLY))
                .binding(BindingSpec.signalIn("enable"))
                .binding(BindingSpec.signalOut("running"))
                .tunable(TunableSpec.of("rf_per_tick", 1, 10_000, 40, "RF/t"))
                .tunable(TunableSpec.of("burn_divisor", 0.25, 64, 4, "x"))
                .tunable(TunableSpec.of("bucket_max_ticks", 1, 100_000, 1_250, "ticks"))
                .behavior(d -> new FuelEngineBehavior())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(SOLAR)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .faces(LocalFace.TOP).needsSky(true)
                .tunable(TunableSpec.of("rf_per_tick", 0, 1_000, 8, "RF/t"))
                .tunable(TunableSpec.of("rain_factor", 0, 1, 0.25, "x"))
                .tunable(TunableSpec.of("check_interval", 20, 1200, 100, "ticks"))
                .behavior(d -> new SolarCollectorBehavior())
                .build(), src);

        // ---- Control ----
        FieldframeRegistry.registerModule(ModuleDefinition.builder(SIGNAL_TERMINAL)
                .category(ModuleCategory.CONTROL).phase(TickPhase.CONTROL).stage(Stage.WORKSHOP)
                .exposes(Exposure.REDSTONE_IN, Exposure.REDSTONE_OUT)
                .binding(BindingSpec.signalOut("channel_out", 0))
                .binding(BindingSpec.signalIn("channel_in", 0))
                .config(ConfigField.choice("mode", "input", "input", "output"))
                .config(ConfigField.bool("analog", false))
                .config(ConfigField.bool("invert", false))
                .behavior(d -> new LogicBehaviors.SignalTerminal())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(LOGIC_PLATE)
                .category(ModuleCategory.CONTROL).phase(TickPhase.CONTROL).stage(Stage.AUTOMATION)
                .binding(BindingSpec.signalIn("a", 0)).binding(BindingSpec.signalIn("b", 1)).binding(BindingSpec.signalOut("out", 2))
                .config(ConfigField.choice("op", "and", "and", "or", "xor", "nand", "nor", "xnor", "not", "buffer"))
                .behavior(d -> new LogicBehaviors.LogicPlate())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(MEMORY_PLATE)
                .category(ModuleCategory.CONTROL).phase(TickPhase.CONTROL).stage(Stage.AUTOMATION)
                .binding(BindingSpec.signalIn("set", 0)).binding(BindingSpec.signalIn("reset", 1))
                .binding(BindingSpec.signalIn("toggle")).binding(BindingSpec.signalOut("out", 2))
                .behavior(d -> new LogicBehaviors.MemoryPlate())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(TIMER_COUNTER)
                .category(ModuleCategory.CONTROL).phase(TickPhase.CONTROL).stage(Stage.INSTRUMENTATION)
                .binding(BindingSpec.signalIn("enable")).binding(BindingSpec.signalIn("count"))
                .binding(BindingSpec.signalIn("reset")).binding(BindingSpec.signalOut("out", 3))
                .config(ConfigField.choice("mode", "timer", "timer", "counter"))
                .config(ConfigField.integer("period", 2, 72_000, 20))
                .config(ConfigField.integer("pulse", 1, 1200, 2))
                .config(ConfigField.integer("target", 1, 1_000_000, 8))
                .behavior(d -> new LogicBehaviors.TimerCounter())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(RESOURCE_SENSOR)
                .category(ModuleCategory.CONTROL).phase(TickPhase.CONTROL).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("lane", 0)).binding(BindingSpec.fluid("tank", 0)).binding(BindingSpec.signalOut("out", 4))
                .config(ConfigField.choice("source", "energy", "items", "fluid", "energy"))
                .config(ConfigField.choice("mode", "on_below", "on_below", "on_above"))
                .config(ConfigField.integer("low", 0, 100, 25))
                .config(ConfigField.integer("high", 0, 100, 75))
                .config(ConfigField.bool("analog", false))
                .behavior(d -> new LogicBehaviors.ResourceSensor())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(ENVIRONMENT_SENSOR)
                .category(ModuleCategory.CONTROL).phase(TickPhase.CONTROL).stage(Stage.INSTRUMENTATION)
                .binding(BindingSpec.signalOut("out", 5))
                .config(ConfigField.choice("observe", "day", "day", "rain", "sky",
                        "entities_any", "entities_player", "entities_mob", "entities_animal", "entities_item"))
                .config(ConfigField.integer("range", 1, 4, 2))
                .config(ConfigField.bool("invert", false))
                .behavior(d -> new LogicBehaviors.EnvironmentSensor())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(INDICATOR)
                .category(ModuleCategory.DISPLAY).phase(TickPhase.CONTROL).stage(Stage.WORKSHOP)
                .binding(BindingSpec.signalIn("in", 0))
                .config(ConfigField.text("label", 24, ""))
                // Lamps and bar lit by the chassis (FaceGauge) rather than a whole-face glow.
                .presentation(new dev.fieldframe.api.data.FfData().putBool("gauge", true).putString("glow_texture", ""))
                .behavior(d -> new LogicBehaviors.IndicatorPanel())
                .build(), src);

        FieldframeRegistry.registerModule(ModuleDefinition.builder(CONTROLLER)
                .category(ModuleCategory.CONTROL).phase(TickPhase.CONTROL).stage(Stage.INSTRUMENTATION)
                .config(ConfigField.text("program", dev.fieldframe.core.controller.ControllerProgram.MAX_TEXT,
                        "# Keep the RF buffer between 25% and 75% (drive ch 5 to enable a generator)\n"
                                + "states charge, idle\n"
                                + "in charge: when power > 75% then goto idle\n"
                                + "in idle: when power < 25% then goto charge\n"
                                + "when state == charge then set ch 5 = 15\n"))
                .config(ConfigField.bool("paused", false))
                .config(ConfigField.integer("step", 0, 1_000_000, 0))
                .behavior(d -> new ControllerBehavior())
                .build(), src);

        registerFieldModules(src);
        registerPowerModules(src);
        registerBurstModules(src);

        // ---- Service-bay upgrades: capacity / throughput only ----
        // Chassis tiers (Thermal Expansion's machine tiers), raised one level at a time with upgrade kits.
        FieldframeRegistry.registerTier(new dev.fieldframe.api.chassis.ChassisTier(FfId.ff("basic"), 0, 1.0, 0, 0, 0, 0, 0));
        FieldframeRegistry.registerTier(new dev.fieldframe.api.chassis.ChassisTier(FfId.ff("hardened"), 1, 1.5, 1, 1, 2_000, 32_000, 256));
        FieldframeRegistry.registerTier(new dev.fieldframe.api.chassis.ChassisTier(FfId.ff("reinforced"), 2, 2.0, 2, 2, 4_000, 96_000, 512));
        FieldframeRegistry.registerTier(new dev.fieldframe.api.chassis.ChassisTier(FfId.ff("signalum"), 3, 3.0, 3, 3, 8_000, 224_000, 1_024));
        FieldframeRegistry.registerTier(new dev.fieldframe.api.chassis.ChassisTier(FfId.ff("resonant"), 4, 4.0, 3, 5, 12_000, 480_000, 2_048));
        FieldframeRegistry.registerUpgrade(new UpgradeDefinition(FfId.ff("storage_upgrade"), 4, 0, 0, 0, 0, 0, 0));
        FieldframeRegistry.registerUpgrade(new UpgradeDefinition(FfId.ff("tank_upgrade"), 0, 4_000, 0, 0, 0, 0, 0));
        FieldframeRegistry.registerUpgrade(new UpgradeDefinition(FfId.ff("cell_upgrade"), 0, 0, 32_000, 0, 0, 0, 0));
        FieldframeRegistry.registerUpgrade(new UpgradeDefinition(FfId.ff("throughput_upgrade"), 0, 0, 0, 16, 1_000, 256, 0));
    }

    /** Heat and wind generators (Steam Loop, Thermocouple, Wind Rotor); see core/modules/power. */
    /**
     * Presentation hint {@code viewer_pages}: recipe-viewer pages (JEI category ids) that list the module as a machine
     * using them. Any module can set it (Java presentation data, KubeJS {@code viewerPage}).
     */
    static dev.fieldframe.api.data.FfData viewerPages(String... pages) {
        return new dev.fieldframe.api.data.FfData().putList("viewer_pages", java.util.List.of(pages));
    }

    /**
     * Content added after Forge 1.20.1 froze (2026-10-22): the Hand Crank (needs the platform's empty-hand hook,
     * ChassisCore#playerUse) and the Overclock Upgrade. Editions that have it ported call this after {@link #registerAll()}.
     */
    public static void registerSinceFreeze() {
        String src = "fieldframe (built-in)";
        FieldframeRegistry.registerUpgrade(new UpgradeDefinition(FfId.ff("overclock_upgrade"), 0, 0, 0, 0, 0, 0, 0, 0.5));
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.HandCrank.ID)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.WORKSHOP)
                .faces(LocalFace.FRONT, LocalFace.BACK, LocalFace.LEFT, LocalFace.RIGHT, LocalFace.TOP)
                .binding(BindingSpec.signalOut("running"))
                .tunable(TunableSpec.of("rf_per_tick", 0, 1_000, 20, "RF/t"))
                .tunable(TunableSpec.of("ticks_per_turn", 1, 200, 10, "ticks"))
                .tunable(TunableSpec.of("max_queue", 1, 1_200, 20, "ticks"))
                .behavior(d -> new dev.fieldframe.core.modules.power.HandCrank())
                .build(), src);
    }

    private static void registerPowerModules(String src) {
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.HeatPower.BOILER)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .binding(BindingSpec.fluid("water", BindingSpec.IN))
                .binding(BindingSpec.fluid("steam", BindingSpec.OUT))
                .binding(BindingSpec.item("fuel", BindingSpec.SUPPLY))
                .binding(BindingSpec.signalIn("enable"))
                .binding(BindingSpec.signalOut("running"))
                .tunable(TunableSpec.of("steam_per_tick", 1, 1_000, 20, "mB/t"))
                .tunable(TunableSpec.of("burn_divisor", 0.25, 64, 4, "x"))
                .tunable(TunableSpec.of("heat_kelvin", 300, 10_000, 600, "K"))
                .tunable(TunableSpec.of("heat_factor", 0, 1, 0.25, "x"))
                .behavior(d -> new dev.fieldframe.core.modules.power.HeatPower.Boiler())
                .presentation(viewerPages("fieldframe:temperatures"))
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.HeatPower.STEAM_TURBINE)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .binding(BindingSpec.fluid("steam", BindingSpec.OUT))
                .binding(BindingSpec.optionalFluid("water_out", BindingSpec.IN))
                .binding(BindingSpec.signalOut("running"))
                .tunable(TunableSpec.of("max_steam", 1, 10_000, 40, "mB/t"))
                .tunable(TunableSpec.of("rf_per_mb", 0, 1_000, 4, "RF/mB"))
                .tunable(TunableSpec.of("spin_up_ticks", 1, 6_000, 300, "ticks"))
                .tunable(TunableSpec.of("coast_ticks", 1, 12_000, 600, "ticks"))
                .tunable(TunableSpec.of("cold_return", 0, 1, 0.9, "x"))
                .tunable(TunableSpec.of("air_return", 0, 1, 0.5, "x"))
                .behavior(d -> new dev.fieldframe.core.modules.power.HeatPower.Turbine())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.HeatPower.CONDENSER)
                .category(ModuleCategory.GENERATION).phase(TickPhase.CONTROL).stage(Stage.AUTOMATION)
                .behavior(d -> new dev.fieldframe.core.modules.power.HeatPower.Condenser())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.HeatPower.HOT_JUNCTION)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.WORKSHOP)
                .binding(BindingSpec.item("thermoelements", BindingSpec.SUPPLY))
                .tunable(TunableSpec.of("rf_per_kelvin", 0, 10, 0.02, "RF/t per K"))
                .tunable(TunableSpec.of("max_rf_per_tick", 0, 10_000, 32, "RF/t"))
                .tunable(TunableSpec.of("waste_max", 0, 10_000, 5, "RF/t"))
                .tunable(TunableSpec.of("check_interval", 20, 1_200, 40, "ticks"))
                .behavior(d -> new dev.fieldframe.core.modules.power.HeatPower.HotJunction())
                .presentation(viewerPages("fieldframe:temperatures"))
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.HeatPower.COLD_JUNCTION)
                .category(ModuleCategory.GENERATION).phase(TickPhase.CONTROL).stage(Stage.WORKSHOP)
                .behavior(d -> new dev.fieldframe.core.modules.power.HeatPower.ColdJunction())
                .presentation(viewerPages("fieldframe:temperatures"))
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.WindRotor.ID)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.WORKSHOP)
                .faces(LocalFace.FRONT, LocalFace.BACK, LocalFace.LEFT, LocalFace.RIGHT)
                .tunable(TunableSpec.of("max_rf_per_tick", 0, 10_000, 32, "RF/t"))
                .tunable(TunableSpec.of("min_height", -64, 512, 64, "y"))
                .tunable(TunableSpec.of("full_height", -64, 512, 192, "y"))
                .tunable(TunableSpec.of("clear_factor", 0, 4, 0.6, "x"))
                .tunable(TunableSpec.of("rain_factor", 0, 4, 1.0, "x"))
                .tunable(TunableSpec.of("thunder_factor", 0, 4, 1.4, "x"))
                .tunable(TunableSpec.of("nether_factor", 0, 4, 0, "x"))
                .tunable(TunableSpec.of("end_factor", 0, 4, 0.5, "x"))
                .tunable(TunableSpec.of("share_radius", 0, 32, 4, "blocks"))
                .tunable(TunableSpec.of("check_interval", 20, 1_200, 100, "ticks"))
                .behavior(d -> new dev.fieldframe.core.modules.power.WindRotor())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.Treadmill.ID)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.WORKSHOP)
                .faces(LocalFace.FRONT, LocalFace.BACK, LocalFace.LEFT, LocalFace.RIGHT)
                .binding(BindingSpec.item("food", BindingSpec.SUPPLY))
                .tunable(TunableSpec.of("feed_ticks", 20, 72_000, 1_200, "ticks"))
                .tunable(TunableSpec.of("coast_ticks", 1, 72_000, 2_400, "ticks"))
                .tunable(TunableSpec.of("hold_interval", 1, 100, 5, "ticks"))
                .behavior(d -> new dev.fieldframe.core.modules.power.Treadmill())
                .presentation(viewerPages("fieldframe:treadmill_animals"))
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.LunarPrism.ID)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.WORKSHOP)
                .faces(LocalFace.TOP).needsSky(true)
                .tunable(TunableSpec.of("rf_per_tick", 0, 1_000, 16, "RF/t"))
                .tunable(TunableSpec.of("rain_factor", 0, 1, 0.5, "x"))
                .tunable(TunableSpec.of("check_interval", 20, 1_200, 100, "ticks"))
                .behavior(d -> new dev.fieldframe.core.modules.power.LunarPrism())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.GeothermalProbe.ID)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .faces(LocalFace.BOTTOM)
                .tunable(TunableSpec.of("max_rf_per_tick", 0, 10_000, 60, "RF/t"))
                .tunable(TunableSpec.of("full_depth_y", -64, 320, 0, "y"))
                .tunable(TunableSpec.of("half_depth_y", -64, 320, 40, "y"))
                .tunable(TunableSpec.of("nether_factor", 0, 8, 1, "x"))
                .tunable(TunableSpec.of("share_radius", 0, 64, 24, "blocks"))
                .tunable(TunableSpec.of("check_interval", 20, 1_200, 100, "ticks"))
                .behavior(d -> new dev.fieldframe.core.modules.power.GeothermalProbe())
                .presentation(viewerPages("fieldframe:temperatures"))
                .build(), src);
    }

    /** Burst and chemistry generators: Storm Coil, Blast Damper, Galvanic Cell. */
    private static void registerBurstModules(String src) {
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.BurstPower.STORM_COIL)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.FIELD)
                .faces(LocalFace.TOP)
                .binding(BindingSpec.signalOut("strike")).binding(BindingSpec.signalOut("level"))
                .tunable(TunableSpec.of("strike_rf", 0, 100_000_000, 200_000, "RF"))
                .tunable(TunableSpec.of("capacity", 1_000, 100_000_000, 400_000, "RF"))
                .tunable(TunableSpec.of("release_per_tick", 1, 100_000, 512, "RF/t"))
                .behavior(d -> new dev.fieldframe.core.modules.power.BurstPower.StormCoil())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.BurstPower.BLAST_DAMPER)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.FIELD)
                .binding(BindingSpec.item("fuel", BindingSpec.SUPPLY)).binding(BindingSpec.signalOut("level"))
                .tunable(TunableSpec.of("capture_radius", 1, 16, 6, "blocks"))
                .tunable(TunableSpec.of("rf_per_power2", 0, 1_000_000, 3_500, "RF"))
                .tunable(TunableSpec.of("capacity", 1_000, 100_000_000, 500_000, "RF"))
                .tunable(TunableSpec.of("release_per_tick", 1, 100_000, 512, "RF/t"))
                .tunable(TunableSpec.of("chamber_tier", 0, 10, 2, "tier"))
                .tunable(TunableSpec.of("chamber_interval", 20, 12_000, 100, "ticks"))
                .behavior(d -> new dev.fieldframe.core.modules.power.BurstPower.BlastDamper())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(dev.fieldframe.core.modules.power.GalvanicCell.ID)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("anode", BindingSpec.IN))
                .binding(BindingSpec.item("cathode", BindingSpec.SUPPLY))
                .binding(BindingSpec.fluid("electrolyte", BindingSpec.IN))
                .tunable(TunableSpec.of("rf_per_volt", 0, 10_000, 25, "RF/t per V"))
                .tunable(TunableSpec.of("max_rf_per_tick", 0, 100_000, 128, "RF/t"))
                .tunable(TunableSpec.of("anode_ticks", 1, 1_000_000, 1_200, "ticks"))
                .tunable(TunableSpec.of("electrolyte_per_tick", 1, 1_000, 1, "mB/t"))
                .behavior(d -> new dev.fieldframe.core.modules.power.GalvanicCell())
                .presentation(viewerPages("fieldframe:galvanic_electrodes"))
                .build(), src);
    }

    private static void registerFieldModules(String src) {
        // ---- Handling (later stages) ----
        FieldframeRegistry.registerModule(ModuleDefinition.builder(SERVICE_PORT)
                .category(ModuleCategory.HANDLING).phase(TickPhase.IMPORT).stage(Stage.FIELD)
                .exposes(Exposure.ITEM, Exposure.FLUID, Exposure.ENERGY)
                .binding(BindingSpec.item("lane", 0)).binding(BindingSpec.fluid("tank", 0)).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.bool("items", true)).config(ConfigField.bool("fluids", true)).config(ConfigField.bool("energy", true))
                .config(ConfigField.integer("item_limit", 1, 64, 8))
                .config(ConfigField.integer("fluid_limit", 1, 4000, 250))
                .config(ConfigField.itemFilter("filter")).config(ConfigField.fluidFilter("fluid_filter"))
                .behavior(d -> new ServicePortBehavior())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(DROP_COLLECTOR)
                .category(ModuleCategory.HANDLING).phase(TickPhase.IMPORT).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("lane", 0)).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.integer("range", 1, 3, 2)).config(ConfigField.itemFilter("filter"))
                .tunable(TunableSpec.of("items_per_pickup", 1, 256, 16, "items"))
                .tunable(TunableSpec.of("interval", 1, 200, 5, "ticks"))
                .behavior(d -> new FieldBehaviors.DropCollector())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(ITEM_EJECTOR)
                .category(ModuleCategory.HANDLING).phase(TickPhase.EXPORT).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("lane", 1)).binding(BindingSpec.signalIn("enable")).binding(BindingSpec.signalIn("trigger"))
                .config(ConfigField.choice("mode", "continuous", "continuous", "pulse"))
                .config(ConfigField.integer("quantity", 1, 64, 1))
                .config(ConfigField.integer("delay", 1, 1200, 10))
                .config(ConfigField.itemFilter("filter"))
                .behavior(d -> new FieldBehaviors.ItemEjector())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(FLUID_PUMP)
                .category(ModuleCategory.HANDLING).phase(TickPhase.IMPORT).stage(Stage.FIELD)
                .binding(BindingSpec.fluid("tank", 0)).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.choice("mode", "pump", "pump", "fill"))
                .config(ConfigField.integer("radius", 0, 4, 1))
                .config(ConfigField.integer("depth", 1, 8, 3))
                .tunable(TunableSpec.of("rf_per_block", 0, 10_000, 40, "RF"))
                .tunable(TunableSpec.of("interval", 1, 200, 10, "ticks"))
                .behavior(d -> new FieldBehaviors.FluidPump())
                .build(), src);

        // ---- Processing ----
        FieldframeRegistry.registerModule(ModuleDefinition.builder(RECIPE_ASSEMBLER)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("input", 0)).binding(BindingSpec.item("output", 1)).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.text("pattern", 9 * 48 + 16, "minecraft:oak_log"))
                .tunable(TunableSpec.of("rf_per_craft", 0, 10_000, 20, "RF"))
                .tunable(TunableSpec.of("interval", 1, 200, 10, "ticks"))
                .behavior(d -> new RecipeAssemblerBehavior())
                .build(), src);

        // ---- Generation ----
        FieldframeRegistry.registerModule(ModuleDefinition.builder(WATERWHEEL)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .faces(LocalFace.FRONT, LocalFace.BACK, LocalFace.LEFT, LocalFace.RIGHT, LocalFace.BOTTOM)
                .binding(BindingSpec.optionalFluid("fluid"))
                .config(ConfigField.choice("mode", "wheel", "wheel", "turbine"))
                .tunable(TunableSpec.of("rf_per_flow", 0, 1_000, 3, "RF/t"))
                .tunable(TunableSpec.of("check_interval", 20, 1200, 100, "ticks"))
                .tunable(TunableSpec.of("mb_per_tick", 1, 1_000, 5, "mB/t"))
                .tunable(TunableSpec.of("rf_per_mb", 0, 1_000, 12, "RF/mB"))
                .behavior(d -> new GeneratorBehaviors.Waterwheel())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(MOTION_HARVESTER)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .tunable(TunableSpec.of("enabled", 0, 1, 1, ""))
                .tunable(TunableSpec.of("rf_per_speed", 0, 1_000, 40, "RF"))
                .tunable(TunableSpec.of("max_rf_per_tick", 0, 1_000, 10, "RF/t"))
                .tunable(TunableSpec.of("max_entities", 1, 16, 4, ""))
                .behavior(d -> new GeneratorBehaviors.MotionHarvester())
                .build(), src);

        // Thermal-style engines: fuels come from data/<ns>/fieldframe/fuels (see docs/thermal_parity.md).
        FieldframeRegistry.registerModule(ModuleDefinition.builder(FLUID_ENGINE)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .binding(BindingSpec.fluid("fuel", BindingSpec.SUPPLY)).binding(BindingSpec.signalIn("enable")).binding(BindingSpec.signalOut("running"))
                .tunable(TunableSpec.of("rf_per_tick", 1, 100_000, 80, "RF/t"))
                .tunable(TunableSpec.of("mb_per_batch", 1, 1_000, 10, "mB"))
                .behavior(d -> new MachineBehaviors.FluidEngine())
                .build(), src);
        itemEngine(GEM_ENGINE, MachineBehaviors.ItemEngine.Mode.DATA, 80, src);
        itemEngine(REDSTONE_ENGINE, MachineBehaviors.ItemEngine.Mode.DATA, 60, src);
        itemEngine(FOOD_ENGINE, MachineBehaviors.ItemEngine.Mode.FOOD, 40, src);
        itemEngine(ARCANE_ENGINE, MachineBehaviors.ItemEngine.Mode.ARCANE, 80, src);

        // ---- Thermal-style devices ----
        FieldframeRegistry.registerModule(ModuleDefinition.builder(ITEM_CHARGER)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("input", 0)).binding(BindingSpec.item("output", 1)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("rf_per_tick", 1, 1_000_000, 1_000, "RF/t"))
                .behavior(d -> new MachineBehaviors.Charger())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(BREWER)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("input", 0)).binding(BindingSpec.item("output", 1)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("rf_per_brew", 0, 100_000, 400, "RF"))
                .tunable(TunableSpec.of("interval", 1, 1_200, 100, "ticks"))
                .behavior(d -> new MachineBehaviors.Brewer())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(ENCHANTER)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(Stage.FIELD)
                .binding(BindingSpec.item("input", 0)).binding(BindingSpec.item("output", 1)).binding(BindingSpec.fluid("experience", 0))
                .binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("level", 1, 30, 30, ""))
                .tunable(TunableSpec.of("mb_per_level", 0, 10_000, 100, "mB"))
                .tunable(TunableSpec.of("rf_per_enchant", 0, 1_000_000, 4_000, "RF"))
                .tunable(TunableSpec.of("interval", 1, 1_200, 100, "ticks"))
                .behavior(d -> new MachineBehaviors.Enchanter())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(STONE_EXTRUDER)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.fluid("lava", 0)).binding(BindingSpec.fluid("water", 1)).binding(BindingSpec.item("output", 1))
                .binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.choice("product", "cobblestone", "cobblestone", "stone", "obsidian"))
                .tunable(TunableSpec.of("min_mb", 1, 64_000, 1_000, "mB"))
                .tunable(TunableSpec.of("obsidian_lava_mb", 1, 64_000, 1_000, "mB"))
                .tunable(TunableSpec.of("rf_per_block", 0, 100_000, 0, "RF"))
                .tunable(TunableSpec.of("rf_stone", 0, 100_000, 40, "RF"))
                .tunable(TunableSpec.of("interval", 1, 1_200, 20, "ticks"))
                .behavior(d -> new MachineBehaviors.StoneExtruder())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(NULLIFIER)
                .category(ModuleCategory.HANDLING).phase(TickPhase.EXPORT).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("target", 0)).binding(BindingSpec.optionalFluid("fluid")).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.bool("armed", false)).config(ConfigField.itemFilter("filter"))
                .tunable(TunableSpec.of("items_per_tick", 1, 4_096, 16, ""))
                .tunable(TunableSpec.of("mb_per_tick", 1, 64_000, 1_000, "mB"))
                .behavior(d -> new MachineBehaviors.Nullifier())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(WATER_COLLECTOR)
                .category(ModuleCategory.WORLD).phase(TickPhase.IMPORT).stage(Stage.AUTOMATION)
                .binding(BindingSpec.fluid("water", 0)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("min_sources", 1, 9, 2, ""))
                .tunable(TunableSpec.of("mb_per_source", 1, 1_000, 25, "mB"))
                .tunable(TunableSpec.of("interval", 1, 1_200, 20, "ticks"))
                .behavior(d -> new DeviceBehaviors.WaterCollector())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(FISHER)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("bait", 0)).binding(BindingSpec.item("output", 1)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("min_water", 1, 9, 6, ""))
                .tunable(TunableSpec.of("rf_per_catch", 0, 100_000, 200, "RF"))
                .tunable(TunableSpec.of("interval", 20, 12_000, 600, "ticks"))
                .behavior(d -> new DeviceBehaviors.Fisher())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(TREE_TAPPER)
                .category(ModuleCategory.WORLD).phase(TickPhase.IMPORT).stage(Stage.AUTOMATION)
                .binding(BindingSpec.fluid("sap", 0)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("mb_per_tap", 1, 1_000, 10, "mB"))
                .tunable(TunableSpec.of("interval", 1, 1_200, 40, "ticks"))
                .behavior(d -> new DeviceBehaviors.TreeTapper())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(XP_COLLECTOR)
                .category(ModuleCategory.WORLD).phase(TickPhase.IMPORT).stage(Stage.AUTOMATION)
                .binding(BindingSpec.fluid("experience", 0)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("range", 1, 8, 4, "blocks"))
                .tunable(TunableSpec.of("mb_per_point", 1, 1_000, 20, "mB"))
                .tunable(TunableSpec.of("interval", 1, 200, 10, "ticks"))
                .behavior(d -> new DeviceBehaviors.XpCollector())
                .build(), src);

        FieldframeRegistry.registerModule(ModuleDefinition.builder(AURA_CHAMBER)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.FIELD)
                .binding(BindingSpec.item("blocks", 0)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("rf_per_tick", 0, 10_000, 20, "RF/t"))
                .tunable(TunableSpec.of("range", 1, 16, 6, "blocks"))
                .tunable(TunableSpec.of("interval", 1, 200, 20, "ticks"))
                .tunable(TunableSpec.of("use_amplifier", 0, 1, 0, ""))
                .behavior(d -> new DeviceBehaviors.AuraChamber())
                .presentation(viewerPages("fieldframe:aura_chamber"))
                .build(), src);

        // ---- World ----
        FieldframeRegistry.registerModule(ModuleDefinition.builder(BLOCK_BREAKER)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("output", 1)).binding(BindingSpec.item("tool", 2)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("rf_per_block", 0, 10_000, 60, "RF"))
                .tunable(TunableSpec.of("interval", 1, 200, 10, "ticks"))
                .behavior(d -> new DiggingBehaviors.BlockBreaker())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(BLOCK_PLACER)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("lane", 0)).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.itemFilter("filter"))
                .tunable(TunableSpec.of("rf_per_block", 0, 10_000, 20, "RF"))
                .tunable(TunableSpec.of("interval", 1, 200, 10, "ticks"))
                .behavior(d -> new HandBehaviors.BlockPlacer())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(INTERACTION_HEAD)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.INSTRUMENTATION)
                .binding(BindingSpec.item("held", 2)).binding(BindingSpec.item("output", 1)).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.choice("mode", "use_item", "use_item", "empty_hand"))
                .config(ConfigField.itemFilter("filter"))
                .tunable(TunableSpec.of("rf_per_use", 0, 10_000, 30, "RF"))
                .tunable(TunableSpec.of("interval", 1, 200, 20, "ticks"))
                .behavior(d -> new HandBehaviors.InteractionHead())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(CROP_TENDER)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("lane", 0)).binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.integer("radius", 0, 2, 1))
                .config(ConfigField.bool("replant", true)).config(ConfigField.bool("bonemeal", false))
                .tunable(TunableSpec.of("rf_per_action", 0, 10_000, 20, "RF"))
                .tunable(TunableSpec.of("interval", 1, 200, 10, "ticks"))
                .behavior(d -> new HandBehaviors.CropTender())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(ENTITY_MOVER)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.INSTRUMENTATION)
                .binding(BindingSpec.signalIn("enable"))
                .config(ConfigField.choice("mode", "push", "push", "pull"))
                .config(ConfigField.integer("range", 1, 4, 2))
                .config(ConfigField.integer("strength", 1, 10, 3))
                .config(ConfigField.bool("players", false))
                .tunable(TunableSpec.of("allow_players", 0, 1, 1, ""))
                .tunable(TunableSpec.of("max_strength", 0.1, 2, 1, "b/t"))
                .tunable(TunableSpec.of("max_entities", 1, 32, 8, ""))
                .tunable(TunableSpec.of("rf_per_entity", 0, 1_000, 4, "RF"))
                .tunable(TunableSpec.of("interval", 1, 200, 4, "ticks"))
                .behavior(d -> new FieldBehaviors.EntityMover())
                .build(), src);
        // FLORA feature set: the Fluidic Armor Infuser as a chassis module.
        FieldframeRegistry.registerModule(ModuleDefinition.builder(ARMOR_INFUSER)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(Stage.FIELD)
                .binding(BindingSpec.item("armor", 0)).binding(BindingSpec.fluid("fluid", 0)).binding(BindingSpec.signalIn("enable"))
                .tunable(TunableSpec.of("mb_per_tick", 1, 10_000, 100, "mB/t"))
                .tunable(TunableSpec.of("rf_per_mb", 0, 1_000, 2, "RF/mB"))
                .behavior(d -> new dev.fieldframe.core.flora.ArmorInfuserBehavior())
                .build(), src);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(EXCAVATOR)
                .category(ModuleCategory.WORLD).phase(TickPhase.PROCESS).stage(Stage.FIELD)
                .binding(BindingSpec.item("output", 1)).binding(BindingSpec.item("tool", 2)).binding(BindingSpec.fluid("fluid", 0))
                .binding(BindingSpec.signalIn("enable")).binding(BindingSpec.signalOut("done", 6)).binding(BindingSpec.signalOut("fault", 7))
                .config(ConfigField.integer("radius", 0, 4, 1))
                .config(ConfigField.integer("depth", 1, 32, 4))
                .config(ConfigField.integer("job", 0, 1_000_000, 0))
                .config(ConfigField.bool("preview", true))
                .tunable(TunableSpec.of("rf_per_block", 0, 100_000, 240, "RF"))
                .tunable(TunableSpec.of("fluid_per_block", 0, 4_000, 100, "mB"))
                .tunable(TunableSpec.of("interval", 1, 200, 8, "ticks"))
                .behavior(d -> new DiggingBehaviors.Excavator())
                .build(), src);
    }

    /** Builds the shared processor definition used for every built-in process family. Public for addons. */
    /** A processor whose tank connections are optional, connected to {@code fluidIn} / {@code fluidOut} by default (-1: none). */
    private static void processorWithTanks(FfId id, FfId family, Stage stage, int fluidIn, int fluidOut, String src) {
        ModuleDefinition.Builder b = baseProcessor(id, family, stage);
        b.binding(fluidIn < 0 ? BindingSpec.optionalFluid("fluid_in") : BindingSpec.optionalFluid("fluid_in", fluidIn));
        b.binding(fluidOut < 0 ? BindingSpec.optionalFluid("fluid_out") : BindingSpec.optionalFluid("fluid_out", fluidOut));
        FieldframeRegistry.registerModule(b.build(), src);
    }

    public static ModuleDefinition.Builder processorBuilder(FfId id, FfId family, Stage stage, boolean fluidIn, boolean fluidOut) {
        ModuleDefinition.Builder b = baseProcessor(id, family, stage);
        b.binding(fluidIn ? BindingSpec.fluid("fluid_in", 0) : BindingSpec.optionalFluid("fluid_in"));
        b.binding(fluidOut ? BindingSpec.fluid("fluid_out", 1) : BindingSpec.optionalFluid("fluid_out"));
        return b;
    }

    private static ModuleDefinition.Builder baseProcessor(FfId id, FfId family, Stage stage) {
        ModuleDefinition.Builder b = ModuleDefinition.builder(id)
                .category(ModuleCategory.PROCESSING).phase(TickPhase.PROCESS).stage(stage)
                .processFamily(family)
                .binding(BindingSpec.item("input", 0))
                .binding(BindingSpec.item("output", 1))
                .binding(BindingSpec.signalIn("enable"))
                .binding(BindingSpec.signalOut("active"))
                .binding(BindingSpec.signalOut("done"))
                .tunable(TunableSpec.of("speed", 0.1, 16, 1, "x"))
                .tunable(TunableSpec.of("energy_multiplier", 0, 16, 1, "x"))
                .behavior(ProcessorBehavior::new);
        return b;
    }

    private static void itemEngine(FfId id, MachineBehaviors.ItemEngine.Mode mode, int rate, String src) {
        ModuleDefinition.Builder b = ModuleDefinition.builder(id)
                .category(ModuleCategory.GENERATION).phase(TickPhase.GENERATE).stage(Stage.AUTOMATION)
                .binding(BindingSpec.item("fuel", BindingSpec.SUPPLY)).binding(BindingSpec.signalIn("enable")).binding(BindingSpec.signalOut("running"))
                .tunable(TunableSpec.of("rf_per_tick", 1, 100_000, rate, "RF/t"));
        if (mode == MachineBehaviors.ItemEngine.Mode.FOOD) {
            b.tunable(TunableSpec.of("rf_per_point", 0, 1_000_000, 1_600, "RF"));
        } else if (mode == MachineBehaviors.ItemEngine.Mode.ARCANE) {
            b.tunable(TunableSpec.of("rf_per_level", 0, 1_000_000, 4_000, "RF"));
        }
        FieldframeRegistry.registerModule(b.behavior(d -> new MachineBehaviors.ItemEngine(mode, id)).build(), src);
    }

    private static void processor(FfId id, FfId family, Stage stage, boolean fluidIn, boolean fluidOut, String src) {
        FieldframeRegistry.registerModule(processorBuilder(id, family, stage, fluidIn, fluidOut).build(), src);
    }
}
