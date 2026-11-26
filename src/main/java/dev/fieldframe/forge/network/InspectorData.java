package dev.fieldframe.forge.network;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ConfigField;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TunableSpec;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.core.storage.FluidReservoir;
import dev.fieldframe.core.storage.ItemLane;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the bounded inspector snapshot sent to an authorized, actively inspecting player. Contains only what
 * the inspector displays: per-face module, status, bindings, config schema/values, tunables, and lane/tank
 * summaries. Never sent to non-inspecting players.
 */
public final class InspectorData {
    private InspectorData() {
    }

    public static FfData build(ChassisBlockEntity be) {
        return build(be.core());
    }

    public static FfData build(ChassisCore c) {
        FfData d = new FfData();
        d.putLong("revision", c.revision());
        d.putString("front", c.orientation().front().name());
        d.putBool("paused", c.isPaused());
        if (c.installedOverclock() > 0 || c.heat() > 0) {
            d.putDouble("overclock", c.installedOverclock()).putInt("heat", c.heat()).putBool("overheated", c.overheated());
        }
        d.putBool("quiet", c.isQuiet());
        d.putInt("bolt_parts", c.group() == null ? 1 : c.group().parts().size());
        d.putBool("bolt_waiting", c.waitingForParts());
        d.putString("comparator", c.comparatorSource());
        d.putString("tier", c.tier().id().toString()).putInt("tier_level", c.tier().level());
        d.putLong("energy", c.energy().stored()).putLong("energyCap", c.energy().capacity());
        d.putLong("energyIn", c.energyInBudget().perTick()).putLong("itemsPerTick", c.itemBudget().perTick()).putLong("fluidPerTick", c.fluidBudget().perTick());
        List<Object> ch = new ArrayList<>();
        for (int v : c.control().snapshotCopy()) {
            ch.add((long) v);
        }
        d.putList("channels", ch);
        List<Object> lanes = new ArrayList<>();
        for (int i = 0; i < ChassisSpec.ITEM_LANES; i++) {
            ItemLane l = c.lane(i);
            FfData ld = new FfData().putInt("slots", l.slots());
            List<Object> items = new ArrayList<>();
            for (int s = 0; s < l.slots(); s++) {
                ItemVariant v = l.variant(s);
                if (v != null) {
                    items.add(new FfData().putInt("slot", s).putString("id", v.id().toString()).putInt("count", l.count(s))
                            .putBool("missing", v instanceof dev.fieldframe.forge.adapter.McVariantCodec.Missing));
                }
            }
            ld.putList("items", items);
            lanes.add(ld);
        }
        d.putList("lanes", lanes);
        List<Object> tanks = new ArrayList<>();
        for (int i = 0; i < ChassisSpec.FLUID_LANES; i++) {
            FluidReservoir t = c.tank(i);
            FfData td = new FfData().putLong("amount", t.amount()).putLong("capacity", t.capacity());
            if (t.fluid() != null) {
                td.putString("fluid", t.fluid().id().toString());
            }
            tanks.add(td);
        }
        d.putList("tanks", tanks);
        List<Object> ups = new ArrayList<>();
        for (int b = 0; b < c.serviceBays(); b++) {
            ups.add(c.upgrade(b) == null ? "" : c.upgrade(b).toString());
        }
        d.putList("upgrades", ups);
        FfData faces = new FfData();
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = c.module(f);
            if (m != null) {
                faces.put(f.key(), face(m));
            }
        }
        d.put("faces", faces);
        List<Object> trace = new ArrayList<>(c.traceLines());
        d.putList("trace", trace);
        return d;
    }

    private static FfData face(InstalledModule m) {
        FfData fd = new FfData().putString("module", m.moduleId().toString());
        fd.putString("status", m.status().code()).putString("severity", m.status().severity().name());
        fd.putList("args", new ArrayList<>(m.status().args()));
        fd.putLong("rfUsed", m.energyUsedLastTick()).putLong("rfMade", m.energyGeneratedLastTick());
        if (m.isMissing()) {
            fd.putBool("missing", true);
            return fd;
        }
        ModuleDefinition def = m.definition();
        fd.putString("name", def.translationKey()).putString("category", def.category().key());
        fd.putString("manual", def.manualEntry().toString());
        fd.putString("detail", m.behavior().detail());
        fd.putDouble("progress", m.behavior().progress());
        List<Object> binds = new ArrayList<>();
        for (BindingSpec b : def.bindings()) {
            binds.add(new FfData().putString("key", b.key()).putString("kind", b.kind().name()).putInt("index", m.binding(b.key())).putInt("default", b.defaultIndex())
                    .putBool("optional", b.optional()).putString("label", BindingSpec.label(b.kind(), m.binding(b.key()))));
        }
        fd.putList("bindings", binds);
        List<Object> cfg = new ArrayList<>();
        FfData values = m.config();
        for (ConfigField f : def.config()) {
            FfData cd = new FfData().putString("key", f.key()).putString("type", f.type().name()).putLong("min", f.min()).putLong("max", f.max());
            cd.putList("options", new ArrayList<>(f.options()));
            Object raw = values.getRaw(f.key());
            if (raw != null && !(raw instanceof FfData)) {
                cd.putAny("value", raw);
            } else if (raw instanceof FfData fv) {
                cd.put("value", fv);
            }
            cfg.add(cd);
        }
        fd.putList("config", cfg);
        List<Object> tun = new ArrayList<>();
        for (TunableSpec t : def.tunables().values()) {
            tun.add(new FfData().putString("key", t.key()).putDouble("value", FieldframeRegistry.tunable(def, t.key())).putString("unit", t.unit()));
        }
        fd.putList("tunables", tun);
        return fd;
    }
}
