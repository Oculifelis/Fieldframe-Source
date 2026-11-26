package dev.fieldframe.neoforge.test;

import com.mojang.authlib.GameProfile;
import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FluidArmorEffects;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.adapter.McFluidVariant;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.adapter.McTagLookup;
import dev.fieldframe.neoforge.adapter.NbtBridge;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.feature.FeatureContent;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import dev.fieldframe.neoforge.feature.MaterialContent;
import dev.fieldframe.neoforge.feature.flora.FloraEvents;
import dev.fieldframe.neoforge.feature.flora.FluidArmorItem;
import dev.fieldframe.neoforge.feature.flora.FluidArmorRows;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fluid armor effects of any fluid (data: fieldframe/fluid_armor_effects) and the JEI-free rows of the Fluid Armor page. */
@GameTestHolder(dev.fieldframe.neoforge.Fieldframe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FluidArmorGameTests {
    private static final String T = "empty7";
    private FluidArmorGameTests() {
    }

    /** Every vanilla, Fieldframe and Metallurgy source fluid has an effect, and every effect id is a real mob effect. */
    @GameTest(template = T, timeoutTicks = 20)
    public static void everyFluidHasAnEffect(GameTestHelper h) {
        h.assertTrue(!FluidArmorEffects.all().isEmpty(), "effects loaded from data");
        List<FfId> fluids = new ArrayList<>(List.of(FfId.parse("minecraft:water"), FfId.parse("minecraft:lava")));
        MaterialContent.FLUID_SETS.keySet().forEach(n -> fluids.add(FfId.ff(n)));
        BuiltInRegistries.FLUID.stream().filter(f -> f.isSource(f.defaultFluidState())).forEach(f -> {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(f);
            if (id.getNamespace().equals("metallurgy")) {
                fluids.add(FfId.of(id.getNamespace(), id.getPath()));
            }
        });
        h.assertTrue(fluids.size() > 2 + 10 + 40, "Metallurgy fluids present: " + fluids.size());
        List<String> missing = new ArrayList<>();
        for (FfId f : fluids) {
            if (FluidArmorEffects.forFluid(f, McTagLookup.INSTANCE).isEmpty()) {
                missing.add(f.toString());
            }
        }
        h.assertTrue(missing.isEmpty(), "fluids without an armor effect: " + missing);
        for (FluidArmorEffects.Entry e : FluidArmorEffects.all()) {
            h.assertTrue(e.effect().equals(FluidArmorEffects.CLEANSE)
                            || BuiltInRegistries.MOB_EFFECT.containsKey(ResourceLocation.fromNamespaceAndPath(e.effect().namespace(), e.effect().path())),
                    "unknown mob effect " + e.effect() + " for " + e.key());
        }
        h.succeed();
    }

    /** The JEI page data: seven FLORA rows with their pairs, one row per table fluid, filled armor holding that fluid. */
    @GameTest(template = T, timeoutTicks = 20)
    public static void jeiRowsWithoutJei(GameTestHelper h) {
        List<FluidArmorRows.Row> rows = FluidArmorRows.all();
        h.assertTrue(rows.stream().filter(FluidArmorRows.Row::flora).count() == 7, "seven FLORA rows");
        h.assertTrue(rows.stream().filter(FluidArmorRows.Row::flora).allMatch(r -> r.pairs().size() == 7), "each FLORA fluid is in 7 pairs");
        FluidArmorRows.Row lava = rows.stream().filter(r -> r.id().toString().equals("minecraft:lava")).findFirst().orElse(null);
        h.assertTrue(lava != null && lava.fluid() == Fluids.LAVA, "lava row");
        h.assertTrue(lava.effects().stream().anyMatch(e -> e.effect().toString().equals("minecraft:fire_resistance")), "lava: fire resistance");
        h.assertTrue(rows.size() >= 7 + 2 + 10 + 40, "rows: " + rows.size());
        List<ItemStack> filled = FluidArmorRows.filled(lava, lava.showMb());
        h.assertTrue(filled.size() == 16, "16 armor pieces");
        Flora.Charge copper = FluidArmorItem.charge(filled.get(0));
        h.assertTrue(copper != null && copper.amount(FfId.parse("minecraft:lava")) == Flora.Tier.COPPER.capacity, "copper holds what fits");
        h.succeed();
    }

    /** Worn lava gives fire resistance, water breathing + dolphin's grace from 4000 mB, quicksilver levels up speed. */
    @GameTest(template = T, timeoutTicks = 20)
    public static void wornFluidsGiveEffects(GameTestHelper h) {
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-fluid-armor-test]"));
        ItemStack chest = new ItemStack(FeatureContent.ARMOR.get("gold_fluid_chestplate").get());
        Flora.Charge c = new Flora.Charge();
        c.fill(FfId.parse("minecraft:lava"), 1000, Flora.Tier.GOLD.capacity, false);
        c.fill(FfId.parse("minecraft:water"), 4000, Flora.Tier.GOLD.capacity, false);
        c.fill(FfId.parse("metallurgy:quicksilver"), 9000, Flora.Tier.GOLD.capacity, false);
        ItemEnergy.editData(chest, t -> t.put(Flora.Charge.DATA_KEY, NbtBridge.toTag(c.save())));
        fp.setItemSlot(EquipmentSlot.CHEST, chest);
        FloraEvents.tickPlayer(fp, new java.util.Random(1), 0);
        h.assertTrue(fp.hasEffect(MobEffects.FIRE_RESISTANCE), "lava: fire resistance");
        h.assertTrue(fp.hasEffect(MobEffects.WATER_BREATHING) && fp.hasEffect(MobEffects.DOLPHINS_GRACE), "water: breathing + dolphin's grace");
        h.assertTrue(fp.hasEffect(MobEffects.MOVEMENT_SPEED) && fp.getEffect(MobEffects.MOVEMENT_SPEED).getAmplifier() == 1,
                "quicksilver 9000 mB: Speed II (1000 + 8000 per level)");
        fp.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        fp.removeAllEffects();
        h.succeed();
    }

    /** The Armor Infuser fills armor with lava (it has an armor effect). */
    @GameTest(template = T, timeoutTicks = 200)
    public static void infuserTakesTableFluids(GameTestHelper h) {
        BlockPos pos = new BlockPos(3, 1, 3);
        h.setBlock(pos, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
        ChassisCore core = ((ChassisBlockEntity) h.getBlockEntity(pos)).core();
        core.energy().receive(16_000, false);
        core.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.ARMOR_INFUSER), null);
        core.lane(0).insert(McItemVariant.of(new ItemStack(FeatureContent.ARMOR.get("copper_fluid_boots").get())), 1, false);
        core.tank(0).fill(McFluidVariant.of(new FluidStack(Fluids.LAVA, 1000)), 1000, false);
        h.succeedWhen(() -> {
            Flora.Charge charge = FluidArmorItem.charge(McItemVariant.toStack(core.lane(0).variant(0), 1));
            h.assertTrue(charge != null && charge.amount(FfId.parse("minecraft:lava")) == 250,
                    "boots hold 250 mB lava; statuses " + core.statuses());
            h.assertTrue(core.tank(0).amount() == 750, "only what fit was drained");
        });
    }
}
