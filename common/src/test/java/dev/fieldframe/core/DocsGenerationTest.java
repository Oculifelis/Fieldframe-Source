package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ConfigField;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TunableSpec;
import dev.fieldframe.core.script.ScriptModuleBuilder;
import dev.fieldframe.core.script.ScriptModuleContext;
import dev.fieldframe.core.script.ScriptRecipes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regenerates documentation that must match the code: docs/module_catalog.csv (from the live registry) and
 * docs/kubejs/fieldframe.d.ts (by reflection over the script-facing classes shared by every KubeJS bridge).
 */
class DocsGenerationTest {
    private static final Path DOCS = Path.of("..", "docs");

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    @Test
    void generateModuleCatalog() throws IOException {
        TestSupport.freshRegistry();
        StringBuilder sb = new StringBuilder("id,category,phase,stage,faces,exposes,bindings,config,tunables,process_family,manual_entry,behavior\n");
        for (ModuleDefinition d : FieldframeRegistry.modules()) {
            String faces = d.allowedFaces().size() == 6 ? "any" : d.allowedFaces().stream().map(f -> f.key()).collect(Collectors.joining(" "));
            String bindings = d.bindings().stream().map(b -> b.key() + ":" + b.kind().name().toLowerCase() + "=" + BindingSpec.label(b.kind(), b.defaultIndex()))
                    .collect(Collectors.joining("; "));
            String config = d.config().stream().map(this::describe).collect(Collectors.joining("; "));
            String tun = d.tunables().values().stream().map(t -> t.key() + "=" + fmt(t.defaultValue()) + t.unit() + " [" + fmt(t.min()) + ".." + fmt(t.max()) + "]")
                    .collect(Collectors.joining("; "));
            String behavior = d.factory().create(d).getClass().getSimpleName();
            sb.append(String.join(",", csv(d.id().toString()), d.category().key(), d.phase().name().toLowerCase(), d.stage().name().toLowerCase(), csv(faces),
                    csv(d.exposures().toString().toLowerCase()), csv(bindings), csv(config), csv(tun),
                    d.processFamily() == null ? "" : d.processFamily().toString(), d.manualEntry().toString(), behavior)).append('\n');
        }
        Files.createDirectories(DOCS);
        Files.writeString(DOCS.resolve("module_catalog.csv"), sb.toString(), StandardCharsets.UTF_8);
        assertTrue(sb.toString().contains("fieldframe:controller_plate"));
    }

    private String describe(ConfigField f) {
        return switch (f.type()) {
            case INT -> f.key() + ":int " + f.min() + ".." + f.max() + " (" + f.defaultNumber() + ")";
            case ENUM -> f.key() + ":" + String.join("|", f.options()) + " (" + f.defaultText() + ")";
            case BOOL -> f.key() + ":bool (" + (f.defaultNumber() != 0) + ")";
            case TEXT -> f.key() + ":text<=" + f.max();
            default -> f.key() + ":" + f.type().name().toLowerCase();
        };
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String ts(Class<?> c) {
        if (c == void.class) {
            return "void";
        }
        if (c == boolean.class || c == Boolean.class) {
            return "boolean";
        }
        if (c.isPrimitive() || Number.class.isAssignableFrom(c)) {
            return "number";
        }
        if (c == String.class) {
            return "string";
        }
        if (c == String[].class) {
            return "string[]";
        }
        if (c == List.class) {
            return "string[]";
        }
        return c.getSimpleName().replace("Builder", "FieldframeRecipeBuilder").replace("Callback", "(ctx: FieldframeModuleContext) => void");
    }

    private static String methods(Class<?> c, String tsName) {
        StringBuilder sb = new StringBuilder("declare interface " + tsName + " {\n");
        Method[] ms = c.getDeclaredMethods();
        Arrays.sort(ms, Comparator.comparing(Method::getName).thenComparing(m -> m.getParameterCount()));
        for (Method m : ms) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            StringBuilder params = new StringBuilder();
            var p = m.getParameters();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) {
                    params.append(", ");
                }
                if (m.isVarArgs() && i == p.length - 1) {
                    params.append("...").append(p[i].getName()).append(": string[]");
                } else {
                    params.append(p[i].getName()).append(": ").append(ts(p[i].getType()));
                }
            }
            String ret = m.getReturnType() == c ? tsName : ts(m.getReturnType());
            if (m.getReturnType().getSimpleName().equals("ModuleDefinition")) {
                continue;
            }
            sb.append("    ").append(m.getName()).append("(").append(params).append("): ").append(ret).append(";\n");
        }
        return sb.append("}\n\n").toString();
    }

    @Test
    void generateKubeJsTypes() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATED by common/src/test/.../DocsGenerationTest from the shared script-facing classes.\n");
        sb.append("// Identical across the KubeJS 6 (Forge 1.20.1), 7 (NeoForge 1.21.1) and 8 (NeoForge 26.1.2) bridges.\n");
        sb.append("// Requires compiling with -parameters for real parameter names (see common/build.gradle).\n\n");
        sb.append(methods(ScriptModuleBuilder.class, "FieldframeModuleBuilder"));
        sb.append(methods(ScriptModuleContext.class, "FieldframeModuleContext"));
        sb.append(methods(ScriptRecipes.Builder.class, "FieldframeRecipeBuilder"));
        sb.append("""
                declare interface FieldframeRegisterModulesEvent {
                    create(id: string): FieldframeModuleBuilder;
                    processor(id: string, family: string): FieldframeModuleBuilder;
                }

                declare interface FieldframeTuningEvent {
                    tune(module: string, key: string, value: number): void;
                    disable(module: string): void;
                    enable(module: string): void;
                }

                declare interface FieldframeOperationStartingEvent {
                    readonly moduleId: string;
                    readonly recipeId: string;
                    readonly family: string;
                    readonly address: string;
                    deny(reason: string): void;
                }

                declare interface FieldframeOperationCompletedEvent {
                    readonly moduleId: string;
                    readonly recipeId: string;
                    readonly address: string;
                    readonly operationId: number;
                }

                declare interface FieldframeWorldActionEvent {
                    readonly moduleId: string;
                    /** break | place | use | drain_fluid | place_fluid | collect_items | drop_item | move_entities | fish | collect_experience */
                    readonly action: string;
                    /** face-local depth, right, up (0 for whole-face actions) */
                    readonly x: number;
                    readonly y: number;
                    readonly z: number;
                    readonly address: string;
                    /** The module reports PROTECTED and changes nothing. */
                    deny(reason: string): void;
                }

                declare interface FieldframeBlueprintSummary {
                    readonly valid: boolean;
                    readonly error?: string;
                    readonly name?: string;
                    readonly chassisCount?: number;
                    readonly chassis?: { offset: number[]; front: string; faces: { [face: string]: string }; upgrades: string[] }[];
                    readonly billOfMaterials?: { [item: string]: number };
                    readonly problems?: string[];
                }

                declare interface FieldframeModuleEvent {
                    readonly moduleId: string;
                    readonly address: string;
                    readonly face: string;
                }

                declare interface FieldframeManualEvent {
                    category(id: string, title: string, icon: string, sort: number): void;
                    entry(id: string, definition: object): void;
                    replace(id: string, definition: object): void;
                    remove(id: string): void;
                }

                declare const FieldframeEvents: {
                    /** startup_scripts */
                    registerModules(handler: (event: FieldframeRegisterModulesEvent) => void): void;
                    /** server_scripts; runs on every datapack reload */
                    tuning(handler: (event: FieldframeTuningEvent) => void): void;
                    operationStarting(handler: (event: FieldframeOperationStartingEvent) => void): void;
                    operationCompleted(handler: (event: FieldframeOperationCompletedEvent) => void): void;
                    moduleInstalled(handler: (event: FieldframeModuleEvent) => void): void;
                    moduleRemoved(handler: (event: FieldframeModuleEvent) => void): void;
                    worldAction(handler: (event: FieldframeWorldActionEvent) => void): void;
                    /** client_scripts; runs on every resource reload */
                    manual(handler: (event: FieldframeManualEvent) => void): void;
                };

                declare const Fieldframe: {
                    readonly recipes: { process(family: string): FieldframeRecipeBuilder };
                    isModule(id: string): boolean;
                    tunable(module: string, key: string): number;
                    modules(): string[];
                    upgrades(): { id: string; extraSlotsPerLane: number; extraFluidCapacity: number; extraEnergyCapacity: number;
                        extraItemsPerTick: number; extraFluidPerTick: number; extraEnergyPerTick: number; extraControllerRules: number }[];
                    blueprint(json: string): FieldframeBlueprintSummary;
                };
                """);
        Files.createDirectories(DOCS.resolve("kubejs"));
        Files.writeString(DOCS.resolve("kubejs").resolve("fieldframe.d.ts"), sb.toString(), StandardCharsets.UTF_8);
        assertTrue(sb.toString().contains("onTick("));
    }
}
