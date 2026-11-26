package dev.fieldframe.neoforge.test;

import com.mojang.authlib.GameProfile;
import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.adapter.McFluidVariant;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.adapter.NbtBridge;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.feature.FeatureContent;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import dev.fieldframe.neoforge.feature.endertech.ExchangerService;
import dev.fieldframe.neoforge.feature.endertech.PadBlockEntity;
import dev.fieldframe.neoforge.feature.endertech.TankControllerEntity;
import dev.fieldframe.neoforge.feature.flora.FloraEvents;
import dev.fieldframe.neoforge.feature.flora.FluidArmorItem;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.UUID;

/** In-world tests of the EnderTech and FLORA feature sets on Forge 1.20.1. */
public final class FeatureGameTests {
    private static final String T = "empty7";

    private static BlockState part(TankStructure.Part p) {
        return FeatureContent.TANK_PARTS.get(p).get().defaultBlockState();
    }

    /** 3x3x3 shell at (2..4, 1..3, 2..4): controller bottom centre, valve top centre, energy input west centre. */
    private static BlockPos buildTank(GameTestHelper h) {
        for (int x = 2; x <= 4; x++) {
            for (int y = 1; y <= 3; y++) {
                for (int z = 2; z <= 4; z++) {
                    int b = (x == 2 || x == 4 ? 1 : 0) + (y == 1 || y == 3 ? 1 : 0) + (z == 2 || z == 4 ? 1 : 0);
                    if (b > 0) {
                        h.setBlock(new BlockPos(x, y, z), part(b >= 2 ? TankStructure.Part.FRAME : TankStructure.Part.GLASS));
                    }
                }
            }
        }
        h.setBlock(new BlockPos(3, 1, 3), part(TankStructure.Part.CONTROLLER));
        h.setBlock(new BlockPos(3, 3, 3), part(TankStructure.Part.VALVE));
        h.setBlock(new BlockPos(2, 2, 3), part(TankStructure.Part.ENERGY_INPUT));
        return new BlockPos(3, 1, 3);
    }

    /** The tank forms, takes at most 20,000 RF per tick, and every mB through the valve costs 1 RF. */
    public static void enderTankFormsAndChargesRfPerMb(GameTestHelper h) {
        BlockPos ctrl = buildTank(h);
        h.runAtTickTime(5, () -> {
            TankControllerEntity c = h.getBlockEntity(ctrl, TankControllerEntity.class);
            c.recheck();
            h.assertTrue(c.core().formed(), "tank formed: " + c.describe());
            h.assertTrue(c.core().fluid().capacity() == 512_000, "1 interior block x 512 buckets, got " + c.core().fluid().capacity());
            var energy = new dev.fieldframe.neoforge.adapter.NeoHandlers.ExternalEnergy(
                    h.getLevel().getCapability(Capabilities.Energy.BLOCK, h.absolutePos(new BlockPos(2, 2, 3)), Direction.WEST));
            h.assertTrue(energy.receive(100_000, false) == 20_000, "energy input capped at 20,000 RF/t");
            var valve = new dev.fieldframe.neoforge.adapter.NeoHandlers.ExternalFluids(
                    h.getLevel().getCapability(Capabilities.Fluid.BLOCK, h.absolutePos(new BlockPos(3, 3, 3)), Direction.UP));
            var water = McFluidVariant.of(new FluidStack(Fluids.WATER, 1000));
            long filled = valve.fill(water, 64_000, false);
            h.assertTrue(filled == 20_000, "fill limited by stored RF, got " + filled);
            h.assertTrue(c.core().energy().stored() == 0, "1 RF per mB spent");
            // Breaking a wall unforms the tank; the valve then accepts nothing, contents stay.
            h.setBlock(new BlockPos(4, 2, 3), Blocks.AIR);
            c.recheck();
            h.assertFalse(c.core().formed(), "unformed after wall removal");
            h.assertTrue(valve.fill(water, 1000, false) == 0, "no access when unformed");
            h.assertTrue(c.core().fluid().amount() == 20_000, "contents kept");
            h.succeed();
        });
    }

    /** A Charge Pad facing up charges an Exchanger lying on it. */
    public static void chargePadChargesItemOnGround(GameTestHelper h) {
        BlockPos pad = new BlockPos(3, 1, 3);
        h.setBlock(pad, FeatureContent.PADS.get(1).get().defaultBlockState()); // charge_pad_resonant (charge pads first, tier order)
        PadBlockEntity be = h.getBlockEntity(pad, PadBlockEntity.class);
        be.buffer().receive(100_000, false);
        ItemStack tool = new ItemStack(FeatureContent.EXCHANGERS.get(EnderTechRules.Tier.REDSTONE).get());
        ItemEntity ie = new ItemEntity(h.getLevel(), h.absolutePos(pad).getX() + 0.5, h.absolutePos(pad).getY() + 1.2, h.absolutePos(pad).getZ() + 0.5, tool);
        ie.setNoPickUpDelay();
        h.getLevel().addFreshEntity(ie);
        h.succeedWhen(() -> {
            long e = ItemEnergy.get(ie.getItem());
            h.assertTrue(e > 0 && e + be.buffer().stored() == 100_000, "conserved charge, item " + e + " pad " + be.buffer().stored());
        });
    }

    /** A Health Pad heals a hurt animal standing on it and pays 192,000 RF per half heart. */
    public static void healthPadHealsAndPays(GameTestHelper h) {
        BlockPos pad = new BlockPos(3, 1, 3);
        h.setBlock(pad, FeatureContent.PADS.get(3).get().defaultBlockState()); // health_pad_redstone
        PadBlockEntity be = h.getBlockEntity(pad, PadBlockEntity.class);
        be.buffer().receive(EnderTechRules.Tier.REDSTONE.capacity, false);
        Cow cow = h.spawn(EntityType.COW, pad.above());
        cow.setHealth(4f);
        cow.setNoAi(true);
        h.succeedWhen(() -> {
            h.assertTrue(cow.getHealth() >= 5f, "healed, health " + cow.getHealth());
            long spent = EnderTechRules.Tier.REDSTONE.capacity - be.buffer().stored();
            h.assertTrue(spent > 0 && spent % EnderTechRules.HEAL_COST_PER_HALF_HEART == 0, "paid per half heart, spent " + spent);
        });
    }

    /** The Exchanger swaps a 3x3 dirt floor to stone ring by ring, using stone from the inventory and returning dirt. */
    public static void exchangerSwapsRingsAndConserves(GameTestHelper h) {
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.DIRT);
            }
        }
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-exchanger-test]"));
        ItemStack tool = new ItemStack(FeatureContent.EXCHANGERS.get(EnderTechRules.Tier.REDSTONE).get());
        ItemEnergy.set(tool, 1_000_000);
        fp.setItemInHand(InteractionHand.MAIN_HAND, tool);
        fp.getInventory().add(new ItemStack(Items.STONE, 9));
        ExchangerService.queue(fp, InteractionHand.MAIN_HAND, h.absolutePos(new BlockPos(3, 1, 3)), WorldDir.UP,
                Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState(), 2);
        h.succeedWhen(() -> {
            for (int x = 2; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    h.assertBlockPresent(Blocks.STONE, new BlockPos(x, 1, z));
                }
            }
            h.assertTrue(fp.getInventory().countItem(Items.DIRT) == 9, "9 dirt returned, have " + fp.getInventory().countItem(Items.DIRT));
            h.assertTrue(fp.getInventory().countItem(Items.STONE) == 0, "9 stone used");
            long expected = 1_000_000 - EnderTechRules.exchangeCost(1, 0.5f) - 8 * EnderTechRules.exchangeCost(2, 0.5f);
            h.assertTrue(ItemEnergy.get(fp.getMainHandItem()) == expected, "RF per swap, have " + ItemEnergy.get(fp.getMainHandItem()));
        });
    }

    /** Mixer makes an effect fluid from Fieldframe materials; the Armor Infuser fills a helmet with it. */
    public static void mixerFluidThenInfuserFillsArmor(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        h.setBlock(c, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
        ChassisBlockEntity be = h.getBlockEntity(c, ChassisBlockEntity.class);
        ChassisCore core = be.core();
        core.energy().receive(16_000, false);
        core.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.MIXER), null);
        core.lane(0).insert(McItemVariant.of(new ItemStack(Items.REDSTONE)), 4, false);
        core.tank(0).fill(McFluidVariant.of(new FluidStack(Fluids.WATER, 1000)), 1000, false);
        core.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.ARMOR_INFUSER), null);
        core.setBinding(LocalFace.BOTTOM, "armor", 2);
        core.setBinding(LocalFace.BOTTOM, "fluid", 1);
        core.lane(2).insert(McItemVariant.of(new ItemStack(FeatureContent.ARMOR.get("copper_fluid_helmet").get())), 1, false);
        h.succeedWhen(() -> {
            ItemStack helmet = McItemVariant.toStack(core.lane(2).variant(0), 1);
            Flora.Charge charge = FluidArmorItem.charge(helmet);
            h.assertTrue(charge != null && charge.amount(Flora.Fluid.REDSTONE) == 250,
                    "helmet holds 250 mB redstone flux; tank B " + core.tank(1).amount() + " statuses " + core.statuses());
            h.assertTrue(core.tank(1).amount() == 250, "500 made, 250 infused, 250 left");
        });
    }

    /** Wearing fluid armor with Blazing Resin + Arcane Mix grants Speed (FLORA Pyrotheum-Mana). */
    public static void floraArmorGrantsEffect(GameTestHelper h) {
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-flora-test]"));
        ItemStack chest = new ItemStack(FeatureContent.ARMOR.get("gold_fluid_chestplate").get());
        Flora.Charge charge = new Flora.Charge();
        charge.fill(Flora.Fluid.BLAZING, 10_000, Flora.Tier.GOLD.capacity, false);
        charge.fill(Flora.Fluid.ARCANE, 10_000, Flora.Tier.GOLD.capacity, false);
        dev.fieldframe.neoforge.feature.ItemEnergy.editData(chest, t -> t.put(Flora.Charge.DATA_KEY, NbtBridge.toTag(charge.save())));
        fp.setItemSlot(EquipmentSlot.CHEST, chest);
        FloraEvents.tickPlayer(fp);
        h.assertTrue(fp.hasEffect(MobEffects.SPEED), "speed applied");
        h.assertTrue(fp.getEffect(MobEffects.SPEED).getAmplifier() == 2, "level log10(100) = 2");
        fp.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        h.succeed();
    }
}
