package dev.fieldframe.frameworks;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleCategory;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TickPhase;
import dev.fieldframe.api.module.TunableSpec;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;

import java.util.Locale;

/**
 * The Starcore's three modules, registered through Fieldframe's public module registry like any addon's: they work on
 * the walls of a Grand Frame that is a Starcore, and find its plasma by their socket's address ({@link Starcores}).
 * <ul>
 *   <li><b>Field Magnet</b>: adds 2 field per signal level (unbound: 15) and costs 40 RF a tick per level while the
 *       plasma is lit;</li>
 *   <li><b>Plasma Injector</b>: injects deuterium from its tank at up to 1 mB a tick (signal level / 15, unbound: 15),
 *       using one Ender Alloy dust per 250 mB;</li>
 *   <li><b>Plasma Probe</b>: puts the pressure (as a share of the field) and the temperature (20 MK a level) on two
 *       signals.</li>
 * </ul>
 */
public final class StarcoreModules {
    private StarcoreModules() {
    }

    public static final FfId FIELD_MAGNET = FfId.of(Frameworks.MOD_ID, "field_magnet");
    public static final FfId PLASMA_INJECTOR = FfId.of(Frameworks.MOD_ID, "plasma_injector");
    public static final FfId PLASMA_PROBE = FfId.of(Frameworks.MOD_ID, "plasma_probe");
    public static final FfId DEUTERIUM = FfId.of(Frameworks.MOD_ID, "deuterium");
    public static final FfId ENDER_ALLOY_DUST = FfId.of("fieldframe", "ender_alloy_dust");
    public static final FfId MANUAL = FfId.of(Frameworks.MOD_ID, "starcore");

    static void register() {
        FieldframeRegistry.registerModule(ModuleDefinition.builder(FIELD_MAGNET)
                .category(ModuleCategory.GENERATION)
                .phase(TickPhase.GENERATE)
                .stage(ModuleDefinition.Stage.FIELD)
                .binding(BindingSpec.signalIn("level"))
                .tunable(TunableSpec.of("rf_per_level", 0, 1_000, Plasma.rfPerLevel(), "RF/t"))
                .behavior(def -> new FieldMagnet())
                .manualEntry(MANUAL)
                .presentation(new FfData().putString("face_texture", Frameworks.MOD_ID + ":block/module/field_magnet"))
                .build(), Frameworks.MOD_ID);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(PLASMA_INJECTOR)
                .category(ModuleCategory.GENERATION)
                .phase(TickPhase.GENERATE)
                .stage(ModuleDefinition.Stage.FIELD)
                .binding(BindingSpec.fluid("fuel", 0))
                .binding(BindingSpec.item("catalyst", 2))
                .binding(BindingSpec.signalIn("rate"))
                .tunable(TunableSpec.of("max_mb_per_tick", 0.1, 10, 1, "mB/t"))
                .behavior(def -> new PlasmaInjector())
                .manualEntry(MANUAL)
                .presentation(new FfData().putString("face_texture", Frameworks.MOD_ID + ":block/module/plasma_injector"))
                .build(), Frameworks.MOD_ID);
        FieldframeRegistry.registerModule(ModuleDefinition.builder(PLASMA_PROBE)
                .category(ModuleCategory.CONTROL)
                .phase(TickPhase.CONTROL)
                .stage(ModuleDefinition.Stage.FIELD)
                .binding(BindingSpec.signalOut("pressure", 0))
                .binding(BindingSpec.signalOut("temperature", 1))
                .behavior(def -> new PlasmaProbe())
                .manualEntry(MANUAL)
                .presentation(new FfData().putString("face_texture", Frameworks.MOD_ID + ":block/module/plasma_probe"))
                .build(), Frameworks.MOD_ID);
    }

    private static final Status NO_STARCORE = Status.of("STARCORE_NONE", Status.Severity.IDLE);

    private static Plasma plasma(ModuleContext ctx) {
        return Starcores.at(ctx.world().position());
    }

    private static int level(ModuleContext ctx, String key) {
        int v = ctx.signal(key);
        return v < 0 ? 15 : Math.max(0, Math.min(15, v));
    }

    /** Field Magnet: the containment field, one magnet's share. */
    public static final class FieldMagnet implements ModuleBehavior {
        private int lastLevel;

        @Override
        public void tick(ModuleContext ctx) {
            Plasma p = plasma(ctx);
            lastLevel = 0;
            if (p == null) {
                ctx.setStatus(NO_STARCORE);
                ctx.sleep(20);
                return;
            }
            int level = level(ctx, "level");
            if (!p.needsField() || level == 0) {
                ctx.setStatus(Status.of("STARCORE_MAGNET_IDLE", Status.Severity.IDLE));
                return;
            }
            // Before ignition the magnets hold the gathering fuel for free; a lit plasma costs power.
            long cost = p.lit() ? level * Math.round(ctx.tunable("rf_per_level")) : 0;
            if (cost > 0 && !ctx.energy().consume(cost)) {
                ctx.setStatus(Status.of("STARCORE_MAGNET_NO_POWER", Status.Severity.BLOCKED, String.valueOf(cost)));
                return;
            }
            p.addField(level * Plasma.fieldPerLevel());
            lastLevel = level;
            ctx.setStatus(Status.of("STARCORE_MAGNET_ON", Status.Severity.WORKING, String.valueOf(level),
                    String.valueOf(Math.round(level * Plasma.fieldPerLevel()))));
        }

        @Override
        public String detail() {
            return lastLevel > 0 ? "level " + lastLevel : "off";
        }

        @Override
        public FfData save() {
            return new FfData();
        }

        @Override
        public void load(FfData d) {
        }
    }

    /** Plasma Injector: feeds deuterium (and a little Ender Alloy dust) into the plasma. */
    public static final class PlasmaInjector implements ModuleBehavior {
        private double owed;
        private double dustLeft;
        private int lastRate;

        @Override
        public void tick(ModuleContext ctx) {
            Plasma p = plasma(ctx);
            lastRate = 0;
            if (p == null) {
                ctx.setStatus(NO_STARCORE);
                ctx.sleep(20);
                return;
            }
            FluidStore tank = ctx.fluids("fuel");
            ItemStore catalyst = ctx.items("catalyst");
            if (tank == null || catalyst == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            int rate = level(ctx, "rate");
            if (rate == 0 || !p.wantsFuel()) {
                owed = 0;
                ctx.setStatus(Status.of("STARCORE_INJECTOR_IDLE", Status.Severity.IDLE));
                return;
            }
            owed = Math.min(owed + rate / 15.0 * ctx.tunable("max_mb_per_tick"), 64);
            int mb = (int) Math.floor(owed);
            lastRate = rate;
            if (mb <= 0) {
                ctx.setStatus(Status.of("STARCORE_INJECTING", Status.Severity.WORKING, String.valueOf(rate)));
                return;
            }
            FluidVariant fuel = deuterium(tank);
            if (fuel == null || tank.drain(fuel, mb, true) < mb) {
                ctx.setStatus(Status.of("STARCORE_NO_DEUTERIUM", Status.Severity.IDLE, ctx.bindingLabel("fuel")));
                return;
            }
            while (dustLeft < mb) {
                if (!takeDust(catalyst)) {
                    ctx.setStatus(Status.of("STARCORE_NO_DUST", Status.Severity.IDLE, ctx.bindingLabel("catalyst")));
                    return;
                }
                dustLeft += Plasma.fuelPerDust();
            }
            tank.drain(fuel, mb, false);
            dustLeft -= mb;
            owed -= mb;
            p.inject(mb);
            ctx.setStatus(Status.of("STARCORE_INJECTING", Status.Severity.WORKING, String.valueOf(rate)));
        }

        private static FluidVariant deuterium(FluidStore tank) {
            for (int t = 0; t < tank.tanks(); t++) {
                FluidVariant v = tank.fluid(t);
                if (v != null && v.id().equals(DEUTERIUM) && tank.amount(t) > 0) {
                    return v;
                }
            }
            return null;
        }

        private static boolean takeDust(ItemStore s) {
            for (int i = 0; i < s.slots(); i++) {
                ItemVariant v = s.variant(i);
                if (v != null && v.id().equals(ENDER_ALLOY_DUST) && s.extractSlot(i, 1, false) == 1) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String detail() {
            return lastRate > 0 ? "rate " + lastRate + "/15" : "idle";
        }

        @Override
        public FfData save() {
            return new FfData().putDouble("owed", owed).putDouble("dust", dustLeft);
        }

        @Override
        public void load(FfData d) {
            owed = Math.max(0, Math.min(64, d.getDouble("owed", 0)));
            dustLeft = Math.max(0, Math.min(Plasma.fuelPerDust() * 64, d.getDouble("dust", 0)));
        }
    }

    /** Plasma Probe: pressure and temperature as signals, for the control circuit that keeps the core lit. */
    public static final class PlasmaProbe implements ModuleBehavior {
        private int pressure;
        private int temperature;

        @Override
        public void tick(ModuleContext ctx) {
            Plasma p = plasma(ctx);
            if (p == null) {
                pressure = temperature = 0;
                ctx.emit("pressure", 0);
                ctx.emit("temperature", 0);
                ctx.setStatus(NO_STARCORE);
                return;
            }
            pressure = p.pressureLevel();
            temperature = p.temperatureLevel();
            ctx.emit("pressure", pressure);
            ctx.emit("temperature", temperature);
            if (!p.lit()) {
                ctx.setStatus(Status.of("STARCORE_OUT", Status.Severity.IDLE, String.valueOf(Math.round(p.fuel())),
                        String.valueOf(Math.round(Plasma.ignitionFuel()))));
                return;
            }
            int share = p.field() <= 0 ? 100 : (int) Math.round(p.pressure() / p.field() * 100);
            ctx.setStatus(Status.of("STARCORE_READING", Status.Severity.WORKING, String.valueOf(share),
                    String.format(Locale.ROOT, "%.0f", p.temperature()), String.format(Locale.ROOT, "%,d", p.lastPower())));
        }

        @Override
        public String detail() {
            return "pressure " + pressure + ", temperature " + temperature;
        }

        @Override
        public FfData save() {
            return new FfData();
        }

        @Override
        public void load(FfData d) {
        }
    }
}
