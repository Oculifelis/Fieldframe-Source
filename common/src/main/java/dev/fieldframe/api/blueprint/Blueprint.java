package dev.fieldframe.api.blueprint;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ModuleDefinition;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A reusable machine design: chassis positions, orientation, modules, configuration, bindings and upgrade ids.
 * Blueprints describe construction only. They never contain items, fluids, RF, operation state, callback code,
 * ownership, notes or permissions, so copying one can never clone resources or authority.
 *
 * <p>Format version {@value #FORMAT}. Decoding is bounded and strict: unknown top-level keys are rejected, unknown
 * extension data is kept only as inert {@code ext} data up to {@value #MAX_EXT_CHARS} characters.
 */
public final class Blueprint {
    public static final int FORMAT = 1;
    public static final int MAX_CHASSIS = 16;
    public static final int MAX_OFFSET = 8;
    public static final int MAX_NAME = 64;
    public static final int MAX_EXT_CHARS = 4096;

    public record Face(FfId module, FfData config, Map<String, Integer> bindings) {
        public Face {
            config = config == null ? new FfData() : config.copy();
            bindings = Map.copyOf(bindings);
        }
    }

    public record Chassis(int dx, int dy, int dz, WorldDir front, Map<LocalFace, Face> faces, List<FfId> upgrades) {
        public Chassis {
            faces = new EnumMap<>(faces.isEmpty() ? new EnumMap<LocalFace, Face>(LocalFace.class) : faces);
            upgrades = List.copyOf(upgrades);
        }
    }

    private final String name;
    private final List<Chassis> chassis;
    private final FfData ext;

    public Blueprint(String name, List<Chassis> chassis, FfData ext) {
        if (name == null || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("blueprint name must be at most " + MAX_NAME + " characters");
        }
        if (chassis.isEmpty() || chassis.size() > MAX_CHASSIS) {
            throw new IllegalArgumentException("blueprint must contain 1.." + MAX_CHASSIS + " chassis");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Chassis c : chassis) {
            if (Math.abs(c.dx()) > MAX_OFFSET || Math.abs(c.dy()) > MAX_OFFSET || Math.abs(c.dz()) > MAX_OFFSET) {
                throw new IllegalArgumentException("chassis offset outside +/-" + MAX_OFFSET);
            }
            if (!seen.add(c.dx() + "," + c.dy() + "," + c.dz())) {
                throw new IllegalArgumentException("two chassis at the same position");
            }
            if (!c.front().horizontal()) {
                throw new IllegalArgumentException("chassis front must be horizontal");
            }
        }
        this.name = name;
        this.chassis = List.copyOf(chassis);
        this.ext = ext == null ? new FfData() : ext.copy();
    }

    public String name() {
        return name;
    }

    public List<Chassis> chassis() {
        return chassis;
    }

    public FfData ext() {
        return ext.copy();
    }

    // ------------------------------------------------------------------ transforms

    /** Rotates the design clockwise (seen from above) around its origin. Local faces are unchanged. */
    public Blueprint rotated(int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        List<Chassis> out = new ArrayList<>();
        for (Chassis c : chassis) {
            int x = c.dx(), z = c.dz();
            WorldDir f = c.front();
            for (int i = 0; i < q; i++) {
                int nx = -z;
                z = x;
                x = nx;
                f = f.clockwise();
            }
            out.add(new Chassis(x, c.dy(), z, f, c.faces(), c.upgrades()));
        }
        return new Blueprint(name, out, ext);
    }

    /**
     * Mirrors across the X axis (east/west). Fronts mirror and the LEFT/RIGHT faces swap so the mirrored machine
     * is the true reflection. Bindings and configuration are kept.
     */
    public Blueprint mirroredX() {
        List<Chassis> out = new ArrayList<>();
        for (Chassis c : chassis) {
            WorldDir f = c.front() == WorldDir.EAST ? WorldDir.WEST : c.front() == WorldDir.WEST ? WorldDir.EAST : c.front();
            Map<LocalFace, Face> faces = new EnumMap<>(LocalFace.class);
            c.faces().forEach((lf, face) -> faces.put(lf == LocalFace.LEFT ? LocalFace.RIGHT : lf == LocalFace.RIGHT ? LocalFace.LEFT : lf, face));
            out.add(new Chassis(-c.dx(), c.dy(), c.dz(), f, faces, c.upgrades()));
        }
        return new Blueprint(name, out, ext);
    }

    // ------------------------------------------------------------------ bill of materials

    /** Item ids and counts needed to build the design: one chassis per entry plus every module and upgrade item. */
    public Map<FfId, Integer> billOfMaterials(FfId chassisItem) {
        Map<FfId, Integer> bom = new LinkedHashMap<>();
        bom.merge(chassisItem, chassis.size(), Integer::sum);
        for (Chassis c : chassis) {
            c.faces().values().forEach(f -> bom.merge(f.module(), 1, Integer::sum));
            c.upgrades().forEach(u -> bom.merge(u, 1, Integer::sum));
        }
        return bom;
    }

    /** Modules referenced by this blueprint that the current registry does not define or has disabled. */
    public List<String> compatibilityProblems() {
        List<String> out = new ArrayList<>();
        for (Chassis c : chassis) {
            for (var e : c.faces().entrySet()) {
                ModuleDefinition def = FieldframeRegistry.module(e.getValue().module());
                if (def == null) {
                    out.add("unknown module " + e.getValue().module() + " on " + e.getKey().key());
                } else if (FieldframeRegistry.isDisabled(def.id())) {
                    out.add("module " + def.id() + " is disabled by the pack");
                } else if (!def.allowedFaces().contains(e.getKey())) {
                    out.add(def.id() + " cannot be mounted on " + e.getKey().key());
                }
            }
            for (FfId u : c.upgrades()) {
                if (FieldframeRegistry.upgrade(u) == null) {
                    out.add("unknown upgrade " + u);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ codec

    public FfData toData() {
        FfData d = new FfData().putInt("format", FORMAT).putString("name", name);
        List<Object> list = new ArrayList<>();
        for (Chassis c : chassis) {
            FfData cd = new FfData().putInt("x", c.dx()).putInt("y", c.dy()).putInt("z", c.dz())
                    .putString("front", c.front().name().toLowerCase(Locale.ROOT));
            FfData faces = new FfData();
            c.faces().forEach((lf, f) -> {
                FfData fd = new FfData().putString("module", f.module().toString()).put("config", f.config());
                FfData b = new FfData();
                f.bindings().forEach(b::putInt);
                fd.put("bindings", b);
                faces.put(lf.key(), fd);
            });
            cd.put("faces", faces);
            List<Object> ups = new ArrayList<>();
            c.upgrades().forEach(u -> ups.add(u.toString()));
            cd.putList("upgrades", ups);
            list.add(cd);
        }
        d.putList("chassis", list);
        if (!ext.isEmpty()) {
            d.put("ext", ext);
        }
        return d;
    }

    private static final Set<String> TOP_KEYS = Set.of("format", "name", "chassis", "ext");
    private static final Set<String> CHASSIS_KEYS = Set.of("x", "y", "z", "front", "faces", "upgrades");
    private static final Set<String> FACE_KEYS = Set.of("module", "config", "bindings");

    /**
     * Strict, bounded decode of untrusted data (items, files, packets). Throws {@link IllegalArgumentException}
     * with a readable reason for anything unsupported. Module configuration is sanitized against the current
     * schema when the definition exists; bindings are range-checked.
     */
    public static Blueprint fromData(FfData d) {
        if (d.depth() > 8) {
            throw new IllegalArgumentException("blueprint nesting too deep");
        }
        for (String k : d.keys()) {
            if (!TOP_KEYS.contains(k)) {
                throw new IllegalArgumentException("unsupported blueprint field '" + k + "'");
            }
        }
        int format = d.getInt("format", -1);
        if (format < 1 || format > FORMAT) {
            throw new IllegalArgumentException("unsupported blueprint format " + format);
        }
        List<Object> list = d.getList("chassis");
        if (list.isEmpty() || list.size() > MAX_CHASSIS) {
            throw new IllegalArgumentException("blueprint must contain 1.." + MAX_CHASSIS + " chassis");
        }
        List<Chassis> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof FfData cd)) {
                throw new IllegalArgumentException("malformed chassis entry");
            }
            for (String k : cd.keys()) {
                if (!CHASSIS_KEYS.contains(k)) {
                    throw new IllegalArgumentException("unsupported chassis field '" + k + "'");
                }
            }
            WorldDir front;
            try {
                front = WorldDir.valueOf(cd.getString("front", "north").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("bad front '" + cd.getString("front", "") + "'");
            }
            Map<LocalFace, Face> faces = new EnumMap<>(LocalFace.class);
            FfData fd = cd.getData("faces");
            for (String fk : fd.keys()) {
                LocalFace lf = LocalFace.byKey(fk);
                FfData f = fd.getDataOrNull(fk);
                if (lf == null || f == null) {
                    throw new IllegalArgumentException("bad face '" + fk + "'");
                }
                for (String k : f.keys()) {
                    if (!FACE_KEYS.contains(k)) {
                        throw new IllegalArgumentException("unsupported face field '" + k + "'");
                    }
                }
                FfId module = FfId.tryParse(f.getString("module", null));
                if (module == null) {
                    throw new IllegalArgumentException("bad module id on " + fk);
                }
                ModuleDefinition def = FieldframeRegistry.module(module);
                FfData config = def == null ? f.getData("config") : def.sanitizeConfig(f.getData("config"));
                Map<String, Integer> bindings = new LinkedHashMap<>();
                FfData b = f.getData("bindings");
                if (b.size() > 12) {
                    throw new IllegalArgumentException("too many bindings on " + fk);
                }
                for (String bk : b.keys()) {
                    int idx = b.getInt(bk, -1);
                    BindingSpec spec = def == null ? null : def.binding(bk);
                    if (def != null && (spec == null || idx < -1 || idx > spec.kind().maxIndex() || (idx == -1 && !spec.optional()))) {
                        throw new IllegalArgumentException("invalid binding " + bk + "=" + idx + " on " + fk);
                    }
                    bindings.put(bk, idx);
                }
                faces.put(lf, new Face(module, config, bindings));
            }
            List<FfId> ups = new ArrayList<>();
            for (Object u : cd.getList("upgrades")) {
                FfId id = u instanceof String s ? FfId.tryParse(s) : null;
                if (id == null) {
                    throw new IllegalArgumentException("bad upgrade id");
                }
                ups.add(id);
            }
            if (ups.size() > 6) {
                throw new IllegalArgumentException("too many upgrades");
            }
            out.add(new Chassis(cd.getInt("x", 0), cd.getInt("y", 0), cd.getInt("z", 0), front, faces, ups));
        }
        FfData ext = d.getDataOrNull("ext");
        if (ext != null && dev.fieldframe.api.data.FfJson.toJsonString(ext).length() > MAX_EXT_CHARS) {
            throw new IllegalArgumentException("extension data larger than " + MAX_EXT_CHARS + " characters");
        }
        return new Blueprint(d.getString("name", "Untitled"), out, ext);
    }
}
