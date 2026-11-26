package dev.fieldframe.forge.test;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-world tests for the world-interaction family on a real Forge server: real loot and tool paths, the
 * protection event hook, fluids, item entities, crafting and ownership.
 */
@GameTestHolder(Fieldframe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldModuleGameTests {
    private static final String T = "empty7";
    /** Absolute positions a stand-in "claim mod" protects; cancelled through the normal Forge break event. */
    private static final Set<BlockPos> PROTECTED = ConcurrentHashMap.newKeySet();

    static {
        MinecraftForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (PROTECTED.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
    }

    private static ChassisBlockEntity chassis(GameTestHelper h, BlockPos rel, Direction front) {
        BlockState st = FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, front);
        h.setBlock(rel, st);
        ChassisBlockEntity be = (ChassisBlockEntity) h.getBlockEntity(rel);
        be.core().energy().receive(16_000, false);
        return be;
    }

    private static void install(ChassisBlockEntity be, LocalFace face, FfId module, FfData cfg) {
        ChassisCore.Result r = be.core().install(face, FieldframeRegistry.module(module), cfg);
        if (!r.ok()) {
            throw new IllegalStateException("install failed: " + r);
        }
    }

    private static FfData cfg(FfId module, Object... kv) {
        FfData d = FieldframeRegistry.module(module).defaultConfig();
        for (int i = 0; i < kv.length; i += 2) {
            d.putAny((String) kv[i], kv[i + 1]);
        }
        return d;
    }

    private static long count(ChassisBlockEntity be, int lane, net.minecraft.world.item.Item item) {
        return be.core().lane(lane).countOf(McItemVariant.of(new ItemStack(item)));
    }

    private static String status(ChassisBlockEntity be, LocalFace f) {
        var s = be.core().statuses().get(f);
        return s == null ? "-" : s.code();
    }

    /** Breaker with a pickaxe: stone becomes cobblestone in lane B through real loot; the tool loses durability. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void breakerUsesRealLootAndTool(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        h.setBlock(c.north(), Blocks.STONE);
        install(be, LocalFace.FRONT, BuiltinModules.BLOCK_BREAKER, null);
        be.core().lane(2).insert(McItemVariant.of(new ItemStack(Items.IRON_PICKAXE)), 1, false);
        h.succeedWhen(() -> {
            h.assertBlockPresent(Blocks.AIR, c.north());
            h.assertTrue(count(be, 1, Items.COBBLESTONE) == 1, "cobblestone in lane B; " + be.core().statuses());
            var tool = be.core().lane(2).variant(0);
            h.assertTrue(tool != null && McItemVariant.toStack(tool, 1).getDamageValue() == 1, "pickaxe took 1 damage");
        });
    }

    /** Without a fitting tool, stone is left in place (never broken without its drops). */
    @GameTest(template = T, timeoutTicks = 100)
    public static void breakerRefusesWrongTool(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        h.setBlock(c.north(), Blocks.STONE);
        install(be, LocalFace.FRONT, BuiltinModules.BLOCK_BREAKER, null);
        be.core().lane(2).insert(McItemVariant.of(new ItemStack(Items.IRON_SHOVEL)), 1, false);
        h.runAtTickTime(40, () -> {
            h.assertBlockPresent(Blocks.STONE, c.north());
            h.assertTrue(status(be, LocalFace.FRONT).equals("WRONG_TOOL"), "status " + status(be, LocalFace.FRONT));
            h.succeed();
        });
    }

    /** A cancelled break event (any protection mod) stops the breaker and reports PROTECTED. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void breakerHonoursProtectionEvent(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        h.setBlock(c.north(), Blocks.DIRT);
        PROTECTED.add(h.absolutePos(c.north()));
        install(be, LocalFace.FRONT, BuiltinModules.BLOCK_BREAKER, null);
        h.runAtTickTime(40, () -> {
            PROTECTED.remove(h.absolutePos(c.north()));
            h.assertBlockPresent(Blocks.DIRT, c.north());
            h.assertTrue(status(be, LocalFace.FRONT).equals("PROTECTED"), "status " + status(be, LocalFace.FRONT));
            h.succeed();
        });
    }

    /** Excavator digs a 1x1x2 column below with water and RF, then stops at bedrock with a fault. */
    @GameTest(template = T, timeoutTicks = 300)
    public static void excavatorDigsAndStopsAtBedrock(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 4, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        h.setBlock(c.below(), Blocks.DIRT);
        h.setBlock(c.below(2), Blocks.GRAVEL);
        h.setBlock(c.below(3), Blocks.BEDROCK);
        install(be, LocalFace.BOTTOM, BuiltinModules.EXCAVATOR, cfg(BuiltinModules.EXCAVATOR, "radius", 0, "depth", 4));
        be.core().lane(2).insert(McItemVariant.of(new ItemStack(Items.IRON_SHOVEL)), 1, false);
        be.core().tank(0).fill(McFluidVariant.of(new FluidStack(Fluids.WATER, 1000)), 1000, false);
        h.succeedWhen(() -> {
            h.assertBlockPresent(Blocks.AIR, c.below());
            h.assertBlockPresent(Blocks.AIR, c.below(2));
            h.assertBlockPresent(Blocks.BEDROCK, c.below(3));
            long gravelOrFlint = count(be, 1, Items.GRAVEL) + count(be, 1, Items.FLINT);
            h.assertTrue(count(be, 1, Items.DIRT) == 1 && gravelOrFlint == 1, "drops in lane B: " + be.core().lane(1).totalCount());
            h.assertTrue(be.core().tank(0).amount() == 800, "200 mB working fluid used, left " + be.core().tank(0).amount());
            h.assertTrue(status(be, LocalFace.BOTTOM).equals("UNBREAKABLE"), "status " + status(be, LocalFace.BOTTOM));
        });
    }

    /** Placer puts one cobblestone in front and keeps the rest. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void placerPlacesOneBlock(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        install(be, LocalFace.FRONT, BuiltinModules.BLOCK_PLACER, null);
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.COBBLESTONE)), 5, false);
        h.succeedWhen(() -> {
            h.assertBlockPresent(Blocks.COBBLESTONE, c.north());
            h.assertTrue(count(be, 0, Items.COBBLESTONE) == 4, "exactly one consumed");
        });
    }

    /** Crop Tender harvests ripe wheat from loot and replants a seed. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void cropTenderHarvestsAndReplants(GameTestHelper h) {
        // Tender at the edge of the field, crop at chassis height in front (vanilla crops need light >= 8).
        BlockPos c = new BlockPos(3, 2, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        h.setBlock(c.north().east(), Blocks.GLOWSTONE); // test area has no sky light; light first
        h.setBlock(c.north().west(), Blocks.GLOWSTONE);
        h.setBlock(c.north().below(), Blocks.FARMLAND);
        h.setBlock(c.north(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        install(be, LocalFace.FRONT, BuiltinModules.CROP_TENDER, cfg(BuiltinModules.CROP_TENDER, "radius", 0));
        // Ripe wheat drops 0-3 seeds by loot roll; one seed in the lane makes replanting deterministic.
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.WHEAT_SEEDS)), 1, false);
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 0, Items.WHEAT) == 1, "wheat harvested; " + be.core().statuses() + " trace " + be.core().traceLines() + " block " + h.getBlockState(c.north()) + " lane " + be.core().lane(0).totalCount());
            BlockState st = h.getBlockState(c.north());
            h.assertTrue(st.is(Blocks.WHEAT) && st.getValue(CropBlock.AGE) == 0, "replanted, found " + st + " "
                    + be.core().statuses() + " seeds " + count(be, 0, Items.WHEAT_SEEDS) + " trace " + be.core().traceLines()
                    + " light " + h.getLevel().getRawBrightness(h.absolutePos(c.north()), 0) + " sky " + h.getLevel().canSeeSky(h.absolutePos(c.north()))
                    + " soil " + h.getBlockState(c.north().below()) + " time " + h.getLevel().getDayTime());
        });
    }

    /** Pump takes a water source into the reservoir; Interaction Head fills a bucket from another. */
    @GameTest(template = T, timeoutTicks = 150)
    public static void pumpAndBucketHead(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 3, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        h.setBlock(c.below(), Blocks.WATER);
        h.setBlock(c.north(), Blocks.WATER);
        install(be, LocalFace.BOTTOM, BuiltinModules.FLUID_PUMP, cfg(BuiltinModules.FLUID_PUMP, "radius", 0, "depth", 1));
        install(be, LocalFace.FRONT, BuiltinModules.INTERACTION_HEAD, null);
        be.core().lane(2).insert(McItemVariant.of(new ItemStack(Items.BUCKET)), 1, false);
        // With the example KubeJS pack loaded, its worldAction rule forbids Interaction Heads: the head must report
        // PROTECTED and leave the water alone (the pump is unaffected).
        boolean packRule = net.minecraftforge.fml.ModList.get().isLoaded("kubejs");
        h.succeedWhen(() -> {
            h.assertTrue(be.core().tank(0).amount() == 1000, "pumped 1000 mB, have " + be.core().tank(0).amount());
            if (packRule) {
                h.assertTrue(be.core().statuses().get(LocalFace.FRONT).code().equals("PROTECTED"), "pack rule vetoed the head; " + be.core().statuses());
                h.assertTrue(count(be, 1, Items.WATER_BUCKET) == 0, "bucket not filled under the pack rule");
                h.assertBlockPresent(Blocks.WATER, c.north());
            } else {
                h.assertTrue(count(be, 1, Items.WATER_BUCKET) == 1, "bucket filled; " + be.core().statuses());
                h.assertBlockPresent(Blocks.AIR, c.north());
            }
        });
    }

    /** Ejector drops items; a Drop Collector on another chassis picks them up again. Nothing is lost. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void ejectorAndCollectorConserve(GameTestHelper h) {
        BlockPos a = new BlockPos(1, 1, 3);
        BlockPos b = new BlockPos(5, 1, 3);
        ChassisBlockEntity src = chassis(h, a, Direction.EAST);
        ChassisBlockEntity dst = chassis(h, b, Direction.WEST);
        install(src, LocalFace.FRONT, BuiltinModules.ITEM_EJECTOR, cfg(BuiltinModules.ITEM_EJECTOR, "quantity", 4, "delay", 5));
        src.core().setBinding(LocalFace.FRONT, "lane", 0);
        src.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.APPLE)), 8, false);
        install(dst, LocalFace.FRONT, BuiltinModules.DROP_COLLECTOR, cfg(BuiltinModules.DROP_COLLECTOR, "range", 3));
        h.succeedWhen(() -> {
            long collected = count(dst, 0, Items.APPLE);
            int ground = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(8)).stream()
                    .mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(collected == 8, "collected " + collected + ", on ground " + ground + ", left " + count(src, 0, Items.APPLE));
        });
    }

    /** Recipe Assembler crafts planks from a log with the real crafting recipes. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void assemblerCraftsPlanks(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        install(be, LocalFace.TOP, BuiltinModules.RECIPE_ASSEMBLER, cfg(BuiltinModules.RECIPE_ASSEMBLER, "pattern", "oak_log"));
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.OAK_LOG)), 2, false);
        h.succeedWhen(() -> h.assertTrue(count(be, 1, Items.OAK_PLANKS) == 8, "8 planks, have " + count(be, 1, Items.OAK_PLANKS)));
    }

    /** An empty hand on a chassis (tank panel face or a bare face) moves nothing and does not crash. */
    @GameTest(template = T, timeoutTicks = 40)
    public static void emptyHandClickOnChassis(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        install(be, LocalFace.FRONT, BuiltinModules.TANK_PANEL, cfg(BuiltinModules.TANK_PANEL, "a", true, "b", true, "c", true));
        Player p = h.makeMockSurvivalPlayer();
        BlockPos abs = h.absolutePos(c);
        h.getBlockState(c).use(h.getLevel(), p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs), Direction.NORTH, abs, false));
        h.getBlockState(c).use(h.getLevel(), p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
        h.assertTrue(be.core().tank(0).amount() == 0, "nothing moved");
        h.succeed();
    }

    /** A player fills a permitted reservoir with a water bucket through the Tank Access Panel. */
    @GameTest(template = T, timeoutTicks = 40)
    public static void tankPanelBucket(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        install(be, LocalFace.FRONT, BuiltinModules.TANK_PANEL, cfg(BuiltinModules.TANK_PANEL, "a", false, "b", true, "c", false));
        Player p = h.makeMockPlayer();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        BlockPos abs = h.absolutePos(c);
        h.getBlockState(c).use(h.getLevel(), p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.NORTH, abs, false));
        h.assertTrue(be.core().tank(1).amount() == 1000, "reservoir B got 1000 mB, has " + be.core().tank(1).amount());
        h.assertTrue(be.core().tank(0).amount() == 0, "reservoir A untouched");
        h.assertTrue(p.getMainHandItem().is(Items.BUCKET), "empty bucket returned");
        h.succeed();
    }

    /** Only the owner (or an operator) may install modules on an owned chassis. */
    @GameTest(template = T, timeoutTicks = 40)
    public static void ownerGatesEditing(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        be.core().setOwner(UUID.randomUUID().toString(), "Someone");
        Player p = h.makeMockPlayer();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FfContent.moduleItem(BuiltinModules.BLOCK_BREAKER)));
        BlockPos abs = h.absolutePos(c);
        h.getBlockState(c).use(h.getLevel(), p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.NORTH, abs, false));
        h.assertTrue(be.core().module(LocalFace.FRONT) == null, "non-owner must not install");
        be.core().setOwner(p.getUUID().toString(), "Mock");
        h.getBlockState(c).use(h.getLevel(), p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.NORTH, abs, false));
        h.assertTrue(be.core().module(LocalFace.FRONT) != null, "owner may install");
        h.succeed();
    }
}
