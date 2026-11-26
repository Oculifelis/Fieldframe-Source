package dev.fieldframe.forge.test;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.adapter.McFluidVariant;
import dev.fieldframe.forge.adapter.McItemVariant;
import dev.fieldframe.forge.chassis.ChassisBlock;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tests for the example KubeJS pack (examples/kubejs-1.20.1). Created only when KubeJS is loaded; run with
 * {@code ./gradlew runGameTestServer -PwithKubeJS}, which copies the example scripts into the run directory.
 * If KubeJS is loaded but the example pack is not, the tests fail with an explicit message.
 */
@GameTestHolder(Fieldframe.MOD_ID)
public final class KubeJSExampleGameTests {
    private static TestFunction test(String name, int timeout, Consumer<GameTestHelper> body) {
        return new TestFunction("kubejs", "fieldframe.kubejs_" + name, "fieldframe:empty7", timeout, 0L, true, body);
    }

    private static ChassisBlockEntity chassis(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        h.setBlock(c, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
        return (ChassisBlockEntity) h.getBlockEntity(c);
    }

    private static void requireModule(GameTestHelper h, String id) {
        if (FieldframeRegistry.module(FfId.parse(id)) == null) {
            h.fail("KubeJS is loaded but module " + id + " is not registered: copy examples/kubejs-1.20.1/kubejs into the run directory");
        }
    }

    @GameTestGenerator
    public static Collection<TestFunction> generate() {
        List<TestFunction> out = new ArrayList<>();
        if (!ModList.get().isLoaded("kubejs")) {
            return out;
        }
        out.add(test("smasher_uses_script_recipe", 200, h -> {
            requireModule(h, "kubejs:smasher");
            ChassisBlockEntity be = chassis(h);
            ChassisCore.Result r = be.core().install(LocalFace.TOP, FieldframeRegistry.module(FfId.parse("kubejs:smasher")), null);
            h.assertTrue(r.ok(), "install " + r);
            be.core().energy().receive(16_000, false);
            be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.COBBLESTONE)), 2, false);
            h.succeedWhen(() -> h.assertTrue(be.core().lane(1).countOf(McItemVariant.of(new ItemStack(Items.SAND))) == 2,
                    "2 sand expected; status " + be.core().statuses()));
        }));
        out.add(test("pulse_divider_counts_and_persists", 300, h -> {
            requireModule(h, "kubejs:pulse_divider");
            ChassisBlockEntity be = chassis(h);
            var timer = FieldframeRegistry.module(BuiltinModules.TIMER_COUNTER);
            be.core().install(LocalFace.LEFT, timer, timer.defaultConfig().putLong("period", 4).putLong("pulse", 1));
            be.core().setBinding(LocalFace.LEFT, "out", 0);
            be.core().install(LocalFace.RIGHT, FieldframeRegistry.module(FfId.parse("kubejs:pulse_divider")), null);
            be.core().setBinding(LocalFace.RIGHT, "in", 0);
            be.core().setBinding(LocalFace.RIGHT, "out", 1);
            h.runAfterDelay(100, () -> {
                var tag = be.saveWithFullMetadata();
                String count = be.core().module(LocalFace.RIGHT).status().args().toString();
                h.assertTrue(!count.equals("[0.0]") && !count.equals("[]"), "divider should have counted pulses: " + count);
                be.load(tag);
                h.assertTrue(be.core().module(LocalFace.RIGHT).status() != null, "reloaded");
                h.succeed();
            });
        }));
        out.add(test("washing_override_with_catalyst_and_fluid_result", 200, h -> {
            ChassisBlockEntity be = chassis(h);
            be.core().install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.WASHER), null);
            be.core().energy().receive(16_000, false);
            be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.SHEARS)), 1, false);
            be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.GRAVEL)), 2, false);
            be.core().tank(0).fill(McFluidVariant.of(new FluidStack(Fluids.WATER, 1)), 1000, false);
            h.succeedWhen(() -> {
                long flint = be.core().lane(1).countOf(McItemVariant.of(new ItemStack(Items.FLINT)));
                h.assertTrue(flint == 2, "2 flint expected, have " + flint + " status " + be.core().statuses());
                h.assertTrue(be.core().lane(0).countOf(McItemVariant.of(new ItemStack(Items.SHEARS))) == 1, "catalyst kept");
                h.assertTrue(be.core().tank(0).amount() == 900, "used 2 x 50 mB, have " + be.core().tank(0).amount());
                h.assertTrue(be.core().tank(1).amount() == 50, "returned 2 x 25 mB, have " + be.core().tank(1).amount());
            });
        }));
        out.add(test("pack_rule_denies_without_touching_inputs", 100, h -> {
            ChassisBlockEntity be = chassis(h);
            be.core().install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.PRESS), null);
            be.core().energy().receive(16_000, false);
            be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.IRON_NUGGET)), 9, false);
            h.runAfterDelay(40, () -> {
                h.assertTrue(be.core().module(LocalFace.TOP).status().code().equals("CANCELLED_BY_RULE"), "status " + be.core().statuses());
                h.assertTrue(be.core().module(LocalFace.TOP).status().args().contains("no_nugget_press"), "reason shown");
                h.assertTrue(be.core().lane(0).countOf(McItemVariant.of(new ItemStack(Items.IRON_NUGGET))) == 9, "inputs untouched");
                h.assertTrue(be.core().energy().stored() == 16_000, "no energy spent");
                h.succeed();
            });
        }));
        out.add(test("tuning_from_script_applied", 20, h -> {
            double v = FieldframeRegistry.tunable(FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), "rf_per_tick");
            h.assertTrue(v == 60.0, "script tuning should set rf_per_tick=60, is " + v);
            h.succeed();
        }));
        out.add(test("machine_data_from_scripts", 20, h -> {
            boolean fuel = dev.fieldframe.api.process.MachineData.fuels().stream()
                    .anyMatch(f -> f.engine().equals(BuiltinModules.REDSTONE_ENGINE) && f.key().equals("minecraft:glowstone_dust") && f.rf() == 12000);
            h.assertTrue(fuel, "script fuel present: " + dev.fieldframe.api.process.MachineData.fuels());
            var auras = dev.fieldframe.api.process.MachineData.auras();
            h.assertTrue(auras.stream().anyMatch(a -> a.id().toString().equals("kubejs:golden_haste") && a.amplifier() == 1), "script aura added");
            h.assertTrue(auras.stream().noneMatch(a -> a.id().toString().equals("fieldframe:metallurgy_carmot")), "Metallurgy's carmot aura removed");
            h.assertTrue(auras.stream().anyMatch(a -> a.id().toString().equals("fieldframe:metallurgy_adamantine")), "other Metallurgy auras kept");
            var temps = dev.fieldframe.core.material.Temperatures.entries();
            h.assertTrue(temps.stream().anyMatch(t -> t.key().equals("minecraft:obsidian") && t.kelvin() == 450), "script temperature added");
            h.assertTrue(temps.stream().noneMatch(t -> t.key().equals("minecraft:packed_ice")), "packed ice removed by the script");
            h.assertTrue(temps.stream().anyMatch(t -> t.key().equals("minecraft:blue_ice")), "other temperatures kept");
            h.assertTrue(dev.fieldframe.core.material.Electrodes.entries().stream()
                    .anyMatch(e -> e.key().equals("minecraft:emerald") && e.volts() == -0.25), "script electrode added");
            h.assertTrue(dev.fieldframe.core.material.RecipeFilters.removed(FfId.parse("fieldframe:crushing/metallurgy/platinum_from_raw"),
                    FfId.ff("crushing")), "script recipe filter");
            h.succeed();
        }));
        out.add(test("script_overrides_metallurgy_recipe", 300, h -> {
            ChassisBlockEntity be = chassis(h);
            be.core().install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.ALLOY_SMELTER), null);
            be.core().energy().receive(16_000, false);
            be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.COPPER_INGOT)), 3, false);
            be.core().lane(0).insert(McItemVariant.of(new ItemStack(item("metallurgy", "zinc_ingot"))), 1, false);
            h.succeedWhen(() -> {
                long brass = be.core().lane(1).countOf(McItemVariant.of(new ItemStack(item("metallurgy", "brass_ingot"))));
                h.assertTrue(brass == 3, "the script's brass recipe (3), not Metallurgy's (4): " + brass + "; " + be.core().statuses());
            });
        }));
        out.add(test("script_registers_chassis_tier", 20, h -> {
            var t = FieldframeRegistry.tier(FfId.parse("kubejs:mythic"));
            h.assertTrue(t != null && t.level() == 5, "kubejs:mythic tier registered");
            h.assertTrue(FfContent.tierKit(t.id()) != null, "its kit item exists");
            var up = FieldframeRegistry.upgrade(FfId.parse("kubejs:overclock_card"));
            h.assertTrue(up != null && up.extraSlotsPerLane() == 2 && up.extraItemsPerTick() == 4, "kubejs:overclock_card upgrade registered");
            h.assertTrue(FfContent.upgradeItem(up.id()) != null, "its item exists");
            h.succeed();
        }));
        return out;
    }

    private static net.minecraft.world.item.Item item(String ns, String path) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(ns, path));
    }
}
