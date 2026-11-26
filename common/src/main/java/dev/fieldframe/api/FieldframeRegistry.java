package dev.fieldframe.api;

import dev.fieldframe.api.chassis.UpgradeDefinition;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TunableSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registration point for module and upgrade definitions. Built-in content uses exactly this API.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li><b>Startup</b> (mod construction, KubeJS startup scripts): {@link #registerModule},
 *       {@link #replaceModule}, {@link #registerUpgrade}, {@link #registerTier}. Each registered module, upgrade and
 *       tier above the base gets an item.</li>
 *   <li><b>Freeze</b>: the platform freezes the registry when it registers items. Later registration throws.</li>
 *   <li><b>Reloadable data</b> (datapacks, KubeJS server scripts): {@link #applyData} stages tunable overrides and
 *       disable flags, validates the whole batch, then swaps it in atomically. Invalid batches are rejected and
 *       the previous effective state is kept.</li>
 * </ol>
 * Precedence: built-in defaults &lt; datapack data (pack order) &lt; script overrides.
 */
public final class FieldframeRegistry {
    private FieldframeRegistry() {
    }

    private static final Map<FfId, ModuleDefinition> MODULES = new LinkedHashMap<>();
    private static final Map<FfId, String> MODULE_SOURCES = new HashMap<>();
    private static final Map<FfId, UpgradeDefinition> UPGRADES = new LinkedHashMap<>();
    private static final Map<FfId, dev.fieldframe.api.chassis.ChassisTier> TIERS = new LinkedHashMap<>();
    private static volatile boolean frozen;
    private static volatile DataState data = DataState.EMPTY;

    /** An immutable snapshot of reloadable overrides. */
    public record DataState(Map<FfId, Map<String, Double>> tunables, Set<FfId> disabled, long generation) {
        static final DataState EMPTY = new DataState(Map.of(), Set.of(), 0);
    }

    public static synchronized void registerModule(ModuleDefinition def, String source) {
        checkOpen(def.id());
        if (MODULES.containsKey(def.id())) {
            throw new IllegalArgumentException("Module " + def.id() + " is already registered by " + MODULE_SOURCES.get(def.id())
                    + "; use replaceModule to override it explicitly");
        }
        MODULES.put(def.id(), def);
        MODULE_SOURCES.put(def.id(), source);
    }

    public static void registerModule(ModuleDefinition def) {
        registerModule(def, def.id().namespace());
    }

    /** Explicitly replaces an existing definition (e.g. a pack re-tuning a built-in behaviour at startup). */
    public static synchronized void replaceModule(ModuleDefinition def, String source) {
        checkOpen(def.id());
        if (!MODULES.containsKey(def.id())) {
            throw new IllegalArgumentException("Cannot replace unknown module " + def.id());
        }
        MODULES.put(def.id(), def);
        MODULE_SOURCES.put(def.id(), source + " (replacing " + MODULE_SOURCES.get(def.id()) + ")");
    }

    public static synchronized void registerUpgrade(UpgradeDefinition up) {
        checkOpen(up.id());
        if (UPGRADES.putIfAbsent(up.id(), up) != null) {
            throw new IllegalArgumentException("Upgrade " + up.id() + " already registered");
        }
    }

    /** Registers a chassis tier; levels must be unique (0 is the base tier). Each tier above 0 gets an upgrade kit. */
    public static synchronized void registerTier(dev.fieldframe.api.chassis.ChassisTier tier) {
        checkOpen(tier.id());
        for (var t : TIERS.values()) {
            if (t.level() == tier.level()) {
                throw new IllegalArgumentException("Tier level " + tier.level() + " is already " + t.id());
            }
        }
        if (TIERS.putIfAbsent(tier.id(), tier) != null) {
            throw new IllegalArgumentException("Tier " + tier.id() + " already registered");
        }
    }

    public static dev.fieldframe.api.chassis.ChassisTier tier(FfId id) {
        return TIERS.get(id);
    }

    /** All tiers, lowest level first. */
    public static List<dev.fieldframe.api.chassis.ChassisTier> tiers() {
        List<dev.fieldframe.api.chassis.ChassisTier> out = new ArrayList<>(TIERS.values());
        out.sort(java.util.Comparator.comparingInt(dev.fieldframe.api.chassis.ChassisTier::level));
        return out;
    }

    /** The tier at a level, or null. */
    public static dev.fieldframe.api.chassis.ChassisTier tierAt(int level) {
        for (var t : TIERS.values()) {
            if (t.level() == level) {
                return t;
            }
        }
        return null;
    }

    private static void checkOpen(FfId id) {
        if (frozen) {
            throw new IllegalStateException("Fieldframe registry is frozen; " + id
                    + " must be registered during startup (Java mod construction or KubeJS startup scripts)");
        }
    }

    public static void freeze() {
        frozen = true;
    }

    public static boolean isFrozen() {
        return frozen;
    }

    /** The definition, or null when unknown. Disabled modules are still returned (see {@link #isDisabled}). */
    public static ModuleDefinition module(FfId id) {
        return MODULES.get(id);
    }

    public static Collection<ModuleDefinition> modules() {
        return Collections.unmodifiableCollection(MODULES.values());
    }

    public static String source(FfId id) {
        return MODULE_SOURCES.getOrDefault(id, "unknown");
    }

    public static UpgradeDefinition upgrade(FfId id) {
        return UPGRADES.get(id);
    }

    public static Collection<UpgradeDefinition> upgrades() {
        return Collections.unmodifiableCollection(UPGRADES.values());
    }

    public static boolean isDisabled(FfId id) {
        return data.disabled().contains(id);
    }

    public static DataState dataState() {
        return data;
    }

    /** Effective tunable: override if present, else default; always clamped to the spec's bounds. */
    public static double tunable(ModuleDefinition def, String key) {
        TunableSpec spec = def.tunables().get(key);
        if (spec == null) {
            throw new IllegalArgumentException(def.id() + " has no tunable '" + key + "'");
        }
        Map<String, Double> o = data.tunables().get(def.id());
        Double v = o == null ? null : o.get(key);
        return v == null ? spec.defaultValue() : spec.clamp(v);
    }

    /** One staged override. {@code source} is used in error messages (datapack file, script path). */
    public record Override(FfId module, String key, double value, String source) {
    }

    /**
     * Validates and atomically applies a complete batch of reloadable overrides. Returns the list of errors;
     * when non-empty nothing was applied and the previous state remains effective.
     */
    public static synchronized List<String> applyData(List<Override> tunables, Set<FfId> disabled) {
        List<String> errors = new ArrayList<>();
        Map<FfId, Map<String, Double>> staged = new HashMap<>();
        for (Override o : tunables) {
            ModuleDefinition def = MODULES.get(o.module());
            if (def == null) {
                errors.add(o.source() + ": unknown module " + o.module());
                continue;
            }
            TunableSpec spec = def.tunables().get(o.key());
            if (spec == null) {
                errors.add(o.source() + ": module " + o.module() + " has no tunable '" + o.key() + "' (has " + def.tunables().keySet() + ")");
                continue;
            }
            if (!Double.isFinite(o.value()) || o.value() < spec.min() || o.value() > spec.max()) {
                errors.add(o.source() + ": " + o.module() + "." + o.key() + "=" + o.value() + " outside safe range [" + spec.min() + ", " + spec.max() + "]");
                continue;
            }
            staged.computeIfAbsent(o.module(), k -> new HashMap<>()).put(o.key(), o.value());
        }
        for (FfId id : disabled) {
            if (!MODULES.containsKey(id)) {
                errors.add("disable: unknown module " + id);
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        Map<FfId, Map<String, Double>> frozenMap = new HashMap<>();
        staged.forEach((k, v) -> frozenMap.put(k, Map.copyOf(v)));
        data = new DataState(Map.copyOf(frozenMap), Set.copyOf(new HashSet<>(disabled)), data.generation() + 1);
        return errors;
    }

    /** Test support: clears everything. Not for production use. */
    public static synchronized void resetForTests() {
        MODULES.clear();
        MODULE_SOURCES.clear();
        UPGRADES.clear();
        TIERS.clear();
        frozen = false;
        data = DataState.EMPTY;
    }
}
