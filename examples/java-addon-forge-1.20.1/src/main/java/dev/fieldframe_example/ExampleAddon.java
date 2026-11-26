package dev.fieldframe_example;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualPageTypes;
import dev.fieldframe.api.module.Archetypes;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ConfigField;
import dev.fieldframe.api.module.ModuleCategory;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TickPhase;
import dev.fieldframe.forge.api.FieldframeForgeApi;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Example external addon. Demonstrates, using only the public API:
 * <ol>
 *   <li>a new processor (Kiln) with its own recipe family {@code fieldframe_example:firing} defined in data;</li>
 *   <li>a new sensor module (Weather Vane) with its own behavior;</li>
 *   <li>a control/diagnostic listener (counts completed operations per module type);</li>
 *   <li>a new manual page type with a validator and a client renderer, used by the addon's manual entry;</li>
 *   <li>machine data from Java (an engine fuel, an Aura Chamber recipe, a recipe removal) and a sixth chassis tier.</li>
 * </ol>
 */
@Mod(ExampleAddon.MOD_ID)
public final class ExampleAddon {
    public static final String MOD_ID = "fieldframe_example";
    public static final FfId KILN = FfId.of(MOD_ID, "kiln");
    public static final FfId FIRING = FfId.of(MOD_ID, "firing");
    public static final FfId WEATHER_VANE = FfId.of(MOD_ID, "weather_vane");
    public static final FfId GAUGE_PAGE = FfId.of(MOD_ID, "gauge");
    public static final FfId KILN_WARMTH = FfId.of(MOD_ID, "kiln_warmth");
    public static final FfId TEMPERED = FfId.of(MOD_ID, "tempered");

    /** Completed operations per module id, maintained by the event listener (server side). */
    public static final Map<FfId, Integer> COMPLETED = new ConcurrentHashMap<>();

    public ExampleAddon() {
        // Machine data: contributors run on every data load, after the datapack files (see MachineData).
        dev.fieldframe.api.process.MachineData.addFuelContributor(f -> f.item(FfId.parse("fieldframe:gem_engine"), "minecraft:amethyst_cluster", 50_000));
        dev.fieldframe.api.process.MachineData.addAuraContributor(a -> a.add(new dev.fieldframe.api.process.MachineData.Aura(
                KILN_WARMTH, "minecraft:magma_block", 4, FfId.parse("minecraft:fire_resistance"), 600, 0)));
        dev.fieldframe.api.process.MachineData.addRecipeFilterContributor(r -> r.remove("fieldframe:pressing/gold_gear"));
        FieldframeForgeApi.onRegisterModules(() -> {
            // A tier above Resonant; Fieldframe generates its kit item fieldframe_example:tempered_upgrade_kit.
            FieldframeRegistry.registerTier(new dev.fieldframe.api.chassis.ChassisTier(TEMPERED, 5, 5.0, 3, 5, 14_000, 700_000, 3_072));
            FieldframeRegistry.registerModule(Archetypes.processor(KILN, FIRING, ModuleDefinition.Stage.AUTOMATION, false, false)
                    .presentation(new FfData().putString("face_texture", "fieldframe_example:block/module/kiln"))
                    .build(), MOD_ID);
            FieldframeRegistry.registerModule(ModuleDefinition.builder(WEATHER_VANE)
                    .category(ModuleCategory.CONTROL)
                    .phase(TickPhase.CONTROL)
                    .binding(BindingSpec.signalOut("out", 6))
                    .config(ConfigField.choice("trigger", "rain", "rain", "thunder", "clear"))
                    .behavior(def -> new WeatherVaneBehavior())
                    .presentation(new FfData().putString("face_texture", "fieldframe:block/module/environment_sensor"))
                    .build(), MOD_ID);
        });
        FieldframeEvents.register(FieldframeEvents.OperationCompleted.class, e -> COMPLETED.merge(e.moduleId(), 1, Integer::sum), MOD_ID);
        ManualPageTypes.register(GAUGE_PAGE, d -> {
            double v = d.getDouble("value", -1);
            return v < 0 || v > 1 ? List.of("gauge 'value' must be between 0 and 1") : List.of();
        });
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ExampleAddonClient::init);
    }
}
