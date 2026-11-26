package dev.fieldframe.api.module;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable description of a module type. Built-in modules, Java addons and KubeJS scripts all register
 * instances of this same class through {@code FieldframeRegistry}. Mutable per-machine state lives in the
 * {@link ModuleBehavior} instance, never here.
 */
public final class ModuleDefinition {
    /** Progression stage used for manual organization and recipe gating. */
    public enum Stage {
        WORKSHOP, AUTOMATION, INSTRUMENTATION, FIELD
    }

    private final FfId id;
    private final ModuleCategory category;
    private final TickPhase phase;
    private final Stage stage;
    private final Set<Exposure> exposures;
    private final Set<LocalFace> allowedFaces;
    private final List<BindingSpec> bindings;
    private final List<ConfigField> config;
    private final Map<String, TunableSpec> tunables;
    private final ModuleBehaviorFactory factory;
    private final FfId processFamily;
    private final FfId manualEntry;
    private final boolean needsSky;
    private final FfData presentation;

    private ModuleDefinition(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.category = Objects.requireNonNull(b.category, "category");
        this.phase = Objects.requireNonNull(b.phase, "phase");
        this.stage = b.stage;
        this.exposures = Collections.unmodifiableSet(b.exposures.isEmpty() ? EnumSet.noneOf(Exposure.class) : EnumSet.copyOf(b.exposures));
        this.allowedFaces = Collections.unmodifiableSet(EnumSet.copyOf(b.allowedFaces));
        this.bindings = List.copyOf(b.bindings);
        this.config = List.copyOf(b.config);
        this.tunables = Collections.unmodifiableMap(new LinkedHashMap<>(b.tunables));
        this.factory = Objects.requireNonNull(b.factory, "behavior factory");
        this.processFamily = b.processFamily;
        this.manualEntry = b.manualEntry != null ? b.manualEntry : FfId.of(b.id.namespace(), "modules/" + b.id.path());
        this.needsSky = b.needsSky;
        this.presentation = b.presentation.copy();
        validate();
    }

    private void validate() {
        Set<String> keys = new java.util.HashSet<>();
        for (BindingSpec s : bindings) {
            if (!keys.add("b:" + s.key())) {
                throw new IllegalArgumentException(id + ": duplicate binding " + s.key());
            }
        }
        for (ConfigField f : config) {
            if (!keys.add("c:" + f.key())) {
                throw new IllegalArgumentException(id + ": duplicate config field " + f.key());
            }
        }
        if (bindings.size() > 12 || config.size() > 24 || tunables.size() > 24) {
            throw new IllegalArgumentException(id + ": too many bindings/config fields/tunables");
        }
        if (allowedFaces.isEmpty()) {
            throw new IllegalArgumentException(id + ": no allowed faces");
        }
    }

    public static Builder builder(FfId id) {
        return new Builder(id);
    }

    public FfId id() {
        return id;
    }

    public ModuleCategory category() {
        return category;
    }

    public TickPhase phase() {
        return phase;
    }

    public Stage stage() {
        return stage;
    }

    public Set<Exposure> exposures() {
        return exposures;
    }

    public boolean exposes(Exposure e) {
        return exposures.contains(e);
    }

    public Set<LocalFace> allowedFaces() {
        return allowedFaces;
    }

    public List<BindingSpec> bindings() {
        return bindings;
    }

    public BindingSpec binding(String key) {
        for (BindingSpec b : bindings) {
            if (b.key().equals(key)) {
                return b;
            }
        }
        return null;
    }

    public List<ConfigField> config() {
        return config;
    }

    public ConfigField configField(String key) {
        for (ConfigField f : config) {
            if (f.key().equals(key)) {
                return f;
            }
        }
        return null;
    }

    public Map<String, TunableSpec> tunables() {
        return tunables;
    }

    public ModuleBehaviorFactory factory() {
        return factory;
    }

    /** Recipe family used by processing modules, or null. */
    public FfId processFamily() {
        return processFamily;
    }

    public FfId manualEntry() {
        return manualEntry;
    }

    public boolean needsSky() {
        return needsSky;
    }

    /** Client presentation hints (model/texture references, accent colour). Inert data only. */
    public FfData presentation() {
        return presentation.copy();
    }

    public String translationKey() {
        return "module." + id.namespace() + "." + id.path().replace('/', '.');
    }

    /** Default configuration values for a freshly installed module. */
    public FfData defaultConfig() {
        FfData d = new FfData();
        for (ConfigField f : config) {
            f.writeDefault(d);
        }
        return d;
    }

    /** Validates untrusted configuration into a complete, schema-conforming copy. */
    public FfData sanitizeConfig(FfData in) {
        FfData out = new FfData();
        for (ConfigField f : config) {
            f.sanitize(in == null ? new FfData() : in, out);
        }
        return out;
    }

    /** Returns a builder pre-filled from this definition, used for explicit overrides. */
    public Builder toBuilder() {
        Builder b = new Builder(id);
        b.category = category;
        b.phase = phase;
        b.stage = stage;
        b.exposures = EnumSet.noneOf(Exposure.class);
        b.exposures.addAll(exposures);
        b.allowedFaces = EnumSet.copyOf(allowedFaces);
        b.bindings.addAll(bindings);
        b.config.addAll(config);
        b.tunables.putAll(tunables);
        b.factory = factory;
        b.processFamily = processFamily;
        b.manualEntry = manualEntry;
        b.needsSky = needsSky;
        b.presentation = presentation.copy();
        return b;
    }

    @Override
    public String toString() {
        return "ModuleDefinition[" + id + "]";
    }

    public static final class Builder {
        private final FfId id;
        private ModuleCategory category;
        private TickPhase phase = TickPhase.PROCESS;
        private Stage stage = Stage.WORKSHOP;
        private EnumSet<Exposure> exposures = EnumSet.noneOf(Exposure.class);
        private EnumSet<LocalFace> allowedFaces = EnumSet.allOf(LocalFace.class);
        private final List<BindingSpec> bindings = new ArrayList<>();
        private final List<ConfigField> config = new ArrayList<>();
        private final Map<String, TunableSpec> tunables = new LinkedHashMap<>();
        private ModuleBehaviorFactory factory;
        private FfId processFamily;
        private FfId manualEntry;
        private boolean needsSky;
        private FfData presentation = new FfData();

        private Builder(FfId id) {
            this.id = id;
        }

        public Builder category(ModuleCategory c) {
            this.category = c;
            return this;
        }

        public Builder phase(TickPhase p) {
            this.phase = p;
            return this;
        }

        public Builder stage(Stage s) {
            this.stage = s;
            return this;
        }

        public Builder exposes(Exposure... e) {
            exposures.addAll(List.of(e));
            return this;
        }

        public Builder faces(LocalFace... faces) {
            allowedFaces = EnumSet.copyOf(List.of(faces));
            return this;
        }

        public Builder binding(BindingSpec s) {
            bindings.add(s);
            return this;
        }

        public Builder config(ConfigField f) {
            config.add(f);
            return this;
        }

        public Builder tunable(TunableSpec t) {
            tunables.put(t.key(), t);
            return this;
        }

        public Builder behavior(ModuleBehaviorFactory f) {
            this.factory = f;
            return this;
        }

        public Builder processFamily(FfId family) {
            this.processFamily = family;
            return this;
        }

        public Builder manualEntry(FfId entry) {
            this.manualEntry = entry;
            return this;
        }

        public Builder needsSky(boolean v) {
            this.needsSky = v;
            return this;
        }

        public Builder presentation(FfData d) {
            this.presentation = d == null ? new FfData() : d.copy();
            return this;
        }

        public ModuleDefinition build() {
            return new ModuleDefinition(this);
        }
    }
}
