package dev.fieldframe.core.modules.power;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Heat generators: the Steam Loop (Boiler, Steam Turbine, Condenser) and the Thermocouple (Hot and Cold Junction).
 * Temperatures come from the temperature table ({@code core/material/Temperatures}) through
 * {@link WorldActions#temperature}; modules on the same chassis find each other through
 * {@link ModuleContext.ChassisView#peers()}.
 */
public final class HeatPower {
    private HeatPower() {
    }

    public static final FfId BOILER = FfId.ff("boiler");
    public static final FfId STEAM_TURBINE = FfId.ff("steam_turbine");
    public static final FfId CONDENSER = FfId.ff("condenser");
    public static final FfId HOT_JUNCTION = FfId.ff("hot_junction");
    public static final FfId COLD_JUNCTION = FfId.ff("cold_junction");
    public static final FfId STEAM = FfId.ff("steam");
    public static final FfId WATER = FfId.of("minecraft", "water");

    /** Working modules on the same chassis whose waste heat a Hot Junction can use. */
    public static final Set<FfId> WASTE_HEAT = Set.of(FfId.ff("heater"), FfId.ff("magma_crucible"), FfId.ff("pyrolyzer"),
            FfId.ff("alloy_smelter"), BOILER);
    /** Temperature a Hot Junction sees from a working Heater (or similar) on the same chassis. */
    public static final int WASTE_HEAT_K = 700;
    /** A condenser whose face touches something at or below this returns most of the water. */
    public static final int CONDENSER_COLD_K = 300;

    /** Temperature of the block in front of {@code face}, or -1 when the world cannot be read there. */
    public static int kelvinAt(ModuleContext ctx, LocalFace face) {
        WorldActions a = ctx.world().actions();
        return a == null ? -1 : a.temperature(WorkTarget.adjacent(face));
    }

    static boolean peerWorking(ModuleContext ctx, Set<FfId> ids) {
        for (ModuleContext.Peer p : ctx.chassis().peers()) {
            if (p.working() && ids.contains(p.definition().id()) && p.face() != ctx.face()) {
                return true;
            }
        }
        return false;
    }

    static List<LocalFace> peerFaces(ModuleContext ctx, FfId id) {
        List<LocalFace> out = new ArrayList<>();
        for (ModuleContext.Peer p : ctx.chassis().peers()) {
            if (p.definition().id().equals(id)) {
                out.add(p.face());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ thermocouple

    /**
     * Hot Junction: power from the temperature difference between the block in front of it and the block in front of
     * a Cold Junction on the same chassis. Junctions pair in side order (the first Hot Junction with the first Cold
     * Junction). When nothing hotter than {@value #WASTE_HEAT_K} K is in front, a working Heater (or Magma Crucible,
     * Pyrolyzer, Alloy Smelter, Boiler) on the chassis counts as that much, capped at {@code waste_max} RF/t.
     */
    /**
     * Junction grade from the thermoelement metals in the Hot Junction's {@code thermoelements} storage: the two most
     * different electrode potentials ({@code core/material/Electrodes}) set it, from x1 (copper and iron, 0.78 V) to x2
     * (2.28 V or more, such as zinc and gold); no metals, or only one kind, is x1.
     */
    public static double grade(ItemStore metals, dev.fieldframe.api.resource.TagLookup tags) {
        if (metals == null) {
            return 1.0;
        }
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int s = 0; s < metals.slots(); s++) {
            Double v = dev.fieldframe.core.material.Electrodes.volts(metals.variant(s), tags);
            if (v != null) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        if (lo > hi) {
            return 1.0;
        }
        return 1.0 + Math.max(0.0, Math.min(1.0, (hi - lo - 0.78) / 1.5));
    }

    public static final class HotJunction implements ModuleBehavior {
        private long nextCheck;
        private long rate;
        private double grade = 1.0;
        private Status status = Statuses.idle();

        @Override
        public void tick(ModuleContext ctx) {
            long now = ctx.gameTime();
            if (now >= nextCheck) {
                nextCheck = now + Math.max(20, Math.round(ctx.tunable("check_interval")));
                measure(ctx);
            }
            if (rate <= 0) {
                ctx.setStatus(status);
                ctx.sleep((int) Math.max(1, nextCheck - now));
                return;
            }
            long got = ctx.energy().generate(rate);
            ctx.setStatus(got > 0 ? status : Statuses.BUFFER_FULL);
        }

        private void measure(ModuleContext ctx) {
            rate = 0;
            List<LocalFace> hots = peerFaces(ctx, HOT_JUNCTION);
            List<LocalFace> colds = peerFaces(ctx, COLD_JUNCTION);
            int mine = hots.indexOf(ctx.face());
            if (mine < 0 || mine >= colds.size()) {
                status = Status.of("NEEDS_COLD_JUNCTION", Status.Severity.BLOCKED);
                return;
            }
            int hot = kelvinAt(ctx, ctx.face());
            int cold = kelvinAt(ctx, colds.get(mine));
            if (hot < 0 || cold < 0) {
                status = Statuses.NEIGHBOR_UNLOADED;
                return;
            }
            boolean waste = false;
            if (hot < WASTE_HEAT_K && peerWorking(ctx, WASTE_HEAT)) {
                hot = WASTE_HEAT_K;
                waste = true;
            }
            int diff = hot - cold;
            if (diff <= 0) {
                status = Status.of("NO_TEMPERATURE_DIFFERENCE", Status.Severity.IDLE, String.valueOf(hot), String.valueOf(cold));
                return;
            }
            // Better thermoelements multiply the output and its cap; waste heat stays capped whatever the grade.
            grade = grade(ctx.items("thermoelements"), ctx.world().tags());
            double rf = diff * ctx.tunable("rf_per_kelvin") * grade;
            rf = Math.min(rf, waste ? ctx.tunable("waste_max") : ctx.tunable("max_rf_per_tick") * grade);
            rate = Math.max(0, Math.round(rf));
            status = rate <= 0 ? Status.of("NO_TEMPERATURE_DIFFERENCE", Status.Severity.IDLE, String.valueOf(hot), String.valueOf(cold))
                    : Status.of(waste ? "THERMO_WASTE_HEAT" : "THERMO_RUNNING", Status.Severity.WORKING, String.valueOf(rate), String.valueOf(diff),
                    String.format(java.util.Locale.ROOT, "%.2f", grade));
        }

        @Override
        public String detail() {
            return rate + " RF/t";
        }
    }

    /** Cold Junction: the cold side of a Hot Junction on the same chassis; shows its temperature. */
    public static final class ColdJunction implements ModuleBehavior {
        @Override
        public void tick(ModuleContext ctx) {
            int k = kelvinAt(ctx, ctx.face());
            boolean paired = peerWorking(ctx, Set.of(HOT_JUNCTION));
            ctx.setStatus(Status.of(paired ? "COLD_SIDE_WORKING" : "COLD_SIDE", paired ? Status.Severity.WORKING : Status.Severity.IDLE,
                    String.valueOf(Math.max(0, k))));
            ctx.sleep(40);
        }
    }

    // ------------------------------------------------------------------ steam loop

    /**
     * Boiler: turns water from its {@code water} tank (Orange) into steam in its {@code steam} tank (Green). Heat comes
     * from the block in front when it is at least {@code heat_kelvin} (lava, magma), otherwise from burning furnace fuel
     * from {@code fuel} (Purple): burn time / {@code burn_divisor} ticks per item, like the Fuel Engine. It never burns
     * fuel without water to boil and room for the steam.
     */
    public static final class Boiler implements ModuleBehavior {
        private int burnLeft;
        private int burnTotal;
        private long nextCheck;
        private boolean hotFront;

        @Override
        public boolean burns(ItemVariant item, dev.fieldframe.api.module.ChassisWorld world) {
            return world.fuelBurnTicks(item) > 0;
        }

        @Override
        public double progress() {
            return burnTotal <= 0 ? 0 : burnLeft / (double) burnTotal;
        }

        @Override
        public void tick(ModuleContext ctx) {
            if (!ctx.enabledBy("enable")) {
                ctx.setStatus(Statuses.signalDisabled(ctx.bindingLabel("enable")));
                return;
            }
            FluidStore water = ctx.fluids("water");
            FluidStore steam = ctx.fluids("steam");
            if (water == null || steam == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            long now = ctx.gameTime();
            if (now >= nextCheck) {
                nextCheck = now + 40;
                int k = kelvinAt(ctx, ctx.face());
                hotFront = k >= ctx.tunable("heat_kelvin");
            }
            FluidVariant w = ctx.world().fluidVariant(WATER);
            FluidVariant s = ctx.world().fluidVariant(STEAM);
            double rate = ctx.tunable("steam_per_tick") * ctx.chassisSpeed();
            // A hot block boils slower than a fire: free steam is a trickle, fuel is the fast route.
            boolean onHeat = hotFront && burnLeft <= 0;
            long want = Math.max(1, Math.round(onHeat ? rate * ctx.tunable("heat_factor") : rate));
            long amount = Math.min(want, Math.min(water.drain(w, want, true), steam.fill(s, want, true)));
            if (amount <= 0) {
                ctx.setStatus(water.amountOf(w) <= 0 ? Statuses.noFluid(ctx.bindingLabel("water")) : Statuses.outputFull(ctx.bindingLabel("steam")));
                ctx.sleep(20);
                return;
            }
            if (!onHeat && burnLeft <= 0 && !refuel(ctx)) {
                return;
            }
            long drained = water.drain(w, amount, false);
            steam.fill(s, drained, false);
            if (!onHeat) {
                burnLeft--;
            }
            ctx.emit("running", 15);
            ctx.setStatus(Status.of(onHeat ? "BOILING_ON_HEAT" : "BOILING", Status.Severity.WORKING, String.valueOf(drained)));
        }

        private boolean refuel(ModuleContext ctx) {
            ItemStore fuel = ctx.items("fuel");
            if (fuel == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return false;
            }
            double divisor = ctx.tunable("burn_divisor");
            for (int i = 0; i < fuel.slots(); i++) {
                ItemVariant v = fuel.variant(i);
                int burn = v == null ? 0 : ctx.world().fuelBurnTicks(v);
                if (burn <= 0) {
                    continue;
                }
                ItemVariant remainder = ctx.world().fuelRemainder(v);
                if (remainder != null && fuel.count(i) > 1 && fuel.insert(remainder, 1, true) < 1) {
                    continue;
                }
                if (fuel.extractSlot(i, 1, false) != 1) {
                    continue;
                }
                if (remainder != null && fuel.insert(remainder, 1, false) < 1) {
                    ctx.spill(remainder, 1);
                }
                burnTotal = Math.max(1, (int) Math.round(burn / divisor));
                burnLeft = burnTotal;
                return true;
            }
            ctx.setStatus(Status.of("NO_HEAT", Status.Severity.IDLE, ctx.bindingLabel("fuel")));
            ctx.sleep(40);
            return false;
        }

        @Override
        public String detail() {
            return hotFront ? "heated" : burnLeft > 0 ? burnLeft + "t left" : "cold";
        }

        @Override
        public FfData save() {
            return new FfData().putInt("burnLeft", burnLeft).putInt("burnTotal", burnTotal);
        }

        @Override
        public void load(FfData d) {
            burnTotal = d.getIntClamped("burnTotal", 0, 0, 1_000_000);
            burnLeft = d.getIntClamped("burnLeft", 0, 0, burnTotal);
        }
    }

    /**
     * Steam Turbine: burns up to {@code max_steam} mB/t of steam from its {@code steam} tank (Green) for
     * {@code rf_per_mb} RF per mB times the rotor speed. The rotor speeds up over {@code spin_up_ticks} while steam
     * flows and coasts down over {@code coast_ticks}; below full speed the rest of the steam vents unused. With a
     * Condenser on the chassis the spent steam comes back as water into {@code water_out} (Orange): 90% when the
     * condenser touches something cold (water, ice, snow), 50% in open air.
     */
    public static final class Turbine implements ModuleBehavior {
        private double speed;
        private long nextCheck;
        private double returnRate;

        @Override
        public double progress() {
            return speed;
        }

        @Override
        public void tick(ModuleContext ctx) {
            FluidStore steam = ctx.fluids("steam");
            if (steam == null) {
                ctx.setStatus(Statuses.UNBOUND);
                return;
            }
            long now = ctx.gameTime();
            if (now >= nextCheck) {
                nextCheck = now + 40;
                returnRate = 0;
                for (LocalFace f : peerFaces(ctx, CONDENSER)) {
                    int k = kelvinAt(ctx, f);
                    returnRate = Math.max(returnRate, k >= 0 && k <= CONDENSER_COLD_K ? ctx.tunable("cold_return") : ctx.tunable("air_return"));
                }
            }
            FluidVariant s = ctx.world().fluidVariant(STEAM);
            long max = Math.max(1, Math.round(ctx.tunable("max_steam") * ctx.chassisSpeed()));
            long available = steam.drain(s, max, true);
            double perMb = ctx.tunable("rf_per_mb");
            if (available > 0) {
                speed = Math.min(1, speed + 1.0 / Math.max(1, ctx.tunable("spin_up_ticks")));
            } else {
                speed = Math.max(0, speed - 1.0 / Math.max(1, ctx.tunable("coast_ticks")));
            }
            if (available <= 0) {
                ctx.setStatus(speed > 0 ? Status.of("TURBINE_COASTING", Status.Severity.IDLE, pct(speed))
                        : Statuses.noFluid(ctx.bindingLabel("steam")));
                if (speed <= 0) {
                    ctx.sleep(20);
                }
                return;
            }
            long room = ctx.energy().capacity() - ctx.energy().stored();
            long use = available;
            if (Math.round(use * perMb * speed) > room) {
                use = Math.max(0, (long) Math.floor(room / Math.max(1e-9, perMb * Math.max(speed, 1e-3))));
            }
            if (use <= 0) {
                ctx.setStatus(Statuses.BUFFER_FULL);
                return;
            }
            long drained = steam.drain(s, use, false);
            long rf = ctx.energy().generate(Math.round(drained * perMb * speed));
            FluidStore back = ctx.fluids("water_out");
            long water = Math.round(drained * returnRate);
            if (back != null && water > 0) {
                back.fill(ctx.world().fluidVariant(WATER), water, false);
            }
            ctx.emit("running", 15);
            ctx.setStatus(speed < 1 ? Status.of("TURBINE_SPINNING_UP", Status.Severity.WORKING, String.valueOf(rf), pct(speed))
                    : Statuses.working(rf + " RF/t"));
        }

        private static String pct(double v) {
            return String.valueOf(Math.round(v * 100));
        }

        @Override
        public String detail() {
            return Math.round(speed * 100) + "%";
        }

        @Override
        public FfData save() {
            return new FfData().putDouble("speed", speed);
        }

        @Override
        public void load(FfData d) {
            speed = Math.max(0, Math.min(1, d.getDouble("speed", 0)));
        }
    }

    /** Condenser: lets a Steam Turbine on the same chassis return its spent steam as water. */
    public static final class Condenser implements ModuleBehavior {
        @Override
        public void tick(ModuleContext ctx) {
            int k = kelvinAt(ctx, ctx.face());
            boolean cold = k >= 0 && k <= CONDENSER_COLD_K;
            boolean running = peerWorking(ctx, Set.of(STEAM_TURBINE));
            ctx.setStatus(Status.of(cold ? "CONDENSING_COLD" : "CONDENSING_AIR", running ? Status.Severity.WORKING : Status.Severity.IDLE));
            ctx.sleep(40);
        }
    }
}
