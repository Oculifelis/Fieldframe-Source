package dev.fieldframe.core.blueprint;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.blueprint.Blueprint;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.InstalledModule;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint capture, comparison and configuration-only application against live chassis cores.
 * Applying configuration never installs or removes hardware, never touches contents, and only changes faces whose
 * installed module matches the blueprint.
 */
public final class BlueprintOps {
    private BlueprintOps() {
    }

    /** One chassis to capture, at an offset from the blueprint origin. */
    public record Source(int dx, int dy, int dz, ChassisCore core) {
    }

    public static Blueprint capture(String name, List<Source> sources) {
        List<Blueprint.Chassis> out = new ArrayList<>();
        for (Source s : sources) {
            out.add(captureOne(s.dx(), s.dy(), s.dz(), s.core()));
        }
        return new Blueprint(name, out, null);
    }

    public static Blueprint.Chassis captureOne(int dx, int dy, int dz, ChassisCore core) {
        Map<LocalFace, Blueprint.Face> faces = new EnumMap<>(LocalFace.class);
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = core.module(f);
            if (m == null || m.isMissing()) {
                continue;
            }
            faces.put(f, new Blueprint.Face(m.moduleId(), m.config(), m.bindings()));
        }
        List<FfId> ups = new ArrayList<>();
        for (int b = 0; b < core.serviceBays(); b++) {
            if (core.upgrade(b) != null) {
                ups.add(core.upgrade(b));
            }
        }
        return new Blueprint.Chassis(dx, dy, dz, core.orientation().front(), faces, ups);
    }

    /** Human-readable differences between a blueprint chassis and a live one. Empty when they match. */
    public static List<String> diff(Blueprint.Chassis plan, ChassisCore core) {
        List<String> out = new ArrayList<>();
        if (plan.front() != core.orientation().front()) {
            out.add("faces " + core.orientation().front().name().toLowerCase(java.util.Locale.ROOT) + ", plan faces "
                    + plan.front().name().toLowerCase(java.util.Locale.ROOT));
        }
        for (LocalFace f : LocalFace.ALL) {
            Blueprint.Face want = plan.faces().get(f);
            InstalledModule have = core.module(f);
            if (want == null && have == null) {
                continue;
            }
            if (want == null) {
                out.add(f.key() + ": extra " + have.moduleId());
            } else if (have == null) {
                out.add(f.key() + ": missing " + want.module());
            } else if (!have.moduleId().equals(want.module())) {
                out.add(f.key() + ": has " + have.moduleId() + ", plan " + want.module());
            } else {
                if (!have.bindings().equals(want.bindings())) {
                    out.add(f.key() + ": bindings differ");
                }
                if (!have.config().equals(want.config())) {
                    out.add(f.key() + ": settings differ");
                }
            }
        }
        return out;
    }

    public record ApplyReport(int applied, List<String> skipped) {
    }

    /** Copies configuration and bindings onto matching modules only. */
    public static ApplyReport applyConfiguration(Blueprint.Chassis plan, ChassisCore core) {
        int applied = 0;
        List<String> skipped = new ArrayList<>();
        for (var e : plan.faces().entrySet()) {
            InstalledModule have = core.module(e.getKey());
            if (have == null || have.isMissing() || !have.moduleId().equals(e.getValue().module())) {
                skipped.add(e.getKey().key() + ": needs " + e.getValue().module());
                continue;
            }
            ModuleDefinition def = FieldframeRegistry.module(have.moduleId());
            FfData cfg = def == null ? e.getValue().config() : def.sanitizeConfig(e.getValue().config());
            if (!core.setConfig(e.getKey(), cfg).ok()) {
                skipped.add(e.getKey().key() + ": settings rejected");
                continue;
            }
            for (var b : e.getValue().bindings().entrySet()) {
                if (!core.setBinding(e.getKey(), b.getKey(), b.getValue()).ok()) {
                    skipped.add(e.getKey().key() + ": binding " + b.getKey() + " rejected");
                }
            }
            applied++;
        }
        return new ApplyReport(applied, skipped);
    }
}
