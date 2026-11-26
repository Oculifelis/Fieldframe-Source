package dev.fieldframe.neoforge.test;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.adapter.McFluidVariant;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.feature.FeatureContent;
import dev.fieldframe.neoforge.feature.MaterialContent;
import dev.fieldframe.neoforge.registry.FfContent;
import dev.fieldframe.core.endertech.EnderTechRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * In-world tests for the Thermal-style machines and materials on a real 26.1.2 server: the game's own brewing,
 * enchanting, item-energy, fishing-loot and experience-orb code paths, and the Metallurgy recipe mirror.
 */
public final class ThermalGameTests {
    private ThermalGameTests() {
    }

    private static final BlockPos C = new BlockPos(3, 2, 3);

    private static ChassisBlockEntity chassis(GameTestHelper h) {
        h.setBlock(C, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
        ChassisBlockEntity be = h.getBlockEntity(C, ChassisBlockEntity.class);
        be.core().energy().receive(16_000, false);
        return be;
    }

    private static void install(ChassisBlockEntity be, LocalFace face, FfId module) {
        ChassisCore.Result r = be.core().install(face, FieldframeRegistry.module(module), null);
        if (!r.ok()) {
            throw new IllegalStateException("install failed: " + r);
        }
    }

    /** Three awkward potions + sugar become three potions of swiftness through the game's brewing recipes. */
    public static void brewerBrewsWithGameRecipes(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.BREWER);
        for (int i = 0; i < 3; i++) {
            be.core().lane(0).insert(McItemVariant.of(PotionContents.createItemStack(Items.POTION, Potions.AWKWARD)), 1, false);
        }
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.SUGAR)), 2, false);
        h.succeedWhen(() -> {
            int swift = 0;
            for (int s = 0; s < be.core().lane(1).slots(); s++) {
                var v = be.core().lane(1).variant(s);
                if (v != null) {
                    PotionContents pc = McItemVariant.toStack(v, 1).get(DataComponents.POTION_CONTENTS);
                    if (pc != null && pc.is(Potions.SWIFTNESS)) {
                        swift += be.core().lane(1).count(s);
                    }
                }
            }
            h.assertTrue(swift == 3, "3 swiftness potions, have " + swift + "; " + be.core().statuses());
            h.assertTrue(be.core().lane(0).countOf(McItemVariant.of(new ItemStack(Items.SUGAR))) == 1, "one sugar used");
        });
    }

    /** An iron sword is enchanted with the game's enchanting rules, paid in liquid experience. */
    public static void enchanterUsesLiquidExperience(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.ENCHANTER);
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.IRON_SWORD)), 1, false);
        var xp = MaterialContent.FLUID_SETS.get("liquid_experience").source().get();
        be.core().tank(0).fill(McFluidVariant.of(new FluidStack(xp, 1)), 4000, false);
        h.succeedWhen(() -> {
            var v = be.core().lane(1).variant(0);
            h.assertTrue(v != null && McItemVariant.toStack(v, 1).isEnchanted(), "enchanted sword out; " + be.core().statuses());
            h.assertTrue(be.core().tank(0).amount() == 4000 - 30 * 100, "30 levels x 100 mB used, left " + be.core().tank(0).amount());
        });
    }

    /** A Redstone Exchanger in lane A receives RF from the chassis buffer through its real energy capability. */
    public static void chargerChargesRealItem(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.ITEM_CHARGER);
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(FeatureContent.EXCHANGERS.get(EnderTechRules.Tier.REDSTONE).get())), 1, false);
        long before = be.core().energy().stored();
        h.succeedWhen(() -> {
            long stored = 0;
            for (int lane = 0; lane < 2; lane++) {
                var v = be.core().lane(lane).variant(0);
                if (v != null) {
                    var handler = net.neoforged.neoforge.transfer.access.ItemAccess.forStack(McItemVariant.toStack(v, 1))
                            .getCapability(net.neoforged.neoforge.capabilities.Capabilities.Energy.ITEM);
                    stored = handler == null ? 0 : handler.getAmountAsLong();
                }
            }
            h.assertTrue(stored >= 1000, "exchanger holds RF, has " + stored + "; " + be.core().statuses());
            h.assertTrue(before - be.core().energy().stored() == stored, "RF moved, not created: buffer -" + (before - be.core().energy().stored()));
        });
    }

    /** With water in front, the Fisher rolls the game's fishing loot table into lane B. */
    public static void fisherCatchesFromRealLootTable(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        BlockPos front = C.north();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                h.setBlock(front.offset(dx, dy, 0), Blocks.WATER);
            }
        }
        install(be, LocalFace.FRONT, BuiltinModules.FISHER);
        h.succeedWhen(() -> h.assertTrue(be.core().lane(1).totalCount() >= 1, "caught something; " + be.core().statuses()));
    }

    /** Experience orbs in front become 20 mB of liquid experience per point. */
    public static void xpCollectorAbsorbsOrbs(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.FRONT, BuiltinModules.XP_COLLECTOR);
        var p = net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(C.north()));
        h.getLevel().addFreshEntity(new ExperienceOrb(h.getLevel(), p.x, p.y, p.z, 7));
        h.succeedWhen(() -> {
            h.assertTrue(be.core().tank(0).amount() == 7 * 20, "140 mB, have " + be.core().tank(0).amount() + "; " + be.core().statuses());
            h.assertTrue(h.getLevel().getEntitiesOfClass(ExperienceOrb.class, new net.minecraft.world.phys.AABB(h.absolutePos(C)).inflate(4)).isEmpty(), "orb absorbed");
        });
    }

    private static net.minecraft.world.item.Item metallurgy(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("metallurgy", path));
    }

    private static ChassisBlockEntity chassisAt(GameTestHelper h, BlockPos at) {
        h.setBlock(at, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
        ChassisBlockEntity be = h.getBlockEntity(at, ChassisBlockEntity.class);
        be.core().energy().receive(16_000, false);
        return be;
    }

    /**
     * Metallurgy integration: the Alloy Smelter runs Metallurgy's alloyer recipe (3 copper + 1 tin = 4 bronze) and the
     * Crusher runs Metallurgy's crusher recipe (tin ingot to tin dust), both from the mirrored datapack recipes.
     */
    public static void metallurgyAlloysAndDusts(GameTestHelper h) {
        h.assertTrue(metallurgy("tin_ingot") != Items.AIR, "Metallurgy is loaded");
        ChassisBlockEntity smelter = chassisAt(h, new BlockPos(1, 2, 3));
        install(smelter, LocalFace.TOP, BuiltinModules.ALLOY_SMELTER);
        smelter.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.COPPER_INGOT)), 3, false);
        smelter.core().lane(0).insert(McItemVariant.of(new ItemStack(metallurgy("tin_ingot"))), 1, false);
        ChassisBlockEntity crusher = chassisAt(h, new BlockPos(5, 2, 3));
        install(crusher, LocalFace.TOP, BuiltinModules.CRUSHER);
        crusher.core().lane(0).insert(McItemVariant.of(new ItemStack(metallurgy("tin_ingot"))), 1, false);
        h.succeedWhen(() -> {
            h.assertTrue(smelter.core().lane(1).countOf(McItemVariant.of(new ItemStack(metallurgy("bronze_ingot")))) == 4,
                    "4 Metallurgy bronze; " + smelter.core().statuses());
            h.assertTrue(smelter.core().lane(0).totalCount() == 0, "inputs used");
            h.assertTrue(crusher.core().lane(1).countOf(McItemVariant.of(new ItemStack(metallurgy("tin_dust")))) >= 1,
                    "Metallurgy tin dust; " + crusher.core().statuses());
        });
    }

    private static net.minecraft.world.item.Item ff(String path) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("fieldframe", path));
    }

    /**
     * Metallurgy molten metals: the Magma Crucible melts a Metallurgy tin ingot into 144 mB of Metallurgy's molten tin,
     * and the Blast Chiller (with an ingot cast) casts 144 mB of it back into an ingot.
     */
    public static void metallurgyMoltenMetals(GameTestHelper h) {
        net.minecraft.world.level.material.Fluid tin = BuiltInRegistries.FLUID.getValue(Identifier.fromNamespaceAndPath("metallurgy", "tin"));
        h.assertTrue(tin != net.minecraft.world.level.material.Fluids.EMPTY, "Metallurgy molten tin exists");
        var molten = McFluidVariant.of(new FluidStack(tin, 1));
        ChassisBlockEntity crucible = chassisAt(h, new BlockPos(1, 2, 3));
        install(crucible, LocalFace.TOP, BuiltinModules.MAGMA_CRUCIBLE);
        crucible.core().lane(0).insert(McItemVariant.of(new ItemStack(metallurgy("tin_ingot"))), 1, false);
        ChassisBlockEntity chiller = chassisAt(h, new BlockPos(5, 2, 3));
        install(chiller, LocalFace.TOP, BuiltinModules.BLAST_CHILLER);
        chiller.core().lane(0).insert(McItemVariant.of(new ItemStack(ff("ingot_cast"))), 1, false);
        chiller.core().tank(0).fill(molten, 144, false);
        h.succeedWhen(() -> {
            h.assertTrue(crucible.core().tank(1).amountOf(molten) == 144, "144 mB molten tin, have "
                    + crucible.core().tank(1).amount() + "; " + crucible.core().statuses());
            h.assertTrue(crucible.core().lane(0).totalCount() == 0, "ingot melted");
            h.assertTrue(chiller.core().lane(1).countOf(McItemVariant.of(new ItemStack(metallurgy("tin_ingot")))) == 1,
                    "tin ingot cast; " + chiller.core().statuses());
            h.assertTrue(chiller.core().tank(0).amount() == 0, "fluid used");
            h.assertTrue(chiller.core().lane(0).countOf(McItemVariant.of(new ItemStack(ff("ingot_cast")))) == 1, "ingot cast kept");
        });
    }

    /** Plates and gears are Fieldframe's: the Press makes them from any mod's ingots with a die, which it keeps. */
    public static void pressMakesPlatesAndGearsWithDies(GameTestHelper h) {
        ChassisBlockEntity plates = chassisAt(h, new BlockPos(1, 2, 3));
        install(plates, LocalFace.TOP, BuiltinModules.PRESS);
        plates.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.IRON_INGOT)), 2, false);
        plates.core().lane(0).insert(McItemVariant.of(new ItemStack(ff("plate_die"))), 1, false);
        ChassisBlockEntity gears = chassisAt(h, new BlockPos(5, 2, 3));
        install(gears, LocalFace.TOP, BuiltinModules.PRESS);
        gears.core().lane(0).insert(McItemVariant.of(new ItemStack(metallurgy("tin_ingot"))), 4, false);
        gears.core().lane(0).insert(McItemVariant.of(new ItemStack(ff("gear_die"))), 1, false);
        h.succeedWhen(() -> {
            h.assertTrue(plates.core().lane(1).countOf(McItemVariant.of(new ItemStack(ff("iron_plate")))) == 2, "2 iron plates; " + plates.core().statuses());
            h.assertTrue(plates.core().lane(0).countOf(McItemVariant.of(new ItemStack(ff("plate_die")))) == 1, "plate die kept");
            h.assertTrue(gears.core().lane(1).countOf(McItemVariant.of(new ItemStack(ff("tin_gear")))) == 1, "a tin gear; " + gears.core().statuses());
            h.assertTrue(gears.core().lane(0).countOf(McItemVariant.of(new ItemStack(ff("gear_die")))) == 1, "gear die kept");
        });
    }

    /**
     * Metallurgy's chamber recipe, run by the Aura Chamber: three adamantine blocks give a player standing nearby
     * Health Boost.
     */
    public static void auraChamberGivesMetallurgyEffect(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.AURA_CHAMBER);
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(metallurgy("adamantine_block"))), 3, false);
        // A plain mock player in the level (a mock server player would make other mods try to network with it).
        net.minecraft.world.entity.player.Player p = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var at = h.absolutePos(C.east(2));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        h.getLevel().addFreshEntity(p);
        h.succeedWhen(() -> {
            h.assertTrue(be.core().lane(0).totalCount() == 0, "a batch of three used; " + be.core().statuses());
            h.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.HEALTH_BOOST), "player has Health Boost");
            p.discard();
        });
    }

    /** Casting picks the shape by cast: 16 mB + nugget cast = nugget, 1,296 mB + block cast = block; alloys melt too. */
    public static void castingNuggetsBlocksAndAlloys(GameTestHelper h) {
        net.minecraft.world.level.material.Fluid tin = BuiltInRegistries.FLUID.getValue(Identifier.fromNamespaceAndPath("metallurgy", "tin"));
        var molten = McFluidVariant.of(new FluidStack(tin, 1));
        ChassisBlockEntity nuggets = chassisAt(h, new BlockPos(1, 2, 1));
        install(nuggets, LocalFace.TOP, BuiltinModules.BLAST_CHILLER);
        nuggets.core().lane(0).insert(McItemVariant.of(new ItemStack(ff("nugget_cast"))), 1, false);
        nuggets.core().tank(0).fill(molten, 16, false);
        ChassisBlockEntity blocks = chassisAt(h, new BlockPos(5, 2, 1));
        install(blocks, LocalFace.TOP, BuiltinModules.BLAST_CHILLER);
        blocks.core().lane(0).insert(McItemVariant.of(new ItemStack(ff("block_cast"))), 1, false);
        blocks.core().tank(0).fill(molten, 1296, false);
        ChassisBlockEntity alloy = chassisAt(h, new BlockPos(3, 2, 5));
        install(alloy, LocalFace.TOP, BuiltinModules.MAGMA_CRUCIBLE);
        alloy.core().lane(0).insert(McItemVariant.of(new ItemStack(ff("signal_alloy_ingot"))), 1, false);
        var signal = McFluidVariant.of(new FluidStack(BuiltInRegistries.FLUID.getValue(Identifier.fromNamespaceAndPath("fieldframe", "molten_signal_alloy")), 1));
        h.succeedWhen(() -> {
            h.assertTrue(nuggets.core().lane(1).countOf(McItemVariant.of(new ItemStack(metallurgy("tin_nugget")))) == 1, "tin nugget; " + nuggets.core().statuses());
            h.assertTrue(blocks.core().lane(1).countOf(McItemVariant.of(new ItemStack(metallurgy("tin_block")))) == 1, "tin block; " + blocks.core().statuses());
            h.assertTrue(blocks.core().lane(0).countOf(McItemVariant.of(new ItemStack(ff("block_cast")))) == 1, "block cast kept");
            h.assertTrue(alloy.core().tank(1).amountOf(signal) == 144, "144 mB molten signal alloy; " + alloy.core().statuses());
        });
    }

    /** A Hardened Upgrade Kit raises a chassis one tier (a second one is refused) and comes back when it is broken. */
    public static void tierKitUpgradesChassisAndIsReturned(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        var kit = FfContent.tierKit(dev.fieldframe.api.id.FfId.ff("hardened"));
        h.assertTrue(kit != null, "kit item registered");
        net.minecraft.world.entity.player.Player p = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(kit, 2));
        BlockPos abs = h.absolutePos(C);
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(abs), Direction.UP, abs, false);
        h.getBlockState(C).useItemOn(p.getMainHandItem(), h.getLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        h.assertTrue(be.core().tier().id().path().equals("hardened"), "now hardened, is " + be.core().tier().id());
        h.assertTrue(p.getMainHandItem().getCount() == 1, "one kit used");
        h.assertTrue(be.core().serviceBays() == 4, "a fourth service bay");
        h.getBlockState(C).useItemOn(p.getMainHandItem(), h.getLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        h.assertTrue(be.core().tier().id().path().equals("hardened") && p.getMainHandItem().getCount() == 1, "a second hardened kit is refused");
        h.destroyBlock(C);
        long kits = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(abs).inflate(3)).stream().filter(e -> e.getItem().is(kit)).count();
        h.assertTrue(kits == 1, "the kit is given back, found " + kits);
        h.succeed();
    }

    /** A pack's recipe filter removes one Metallurgy crusher recipe from the Crusher; restoring the loaded filters brings it back. */
    public static void recipeFilterRemovesMetallurgyRecipe(GameTestHelper h) {
        var before = dev.fieldframe.core.material.RecipeFilters.state();
        dev.fieldframe.core.material.RecipeFilters.load(java.util.Map.of("test:filter",
                "{\"remove\": [\"fieldframe:crushing/metallurgy/zinc_from_ingot\"]}"), java.util.List.of());
        dev.fieldframe.neoforge.recipe.RecipeBridge.invalidate();
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.CRUSHER);
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(metallurgy("zinc_ingot"))), 1, false);
        boolean[] checked = new boolean[1];
        h.runAfterDelay(80, () -> {
            boolean untouched = be.core().lane(0).totalCount() == 1 && be.core().lane(1).totalCount() == 0;
            // Put back the filters that were loaded (KubeJS scripts' included), not an empty table.
            dev.fieldframe.core.material.RecipeFilters.restore(before);
            dev.fieldframe.neoforge.recipe.RecipeBridge.invalidate();
            if (!untouched) {
                h.fail("the removed recipe ran; " + be.core().statuses());
            }
            checked[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(checked[0], "filter phase checked");
            h.assertTrue(be.core().lane(1).countOf(McItemVariant.of(new ItemStack(metallurgy("zinc_dust")))) >= 1,
                    "recipe back after the filter is cleared; " + be.core().statuses());
        });
    }
}
