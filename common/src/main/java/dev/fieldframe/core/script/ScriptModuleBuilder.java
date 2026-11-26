package dev.fieldframe.core.script;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ConfigField;
import dev.fieldframe.api.module.Exposure;
import dev.fieldframe.api.module.ModuleCategory;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TickPhase;
import dev.fieldframe.api.module.TunableSpec;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.modules.ProcessorBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Script-friendly builder for module definitions (used by every KubeJS generation's bridge). Two archetypes:
 * <ul>
 *   <li>{@code processor(family)}: the shared processing engine with the standard bindings; recipes for the family
 *       come from data ({@code fieldframe:process} recipes with that {@code family}) or scripts.</li>
 *   <li>callbacks: {@code onTick}/{@code onInstalled}/{@code onRemoved} run trusted script code against a
 *       {@link ScriptModuleContext}.</li>
 * </ul>
 * All inputs are validated with messages naming the module id.
 */
public final class ScriptModuleBuilder {
    private final FfId id;
    private ModuleCategory category = ModuleCategory.CONTROL;
    private TickPhase phase = TickPhase.PROCESS;
    private ModuleDefinition.Stage stage = ModuleDefinition.Stage.AUTOMATION;
    private final List<BindingSpec> bindings = new ArrayList<>();
    private final List<ConfigField> config = new ArrayList<>();
    private final List<TunableSpec> tunables = new ArrayList<>();
    private final List<Exposure> exposures = new ArrayList<>();
    private LocalFace[] faces;
    private FfId family;
    private boolean fluidIn;
    private boolean fluidOut;
    private ScriptedBehavior.Callback onTick;
    private ScriptedBehavior.Callback onInstalled;
    private ScriptedBehavior.Callback onPlayerUse;
    private ScriptedBehavior.Callback onRemoved;
    private final FfData presentation = new FfData();
    private boolean replace;

    public ScriptModuleBuilder(String id) {
        FfId parsed = FfId.tryParse(id);
        if (parsed == null || !id.contains(":")) {
            throw new IllegalArgumentException("Module id must be 'namespace:path', got '" + id + "'");
        }
        this.id = parsed;
    }

    public FfId id() {
        return id;
    }

    public boolean isReplace() {
        return replace;
    }

    private <E extends Enum<E>> E parse(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(id + ": unknown " + what + " '" + value + "'");
        }
    }

    public ScriptModuleBuilder category(String c) {
        category = parse(ModuleCategory.class, c, "category");
        return this;
    }

    public ScriptModuleBuilder phase(String p) {
        phase = parse(TickPhase.class, p, "phase");
        return this;
    }

    public ScriptModuleBuilder stage(String s) {
        stage = parse(ModuleDefinition.Stage.class, s, "stage");
        return this;
    }

    public ScriptModuleBuilder faces(String... f) {
        faces = new LocalFace[f.length];
        for (int i = 0; i < f.length; i++) {
            faces[i] = parse(LocalFace.class, f[i], "face");
        }
        return this;
    }

    public ScriptModuleBuilder exposes(String... e) {
        for (String x : e) {
            Exposure ex = parse(Exposure.class, x, "exposure");
            if (ex == Exposure.ITEM || ex == Exposure.FLUID || ex == Exposure.ENERGY) {
                throw new IllegalArgumentException(id + ": scripted modules cannot expose " + x
                        + " directly; use an archetype or a Java addon (keeps budgets and conservation enforced)");
            }
            exposures.add(ex);
        }
        return this;
    }

    public ScriptModuleBuilder itemLane(String key, String lane) {
        bindings.add(BindingSpec.item(key, laneIndex(lane)));
        return this;
    }

    public ScriptModuleBuilder fluidLane(String key, String lane) {
        bindings.add(BindingSpec.fluid(key, laneIndex(lane)));
        return this;
    }

    public ScriptModuleBuilder signalIn(String key) {
        bindings.add(BindingSpec.signalIn(key));
        return this;
    }

    public ScriptModuleBuilder signalOut(String key) {
        bindings.add(BindingSpec.signalOut(key));
        return this;
    }

    private int laneIndex(String lane) {
        if (lane == null || lane.length() != 1 || "ABCabc".indexOf(lane.charAt(0)) < 0) {
            throw new IllegalArgumentException(id + ": lane must be A, B or C, got '" + lane + "'");
        }
        return Character.toUpperCase(lane.charAt(0)) - 'A';
    }

    public ScriptModuleBuilder configBool(String key, boolean def) {
        config.add(ConfigField.bool(key, def));
        return this;
    }

    public ScriptModuleBuilder configInt(String key, double min, double max, double def) {
        config.add(ConfigField.integer(key, (long) min, (long) max, (long) def));
        return this;
    }

    public ScriptModuleBuilder configChoice(String key, String def, String... options) {
        config.add(ConfigField.choice(key, def, options));
        return this;
    }

    public ScriptModuleBuilder tunable(String key, double min, double max, double def, String unit) {
        tunables.add(TunableSpec.of(key, min, max, def, unit));
        return this;
    }

    /** Uses the shared processing engine for the given recipe family (e.g. {@code mypack:smashing}). */
    public ScriptModuleBuilder processor(String familyId) {
        FfId f = FfId.tryParse(familyId);
        if (f == null) {
            throw new IllegalArgumentException(id + ": invalid family '" + familyId + "'");
        }
        family = f;
        category = ModuleCategory.PROCESSING;
        phase = TickPhase.PROCESS;
        return this;
    }

    public ScriptModuleBuilder withFluids(boolean in, boolean out) {
        fluidIn = in;
        fluidOut = out;
        return this;
    }

    public ScriptModuleBuilder onTick(ScriptedBehavior.Callback cb) {
        onTick = cb;
        return this;
    }

    public ScriptModuleBuilder onInstalled(ScriptedBehavior.Callback cb) {
        onInstalled = cb;
        return this;
    }

    /** Called once per empty-hand use of the face (next tick); the use no longer opens the machine screen. */
    public ScriptModuleBuilder onPlayerUse(ScriptedBehavior.Callback cb) {
        onPlayerUse = cb;
        return this;
    }

    public ScriptModuleBuilder onRemoved(ScriptedBehavior.Callback cb) {
        onRemoved = cb;
        return this;
    }

    /** Texture used for the faceplate on the chassis, e.g. {@code kubejs:block/smasher_face}. */
    public ScriptModuleBuilder faceTexture(String texture) {
        if (FfId.tryParse(texture) == null) {
            throw new IllegalArgumentException(id + ": invalid texture id '" + texture + "'");
        }
        presentation.putString("face_texture", texture);
        return this;
    }

    /** Texture drawn full-bright over the faceplate while the module works, e.g. {@code kubejs:block/smasher_glow}. */
    public ScriptModuleBuilder glowTexture(String texture) {
        if (FfId.tryParse(texture) == null) {
            throw new IllegalArgumentException(id + ": invalid texture id '" + texture + "'");
        }
        presentation.putString("glow_texture", texture);
        return this;
    }

    /** Shows the module's progress (0..1 as level 0..15) as lamps and a bar on its face, like the Indicator Panel. */
    public ScriptModuleBuilder gauge(boolean shown) {
        presentation.putBool("gauge", shown);
        return this;
    }

    /** Sound event played now and then while the module works, e.g. {@code minecraft:block.grindstone.use}; "" for none. */
    public ScriptModuleBuilder workSound(String sound) {
        if (!sound.isEmpty() && FfId.tryParse(sound) == null) {
            throw new IllegalArgumentException(id + ": invalid sound id '" + sound + "'");
        }
        presentation.putString("work_sound", sound);
        return this;
    }

    /** Simple particle puffed from the face while the module works, e.g. {@code minecraft:smoke}; "" for none. */
    public ScriptModuleBuilder workParticle(String particle) {
        if (!particle.isEmpty() && FfId.tryParse(particle) == null) {
            throw new IllegalArgumentException(id + ": invalid particle id '" + particle + "'");
        }
        presentation.putString("work_particle", particle);
        return this;
    }

    /**
     * Lists the module as a machine on a recipe-viewer page (a JEI category id), e.g. {@code fieldframe:temperatures}
     * or {@code fieldframe:galvanic_electrodes}; call once per page.
     */
    public ScriptModuleBuilder viewerPage(String page) {
        if (FfId.tryParse(page) == null) {
            throw new IllegalArgumentException(id + ": invalid viewer page id '" + page + "'");
        }
        java.util.List<Object> pages = new java.util.ArrayList<>(presentation.getList("viewer_pages"));
        pages.add(page);
        presentation.putList("viewer_pages", pages);
        return this;
    }

    /** Fallback display name used when no translation exists. */
    public ScriptModuleBuilder displayName(String name) {
        presentation.putString("display_name", name.length() > 64 ? name.substring(0, 64) : name);
        return this;
    }

    public ScriptModuleBuilder manualEntry(String entryId) {
        presentation.putString("manual_entry", entryId);
        return this;
    }

    /** Explicitly replace an existing definition with the same id (startup only). */
    public ScriptModuleBuilder replaceExisting() {
        replace = true;
        return this;
    }

    public ModuleDefinition build() {
        ModuleDefinition.Builder b;
        if (family != null) {
            if (onTick != null) {
                throw new IllegalArgumentException(id + ": a processor archetype cannot also define onTick");
            }
            b = BuiltinModules.processorBuilder(id, family, stage, fluidIn, fluidOut).behavior(ProcessorBehavior::new);
        } else {
            String sid = id.toString();
            ScriptedBehavior.Callback t = onTick, i = onInstalled, r = onRemoved, u = onPlayerUse;
            b = ModuleDefinition.builder(id).category(category).phase(phase).stage(stage)
                    .behavior(d -> new ScriptedBehavior(sid, t, i, r).withPlayerUse(u));
        }
        for (BindingSpec s : bindings) {
            b.binding(s);
        }
        for (ConfigField f : config) {
            b.config(f);
        }
        for (TunableSpec t : tunables) {
            b.tunable(t);
        }
        if (!exposures.isEmpty()) {
            b.exposes(exposures.toArray(new Exposure[0]));
        }
        if (faces != null) {
            b.faces(faces);
        }
        if (presentation.has("manual_entry")) {
            FfId me = FfId.tryParse(presentation.getString("manual_entry", ""));
            if (me != null) {
                b.manualEntry(me);
            }
        }
        b.presentation(presentation);
        return b.build();
    }
}
