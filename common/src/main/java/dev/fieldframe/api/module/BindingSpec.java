package dev.fieldframe.api.module;

/**
 * A typed connection point a module declares, e.g. {@code input: ITEM_LANE default 0}. The player rebinds it
 * on the machine screen. Index meaning depends on kind: lanes 0..2 = the Orange, Green and Purple storage (or tank),
 * signals 0..7 = signal 1..8, -1 = none. Kinds never mix: an item binding can only point at an item lane.
 *
 * <p>Default convention (so a first machine works without any settings): things a module takes in come from
 * {@link #IN} (Orange), results go to {@link #OUT} (Green), fuel, tools and held items come from {@link #SUPPLY} (Purple).
 */
public record BindingSpec(String key, Kind kind, int defaultIndex, boolean optional) {
    /** Lane a module takes its inputs from by default (Orange). */
    public static final int IN = 0;
    /** Lane a module puts its results into by default (Green). */
    public static final int OUT = 1;
    /** Lane for fuel, tools and held items by default (Purple). */
    public static final int SUPPLY = 2;
    /** Display colour names of lanes 0..2. */
    public static final String[] LANE_COLOURS = {"Orange", "Green", "Purple"};

    public enum Kind {
        ITEM_LANE, FLUID_LANE, SIGNAL_IN, SIGNAL_OUT;

        public int maxIndex() {
            return this == ITEM_LANE || this == FLUID_LANE ? 2 : 7;
        }

        public boolean signal() {
            return this == SIGNAL_IN || this == SIGNAL_OUT;
        }
    }

    public BindingSpec {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("binding key: " + key);
        }
        if (defaultIndex < -1 || defaultIndex > kind.maxIndex()) {
            throw new IllegalArgumentException("default index out of range for " + key);
        }
        if (defaultIndex == -1 && !optional) {
            throw new IllegalArgumentException("non-optional binding needs a default: " + key);
        }
    }

    public static BindingSpec item(String key, int lane) {
        return new BindingSpec(key, Kind.ITEM_LANE, lane, false);
    }

    public static BindingSpec fluid(String key, int lane) {
        return new BindingSpec(key, Kind.FLUID_LANE, lane, false);
    }

    public static BindingSpec optionalFluid(String key) {
        return new BindingSpec(key, Kind.FLUID_LANE, -1, true);
    }

    /** A tank connection the player may clear, connected to {@code tank} by default. */
    public static BindingSpec optionalFluid(String key, int tank) {
        return new BindingSpec(key, Kind.FLUID_LANE, tank, true);
    }

    public static BindingSpec signalIn(String key) {
        return new BindingSpec(key, Kind.SIGNAL_IN, -1, true);
    }

    public static BindingSpec signalIn(String key, int channel) {
        return new BindingSpec(key, Kind.SIGNAL_IN, channel, true);
    }

    public static BindingSpec signalOut(String key) {
        return new BindingSpec(key, Kind.SIGNAL_OUT, -1, true);
    }

    public static BindingSpec signalOut(String key, int channel) {
        return new BindingSpec(key, Kind.SIGNAL_OUT, channel, true);
    }

    /** Human label such as {@code Orange storage}, {@code Green tank}, {@code signal 3}, or {@code none}. */
    public static String label(Kind kind, int index) {
        if (index < 0) {
            return "none";
        }
        return switch (kind) {
            case ITEM_LANE -> laneName(index);
            case FLUID_LANE -> tankName(index);
            case SIGNAL_IN, SIGNAL_OUT -> "signal " + (index + 1);
        };
    }

    /** {@code Orange storage}, {@code Green storage}, {@code Purple storage}. */
    public static String laneName(int index) {
        return (index >= 0 && index < LANE_COLOURS.length ? LANE_COLOURS[index] : "Lane " + (index + 1)) + " storage";
    }

    /** {@code Orange tank}, {@code Green tank}, {@code Purple tank}. */
    public static String tankName(int index) {
        return (index >= 0 && index < LANE_COLOURS.length ? LANE_COLOURS[index] : "Tank " + (index + 1)) + " tank";
    }
}
