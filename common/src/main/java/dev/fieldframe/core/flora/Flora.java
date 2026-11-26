package dev.fieldframe.core.flora;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * FLORA (fluid armor) model, reimplemented from FLORA by pixlepix and FLORA Reborn by dmf444 (see
 * docs/features/flora.md). Seven effect fluids, four armor tiers, the fluid charge stored on each armor piece and the
 * 7x7 interaction matrix.
 */
public final class Flora {
    private Flora() {
    }

    /** Effect fluids in the original matrix order (Coal, Pyrotheum, Cryotheum, Mana, Ender, Redstone, Glowstone). */
    public enum Fluid {
        COAL("liquid_coal", 0xFF2B2622, 300, 0),
        BLAZING("blazing_fluid", 0xFFF07A1C, 1700, 12),
        GELID("gelid_fluid", 0xFF7FD6F2, 50, 0),
        ARCANE("arcane_fluid", 0xFF6A4BD8, 300, 7),
        ENDER("resonant_ender", 0xFF1F7A6E, 300, 4),
        REDSTONE("liquid_redstone", 0xFFB01E14, 300, 7),
        GLOWSTONE("liquid_glowstone", 0xFFF5D25A, 300, 15);

        public final String path;
        public final int color;
        /** Kelvin; 1000 and up pours like lava. */
        public final int temperature;
        /** Block light when poured into the world. */
        public final int light;

        Fluid(String path, int color, int temperature, int light) {
            this.path = path;
            this.color = color;
            this.temperature = temperature;
            this.light = light;
        }

        public FfId id() {
            return FfId.ff(path);
        }

        public static Fluid byId(FfId id) {
            if (id == null || !id.namespace().equals("fieldframe")) {
                return null;
            }
            for (Fluid f : values()) {
                if (f.path.equals(id.path())) {
                    return f;
                }
            }
            return null;
        }
    }

    /** Armor tiers: original Leadstone/Hardened/Redstone/Resonant capacities and defense, Fieldframe materials. */
    public enum Tier {
        COPPER("copper", 250, 4),
        IRON("iron", 2_500, 5),
        GOLD("gold", 25_000, 6),
        ENDER_ALLOY("ender_alloy", 250_000, 7);

        public final String key;
        /** mB per piece, shared by all fluids in it. */
        public final long capacity;
        /** Original per-piece protection value (before the 0.0425 per point scaling). */
        public final int protection;

        Tier(String key, long capacity, int protection) {
            this.key = key;
            this.capacity = capacity;
            this.protection = protection;
        }
    }

    public enum Piece {
        HELMET, CHESTPLATE, LEGGINGS, BOOTS
    }

    /**
     * Fluid content of one armor piece. Stored on the item under {@link #DATA_KEY}: the seven FLORA fluids by their path,
     * any other fluid (see {@link FluidArmorEffects}) by its full id.
     */
    public static final class Charge {
        public static final String DATA_KEY = "flora_charge";
        private final Map<Fluid, Long> amounts = new EnumMap<>(Fluid.class);
        private final Map<FfId, Long> others = new java.util.LinkedHashMap<>();

        public long amount(Fluid f) {
            return amounts.getOrDefault(f, 0L);
        }

        /** Amount of any fluid by id (FLORA fluids included). */
        public long amount(FfId id) {
            Fluid f = Fluid.byId(id);
            return f != null ? amount(f) : others.getOrDefault(id, 0L);
        }

        public long total() {
            long n = 0;
            for (long v : amounts.values()) {
                n += v;
            }
            for (long v : others.values()) {
                n += v;
            }
            return n;
        }

        public Map<Fluid, Long> view() {
            return java.util.Collections.unmodifiableMap(amounts);
        }

        /** Every fluid in the piece by id, FLORA fluids first. */
        public Map<FfId, Long> all() {
            Map<FfId, Long> out = new java.util.LinkedHashMap<>();
            amounts.forEach((f, v) -> out.put(f.id(), v));
            out.putAll(others);
            return out;
        }

        /** Adds up to {@code amount} of {@code f}, bounded by the shared capacity. Returns what fit. */
        public long fill(Fluid f, long amount, long capacity, boolean simulate) {
            long n = Math.max(0, Math.min(amount, capacity - total()));
            if (!simulate && n > 0) {
                amounts.merge(f, n, Long::sum);
            }
            return n;
        }

        /** As {@link #fill(Fluid, long, long, boolean)} for any fluid id. */
        public long fill(FfId id, long amount, long capacity, boolean simulate) {
            Fluid f = Fluid.byId(id);
            if (f != null) {
                return fill(f, amount, capacity, simulate);
            }
            long n = Math.max(0, Math.min(amount, capacity - total()));
            if (!simulate && n > 0) {
                others.merge(id, n, Long::sum);
            }
            return n;
        }

        public FfData save() {
            FfData d = new FfData();
            for (Map.Entry<Fluid, Long> e : amounts.entrySet()) {
                if (e.getValue() > 0) {
                    d.putLong(e.getKey().path, e.getValue());
                }
            }
            for (Map.Entry<FfId, Long> e : others.entrySet()) {
                if (e.getValue() > 0) {
                    d.putLong(e.getKey().toString(), e.getValue());
                }
            }
            return d;
        }

        public static Charge load(FfData d, long capacity) {
            Charge c = new Charge();
            if (d == null) {
                return c;
            }
            for (Fluid f : Fluid.values()) {
                long v = Math.max(0, d.getLong(f.path, 0));
                if (v > 0) {
                    c.amounts.put(f, Math.min(v, Math.max(0, capacity - c.total())));
                }
            }
            for (String k : new java.util.TreeSet<>(d.keys())) {
                FfId id = k.indexOf(':') > 0 ? FfId.tryParse(k) : null;
                long v = Math.max(0, d.getLong(k, 0));
                if (id != null && v > 0 && Fluid.byId(id) == null) {
                    long n = Math.min(v, Math.max(0, capacity - c.total()));
                    if (n > 0) {
                        c.others.put(id, n);
                    }
                }
            }
            return c;
        }
    }

    /**
     * Interaction matrix over the worn pieces: {@code m[i][j] = 10 * sqrt(t[i] * t[j])} where {@code t} are total
     * buckets per fluid (original FLORA formula). A value of 10 means one bucket of each fluid.
     */
    public static float[][] matrix(List<Charge> worn) {
        float[] t = new float[7];
        for (Charge c : worn) {
            if (c == null) {
                continue;
            }
            for (Fluid f : Fluid.values()) {
                t[f.ordinal()] += c.amount(f) / 1000f;
            }
        }
        float[][] m = new float[7][7];
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 7; j++) {
                m[i][j] = 10f * (float) Math.sqrt(t[i] * t[j]);
            }
        }
        return m;
    }

    public static float m(float[][] m, Fluid a, Fluid b) {
        return m[a.ordinal()][b.ordinal()];
    }

    /** The 28 effects, one per unordered fluid pair, with a stable key for localisation ({@code fieldframe.flora.effect.<key>}). */
    public record Effect(Fluid a, Fluid b, String key, boolean negative) {
    }

    public static final List<Effect> EFFECTS;

    static {
        List<Effect> e = new ArrayList<>();
        Fluid C = Fluid.COAL, P = Fluid.BLAZING, Y = Fluid.GELID, M = Fluid.ARCANE, E = Fluid.ENDER, R = Fluid.REDSTONE, G = Fluid.GLOWSTONE;
        e.add(new Effect(C, C, "fireball", false));
        e.add(new Effect(C, P, "explode_burning", false));
        e.add(new Effect(C, Y, "mining_speed", false));
        e.add(new Effect(C, M, "pacify", false));
        e.add(new Effect(C, E, "teleport_attacker", false));
        e.add(new Effect(C, R, "damage_pulse", false));
        e.add(new Effect(C, G, "ignite_enemies", false));
        e.add(new Effect(P, P, "fall_damage_up", true));
        e.add(new Effect(P, Y, "slowness", true));
        e.add(new Effect(P, M, "speed", false));
        e.add(new Effect(P, E, "fall_damage_down", false));
        e.add(new Effect(P, R, "ignite_pulse", false));
        e.add(new Effect(P, G, "fire_protection", false));
        e.add(new Effect(Y, Y, "water_breathing", false));
        e.add(new Effect(Y, M, "hunger_loss", true));
        e.add(new Effect(Y, E, "escape_teleport", false));
        e.add(new Effect(Y, R, "slowness_pulse", false));
        e.add(new Effect(Y, G, "cold_damage", true));
        e.add(new Effect(M, M, "antidote", false));
        e.add(new Effect(M, E, "long_jump", false));
        e.add(new Effect(M, R, "antidote_pulse", false));
        e.add(new Effect(M, G, "hunger_gain", false));
        e.add(new Effect(E, E, "blink", false));
        e.add(new Effect(E, R, "teleport_pulse", false));
        e.add(new Effect(E, G, "sink", false));
        e.add(new Effect(R, R, "max_health_flux", true));
        e.add(new Effect(R, G, "self_combustion", true));
        e.add(new Effect(G, G, "night_vision", false));
        EFFECTS = List.copyOf(e);
    }
}
