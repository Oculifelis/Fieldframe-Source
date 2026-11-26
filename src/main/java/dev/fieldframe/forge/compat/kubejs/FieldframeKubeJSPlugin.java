package dev.fieldframe.forge.compat.kubejs;

import com.google.gson.JsonElement;
import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualModel;
import dev.fieldframe.api.manual.ManualRegistry;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.core.script.ScriptModuleBuilder;
import dev.fieldframe.core.script.ScriptRecipes;
import dev.fieldframe.core.script.ScriptedBehavior;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.data.TuningReloadListener;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.EventJS;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.JsonIO;

import java.util.ArrayList;
import java.util.List;

/**
 * KubeJS 6 (Forge 1.20.1, kubejs-forge 2001.6.x) bridge. Script surface (see docs/kubejs):
 * <pre>
 * // startup_scripts
 * FieldframeEvents.registerModules(event => { event.create('kubejs:bell').signalIn('ring').onTick(ctx => ...) })
 * // server_scripts
 * FieldframeEvents.tuning(event => event.tune('fieldframe:fuel_engine', 'rf_per_tick', 60))
 * FieldframeEvents.operationStarting(event => { if (...) event.deny('reason') })
 * FieldframeEvents.operationCompleted(event => ...)
 * FieldframeEvents.fuels(event => { event.fluid('fieldframe:fluid_engine', 'minecraft:water', 1); event.remove('fieldframe:fluid_engine', 'minecraft:lava') })
 * FieldframeEvents.fluidArmor(event => { event.effect('minecraft:lava', 'minecraft:strength', 1); event.remove('minecraft:water') })
 * FieldframeEvents.auras(event => { event.add('kubejs:haste', 'minecraft:gold_block', 2, 'minecraft:haste', 1200, 0); event.remove('fieldframe:metallurgy_adamantine') })
 * FieldframeEvents.recipeFilter(event => { event.remove('fieldframe:alloying/metallurgy/bronze'); event.removeFamily('fieldframe:heating') })
 * // startup_scripts: event.tier('kubejs:mythic', 5, 6.0, 3, 6, 16000, 1000000, 4096) in registerModules
 * ServerEvents.recipes(event => event.custom(Fieldframe.recipes.process('kubejs:smashing').input('minecraft:stone', 1).result('minecraft:gravel', 1).json()))
 * // client_scripts
 * FieldframeEvents.manual(event => event.entry('kubejs:guide/bell', { category: 'kubejs:guide', title: 'Bell', pages: [...] }))
 * </pre>
 * Java listeners for runtime events are registered once; KubeJS itself clears and re-registers script handlers on
 * reload, so repeated reloads never duplicate callbacks.
 */
public final class FieldframeKubeJSPlugin extends KubeJSPlugin {
    public static final EventGroup GROUP = EventGroup.of("FieldframeEvents");
    public static final EventHandler REGISTER_MODULES = GROUP.startup("registerModules", () -> RegisterModulesEventJS.class);
    public static final EventHandler TUNING = GROUP.server("tuning", () -> TuningEventJS.class);
    public static final EventHandler OPERATION_STARTING = GROUP.server("operationStarting", () -> OperationStartingEventJS.class);
    public static final EventHandler WORLD_ACTION = GROUP.server("worldAction", () -> WorldActionEventJS.class);
    public static final EventHandler OPERATION_COMPLETED = GROUP.server("operationCompleted", () -> OperationCompletedEventJS.class);
    public static final EventHandler FUELS = GROUP.server("fuels", () -> FuelsEventJS.class);
    public static final EventHandler FLUID_ARMOR = GROUP.server("fluidArmor", () -> FluidArmorEventJS.class);
    public static final EventHandler AURAS = GROUP.server("auras", () -> AurasEventJS.class);
    public static final EventHandler TEMPERATURES = GROUP.server("temperatures", () -> TemperaturesEventJS.class);
    public static final EventHandler ELECTRODES = GROUP.server("electrodes", () -> ElectrodesEventJS.class);
    public static final EventHandler TREADMILL = GROUP.server("treadmill", () -> TreadmillEventJS.class);
    public static final EventHandler FRAME_COMPONENTS = GROUP.server("frameComponents", () -> FrameComponentsEventJS.class);
    public static final EventHandler UNIFICATION = GROUP.server("unification", () -> UnificationEventJS.class);
    public static final EventHandler RECIPE_FILTER = GROUP.server("recipeFilter", () -> RecipeFilterEventJS.class);
    public static final EventHandler MODULE_INSTALLED = GROUP.server("moduleInstalled", () -> ModuleEventJS.class);
    public static final EventHandler MODULE_REMOVED = GROUP.server("moduleRemoved", () -> ModuleEventJS.class);
    public static final EventHandler MANUAL = GROUP.client("manual", () -> ManualEventJS.class);

    private static final Object OWNER = new Object();
    private static boolean javaListeners;

    @Override
    public void registerEvents() {
        GROUP.register();
    }

    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("Fieldframe", new Bindings());
    }

    @Override
    public void init() {
        ScriptedBehavior.setErrorSink(Fieldframe.LOG::error);
        if (javaListeners) {
            return;
        }
        javaListeners = true;
        FieldframeEvents.register(FieldframeEvents.OperationStarting.class, e -> {
            if (OPERATION_STARTING.hasListeners()) {
                OperationStartingEventJS js = new OperationStartingEventJS(e);
                OPERATION_STARTING.post(ScriptType.SERVER, js);
            }
        }, OWNER);
        FieldframeEvents.register(FieldframeEvents.WorldAction.class, e -> {
            if (WORLD_ACTION.hasListeners()) {
                WORLD_ACTION.post(ScriptType.SERVER, new WorldActionEventJS(e));
            }
        }, OWNER);
        FieldframeEvents.register(FieldframeEvents.OperationCompleted.class, e -> {
            if (OPERATION_COMPLETED.hasListeners()) {
                OPERATION_COMPLETED.post(ScriptType.SERVER, new OperationCompletedEventJS(e));
            }
        }, OWNER);
        FieldframeEvents.register(FieldframeEvents.ModuleInstalled.class, e -> {
            if (MODULE_INSTALLED.hasListeners()) {
                MODULE_INSTALLED.post(ScriptType.SERVER, new ModuleEventJS(e.address(), e.moduleId(), e.face().key()));
            }
        }, OWNER);
        FieldframeEvents.register(FieldframeEvents.ModuleRemoved.class, e -> {
            if (MODULE_REMOVED.hasListeners()) {
                MODULE_REMOVED.post(ScriptType.SERVER, new ModuleEventJS(e.address(), e.moduleId(), e.face().key()));
            }
        }, OWNER);
        dev.fieldframe.api.process.MachineData.addFuelContributor(sink -> {
            if (FUELS.hasListeners()) {
                FUELS.post(ScriptType.SERVER, new FuelsEventJS(sink));
            }
        });
        dev.fieldframe.api.process.MachineData.addFluidArmorContributor(sink -> {
            if (FLUID_ARMOR.hasListeners()) {
                FLUID_ARMOR.post(ScriptType.SERVER, new FluidArmorEventJS(sink));
            }
        });
        dev.fieldframe.api.process.MachineData.addElectrodeContributor(sink -> {
            if (ELECTRODES.hasListeners()) {
                ELECTRODES.post(ScriptType.SERVER, new ElectrodesEventJS(sink));
            }
        });
        dev.fieldframe.api.process.MachineData.addUnificationContributor(sink -> {
            if (UNIFICATION.hasListeners()) {
                UNIFICATION.post(ScriptType.SERVER, new UnificationEventJS(sink));
            }
        });
        dev.fieldframe.api.process.MachineData.addTreadmillContributor(sink -> {
            if (TREADMILL.hasListeners()) {
                TREADMILL.post(ScriptType.SERVER, new TreadmillEventJS(sink));
            }
        });
        dev.fieldframe.api.frame.FrameComponents.addContributor(sink -> {
            if (FRAME_COMPONENTS.hasListeners()) {
                FRAME_COMPONENTS.post(ScriptType.SERVER, new FrameComponentsEventJS(sink));
            }
        });
        dev.fieldframe.api.process.MachineData.addTemperatureContributor(sink -> {
            if (TEMPERATURES.hasListeners()) {
                TEMPERATURES.post(ScriptType.SERVER, new TemperaturesEventJS(sink));
            }
        });
        dev.fieldframe.api.process.MachineData.addAuraContributor(sink -> {
            if (AURAS.hasListeners()) {
                AURAS.post(ScriptType.SERVER, new AurasEventJS(sink));
            }
        });
        dev.fieldframe.api.process.MachineData.addRecipeFilterContributor(sink -> {
            if (RECIPE_FILTER.hasListeners()) {
                RECIPE_FILTER.post(ScriptType.SERVER, new RecipeFilterEventJS(sink));
            }
        });
        TuningReloadListener.addScriptContributor(collector -> {
            if (TUNING.hasListeners()) {
                TUNING.post(ScriptType.SERVER, new TuningEventJS(collector));
            }
        });
        ManualRegistry.setScriptContributors(List.of(builder -> {
            if (MANUAL.hasListeners()) {
                MANUAL.post(ScriptType.CLIENT, new ManualEventJS(builder));
            }
        }));
    }

    /** Called by Fieldframe just before its module registry freezes (item registration). */
    static void postRegisterModules() {
        RegisterModulesEventJS event = new RegisterModulesEventJS();
        REGISTER_MODULES.post(ScriptType.STARTUP, event);
        for (dev.fieldframe.api.chassis.UpgradeDefinition u : event.upgrades) {
            try {
                FieldframeRegistry.registerUpgrade(u);
                Fieldframe.LOG.info("Fieldframe: KubeJS registered service-bay upgrade {}", u.id());
            } catch (RuntimeException ex) {
                ScriptType.STARTUP.console.error("Fieldframe: upgrade " + u.id() + " was not registered: " + ex.getMessage());
            }
        }
        for (dev.fieldframe.api.chassis.ChassisTier t : event.tiers) {
            try {
                FieldframeRegistry.registerTier(t);
                Fieldframe.LOG.info("Fieldframe: KubeJS registered chassis tier {}", t.id());
            } catch (RuntimeException ex) {
                ScriptType.STARTUP.console.error("Fieldframe: tier " + t.id() + " was not registered: " + ex.getMessage());
            }
        }
        for (ScriptModuleBuilder b : event.builders) {
            try {
                ModuleDefinition def = b.build();
                if (b.isReplace()) {
                    FieldframeRegistry.replaceModule(def, "kubejs startup script");
                } else {
                    FieldframeRegistry.registerModule(def, "kubejs startup script");
                }
                Fieldframe.LOG.info("Fieldframe: KubeJS registered module {}", def.id());
            } catch (RuntimeException ex) {
                ScriptType.STARTUP.console.error("Fieldframe: module " + b.id() + " was not registered: " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ events

    public static final class RegisterModulesEventJS extends EventJS {
        final List<ScriptModuleBuilder> builders = new ArrayList<>();

        /** Starts a new module definition; finish configuring it with the returned builder. */
        public ScriptModuleBuilder create(String id) {
            ScriptModuleBuilder b = new ScriptModuleBuilder(id);
            builders.add(b);
            return b;
        }

        /**
         * Registers a chassis tier above the built-in ones (Resonant is level 4); it gets an upgrade kit item
         * {@code <id>_upgrade_kit}. Arguments: id, level, speed, extra service bays, extra stacks per lane,
         * extra mB per reservoir, extra RF buffer, extra power-terminal RF/t.
         */
        public void tier(String id, int level, double speed, int bays, int slots, long fluid, long energy, long rfPerTick) {
            tiers.add(new dev.fieldframe.api.chassis.ChassisTier(FfId.parse(id), level, speed, bays, slots, fluid, energy, rfPerTick));
        }

        final List<dev.fieldframe.api.chassis.ChassisTier> tiers = new ArrayList<>();
        final List<dev.fieldframe.api.chassis.UpgradeDefinition> upgrades = new ArrayList<>();

        /**
         * Registers a service-bay upgrade with its item {@code <id>}. {@code effects} names what it adds, any of:
         * extraSlotsPerLane (0..9), extraFluidCapacity, extraEnergyCapacity, extraItemsPerTick, extraFluidPerTick,
         * extraEnergyPerTick, extraControllerRules (0..32), overclock (0..1: processors that much faster, for RF and heat).
         * Upgrades only ever add capacity, throughput, control memory or overclock.
         */
        public void upgrade(String id, java.util.Map<String, Object> effects) {
            java.util.function.ToLongFunction<String> n = k -> effects != null && effects.get(k) instanceof Number num ? num.longValue() : 0L;
            for (String k : effects == null ? java.util.Set.<String>of() : effects.keySet()) {
                if (!java.util.Set.of("extraSlotsPerLane", "extraFluidCapacity", "extraEnergyCapacity", "extraItemsPerTick",
                        "extraFluidPerTick", "extraEnergyPerTick", "extraControllerRules", "overclock").contains(k)) {
                    throw new IllegalArgumentException("upgrade " + id + ": unknown effect '" + k + "'");
                }
            }
            upgrades.add(new dev.fieldframe.api.chassis.UpgradeDefinition(FfId.parse(id), (int) n.applyAsLong("extraSlotsPerLane"),
                    n.applyAsLong("extraFluidCapacity"), n.applyAsLong("extraEnergyCapacity"), n.applyAsLong("extraItemsPerTick"),
                    n.applyAsLong("extraFluidPerTick"), n.applyAsLong("extraEnergyPerTick"), (int) n.applyAsLong("extraControllerRules"),
                    effects != null && effects.get("overclock") instanceof Number oc ? oc.doubleValue() : 0));
        }

        /** Shorthand for a processing module using the shared engine and recipes of {@code family}. */
        public ScriptModuleBuilder processor(String id, String family) {
            return create(id).processor(family);
        }
    }

    /** Engine fuels after the datapack files: add, replace or remove entries (see {@code MachineData}). */
    public static final class FuelsEventJS extends EventJS {
        private final dev.fieldframe.api.process.MachineData.FuelSink sink;

        FuelsEventJS(dev.fieldframe.api.process.MachineData.FuelSink sink) {
            this.sink = sink;
        }

        /** RF per mB of a fluid (id or #tag) in an engine. */
        public void fluid(String engine, String fluid, long rfPerMb) {
            sink.fluid(FfId.parse(engine), fluid, rfPerMb);
        }

        /** RF per item (id or #tag) in an engine. */
        public void item(String engine, String item, long rf) {
            sink.item(FfId.parse(engine), item, rf);
        }

        public void remove(String engine, String key) {
            sink.remove(FfId.parse(engine), key);
        }

        /** Removes every fuel of an engine. */
        public void clear(String engine) {
            sink.clear(FfId.parse(engine));
        }
    }

    /** Fluid armor effects after the datapack files (see {@code FluidArmorEffects}): fluid id or #tag, mob effect id. */
    public static final class FluidArmorEventJS extends EventJS {
        private final dev.fieldframe.api.process.MachineData.FluidArmorSink sink;

        FluidArmorEventJS(dev.fieldframe.api.process.MachineData.FluidArmorSink sink) {
            this.sink = sink;
        }

        /** The effect at {@code amplifier} from 1000 mB worn. */
        public void effect(String fluid, String effect, int amplifier) {
            sink.effect(fluid, FfId.parse(effect), amplifier, dev.fieldframe.core.flora.FluidArmorEffects.DEFAULT_MIN_MB, 0, amplifier);
        }

        /** Full form: from {@code minMb} worn, +1 level per {@code mbPerLevel} (0 = fixed) up to {@code maxAmplifier}. */
        public void effect(String fluid, String effect, int amplifier, long minMb, long mbPerLevel, int maxAmplifier) {
            sink.effect(fluid, FfId.parse(effect), amplifier, minMb, mbPerLevel, maxAmplifier);
        }

        /** Removes every effect of this fluid key (as written: id or #tag). */
        public void remove(String fluid) {
            sink.remove(fluid);
        }
    }

    /** The Tag Unifier's preferred mods: prefer('thermal') puts a mod first, avoid('mekanism') drops one. */
    public static final class UnificationEventJS extends EventJS {
        private final dev.fieldframe.api.process.MachineData.UnificationSink sink;

        UnificationEventJS(dev.fieldframe.api.process.MachineData.UnificationSink sink) {
            this.sink = sink;
        }

        public void prefer(String namespace) {
            sink.prefer(namespace);
        }

        public void avoid(String namespace) {
            sink.avoid(namespace);
        }
    }

    /** Blocks a multiblock frame accepts inside its walls, after the datapack files (see FrameComponents). */
    public static final class FrameComponentsEventJS extends EventJS {
        private final dev.fieldframe.api.frame.FrameComponents.Sink sink;

        FrameComponentsEventJS(dev.fieldframe.api.frame.FrameComponents.Sink sink) {
            this.sink = sink;
        }

        /** What one block adds: slots to each storage, mB to each tank, RF, a speed bonus and a role (e.g. "crucible"). */
        public void component(String block, int slotsPerLane, long mbPerTank, long rf, double speed, String role) {
            sink.component(block, slotsPerLane, mbPerTank, rf, speed, role);
        }

        public void remove(String block) {
            sink.remove(block);
        }
    }

    /** Animals for the Treadmill, after the datapack files: entity id, RF a tick (or per point of speed) and food. */
    public static final class TreadmillEventJS extends EventJS {
        private final dev.fieldframe.api.process.MachineData.TreadmillSink sink;

        TreadmillEventJS(dev.fieldframe.api.process.MachineData.TreadmillSink sink) {
            this.sink = sink;
        }

        /** A fixed {@code rfPerTick}; {@code food} is an item id or #tag. */
        public void mob(String entity, double rfPerTick, String food) {
            sink.mob(entity, rfPerTick, 0, food);
        }

        /** RF a tick per point of the animal's movement speed (horses: 0.11 to 0.34). */
        public void fastMob(String entity, double rfPerSpeed, String food) {
            sink.mob(entity, 0, rfPerSpeed, food);
        }

        public void remove(String entity) {
            sink.remove(entity);
        }
    }

    /** Electrode potentials (volts) for the Galvanic Cell, after the datapack files. Keys are item ids or #tags. */
    public static final class ElectrodesEventJS extends EventJS {
        private final dev.fieldframe.api.process.MachineData.ElectrodeSink sink;

        ElectrodesEventJS(dev.fieldframe.api.process.MachineData.ElectrodeSink sink) {
            this.sink = sink;
        }

        public void item(String itemOrTag, double volts) {
            sink.item(itemOrTag, volts);
        }

        public void remove(String key) {
            sink.remove(key);
        }
    }

    /** Temperatures (kelvin) the heat generators read, after the datapack files. Keys are ids or #tags. */
    public static final class TemperaturesEventJS extends EventJS {
        private final dev.fieldframe.api.process.MachineData.TemperatureSink sink;

        TemperaturesEventJS(dev.fieldframe.api.process.MachineData.TemperatureSink sink) {
            this.sink = sink;
        }

        public void block(String blockOrTag, int kelvin) {
            sink.block(blockOrTag, kelvin);
        }

        /** Only while the block is lit (furnaces, campfires). */
        public void litBlock(String blockOrTag, int kelvin) {
            sink.litBlock(blockOrTag, kelvin);
        }

        public void fluid(String fluidOrTag, int kelvin) {
            sink.fluid(fluidOrTag, kelvin);
        }

        public void remove(String key) {
            sink.remove(key);
        }
    }

    /** Aura Chamber recipes after the datapack files and Metallurgy's chamber recipes. */
    public static final class AurasEventJS extends EventJS {
        private final dev.fieldframe.api.process.MachineData.AuraSink sink;

        AurasEventJS(dev.fieldframe.api.process.MachineData.AuraSink sink) {
            this.sink = sink;
        }

        /** Adds or replaces (same id): count of ingredient (id or #tag) gives effect for durationTicks at amplifier. */
        public void add(String id, String ingredient, int count, String effect, int durationTicks, int amplifier) {
            sink.add(new dev.fieldframe.api.process.MachineData.Aura(FfId.parse(id), ingredient, count, FfId.parse(effect), durationTicks, amplifier));
        }

        public void remove(String id) {
            sink.remove(FfId.parse(id));
        }
    }

    /**
     * Removes recipes from every Fieldframe machine, JEI and the manual: Fieldframe's own, smelting used by the Heater
     * and the Metallurgy recipes read on NeoForge. {@code remove} takes an id or a pattern ending in {@code *}.
     */
    public static final class RecipeFilterEventJS extends EventJS {
        private final dev.fieldframe.api.process.MachineData.RecipeFilterSink sink;

        RecipeFilterEventJS(dev.fieldframe.api.process.MachineData.RecipeFilterSink sink) {
            this.sink = sink;
        }

        public void remove(String idOrPattern) {
            sink.remove(idOrPattern);
        }

        public void removeFamily(String family) {
            sink.removeFamily(FfId.parse(family));
        }
    }

    public static final class TuningEventJS extends EventJS {
        private final TuningReloadListener.Collector collector;

        TuningEventJS(TuningReloadListener.Collector c) {
            this.collector = c;
        }

        public void tune(String module, String key, double value) {
            collector.tune(FfId.parse(module), key, value, "kubejs server script");
        }

        public void disable(String module) {
            collector.disable(FfId.parse(module));
        }

        public void enable(String module) {
            collector.enable(FfId.parse(module));
        }
    }

    public static final class OperationStartingEventJS extends EventJS {
        private final FieldframeEvents.OperationStarting e;

        OperationStartingEventJS(FieldframeEvents.OperationStarting e) {
            this.e = e;
        }

        public String getModuleId() {
            return e.moduleId().toString();
        }

        public String getRecipeId() {
            return e.recipeId().toString();
        }

        public String getFamily() {
            return e.family().toString();
        }

        public String getAddress() {
            return e.address();
        }

        /** Prevents the operation from starting. Inputs are untouched (the event fires before reservation). */
        public void deny(String reason) {
            e.cancel(reason == null ? "script" : reason);
        }
    }

    /**
     * Before a world module breaks, places, uses, pumps, collects, drops or moves anything. {@code x/y/z} are face-local
     * (depth, right, up). {@code deny} makes the module report PROTECTED and change nothing.
     */
    public static final class WorldActionEventJS extends EventJS {
        private final FieldframeEvents.WorldAction e;

        WorldActionEventJS(FieldframeEvents.WorldAction e) {
            this.e = e;
        }

        public String getModuleId() {
            return e.moduleId().toString();
        }

        public String getAction() {
            return e.action();
        }

        public int getX() {
            return e.x();
        }

        public int getY() {
            return e.y();
        }

        public int getZ() {
            return e.z();
        }

        public String getAddress() {
            return e.address();
        }

        public void deny(String reason) {
            e.cancel(reason == null ? "script" : reason);
        }
    }

    public static final class OperationCompletedEventJS extends EventJS {
        private final FieldframeEvents.OperationCompleted e;

        OperationCompletedEventJS(FieldframeEvents.OperationCompleted e) {
            this.e = e;
        }

        public String getModuleId() {
            return e.moduleId().toString();
        }

        public String getRecipeId() {
            return e.recipeId().toString();
        }

        public String getAddress() {
            return e.address();
        }

        public long getOperationId() {
            return e.operationId();
        }
    }

    public static final class ModuleEventJS extends EventJS {
        private final String address;
        private final FfId moduleId;
        private final String face;

        ModuleEventJS(String address, FfId moduleId, String face) {
            this.address = address;
            this.moduleId = moduleId;
            this.face = face;
        }

        public String getAddress() {
            return address;
        }

        public String getModuleId() {
            return moduleId.toString();
        }

        public String getFace() {
            return face;
        }
    }

    public static final class ManualEventJS extends EventJS {
        private final ManualRegistry.Builder builder;

        ManualEventJS(ManualRegistry.Builder b) {
            this.builder = b;
        }

        public void category(String id, String title, String icon, int sort) {
            builder.category(new ManualModel.Category(FfId.parse(id), title, icon, sort, ""));
        }

        /** Adds an entry from a plain JS object in the manual entry format (docs/manual_format.md). */
        public void entry(String id, Object definition) {
            add(id, definition, false);
        }

        /** Replaces an existing entry (including shipped ones) by explicit id. */
        public void replace(String id, Object definition) {
            add(id, definition, true);
        }

        public void remove(String id) {
            builder.remove(FfId.parse(id));
        }

        private void add(String id, Object definition, boolean replace) {
            JsonElement json = JsonIO.of(definition);
            if (json == null || !json.isJsonObject()) {
                throw new IllegalArgumentException("Manual entry " + id + " must be an object");
            }
            builder.entry(ManualModel.parseEntry(FfId.parse(id), FfJson.fromJson(json.getAsJsonObject()), "kubejs client script"), replace);
        }
    }

    /** {@code Fieldframe} script binding: helpers and read-only lookups. */
    public static final class Bindings {
        /** Recipe JSON builders for {@code event.custom(...)}; 1.20.1 result key is {@code item}. */
        public final ScriptRecipes recipes = new ScriptRecipes("item", false);

        public boolean isModule(String id) {
            FfId f = FfId.tryParse(id);
            return f != null && FieldframeRegistry.module(f) != null;
        }

        public double tunable(String module, String key) {
            ModuleDefinition d = FieldframeRegistry.module(FfId.parse(module));
            if (d == null) {
                throw new IllegalArgumentException("unknown module " + module);
            }
            return FieldframeRegistry.tunable(d, key);
        }

        /** Chassis tiers, lowest first, with what each adds and its kit item (read-only). */
        public List<java.util.Map<String, Object>> tiers() {
            return dev.fieldframe.core.script.ScriptLookups.tiers();
        }

        /** The effective engine fuels table (read-only). */
        public List<java.util.Map<String, Object>> fuels() {
            return dev.fieldframe.core.script.ScriptLookups.fuels();
        }

        /** The effective Aura Chamber table, Metallurgy's chamber recipes included (read-only). */
        public List<java.util.Map<String, Object>> auras() {
            return dev.fieldframe.core.script.ScriptLookups.auras();
        }

        /** Service-bay upgrades with what each adds (read-only). */
        public List<java.util.Map<String, Object>> upgrades() {
            return dev.fieldframe.core.script.ScriptLookups.upgrades();
        }

        /** Summarizes blueprint JSON: validity, chassis and faces, bill of materials, compatibility problems (read-only). */
        public java.util.Map<String, Object> blueprint(String json) {
            return dev.fieldframe.core.script.ScriptLookups.blueprint(json, "fieldframe:chassis");
        }

        public List<String> modules() {
            List<String> out = new ArrayList<>();
            FieldframeRegistry.modules().forEach(d -> out.add(d.id().toString()));
            return out;
        }
        /** Lets a module be installed in a Field Kit (it must work with no neighbours). */
        public void allowInFieldKit(String module) {
            dev.fieldframe.api.chassis.FieldKits.allow(FfId.parse(module));
        }

        /** The modules a Field Kit takes (read-only). */
        public List<String> fieldKitModules() {
            List<String> out = new ArrayList<>();
            dev.fieldframe.api.chassis.FieldKits.allowedModules().forEach(id -> out.add(id.toString()));
            return out;
        }
    }
}
