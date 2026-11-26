package dev.fieldframe.core.modules.power;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.api.world.WorldPos;

/**
 * Burst generators: the Storm Coil (lightning) and the Blast Damper (explosions). Both catch a lot of energy at once
 * into a {@link Capacitor} owned by the module and feed it to the chassis a tick at a time.
 */
public final class BurstPower {
    private BurstPower() {
    }

    public static final FfId STORM_COIL = FfId.ff("storm_coil");
    public static final FfId BLAST_DAMPER = FfId.ff("blast_damper");
    /** Blocks a Storm Coil accepts on top of it (data tag; vanilla lightning rod by default). */
    public static final FfId LIGHTNING_RODS = FfId.ff("lightning_rods");
    /** Items the Blast Damper's chamber detonates (data tag; TNT by default). */
    public static final FfId EXPLOSIVES = FfId.ff("explosives");
    /** Vanilla TNT explosion power. */
    public static final float TNT_POWER = 4f;

    /**
     * Energy held by a module for bursts. Fills at once up to its capacity (the rest of a burst is lost), then feeds
     * the chassis buffer at most {@code rate} RF per tick. Comparator-style level 0..15 for a signal output.
     */
    public static final class Capacitor {
        private long stored;

        public long stored() {
            return stored;
        }

        /** Adds up to {@code capacity}; returns what fit. */
        public long charge(long rf, long capacity) {
            long fit = Math.max(0, Math.min(rf, capacity - stored));
            stored += fit;
            return fit;
        }

        /** Moves up to {@code rate} into the chassis buffer; returns what moved. */
        public long release(ModuleContext ctx, long rate) {
            if (stored <= 0) {
                return 0;
            }
            long got = ctx.energy().generate(Math.min(stored, rate));
            stored -= got;
            return got;
        }

        public int level(long capacity) {
            return stored <= 0 || capacity <= 0 ? 0 : Math.min(15, 1 + (int) Math.floor(14.0 * stored / capacity));
        }

        public void load(FfData d) {
            stored = Math.max(0, d.getLong("stored", 0));
        }

        public FfData save(FfData d) {
            return d.putLong("stored", stored);
        }
    }

    static long capacity(ModuleContext ctx) {
        return Math.round(ctx.tunable("capacity") * ctx.chassisSpeed());
    }

    // ------------------------------------------------------------------ storm coil

    /**
     * Storm Coil: top side only, with a lightning rod ({@code #fieldframe:lightning_rods}) on it. When lightning hits
     * the rod (the rod turns powered for a moment) the coil captures {@code strike_rf} into its capacitor and sends a
     * pulse on {@code strike}; {@code level} reports how full the capacitor is. Only a real rod counts, so a lever
     * or redstone next to the coil never fakes a strike.
     */
    public static final class StormCoil implements ModuleBehavior {
        private final Capacitor cap = new Capacitor();
        private boolean rodPowered;
        private boolean hasRod;
        private long nextCheck;
        private long lastStrikeLost;
        private int pulse;

        @Override
        public void tick(ModuleContext ctx) {
            long now = ctx.gameTime();
            WorldActions a = ctx.world().actions();
            if (a == null) {
                ctx.setStatus(Statuses.NO_WORLD_ACCESS);
                return;
            }
            if (now >= nextCheck) {
                nextCheck = now + 40;
                WorldActions.BlockInfo info = a.probe(WorkTarget.adjacent(ctx.face()));
                hasRod = info.access().ok() && info.block() != null && ctx.world().tags().blockInTag(info.block(), LIGHTNING_RODS);
            }
            long capacity = capacity(ctx);
            boolean powered = hasRod && ctx.world().redstoneInput(ctx.face()) > 0;
            if (powered && !rodPowered) {
                long strike = Math.round(ctx.tunable("strike_rf"));
                long fit = cap.charge(strike, capacity);
                lastStrikeLost = strike - fit;
                pulse = 4;
                ctx.trace("lightning: +" + fit + " RF" + (lastStrikeLost > 0 ? ", " + lastStrikeLost + " lost (full)" : ""));
            }
            rodPowered = powered;
            if (pulse > 0) {
                pulse--;
                ctx.emit("strike", 15);
            }
            long out = cap.release(ctx, Math.round(ctx.tunable("release_per_tick")));
            ctx.emit("level", cap.level(capacity));
            if (!hasRod) {
                ctx.setStatus(Status.of("NEEDS_LIGHTNING_ROD", Status.Severity.BLOCKED));
            } else if (out > 0) {
                ctx.setStatus(Status.of("RELEASING_CHARGE", Status.Severity.WORKING, String.valueOf(out), String.valueOf(cap.stored())));
            } else if (cap.stored() > 0) {
                ctx.setStatus(Statuses.BUFFER_FULL);
            } else {
                ctx.setStatus(Status.of("WAITING_FOR_LIGHTNING", Status.Severity.IDLE));
            }
        }

        @Override
        public String detail() {
            return cap.stored() + " RF stored";
        }

        @Override
        public FfData save() {
            return cap.save(new FfData()).putBool("rod", rodPowered);
        }

        @Override
        public void load(FfData d) {
            cap.load(d);
            rodPowered = d.getBool("rod", false);
        }
    }

    // ------------------------------------------------------------------ blast damper

    /**
     * Blast Damper: explosions within {@code capture_radius} blocks of the machine are cancelled (nothing breaks,
     * nobody is hurt) and {@code rf_per_power2} x power squared goes into its capacitor (a creeper: 31,500 RF, TNT:
     * 56,000). Only the nearest damper takes an explosion. The platform finds dampers through {@link #registry()},
     * cancels the explosion and calls {@link #queue}; the energy is stored on the damper's next tick. With TNT ({@code #fieldframe:explosives}) in its {@code fuel} storage (Purple) it also
     * detonates one every {@code chamber_interval} ticks inside the chassis, when the chassis is at least
     * {@code chamber_tier} (Reinforced) and the capacitor has room.
     */
    public static final class BlastDamper implements ModuleBehavior {
        private final Capacitor cap = new Capacitor();
        private long nextChamber;
        /** Sum of power squared of the explosions caught since the last tick. */
        private double pending;
        private int caught;

        /** Called by the platform (server thread) for an explosion this damper caught and cancelled. */
        public synchronized void queue(float power) {
            pending += (double) power * power;
            caught++;
        }

        /** Explosions caught so far (for tests and the status line). */
        public int caught() {
            return caught;
        }

        @Override
        public void tick(ModuleContext ctx) {
            long now = ctx.gameTime();
            WorldPos pos = ctx.world().position();
            if (pos != null) {
                REGISTRY.claim(pos, (int) Math.round(ctx.tunable("capture_radius")), now);
            }
            long capacity = capacity(ctx);
            double p2;
            synchronized (this) {
                p2 = pending;
                pending = 0;
            }
            if (p2 > 0) {
                long rf = Math.round(ctx.tunable("rf_per_power2") * p2);
                long fit = cap.charge(rf, capacity);
                ctx.trace("caught an explosion: +" + fit + " RF" + (fit < rf ? ", " + (rf - fit) + " lost (full)" : ""));
            }
            Status chamber = chamber(ctx, now, capacity);
            long out = cap.release(ctx, Math.round(ctx.tunable("release_per_tick")));
            ctx.emit("level", cap.level(capacity));
            if (out > 0) {
                ctx.setStatus(Status.of("RELEASING_CHARGE", Status.Severity.WORKING, String.valueOf(out), String.valueOf(cap.stored())));
            } else if (chamber != null) {
                ctx.setStatus(chamber);
            } else if (cap.stored() > 0) {
                ctx.setStatus(Statuses.BUFFER_FULL);
            } else {
                ctx.setStatus(Status.of("WATCHING_FOR_EXPLOSIONS", Status.Severity.IDLE, String.valueOf(Math.round(ctx.tunable("capture_radius")))));
            }
        }

        /** Detonates one explosive from the fuel storage if allowed; returns a status to show when it cannot. */
        private Status chamber(ModuleContext ctx, long now, long capacity) {
            ItemStore fuel = ctx.items("fuel");
            if (fuel == null || now < nextChamber) {
                return null;
            }
            for (int s = 0; s < fuel.slots(); s++) {
                ItemVariant v = fuel.variant(s);
                if (v == null || !ctx.world().tags().itemInTag(v, EXPLOSIVES)) {
                    continue;
                }
                if (ctx.chassisTier() < ctx.tunable("chamber_tier")) {
                    return Status.of("CHASSIS_TOO_WEAK", Status.Severity.BLOCKED, String.valueOf(Math.round(ctx.tunable("chamber_tier"))));
                }
                long rf = Math.round(ctx.tunable("rf_per_power2") * TNT_POWER * TNT_POWER);
                if (capacity - cap.stored() < rf) {
                    return null;
                }
                if (fuel.extractSlot(s, 1, false) == 1) {
                    cap.charge(rf, capacity);
                    nextChamber = now + Math.max(20, Math.round(ctx.tunable("chamber_interval")));
                    ctx.trace("chamber: detonated " + v.id().path() + ", +" + rf + " RF");
                }
                return null;
            }
            return null;
        }

        @Override
        public String detail() {
            return cap.stored() + " RF stored";
        }

        @Override
        public FfData save() {
            return cap.save(new FfData()).putLong("nextChamber", nextChamber);
        }

        @Override
        public void load(FfData d) {
            cap.load(d);
            nextChamber = d.getLong("nextChamber", 0);
        }
    }

    private static final DamperRegistry REGISTRY = new DamperRegistry();

    /** Where Blast Dampers are, so the platform can find the nearest one for an explosion. */
    public static DamperRegistry registry() {
        return REGISTRY;
    }

    /** Positions of working Blast Dampers with their capture radius; claims expire when a damper stops ticking. */
    public static final class DamperRegistry {
        private final java.util.Map<String, java.util.Map<String, long[]>> claims = new java.util.HashMap<>();

        public synchronized void claim(WorldPos p, int radius, long now) {
            claims.computeIfAbsent(p.dimension(), k -> new java.util.HashMap<>()).put(p.x() + "," + p.y() + "," + p.z(),
                    new long[]{p.x(), p.y(), p.z(), radius, now});
        }

        /** The nearest damper whose radius covers the point, or null. Claims older than {@code ttl} ticks are dropped. */
        public synchronized WorldPos nearest(String dimension, double x, double y, double z, long now, long ttl) {
            java.util.Map<String, long[]> m = claims.get(dimension);
            if (m == null) {
                return null;
            }
            long[] best = null;
            double bestD = Double.MAX_VALUE;
            for (java.util.Iterator<long[]> it = m.values().iterator(); it.hasNext(); ) {
                long[] c = it.next();
                if (Math.abs(now - c[4]) > ttl) {
                    it.remove();
                    continue;
                }
                double dx = x - (c[0] + 0.5);
                double dy = y - (c[1] + 0.5);
                double dz = z - (c[2] + 0.5);
                double d = dx * dx + dy * dy + dz * dz;
                if (d <= c[3] * c[3] && d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            return best == null ? null : new WorldPos(dimension, (int) best[0], (int) best[1], (int) best[2]);
        }

        /** Tests only. */
        public synchronized void clear() {
            claims.clear();
        }
    }
}
