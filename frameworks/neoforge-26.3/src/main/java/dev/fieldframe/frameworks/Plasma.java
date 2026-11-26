package dev.fieldframe.frameworks;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.resource.EnergyStore;

/**
 * The Starcore's plasma: a fuel count and a temperature, and the pressure they make. Loader-independent (unit tested).
 *
 * <p>Each tick a fixed share of the fuel fuses ({@link #burn()}). Fusion heats the plasma in proportion to the fuel in it,
 * and the plasma loses a fixed share of its heat ({@link #loss()}), so with more fuel than {@link #criticalFuel()} it keeps
 * getting hotter and with less it cools down; there is no fuel rate at which it settles by itself. Pressure is fuel
 * times temperature; while it stays at or below the containment field (Field Magnets, at most {@link #fieldPerCoil()} per
 * coil) the plasma makes RF in proportion to its pressure. When pressure passes the field the plasma escapes: the core
 * goes out and its coils wear. Below {@link #minMk()} it fades out quietly.
 *
 * <p>Modules talk to it between ticks: magnets {@link #addField add field}, injectors {@link #inject inject fuel} and
 * probes read the last tick. What modules add in a tick counts on the core's next tick.
 */
public final class Plasma {
    /** Share of its temperature the plasma loses each tick. */
    public static double loss() {
        return FwTuning.get("starcore.loss");
    }

    /** Fuel (mB) at which fusion heat balances the loss. */
    public static double criticalFuel() {
        return FwTuning.get("starcore.critical_fuel");
    }

    /** Share of the fuel that fuses each tick. */
    public static double burn() {
        return FwTuning.get("starcore.burn");
    }

    /** Tuning {@code starcore.pressure_div}. */
    public static double pressureDiv() {
        return FwTuning.get("starcore.pressure_div");
    }

    /** RF a tick per mB of fuel per MK. */
    public static double rfPerUnit() {
        return FwTuning.get("starcore.rf_per_unit");
    }

    /** Tuning {@code starcore.ignition_mk}. */
    public static double ignitionMk() {
        return FwTuning.get("starcore.ignition_mk");
    }

    /** Tuning {@code starcore.min_mk}. */
    public static double minMk() {
        return FwTuning.get("starcore.min_mk");
    }

    /** Tuning {@code starcore.max_mk}. */
    public static double maxMk() {
        return FwTuning.get("starcore.max_mk");
    }

    /** Fuel gathered before ignition. */
    public static double ignitionFuel() {
        return FwTuning.get("starcore.ignition_fuel");
    }

    /** Tuning {@code starcore.ignition_rf}. */
    public static long ignitionRf() {
        return FwTuning.getLong("starcore.ignition_rf");
    }

    /** Field each Field Magnet makes per signal level (0..15). */
    public static double fieldPerLevel() {
        return FwTuning.get("starcore.field_per_level");
    }

    /** RF a tick a Field Magnet costs per signal level. */
    public static long rfPerLevel() {
        return FwTuning.getLong("starcore.rf_per_level");
    }

    /** The most field each containment coil can hold. */
    public static double fieldPerCoil() {
        return FwTuning.get("starcore.field_per_coil");
    }

    /** Escapes a coil takes before one breaks. */
    public static int escapesPerCoil() {
        return FwTuning.getInt("starcore.escapes_per_coil");
    }

    /** Fuel an Ender Alloy dust is good for (mB). */
    public static double fuelPerDust() {
        return FwTuning.get("starcore.fuel_per_dust");
    }

    public enum Event { NONE, IGNITED, ESCAPED, FADED }

    private double fuel;
    private double temperature;
    private boolean lit;
    private int wear;
    private int coils;
    private double field;
    private double pendingField;
    private double pendingFuel;
    private long lastRf;
    private long lastPower;
    private long escapes;

    public void setCoils(int coils) {
        this.coils = Math.max(0, coils);
    }

    public int coils() {
        return coils;
    }

    public boolean lit() {
        return lit;
    }

    public double fuel() {
        return fuel;
    }

    public double temperature() {
        return temperature;
    }

    public double pressure() {
        return fuel * temperature / pressureDiv();
    }

    /** The containment field of the last tick. */
    public double field() {
        return field;
    }

    /** RF the frame's buffer took last tick. */
    public long lastRf() {
        return lastRf;
    }

    /** RF the fusion made last tick, whether the buffer had room or not. */
    public long lastPower() {
        return lastPower;
    }

    public int wear() {
        return wear;
    }

    public long escapes() {
        return escapes;
    }

    /** True while the core takes fuel: lit, or still gathering its first fuel. */
    public boolean wantsFuel() {
        return coils > 0 && (lit || fuel + pendingFuel < ignitionFuel());
    }

    /** True while magnets are needed (lit, or fuel waiting to ignite). */
    public boolean needsField() {
        return coils > 0 && (lit || fuel + pendingFuel > 0);
    }

    /** A Field Magnet's field for the next tick. */
    public void addField(double f) {
        pendingField += Math.max(0, f);
    }

    /** Fuel (mB) injected for the next tick. */
    public void inject(double mb) {
        pendingFuel += Math.max(0, mb);
    }

    /** One tick: takes the field and fuel the modules gave, ignites from {@code energy}, puts RF into it. */
    public Event tick(EnergyStore energy) {
        field = Math.min(pendingField, coils * fieldPerCoil());
        pendingField = 0;
        double add = pendingFuel;
        pendingFuel = 0;
        lastRf = 0;
        lastPower = 0;
        if (coils <= 0) {
            boolean was = lit;
            out();
            return was ? Event.FADED : Event.NONE;
        }
        if (!lit) {
            fuel = Math.min(ignitionFuel(), fuel + add);
            if (fuel >= ignitionFuel() && field > 0 && energy.extract(ignitionRf(), true) >= ignitionRf()) {
                energy.extract(ignitionRf(), false);
                lit = true;
                temperature = ignitionMk();
                return Event.IGNITED;
            }
            return Event.NONE;
        }
        double burn = burn() * fuel;
        fuel = Math.max(0, fuel + add - burn);
        temperature = Math.min(maxMk(), temperature + (fuel / criticalFuel() - 1) * loss() * temperature);
        if (pressure() > field) {
            out();
            wear++;
            escapes++;
            return Event.ESCAPED;
        }
        if (temperature < minMk()) {
            out();
            return Event.FADED;
        }
        lastPower = Math.round(rfPerUnit() * fuel * temperature);
        lastRf = energy.receive(lastPower, false);
        return Event.NONE;
    }

    /** Between ticks while the machine is not ready (just formed or loaded): what modules gave is discarded. */
    public void hold() {
        pendingField = 0;
        pendingFuel = 0;
        lastRf = 0;
        lastPower = 0;
    }

    /** The plasma is let out by force (its machine came apart): it counts as an escape. */
    public void breach() {
        if (lit) {
            out();
            wear++;
            escapes++;
        }
    }

    private void out() {
        lit = false;
        fuel = 0;
        temperature = 0;
    }

    /** A coil broke: wear starts again. */
    public void coilBroke() {
        wear = 0;
    }

    /** Signal level 0..15 of the pressure as a share of the field (15 when there is no field). */
    public int pressureLevel() {
        if (!lit) {
            return 0;
        }
        if (field <= 0) {
            return 15;
        }
        return (int) Math.max(0, Math.min(15, Math.floor(pressure() / field * 15)));
    }

    /** Signal level 0..15 of the temperature, 20 MK a level. */
    public int temperatureLevel() {
        return (int) Math.max(0, Math.min(15, Math.floor(temperature / 20)));
    }

    public FfData save() {
        return new FfData().putDouble("fuel", fuel).putDouble("temperature", temperature).putBool("lit", lit).putInt("wear", wear)
                .putDouble("field", field).putLong("escapes", escapes);
    }

    public void load(FfData d) {
        fuel = Math.max(0, d.getDouble("fuel", 0));
        temperature = Math.max(0, Math.min(maxMk(), d.getDouble("temperature", 0)));
        lit = d.getBool("lit", false) && temperature > 0;
        wear = Math.max(0, d.getInt("wear", 0));
        field = Math.max(0, d.getDouble("field", 0));
        escapes = Math.max(0, d.getLong("escapes", 0));
    }
}
