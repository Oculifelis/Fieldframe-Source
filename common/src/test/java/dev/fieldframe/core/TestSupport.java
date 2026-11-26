package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessFamilies;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;

import java.util.List;

public final class TestSupport {
    private TestSupport() {
    }

    public static final ItemVariant IRON_ORE = ItemVariant.Simple.of("minecraft:raw_iron");
    public static final ItemVariant IRON = ItemVariant.Simple.of("minecraft:iron_ingot");
    public static final ItemVariant COAL = ItemVariant.Simple.of("minecraft:coal");
    public static final ItemVariant GRAVEL = ItemVariant.Simple.of("minecraft:gravel");
    public static final ItemVariant FLINT = ItemVariant.Simple.of("minecraft:flint");
    public static final ItemVariant COBBLE = ItemVariant.Simple.of("minecraft:cobblestone");

    public static void freshRegistry() {
        FieldframeRegistry.resetForTests();
        BuiltinModules.registerAll();
        BuiltinModules.registerSinceFreeze();
    }

    public static SimulatedWorld world() {
        SimulatedWorld w = new SimulatedWorld();
        w.fuels.put(COAL.id(), 1600);
        w.addRecipe(new ProcessRecipe(FfId.ff("smelt_iron"), ProcessFamilies.HEATING,
                List.of(new ProcessRecipe.ItemInput(ItemFilter.allowIds("minecraft:raw_iron"), 1, true, "raw iron")),
                null, List.of(new ProcessRecipe.ItemResult(IRON, 1, 1.0)), null, 100, 20));
        w.addRecipe(new ProcessRecipe(FfId.ff("crush_gravel"), ProcessFamilies.CRUSHING,
                List.of(new ProcessRecipe.ItemInput(ItemFilter.allowIds("minecraft:gravel"), 1, true, "gravel")),
                null, List.of(new ProcessRecipe.ItemResult(FLINT, 1, 0.5)), null, 20, 10));
        return w;
    }

    public static ChassisCore chassis(SimulatedWorld w) {
        ChassisCore c = new ChassisCore(ChassisSpec.STANDARD, VariantCodec.SIMPLE, () -> w.time);
        c.setAddress("test@0,0,0");
        return c;
    }

    public static void run(ChassisCore c, SimulatedWorld w, int ticks) {
        for (int i = 0; i < ticks; i++) {
            w.time++;
            c.tick(w);
        }
    }

    public static long count(dev.fieldframe.api.resource.ItemStore s, ItemVariant v) {
        return s.countOf(v);
    }
}
