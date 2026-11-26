package dev.fieldframe.core.script;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.blueprint.Blueprint;
import dev.fieldframe.api.chassis.UpgradeDefinition;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only lookups for scripts (KubeJS {@code Fieldframe} binding on every edition): upgrades and blueprint
 * inspection. Results are plain maps and lists of strings and numbers, so they convert cleanly to JavaScript.
 * Nothing here changes game state.
 */
public final class ScriptLookups {
    private ScriptLookups() {
    }

    /** Every registered service-bay upgrade with what it adds. */
    public static List<Map<String, Object>> upgrades() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UpgradeDefinition u : FieldframeRegistry.upgrades()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.id().toString());
            m.put("extraSlotsPerLane", u.extraSlotsPerLane());
            m.put("extraFluidCapacity", u.extraFluidCapacity());
            m.put("extraEnergyCapacity", u.extraEnergyCapacity());
            m.put("extraItemsPerTick", u.extraItemsPerTick());
            m.put("extraFluidPerTick", u.extraFluidPerTick());
            m.put("extraEnergyPerTick", u.extraEnergyPerTick());
            m.put("extraControllerRules", u.extraControllerRules());
            m.put("overclock", u.overclock());
            out.add(m);
        }
        return out;
    }

    /** Chassis tiers, lowest first, with what each adds (read-only). */
    public static List<Map<String, Object>> tiers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (dev.fieldframe.api.chassis.ChassisTier t : FieldframeRegistry.tiers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id().toString());
            m.put("level", t.level());
            m.put("speed", t.speed());
            m.put("extraServiceBays", t.extraServiceBays());
            m.put("extraSlotsPerLane", t.extraSlotsPerLane());
            m.put("extraFluidCapacity", t.extraFluidCapacity());
            m.put("extraEnergyCapacity", t.extraEnergyCapacity());
            m.put("extraEnergyPerTick", t.extraEnergyPerTick());
            m.put("kit", t.level() > 0 ? t.kitId().toString() : "");
            out.add(m);
        }
        return out;
    }

    /** The effective engine fuels table (read-only). */
    public static List<Map<String, Object>> fuels() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (dev.fieldframe.api.process.MachineData.Fuel f : dev.fieldframe.api.process.MachineData.fuels()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engine", f.engine().toString());
            m.put(f.fluid() ? "fluid" : "item", f.key());
            m.put(f.fluid() ? "rfPerMb" : "rf", f.rf());
            out.add(m);
        }
        return out;
    }

    /** The effective Aura Chamber table, Metallurgy's chamber recipes included (read-only). */
    public static List<Map<String, Object>> auras() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (dev.fieldframe.api.process.MachineData.Aura a : dev.fieldframe.api.process.MachineData.auras()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id().toString());
            m.put("ingredient", a.ingredient());
            m.put("count", a.count());
            m.put("effect", a.effect().toString());
            m.put("duration", a.duration());
            m.put("amplifier", a.amplifier());
            out.add(m);
        }
        return out;
    }

    /**
     * Parses blueprint JSON (the format in docs/blueprint_format.md) and summarizes it: name, chassis count, modules per
     * chassis, bill of materials and compatibility problems. Invalid JSON returns {@code {valid: false, error: ...}}.
     */
    public static Map<String, Object> blueprint(String json, String chassisItem) {
        Map<String, Object> out = new LinkedHashMap<>();
        Blueprint bp;
        try {
            bp = Blueprint.fromData(FfJson.parse(json));
        } catch (RuntimeException e) {
            out.put("valid", false);
            out.put("error", String.valueOf(e.getMessage()));
            return out;
        }
        out.put("valid", true);
        out.put("name", bp.name());
        out.put("chassisCount", bp.chassis().size());
        List<Map<String, Object>> chassis = new ArrayList<>();
        for (Blueprint.Chassis c : bp.chassis()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("offset", List.of(c.dx(), c.dy(), c.dz()));
            cm.put("front", c.front().name().toLowerCase(java.util.Locale.ROOT));
            Map<String, Object> faces = new LinkedHashMap<>();
            for (Map.Entry<LocalFace, Blueprint.Face> f : c.faces().entrySet()) {
                faces.put(f.getKey().key(), f.getValue().module().toString());
            }
            cm.put("faces", faces);
            List<String> ups = new ArrayList<>();
            c.upgrades().forEach(u -> ups.add(u.toString()));
            cm.put("upgrades", ups);
            chassis.add(cm);
        }
        out.put("chassis", chassis);
        Map<String, Object> bom = new LinkedHashMap<>();
        for (Map.Entry<FfId, Integer> e : bp.billOfMaterials(FfId.parse(chassisItem)).entrySet()) {
            bom.put(e.getKey().toString(), e.getValue());
        }
        out.put("billOfMaterials", bom);
        out.put("problems", new ArrayList<>(bp.compatibilityProblems()));
        return out;
    }
}
