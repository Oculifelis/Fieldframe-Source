package dev.fieldframe.neoforge.test;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-world tests on a real server with the real Forge adapters: chest/hopper item capabilities, the energy
 * capability between two chassis, redstone, persistence and destruction accounting. Run with
 * {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(Fieldframe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FieldframeGameTests {
    private static final String T = "empty7";

    private static ChassisBlockEntity chassis(GameTestHelper h, BlockPos rel, Direction front) {
        BlockState st = FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, front);
        h.setBlock(rel, st);
        return (ChassisBlockEntity) h.getBlockEntity(rel);
    }

    private static void install(ChassisBlockEntity be, LocalFace face, FfId module, FfData cfg) {
        ChassisCore.Result r = be.core().install(face, FieldframeRegistry.module(module), cfg);
        if (!r.ok()) {
            throw new IllegalStateException("install failed: " + r);
        }
    }

    private static FfData cfg(FfId module, String key, String value) {
        return FieldframeRegistry.module(module).defaultConfig().putString(key, value);
    }

    /** Scenario 1+2: chest -> import port -> heater (engine powered) -> export port -> chest. No pipes. */
    @GameTest(template = T, timeoutTicks = 600)
    public static void adjacentChestAutomation(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 2, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        // North-facing: LEFT = west, RIGHT = east.
        h.setBlock(c.west(), Blocks.CHEST);
        h.setBlock(c.east(), Blocks.CHEST);
        ChestBlockEntity in = (ChestBlockEntity) h.getBlockEntity(c.west());
        in.setItem(0, new ItemStack(Items.RAW_IRON, 3));
        install(be, LocalFace.LEFT, BuiltinModules.ITEM_PORT, cfg(BuiltinModules.ITEM_PORT, "mode", "import"));
        install(be, LocalFace.RIGHT, BuiltinModules.ITEM_PORT, cfg(BuiltinModules.ITEM_PORT, "mode", "export"));
        be.core().setBinding(LocalFace.RIGHT, "lane", 1);
        install(be, LocalFace.TOP, BuiltinModules.HEATER, null);
        install(be, LocalFace.BOTTOM, BuiltinModules.FUEL_ENGINE, null);
        be.core().setBinding(LocalFace.BOTTOM, "fuel", 2);
        be.core().lane(2).insert(McItemVariant.of(new ItemStack(Items.COAL)), 1, false);
        h.succeedWhen(() -> {
            ChestBlockEntity out = (ChestBlockEntity) h.getBlockEntity(c.east());
            int ingots = 0;
            for (int i = 0; i < out.getContainerSize(); i++) {
                if (out.getItem(i).is(Items.IRON_INGOT)) {
                    ingots += out.getItem(i).getCount();
                }
            }
            h.assertTrue(ingots == 3, "expected 3 ingots in output chest, have " + ingots + "; statuses " + be.core().statuses()
                    + " A=" + be.core().lane(0).totalCount() + " B=" + be.core().lane(1).totalCount() + " C=" + be.core().lane(2).totalCount()
                    + " rf=" + be.core().energy().stored() + " trace=" + be.core().traceLines());
        });
    }

    /** A vanilla hopper can feed a passive Item Port; an empty face and a null side expose nothing. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void hopperFeedsPassivePortOnly(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        install(be, LocalFace.TOP, BuiltinModules.ITEM_PORT, null);
        h.assertTrue(h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(c), null) == null, "null side must expose nothing");
        h.assertTrue(h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(c), Direction.WEST) == null, "empty face must expose nothing");
        h.assertTrue(h.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, h.absolutePos(c), Direction.UP) == null, "item port is not an energy port");
        h.setBlock(c.above(), Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.FACING, Direction.DOWN));
        var hopper = (net.minecraft.world.level.block.entity.HopperBlockEntity) h.getBlockEntity(c.above());
        hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        h.succeedWhen(() -> h.assertTrue(be.core().lane(0).totalCount() == 5, "lane A should hold 5, has " + be.core().lane(0).totalCount()));
    }

    /** RF moves between two chassis through the platform energy capability: push terminal -> receive terminal. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void energyBetweenChassisViaCapability(GameTestHelper h) {
        BlockPos a = new BlockPos(2, 1, 3);
        BlockPos b = new BlockPos(3, 1, 3);
        ChassisBlockEntity src = chassis(h, a, Direction.NORTH);
        ChassisBlockEntity dst = chassis(h, b, Direction.NORTH);
        install(src, LocalFace.RIGHT, BuiltinModules.POWER_TERMINAL, cfg(BuiltinModules.POWER_TERMINAL, "mode", "push"));
        install(dst, LocalFace.LEFT, BuiltinModules.POWER_TERMINAL, null);
        src.core().energy().receive(10_000, false);
        h.succeedWhen(() -> {
            long moved = dst.core().energy().stored();
            h.assertTrue(moved > 0 && moved + src.core().energy().stored() == 10_000, "conserved transfer, moved " + moved);
            h.assertTrue(dst.core().energy().stored() >= 2048, "several ticks of transfer");
        });
    }

    /** Lever -> Signal Terminal (input) -> channel -> Signal Terminal (output) -> world redstone. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void redstoneRoundTrip(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        install(be, LocalFace.LEFT, BuiltinModules.SIGNAL_TERMINAL, null);
        install(be, LocalFace.RIGHT, BuiltinModules.SIGNAL_TERMINAL, cfg(BuiltinModules.SIGNAL_TERMINAL, "mode", "output"));
        h.setBlock(c.west(), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        h.pullLever(c.west());
        h.succeedWhen(() -> h.assertTrue(be.core().redstoneOutput(WorldDir.EAST) == 15
                && h.getLevel().getSignal(h.absolutePos(c), Direction.WEST) == 15, "east face should emit 15"));
    }

    /** Save -> load into a fresh block entity keeps lanes, modules and an in-flight operation exactly once. */
    @GameTest(template = T, timeoutTicks = 400)
    public static void persistenceRoundTrip(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.EAST);
        install(be, LocalFace.TOP, BuiltinModules.HEATER, null);
        be.core().energy().receive(16_000, false);
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.RAW_GOLD)), 1, false);
        h.runAfterDelay(20, () -> {
            var tag = be.saveWithFullMetadata(h.getLevel().registryAccess());
            h.setBlock(c, Blocks.AIR);
            h.setBlock(c, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.EAST));
            ChassisBlockEntity fresh = (ChassisBlockEntity) h.getBlockEntity(c);
            fresh.loadWithComponents(tag, h.getLevel().registryAccess());
            h.assertTrue(fresh.core().module(LocalFace.TOP) != null, "module restored");
            h.assertTrue(fresh.core().lane(0).totalCount() == 0, "input stays reserved");
            h.succeedWhen(() -> {
                int gold = (int) fresh.core().lane(1).countOf(McItemVariant.of(new ItemStack(Items.GOLD_INGOT)));
                h.assertTrue(gold == 1, "exactly one ingot, have " + gold);
            });
        });
    }

    /** Breaking releases modules, upgrades and lane items exactly once. */
    @GameTest(template = T, timeoutTicks = 60)
    public static void breakingDropsContentsOnce(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c, Direction.NORTH);
        install(be, LocalFace.TOP, BuiltinModules.HEATER, null);
        be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.DIAMOND)), 7, false);
        h.getLevel().destroyBlock(h.absolutePos(c), true);
        h.runAfterDelay(5, () -> {
            AABB box = new AABB(h.absolutePos(c)).inflate(3);
            int diamonds = 0;
            int heaters = 0;
            for (ItemEntity e : h.getLevel().getEntitiesOfClass(ItemEntity.class, box)) {
                if (e.getItem().is(Items.DIAMOND)) {
                    diamonds += e.getItem().getCount();
                }
                if (e.getItem().getItem() == FfContent.moduleItem(BuiltinModules.HEATER)) {
                    heaters += e.getItem().getCount();
                }
            }
            h.assertTrue(diamonds == 7, "7 diamonds dropped once, got " + diamonds);
            h.assertTrue(heaters == 1, "heater dropped once, got " + heaters);
            h.succeed();
        });
    }

    /** Blueprint: capture a 2-chassis machine (with contents), rebuild it elsewhere in stages; no contents copied. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void blueprintCaptureAndStagedBuild(GameTestHelper h) {
        BlockPos a = new BlockPos(1, 1, 1);
        BlockPos b = new BlockPos(2, 1, 1);
        ChassisBlockEntity ca = chassis(h, a, Direction.NORTH);
        ChassisBlockEntity cb = chassis(h, b, Direction.NORTH);
        install(ca, LocalFace.TOP, BuiltinModules.HEATER, null);
        ca.core().setBinding(LocalFace.TOP, "enable", 6);
        install(cb, LocalFace.LEFT, BuiltinModules.ITEM_PORT, cfg(BuiltinModules.ITEM_PORT, "mode", "import"));
        ca.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.DIAMOND)), 5, false);
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ItemStack sheet = new ItemStack(FfContent.BLUEPRINT.get());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, sheet);
        player.setShiftKeyDown(true);
        use(h, player, a, Direction.UP);
        h.assertTrue(dev.fieldframe.neoforge.item.BlueprintItem.read(sheet) != null, "captured");
        h.assertTrue(dev.fieldframe.neoforge.item.BlueprintItem.read(sheet).chassis().size() == 2, "both connected chassis captured");
        player.setShiftKeyDown(false);
        // Survival materials: exactly the bill of materials.
        player.getInventory().add(new ItemStack(FfContent.CHASSIS_ITEM.get(), 2));
        player.getInventory().add(new ItemStack(FfContent.moduleItem(BuiltinModules.HEATER)));
        player.getInventory().add(new ItemStack(FfContent.moduleItem(BuiltinModules.ITEM_PORT)));
        // Build on the floor at z=4: stage 1 then stage 2.
        h.setBlock(new BlockPos(1, 0, 4), net.minecraft.world.level.block.Blocks.STONE);
        use(h, player, new BlockPos(1, 0, 4), Direction.UP);
        use(h, player, new BlockPos(1, 0, 4), Direction.UP);
        h.runAfterDelay(2, () -> {
            BlockPos na = new BlockPos(1, 1, 4);
            // Player faces north by default (yaw 0 = south in MC? mock player yaw 0 faces south), accept either rotation.
            ChassisBlockEntity built = h.getLevel().getBlockEntity(h.absolutePos(na)) instanceof ChassisBlockEntity x ? x : null;
            h.assertTrue(built != null, "first chassis built at the origin");
            h.assertTrue(built.core().module(LocalFace.TOP) != null && built.core().module(LocalFace.TOP).moduleId().equals(BuiltinModules.HEATER), "heater installed");
            h.assertTrue(built.core().module(LocalFace.TOP).binding("enable") == 6, "binding copied");
            h.assertTrue(built.core().lane(0).totalCount() == 0, "contents never copied");
            int chassisCount = 0;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (h.getLevel().getBlockEntity(h.absolutePos(na.offset(dx, 0, dz))) instanceof ChassisBlockEntity) {
                        chassisCount++;
                    }
                }
            }
            h.assertTrue(chassisCount == 2, "two stages built two chassis, found " + chassisCount);
            h.assertTrue(player.getInventory().countItem(FfContent.CHASSIS_ITEM.get()) == 0, "materials consumed");
            h.succeed();
        });
    }

    private static void use(GameTestHelper h, net.minecraft.world.entity.player.Player p, BlockPos rel, Direction side) {
        BlockPos abs = h.absolutePos(rel);
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(abs), side, abs, false);
        ItemStack held = p.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
        held.useOn(new net.minecraft.world.item.context.UseOnContext(p, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
    }
}
