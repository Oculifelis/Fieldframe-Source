package dev.fieldframe.core.endertech;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.core.storage.EnergyBuffer;
import dev.fieldframe.core.storage.FluidReservoir;

/**
 * State of one Ender Tank, owned by its controller block entity: the fluid, the RF that pays for moving it, and
 * whether the structure is currently formed. Contents survive disassembly; a reassembled tank smaller than its
 * contents keeps them and only refuses further filling.
 */
public final class TankCore {
    private final FluidReservoir fluid = new FluidReservoir("tank", 0);
    private final EnergyBuffer energy = new EnergyBuffer(EnderTechRules.TANK_MAX_ENERGY);
    private boolean formed;
    private String problem = "not_checked";
    private long receivedTick = Long.MIN_VALUE;
    private long receivedThisTick;
    private Runnable onChange = () -> { };

    public void setOnChange(Runnable r) {
        onChange = r;
        fluid.setOnChange(r);
        energy.setOnChange(r);
    }

    public void applyStructure(TankStructure.Result r) {
        formed = r.valid();
        problem = r.valid() ? null : r.problem();
        if (formed) {
            fluid.setCapacity(Math.max(r.capacity(), fluid.amount()));
        }
        onChange.run();
    }

    public boolean formed() {
        return formed;
    }

    public String problem() {
        return problem;
    }

    public FluidReservoir fluid() {
        return fluid;
    }

    public EnergyBuffer energy() {
        return energy;
    }

    /** Fluid access through a valve: every mB moved costs {@link EnderTechRules#TANK_RF_PER_MB} RF; unformed = none. */
    public FluidStore valve() {
        return new FluidStore() {
            @Override
            public int tanks() {
                return 1;
            }

            @Override
            public FluidVariant fluid(int tank) {
                return fluid.fluid();
            }

            @Override
            public long amount(int tank) {
                return fluid.amount();
            }

            @Override
            public long capacity(int tank) {
                return fluid.capacity();
            }

            private long affordable(long req) {
                return Math.min(req, energy.stored() / EnderTechRules.TANK_RF_PER_MB);
            }

            @Override
            public long fill(FluidVariant v, long amount, boolean simulate) {
                if (!formed || amount <= 0) {
                    return 0;
                }
                long n = fluid.fill(v, affordable(amount), simulate);
                if (!simulate && n > 0) {
                    energy.extract(n * EnderTechRules.TANK_RF_PER_MB, false);
                }
                return n;
            }

            @Override
            public long drain(FluidVariant v, long amount, boolean simulate) {
                if (!formed || amount <= 0) {
                    return 0;
                }
                long n = fluid.drain(v, affordable(amount), simulate);
                if (!simulate && n > 0) {
                    energy.extract(n * EnderTechRules.TANK_RF_PER_MB, false);
                }
                return n;
            }
        };
    }

    /** RF access through an energy input: receive only, at most 20,000 RF per tick across all inputs. */
    public EnergyStore energyInput(java.util.function.LongSupplier clock) {
        return new EnergyStore() {
            @Override
            public long stored() {
                return energy.stored();
            }

            @Override
            public long capacity() {
                return energy.capacity();
            }

            @Override
            public long receive(long amount, boolean simulate) {
                if (!formed) {
                    return 0;
                }
                long now = clock.getAsLong();
                if (now != receivedTick) {
                    receivedTick = now;
                    receivedThisTick = 0;
                }
                long room = EnderTechRules.TANK_ENERGY_INPUT_PER_TICK - receivedThisTick;
                long n = energy.receive(Math.min(amount, Math.max(0, room)), simulate);
                if (!simulate) {
                    receivedThisTick += n;
                }
                return n;
            }

            @Override
            public long extract(long amount, boolean simulate) {
                return 0;
            }

            @Override
            public boolean canExtract() {
                return false;
            }
        };
    }

    /** Transaction support: fluid, energy and the per-tick input counter. */
    public Object snapshot() {
        return new Object[] {fluid.snapshot(), energy.snapshot(), receivedTick, receivedThisTick};
    }

    public void restore(Object snapshot) {
        Object[] s = (Object[]) snapshot;
        fluid.restore(s[0]);
        energy.restore((long[]) s[1]);
        receivedTick = (Long) s[2];
        receivedThisTick = (Long) s[3];
    }

    public FfData save(VariantCodec codec) {
        return new FfData().put("fluid", fluid.save(codec)).put("energy", energy.save()).putBool("formed", formed);
    }

    public void load(FfData d, VariantCodec codec) {
        FfData f = d.getDataOrNull("fluid");
        if (f != null) {
            // Restore capacity before contents so a large tank is never clipped on load.
            fluid.setCapacity(Math.max(fluid.capacity(), f.getLong("capacity", 0)));
            fluid.load(f, codec);
        }
        FfData e = d.getDataOrNull("energy");
        if (e != null) {
            energy.load(e);
        }
        formed = d.getBool("formed", false);
        problem = formed ? null : "not_checked";
    }
}
