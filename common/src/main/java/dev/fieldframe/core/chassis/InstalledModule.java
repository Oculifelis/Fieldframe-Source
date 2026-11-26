package dev.fieldframe.core.chassis;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ConfigView;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.status.Status;
import dev.fieldframe.api.status.Statuses;

import java.util.HashMap;
import java.util.Map;

/**
 * One module mounted on one face. If the definition is missing (addon removed, script no longer defines it)
 * the raw saved data is retained verbatim so it can be restored or recovered later.
 */
public final class InstalledModule {
    final FfId moduleId;
    final ModuleDefinition definition;
    final ModuleBehavior behavior;
    final Map<String, Integer> bindings = new HashMap<>();
    FfData config;
    ConfigView configView;
    Status status = Statuses.idle();
    long sleepUntil;
    boolean wake;
    final int generation;
    /** Raw saved form, retained only for missing definitions. */
    final FfData rawForMissing;
    int lastRedstone;
    /** Channel values this module drove during its last real tick; re-driven while it sleeps. */
    final int[] heldOutputs = new int[8];
    /** Game time this module last reported WORKING (client looks; not saved). */
    long lastWorking = Long.MIN_VALUE / 2;
    long energyUsedLastTick;
    long energyGeneratedLastTick;
    long energyUsedThisTick;
    long energyGeneratedThisTick;

    InstalledModule(ModuleDefinition def, FfData config, int generation) {
        this.moduleId = def.id();
        this.definition = def;
        this.behavior = def.factory().create(def);
        this.generation = generation;
        this.rawForMissing = null;
        for (BindingSpec b : def.bindings()) {
            bindings.put(b.key(), b.defaultIndex());
        }
        setConfig(config);
    }

    /** Placeholder for a module whose definition is unavailable. */
    InstalledModule(FfId missingId, FfData raw, int generation) {
        this.moduleId = missingId;
        this.definition = null;
        this.behavior = null;
        this.generation = generation;
        this.rawForMissing = raw.copy();
        this.config = new FfData();
        this.configView = new ConfigView(config);
        this.status = Statuses.missingDefinition(missingId.toString());
    }

    void setConfig(FfData untrusted) {
        this.config = definition.sanitizeConfig(untrusted == null ? definition.defaultConfig() : untrusted);
        this.configView = new ConfigView(config);
    }

    public FfId moduleId() {
        return moduleId;
    }

    public ModuleDefinition definition() {
        return definition;
    }

    public boolean isMissing() {
        return definition == null;
    }

    public ModuleBehavior behavior() {
        return behavior;
    }

    public Status status() {
        return status;
    }

    public FfData config() {
        return config.copy();
    }

    public int binding(String key) {
        Integer i = bindings.get(key);
        return i == null ? -1 : i;
    }

    public Map<String, Integer> bindings() {
        return Map.copyOf(bindings);
    }

    public int generation() {
        return generation;
    }

    public long energyUsedLastTick() {
        return energyUsedLastTick;
    }

    public long energyGeneratedLastTick() {
        return energyGeneratedLastTick;
    }

    public FfData rawForMissing() {
        return rawForMissing == null ? null : rawForMissing.copy();
    }
}
