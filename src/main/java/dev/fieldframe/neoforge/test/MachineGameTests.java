package dev.fieldframe.neoforge.test;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.adapter.McFluidVariant;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * In-world tests for the machines that had only unit tests: each runs on a real chassis with the game's own items,
 * fluids, blocks and neighbours, using the default connections (things in from Orange, results to Green, fuel from
 * Purple; tanks the same colours), so they also check that a machine works without any settings.
 */
@GameTestHolder(dev.fieldframe.neoforge.Fieldframe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MachineGameTests {
    private static final String T = "empty7";

    private MachineGameTests() {
    }

    private static final BlockPos C = new BlockPos(3, 2, 3);

    private static ChassisBlockEntity chassisAt(GameTestHelper h, BlockPos at, long rf) {
        h.setBlock(at, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
        ChassisBlockEntity be = (ChassisBlockEntity) h.getBlockEntity(at);
        if (rf > 0) {
            be.core().energy().receive(rf, false);
        }
        return be;
    }

    private static ChassisBlockEntity chassis(GameTestHelper h) {
        return chassisAt(h, C, 16_000);
    }

    private static void install(ChassisBlockEntity be, LocalFace face, FfId module, FfData config) {
        ChassisCore.Result r = be.core().install(face, FieldframeRegistry.module(module), config);
        if (!r.ok()) {
            throw new IllegalStateException("install failed: " + r);
        }
    }

    private static void install(ChassisBlockEntity be, LocalFace face, FfId module) {
        install(be, face, module, null);
    }

    private static FfData config(FfId module) {
        return FieldframeRegistry.module(module).defaultConfig();
    }

    private static Item item(String ns, String path) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(ns, path));
    }

    private static Fluid fluid(String ns, String path) {
        return BuiltInRegistries.FLUID.get(ResourceLocation.fromNamespaceAndPath(ns, path));
    }

    private static McFluidVariant fv(Fluid f) {
        return McFluidVariant.of(new FluidStack(f, 1));
    }

    private static long count(ChassisBlockEntity be, int lane, Item item) {
        return be.core().lane(lane).countOf(McItemVariant.of(new ItemStack(item)));
    }

    private static void put(ChassisBlockEntity be, int lane, ItemStack stack) {
        be.core().lane(lane).insert(McItemVariant.of(stack.copyWithCount(1)), stack.getCount(), false);
    }

    // ------------------------------------------------------------------ processors

    /** Sawmill: an oak log becomes planks and sawdust. */
    @GameTest(template = T, timeoutTicks = 300)
    public static void sawmillCutsLogs(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.SAWMILL);
        put(be, 0, new ItemStack(Items.OAK_LOG));
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 1, Items.OAK_PLANKS) >= 4, "planks in Green; " + be.core().statuses());
            h.assertTrue(count(be, 1, item("fieldframe", "sawdust")) >= 1, "sawdust in Green");
        });
    }

    /** Insolator: a carrot (kept as the seed) and water from the Orange tank grow more carrots. */
    @GameTest(template = T, timeoutTicks = 700)
    public static void insolatorGrowsCrops(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.INSOLATOR);
        put(be, 0, new ItemStack(Items.CARROT));
        be.core().tank(0).fill(fv(Fluids.WATER), 1000, false);
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 1, Items.CARROT) >= 2, "carrots grown into Green; " + be.core().statuses());
            h.assertTrue(count(be, 0, Items.CARROT) == 1, "the seed carrot is kept");
        });
    }

    /** Centrifuge: gravel separates into sand (and maybe flint). */
    @GameTest(template = T, timeoutTicks = 300)
    public static void centrifugeSeparatesGravel(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.CENTRIFUGE);
        put(be, 0, new ItemStack(Items.GRAVEL));
        h.succeedWhen(() -> h.assertTrue(count(be, 1, Items.SAND) == 1, "sand in Green; " + be.core().statuses()));
    }

    /** Fluid Transposer (no settings): a lava bucket is emptied into the Green tank and the bucket comes out. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void transposerEmptiesBucket(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.FLUID_TRANSPOSER);
        put(be, 0, new ItemStack(Items.LAVA_BUCKET));
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 1, Items.BUCKET) == 1, "empty bucket in Green; " + be.core().statuses());
            h.assertTrue(be.core().tank(1).amountOf(fv(Fluids.LAVA)) == 1000, "1000 mB lava in the Green tank");
        });
    }

    /** Refinery: tree sap from the Orange tank becomes refined oil in the Green tank. */
    @GameTest(template = T, timeoutTicks = 400)
    public static void refineryRefinesSap(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.REFINERY);
        be.core().tank(0).fill(fv(fluid("fieldframe", "tree_sap")), 400, false);
        h.succeedWhen(() -> h.assertTrue(be.core().tank(1).amountOf(fv(fluid("fieldframe", "refined_oil"))) >= 100,
                "refined oil in the Green tank; " + be.core().statuses()));
    }

    /** Pyrolyzer (no settings): a log becomes charcoal, and its creosote goes to the Green tank. */
    @GameTest(template = T, timeoutTicks = 500)
    public static void pyrolyzerMakesCharcoal(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.PYROLYZER);
        put(be, 0, new ItemStack(Items.OAK_LOG));
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 1, Items.CHARCOAL) == 1, "charcoal in Green; " + be.core().statuses());
            h.assertTrue(be.core().tank(1).amountOf(fv(fluid("fieldframe", "creosote"))) > 0, "creosote in the Green tank");
        });
    }

    /** Glacial Precipitator: water from the Orange tank freezes into ice, snow or snowballs. */
    @GameTest(template = T, timeoutTicks = 400)
    public static void precipitatorFreezesWater(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.PRECIPITATOR);
        be.core().tank(0).fill(fv(Fluids.WATER), 2000, false);
        h.succeedWhen(() -> h.assertTrue(count(be, 1, Items.ICE) + count(be, 1, Items.SNOW_BLOCK) + count(be, 1, Items.SNOWBALL) >= 1,
                "something frozen in Green; " + be.core().statuses()));
    }

    /** Stone Extruder: lava (Orange tank) and water (Green tank), both kept, make cobblestone. */
    @GameTest(template = T, timeoutTicks = 300)
    public static void extruderMakesCobblestone(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.STONE_EXTRUDER);
        be.core().tank(0).fill(fv(Fluids.LAVA), 1000, false);
        be.core().tank(1).fill(fv(Fluids.WATER), 1000, false);
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 1, Items.COBBLESTONE) >= 2, "cobblestone in Green; " + be.core().statuses());
            h.assertTrue(be.core().tank(0).amount() == 1000 && be.core().tank(1).amount() == 1000, "lava and water kept");
        });
    }

    // ------------------------------------------------------------------ engines

    /** Fluid, Gem and Redstone engines burn their data fuels from Purple into RF. */
    @GameTest(template = T, timeoutTicks = 400)
    public static void dataEnginesBurnFuel(GameTestHelper h) {
        ChassisBlockEntity fluidEngine = chassisAt(h, new BlockPos(1, 2, 1), 0);
        install(fluidEngine, LocalFace.TOP, BuiltinModules.FLUID_ENGINE);
        fluidEngine.core().tank(2).fill(fv(Fluids.LAVA), 100, false);
        ChassisBlockEntity gem = chassisAt(h, new BlockPos(5, 2, 1), 0);
        install(gem, LocalFace.TOP, BuiltinModules.GEM_ENGINE);
        put(gem, 2, new ItemStack(Items.LAPIS_LAZULI));
        ChassisBlockEntity redstone = chassisAt(h, new BlockPos(3, 2, 5), 0);
        install(redstone, LocalFace.TOP, BuiltinModules.REDSTONE_ENGINE);
        put(redstone, 2, new ItemStack(Items.REDSTONE));
        h.succeedWhen(() -> {
            h.assertTrue(fluidEngine.core().tank(2).amount() == 0 && fluidEngine.core().energy().stored() > 0,
                    "lava burned into RF; " + fluidEngine.core().statuses());
            h.assertTrue(gem.core().lane(2).totalCount() == 0 && gem.core().energy().stored() > 0, "lapis burned; " + gem.core().statuses());
            h.assertTrue(redstone.core().lane(2).totalCount() == 0 && redstone.core().energy().stored() > 0,
                    "redstone burned; " + redstone.core().statuses());
        });
    }

    /** Food Engine burns bread; Arcane Engine burns an enchanted sword (destroyed) from Purple. */
    @GameTest(template = T, timeoutTicks = 400)
    public static void foodAndArcaneEnginesBurn(GameTestHelper h) {
        ChassisBlockEntity food = chassisAt(h, new BlockPos(1, 2, 3), 0);
        install(food, LocalFace.TOP, BuiltinModules.FOOD_ENGINE);
        put(food, 2, new ItemStack(Items.BREAD));
        ChassisBlockEntity arcane = chassisAt(h, new BlockPos(5, 2, 3), 0);
        install(arcane, LocalFace.TOP, BuiltinModules.ARCANE_ENGINE);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 2);
        put(arcane, 2, sword);
        h.succeedWhen(() -> {
            h.assertTrue(food.core().lane(2).totalCount() == 0 && food.core().energy().stored() > 0, "bread burned; " + food.core().statuses());
            h.assertTrue(arcane.core().lane(2).totalCount() == 0 && arcane.core().energy().stored() > 0,
                    "enchanted sword burned; " + arcane.core().statuses());
        });
    }

    // ------------------------------------------------------------------ devices and handling

    /** Nullifier: does nothing until armed, then deletes what is in Orange. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void nullifierDeletesOnlyWhenArmed(GameTestHelper h) {
        ChassisBlockEntity safe = chassisAt(h, new BlockPos(1, 2, 3), 16_000);
        install(safe, LocalFace.TOP, BuiltinModules.NULLIFIER);
        put(safe, 0, new ItemStack(Items.DIRT, 5));
        ChassisBlockEntity armed = chassisAt(h, new BlockPos(5, 2, 3), 16_000);
        install(armed, LocalFace.TOP, BuiltinModules.NULLIFIER, config(BuiltinModules.NULLIFIER).putBool("armed", true));
        put(armed, 0, new ItemStack(Items.DIRT, 5));
        h.runAfterDelay(60, () -> {
            h.assertTrue(count(safe, 0, Items.DIRT) == 5, "not armed: nothing deleted");
            h.assertTrue(count(armed, 0, Items.DIRT) == 0, "armed: dirt deleted; " + armed.core().statuses());
            h.succeed();
        });
    }

    /** Water Collector: water sources in front fill the Orange tank. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void waterCollectorFillsTank(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        BlockPos front = C.north();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                h.setBlock(front.offset(dx, dy, 0), Blocks.WATER);
            }
        }
        install(be, LocalFace.FRONT, BuiltinModules.WATER_COLLECTOR);
        h.succeedWhen(() -> h.assertTrue(be.core().tank(0).amountOf(fv(Fluids.WATER)) >= 50, "water in the Orange tank; " + be.core().statuses()));
    }

    /** Tree Tapper: a log in front gives tree sap in the Orange tank. */
    @GameTest(template = T, timeoutTicks = 300)
    public static void treeTapperCollectsSap(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        h.setBlock(C.north(), Blocks.OAK_LOG);
        install(be, LocalFace.FRONT, BuiltinModules.TREE_TAPPER);
        h.succeedWhen(() -> h.assertTrue(be.core().tank(0).amountOf(fv(fluid("fieldframe", "tree_sap"))) > 0,
                "sap in the Orange tank; " + be.core().statuses()));
    }

    /** Fluid Port: an importing port pulls water from the neighbouring chassis' passive port. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void fluidPortImportsFromNeighbour(GameTestHelper h) {
        ChassisBlockEntity puller = chassisAt(h, new BlockPos(3, 2, 4), 16_000);
        install(puller, LocalFace.FRONT, BuiltinModules.FLUID_PORT, config(BuiltinModules.FLUID_PORT).putString("mode", "import"));
        ChassisBlockEntity source = chassisAt(h, new BlockPos(3, 2, 3), 16_000);
        install(source, LocalFace.BACK, BuiltinModules.FLUID_PORT, config(BuiltinModules.FLUID_PORT).putString("mode", "passive_out"));
        source.core().tank(0).fill(fv(Fluids.WATER), 1000, false);
        h.succeedWhen(() -> {
            h.assertTrue(puller.core().tank(0).amountOf(fv(Fluids.WATER)) == 1000, "1000 mB pulled; " + puller.core().statuses());
            h.assertTrue(source.core().tank(0).amount() == 0, "source drained");
        });
    }

    /** Controller Plate: a one-line program drives a channel while the chassis has power. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void controllerProgramDrivesChannel(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.CONTROLLER,
                config(BuiltinModules.CONTROLLER).putString("program", "when power > 0% then set ch 1 = 15\n"));
        h.succeedWhen(() -> h.assertTrue(be.core().control().snapshotCopy()[0] == 15, "channel 1 driven to 15; " + be.core().statuses()));
    }

    /** One chest with ore and coal behind an importing port: the coal goes to the engine (Purple), ore gets smelted. */
    @GameTest(template = T, timeoutTicks = 900)
    public static void portSendsFuelToEngine(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        h.setBlock(C.north(), Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) h.getBlockEntity(C.north());
        chest.setItem(0, new ItemStack(Items.RAW_IRON, 3));
        chest.setItem(1, new ItemStack(Items.COAL, 2));
        install(be, LocalFace.FRONT, BuiltinModules.ITEM_PORT, config(BuiltinModules.ITEM_PORT).putString("mode", "import"));
        install(be, LocalFace.TOP, BuiltinModules.HEATER);
        install(be, LocalFace.BOTTOM, BuiltinModules.FUEL_ENGINE);
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 1, Items.IRON_INGOT) == 3, "3 iron ingots in Green; " + be.core().statuses());
            h.assertTrue(count(be, 0, Items.COAL) == 0, "no coal stuck in Orange");
        });
    }

    // ------------------------------------------------------------------ polish: comparator, lit faces, sounds, advancements

    /** A vanilla comparator behind the machine reads how full Green storage is, and follows it when it changes. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void comparatorReadsGreenStorage(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        BlockPos cmp = C.south();
        h.setBlock(cmp.below(), Blocks.STONE);
        h.setBlock(cmp, Blocks.COMPARATOR.defaultBlockState().setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, Direction.NORTH));
        put(be, 1, new ItemStack(Items.IRON_INGOT, 64));
        h.succeedWhen(() -> {
            int level = be.core().comparatorLevel();
            h.assertTrue(level > 0, "Green storage holds 64 ingots, so the level is above 0");
            h.assertTrue(h.getLevel().getBlockEntity(h.absolutePos(cmp)) instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity c
                    && c.getOutputSignal() == level, "the comparator outputs " + level);
        });
    }

    /** A working module is sent to clients as lit (the faceplate glow and puffs); an idle one is not. */
    @GameTest(template = T, timeoutTicks = 300)
    public static void workingFaceSyncsToClients(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.HEATER);
        install(be, LocalFace.LEFT, BuiltinModules.ITEM_PORT);
        put(be, 0, new ItemStack(Items.RAW_IRON, 8));
        h.succeedWhen(() -> {
            int work = be.getUpdateTag(h.getLevel().registryAccess()).getCompound("client").getInt("work");
            h.assertTrue((work & (1 << LocalFace.TOP.ordinal())) != 0, "heater lit; " + be.core().statuses());
            h.assertTrue((work & (1 << LocalFace.LEFT.ordinal())) == 0, "idle port not lit");
        });
    }

    /** An Indicator Panel's level reaches clients (lamps and bar on its face); other faces carry none. */
    @GameTest(template = T, timeoutTicks = 300)
    public static void indicatorGaugeSyncsToClients(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        // A NOT gate with nothing on its input drives channel 2 at 15; the panel shows channel 2.
        be.core().install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.LOGIC_PLATE),
                FieldframeRegistry.module(BuiltinModules.LOGIC_PLATE).defaultConfig().putString("op", "not"));
        install(be, LocalFace.TOP, BuiltinModules.INDICATOR);
        be.core().setBinding(LocalFace.TOP, "in", 2);
        install(be, LocalFace.LEFT, BuiltinModules.HEATER);
        h.succeedWhen(() -> {
            net.minecraft.nbt.CompoundTag c = be.getUpdateTag(h.getLevel().registryAccess()).getCompound("client");
            h.assertTrue(c.contains(LocalFace.TOP.key() + "_g") && c.getByte(LocalFace.TOP.key() + "_g") == 15, "panel shows 15; " + be.core().statuses());
            h.assertTrue(!c.contains(LocalFace.LEFT.key() + "_g"), "a heater has no gauge");
        });
    }

    /**
     * Two players edit one machine in the same tick, through the same handler the network uses: the first edit wins,
     * the second (made against the old revision) is refused; both pulling the same module out yields one module, not
     * two; and a flood of requests from one player is capped at the per-second limit.
     */
    @GameTest(template = T, timeoutTicks = 40, batch = "ff_isolated_twoplayerseditonemachineinonetick")
    public static void twoPlayersEditOneMachineInOneTick(GameTestHelper h) {
        ChassisBlockEntity be = chassis(h);
        install(be, LocalFace.TOP, BuiltinModules.HEATER);
        BlockPos pos = be.getBlockPos();
        java.util.List<net.minecraft.server.level.ServerPlayer> players = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                // Fake players: real ServerPlayers that never join the player list, so other mods (Metallurgy) don't
                // try to send them packets.
                net.minecraft.server.level.ServerPlayer p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(h.getLevel(),
                        new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ff_mp_" + i));
                p.setPos(pos.getX() + 0.5 + i, pos.getY(), pos.getZ() - 1.5);
                p.getInventory().clearContent();
                players.add(p);
            }
            var a = players.get(0);
            var b = players.get(1);
            int top = LocalFace.TOP.ordinal();
            long rev = be.core().revision();
            dev.fieldframe.neoforge.network.FfNetwork.Action.apply(a, new dev.fieldframe.neoforge.network.FfNetwork.Action(pos, rev, "set_binding", top, "output", 2, new net.minecraft.nbt.CompoundTag()));
            dev.fieldframe.neoforge.network.FfNetwork.Action.apply(b, new dev.fieldframe.neoforge.network.FfNetwork.Action(pos, rev, "set_binding", top, "output", 0, new net.minecraft.nbt.CompoundTag()));
            h.assertTrue(be.core().module(LocalFace.TOP).binding("output") == 2, "first edit wins, the stale one is refused");
            h.assertTrue(be.core().revision() > rev, "the winning edit bumped the revision");
            long rev2 = be.core().revision();
            dev.fieldframe.neoforge.network.FfNetwork.Action.apply(a, new dev.fieldframe.neoforge.network.FfNetwork.Action(pos, rev2, "remove_module", top, "", 0, new net.minecraft.nbt.CompoundTag()));
            dev.fieldframe.neoforge.network.FfNetwork.Action.apply(b, new dev.fieldframe.neoforge.network.FfNetwork.Action(pos, rev2, "remove_module", top, "", 0, new net.minecraft.nbt.CompoundTag()));
            int heaters = 0;
            for (var p : java.util.List.of(a, b)) {
                for (int s = 0; s < p.getInventory().getContainerSize(); s++) {
                    if (p.getInventory().getItem(s).is(FfContent.moduleItem(BuiltinModules.HEATER))) {
                        heaters += p.getInventory().getItem(s).getCount();
                    }
                }
            }
            h.assertTrue(be.core().module(LocalFace.TOP) == null, "the module came out");
            h.assertTrue(heaters == 1, "exactly one heater between the two players, got " + heaters);
            var c = players.get(2);
            int before = dev.fieldframe.core.util.MpProbe.ACTIONS.get();
            for (int i = 0; i < 30; i++) {
                dev.fieldframe.neoforge.network.FfNetwork.Action.apply(c, new dev.fieldframe.neoforge.network.FfNetwork.Action(pos, be.core().revision(), "set_quiet", top, "", i & 1, new net.minecraft.nbt.CompoundTag()));
            }
            int handled = dev.fieldframe.core.util.MpProbe.ACTIONS.get() - before;
            h.assertTrue(handled == dev.fieldframe.neoforge.network.FfNetwork.MAX_ACTIONS_PER_SECOND,
                    "a flood is capped at " + dev.fieldframe.neoforge.network.FfNetwork.MAX_ACTIONS_PER_SECOND + " per second, handled " + handled);
        } finally {
            players.forEach(p -> p.getInventory().clearContent());
        }
        h.succeed();
    }

    /**
     * A Program Card copies one Controller Plate's program (sneak-use) and pastes it into another (use), through the
     * chassis block like a player; a machine someone else owns refuses the paste; sneak-use in the air wipes the card.
     */
    @GameTest(template = T, timeoutTicks = 40)
    public static void programCardCopiesAndPastes(GameTestHelper h) {
        BlockPos fromPos = new BlockPos(1, 2, 1);
        BlockPos toPos = new BlockPos(4, 2, 1);
        BlockPos theirsPos = new BlockPos(1, 2, 4);
        ChassisBlockEntity from = chassisAt(h, fromPos, 0);
        ChassisBlockEntity to = chassisAt(h, toPos, 0);
        ChassisBlockEntity theirs = chassisAt(h, theirsPos, 0);
        String program = "states fill, drain\nin fill: when fluid a == 0 then goto drain\nin drain: when always then set ch 4 = 15";
        install(from, LocalFace.TOP, BuiltinModules.CONTROLLER, config(BuiltinModules.CONTROLLER).putString("program", program));
        install(to, LocalFace.TOP, BuiltinModules.CONTROLLER);
        install(theirs, LocalFace.TOP, BuiltinModules.CONTROLLER);
        theirs.core().setOwner(java.util.UUID.randomUUID().toString(), "someone else");
        net.minecraft.world.entity.player.Player p = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ItemStack card = new ItemStack(FfContent.PROGRAM_CARD.get());
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, card);
        p.setShiftKeyDown(true);
        useOnTop(h, p, fromPos, card);
        h.assertTrue(program.equals(dev.fieldframe.neoforge.item.ProgramCardItem.program(card)), "copied: " + dev.fieldframe.neoforge.item.ProgramCardItem.program(card));
        p.setShiftKeyDown(false);
        useOnTop(h, p, toPos, card);
        h.assertTrue(program.equals(to.core().module(LocalFace.TOP).config().getString("program", "")), "pasted into the other plate");
        useOnTop(h, p, theirsPos, card);
        h.assertTrue(theirs.core().module(LocalFace.TOP).config().getString("program", "").isEmpty() || !program.equals(theirs.core().module(LocalFace.TOP).config().getString("program", "")),
                "someone else's machine refused the paste");
        p.setShiftKeyDown(true);
        card.getItem().use(h.getLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
        h.assertTrue(dev.fieldframe.neoforge.item.ProgramCardItem.program(card).isEmpty(), "wiped");
        h.succeed();
    }

    /** Hand Crank: an empty-hand right-click turns it for RF; it does not open the machine screen. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void handCrankTurnsForThePlayer(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.TOP, dev.fieldframe.core.modules.power.HandCrank.ID);
        be.core().energy().extract(be.core().energy().stored(), false);
        net.minecraft.world.entity.player.Player p = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos abs = h.absolutePos(C);
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(abs).add(0, 0.5, 0), Direction.UP, abs, false);
        for (int i = 0; i < 2; i++) {
            h.getLevel().getBlockState(abs).useWithoutItem(h.getLevel(), p, hit);
        }
        h.succeedWhen(() -> h.assertTrue(be.core().energy().stored() >= 200, "the crank made RF: " + be.core().energy().stored() + "; " + be.core().statuses()));
    }

    /**
     * Field Kit: a Hand Crank in the off hand installs onto the kit, a Heater is refused, and holding use turns the
     * crank; RF charged into the item moves into the kit, and the kit's RF is written back onto the item.
     */
    @GameTest(template = T, timeoutTicks = 120)
    public static void fieldKitInstallsAndCranks(GameTestHelper h) {
        var level = h.getLevel();
        net.minecraft.world.entity.player.Player p = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ItemStack kit = new ItemStack(FfContent.FIELD_KIT.get());
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, kit);
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(FfContent.moduleItem(BuiltinModules.HEATER)));
        FfContent.FIELD_KIT.get().use(level, p, net.minecraft.world.InteractionHand.MAIN_HAND);
        h.assertTrue(!p.getOffhandItem().isEmpty(), "a Heater does not fit a Field Kit");
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(FfContent.moduleItem(dev.fieldframe.core.modules.power.HandCrank.ID)));
        FfContent.FIELD_KIT.get().use(level, p, net.minecraft.world.InteractionHand.MAIN_HAND);
        h.assertTrue(p.getOffhandItem().isEmpty(), "the Hand Crank was installed from the off hand");
        var runtime = dev.fieldframe.neoforge.chassis.FieldKitRuntime.get(kit, level);
        h.assertTrue(runtime.core.module(LocalFace.FRONT) != null, "the crank sits on the kit's first face");
        // Charged like any RF item (what a chassis Item Charger does): the RF waits on the item until the kit ticks.
        var charger = new dev.fieldframe.neoforge.chassis.ForgeChassisWorld(level, h.absolutePos(BlockPos.ZERO),
                new dev.fieldframe.api.face.Orientation(dev.fieldframe.api.face.WorldDir.NORTH));
        var charged = charger.charge(McItemVariant.of(kit), 200);
        h.assertTrue(charged != null && charged.accepted() == 200, "the kit takes RF as an item: " + (charged == null ? "no handler" : charged.accepted()));
        dev.fieldframe.neoforge.feature.ItemEnergy.set(kit, dev.fieldframe.neoforge.feature.ItemEnergy.get(McItemVariant.toStack(charged.result(), 1)));
        h.onEachTick(() -> {
            int t = (int) level.getGameTime();
            FfContent.FIELD_KIT.get().onUseTick(level, p, kit, 72_000 - t);
            dev.fieldframe.neoforge.chassis.FieldKitRuntime.tick(kit, level, p);
        });
        h.succeedWhen(() -> {
            h.assertTrue(dev.fieldframe.neoforge.feature.ItemEnergy.get(kit) == 0, "the charged RF moved into the kit");
            h.assertTrue(runtime.core.energy().stored() >= 400, "200 charged + the crank's RF: " + runtime.core.energy().stored());
            h.assertTrue(dev.fieldframe.neoforge.feature.ItemEnergy.data(kit).getLong(dev.fieldframe.neoforge.chassis.FieldKitRuntime.RF) > 0,
                    "the kit's RF is saved on the item");
        });
    }

    /** Uses {@code stack} on the top of the chassis at {@code rel}, as a player's right-click does. */
    private static void useOnTop(GameTestHelper h, net.minecraft.world.entity.player.Player p, BlockPos rel, ItemStack stack) {
        BlockPos abs = h.absolutePos(rel);
        net.minecraft.world.phys.Vec3 hit = net.minecraft.world.phys.Vec3.atCenterOf(abs).add(0, 0.5, 0);
        h.getLevel().getBlockState(abs).useItemOn(stack, h.getLevel(), p, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(hit, Direction.UP, abs, false));
    }

    /** Every machine sound is registered and every advancement in the Fieldframe tab is loaded. */
    @GameTest(template = T, timeoutTicks = 40)
    public static void soundsAndAdvancementsLoaded(GameTestHelper h) {
        for (String s : dev.fieldframe.core.chassis.MachineLook.SOUND_EVENTS) {
            h.assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(ResourceLocation.fromNamespaceAndPath("fieldframe", s)), "sound " + s);
        }
        for (String a : dev.fieldframe.core.chassis.Milestones.ALL) {
            h.assertTrue(h.getLevel().getServer().getAdvancements().get(ResourceLocation.fromNamespaceAndPath("fieldframe", a)) != null, "advancement " + a);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ heat and wind generators

    private static String status(ChassisBlockEntity be, LocalFace f) {
        return be.core().module(f).status().code();
    }

    /** Steam Loop on coal: Boiler, Steam Turbine and a Condenser against water make RF and return most of the water. */
    @GameTest(template = T, timeoutTicks = 400)
    public static void steamLoopRunsOnCoal(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.BOTTOM, dev.fieldframe.core.modules.power.HeatPower.BOILER);
        install(be, LocalFace.LEFT, dev.fieldframe.core.modules.power.HeatPower.STEAM_TURBINE);
        install(be, LocalFace.RIGHT, dev.fieldframe.core.modules.power.HeatPower.CONDENSER);
        h.setBlock(C.east(), Blocks.WATER);
        be.core().tank(0).fill(fv(Fluids.WATER), 4_000, false);
        put(be, 2, new ItemStack(Items.COAL, 4));
        h.succeedWhen(() -> {
            h.assertTrue(be.core().energy().stored() > 4_000, "turbine made RF: " + be.core().energy().stored() + "; " + be.core().statuses());
            h.assertTrue("CONDENSING_COLD".equals(status(be, LocalFace.RIGHT)), "condenser touches water: " + status(be, LocalFace.RIGHT));
            h.assertTrue(be.core().tank(0).amountOf(fv(Fluids.WATER)) > 3_000, "most water came back");
        });
    }

    /** A Boiler with a magma block against it boils without any fuel. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void boilerRunsOnMagmaHeat(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.BOTTOM, dev.fieldframe.core.modules.power.HeatPower.BOILER);
        h.setBlock(C.below(), Blocks.MAGMA_BLOCK);
        be.core().tank(0).fill(fv(Fluids.WATER), 1_000, false);
        h.succeedWhen(() -> {
            h.assertTrue(be.core().tank(1).amountOf(fv(fluid("fieldframe", "steam"))) >= 200, "steam in the Green tank; " + be.core().statuses());
            h.assertTrue("BOILING_ON_HEAT".equals(status(be, LocalFace.BOTTOM)), "boiling on heat: " + status(be, LocalFace.BOTTOM));
        });
    }

    /** Thermocouple: magma (1000 K) against blue ice (180 K) makes 16 RF/t; the temperature table is loaded from data. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void thermocoupleMagmaAgainstBlueIce(GameTestHelper h) {
        h.assertTrue(dev.fieldframe.core.material.Temperatures.entries().stream()
                .anyMatch(e -> e.key().equals("minecraft:magma_block") && e.kelvin() == 1000), "temperature data loaded");
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.LEFT, dev.fieldframe.core.modules.power.HeatPower.HOT_JUNCTION);
        install(be, LocalFace.RIGHT, dev.fieldframe.core.modules.power.HeatPower.COLD_JUNCTION);
        h.setBlock(C.west(), Blocks.MAGMA_BLOCK);
        h.setBlock(C.east(), Blocks.BLUE_ICE);
        h.succeedWhen(() -> {
            h.assertTrue("THERMO_RUNNING".equals(status(be, LocalFace.LEFT)), "running: " + be.core().module(LocalFace.LEFT).status());
            h.assertTrue(be.core().module(LocalFace.LEFT).status().args().get(0).equals("16"), "16 RF/t: " + be.core().module(LocalFace.LEFT).status());
            h.assertTrue(be.core().energy().stored() > 0, "RF in the buffer");
        });
    }

    /** A Wind Rotor down at the test height says it is too low instead of making RF. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void windRotorNeedsHeight(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.LEFT, dev.fieldframe.core.modules.power.WindRotor.ID);
        h.succeedWhen(() -> {
            h.assertTrue("TOO_LOW_FOR_WIND".equals(status(be, LocalFace.LEFT)), "too low: " + status(be, LocalFace.LEFT));
            h.assertTrue(be.core().energy().stored() == 0, "no RF");
        });
    }

    // ------------------------------------------------------------------ burst and chemistry generators

    /** Storm Coil: lightning on the vanilla rod on top fills the capacitor, which feeds the chassis. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void stormCoilCatchesLightning(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.TOP, dev.fieldframe.core.modules.power.BurstPower.STORM_COIL);
        h.setBlock(C.above(), Blocks.LIGHTNING_ROD);
        h.runAfterDelay(5, () -> {
            BlockPos rod = h.absolutePos(C.above());
            ((net.minecraft.world.level.block.LightningRodBlock) Blocks.LIGHTNING_ROD).onLightningStrike(h.getLevel().getBlockState(rod), h.getLevel(), rod);
        });
        h.succeedWhen(() -> {
            h.assertTrue(be.core().energy().stored() > 2_000, "lightning fed the chassis: " + be.core().energy().stored() + "; " + be.core().statuses());
            h.assertTrue("RELEASING_CHARGE".equals(status(be, LocalFace.TOP)), "releasing: " + status(be, LocalFace.TOP));
        });
    }

    /** Blast Damper: primed TNT next to the machine explodes into the damper; nothing nearby breaks. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void blastDamperCatchesTnt(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.FRONT, dev.fieldframe.core.modules.power.BurstPower.BLAST_DAMPER);
        BlockPos wall = C.east(3);
        h.setBlock(wall, Blocks.STONE);
        h.runAfterDelay(5, () -> {
            BlockPos at = h.absolutePos(C.east(2));
            net.minecraft.world.entity.item.PrimedTnt tnt = new net.minecraft.world.entity.item.PrimedTnt(h.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, null);
            tnt.setFuse(5);
            h.getLevel().addFreshEntity(tnt);
        });
        h.succeedWhen(() -> {
            var damper = (dev.fieldframe.core.modules.power.BurstPower.BlastDamper) be.core().module(LocalFace.FRONT).behavior();
            h.assertTrue(damper.caught() == 1, "caught one explosion");
            h.assertTrue(be.core().energy().stored() > 0, "stored its energy");
            h.assertBlockPresent(Blocks.STONE, wall);
            h.assertBlockPresent(FfContent.CHASSIS.get(), C);
        });
    }

    /** Galvanic Cell: iron against copper in water makes RF; the electrode table is loaded from data. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void galvanicCellRunsOnIronAndCopper(GameTestHelper h) {
        h.assertTrue(dev.fieldframe.core.material.Electrodes.entries().size() > 40, "electrode data loaded");
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.LEFT, dev.fieldframe.core.modules.power.GalvanicCell.ID);
        put(be, 0, new ItemStack(Items.IRON_INGOT, 2));
        put(be, 2, new ItemStack(Items.COPPER_INGOT, 1));
        be.core().tank(0).fill(fv(Fluids.WATER), 1_000, false);
        h.succeedWhen(() -> {
            h.assertTrue("CELL_RUNNING".equals(status(be, LocalFace.LEFT)), "running: " + be.core().module(LocalFace.LEFT).status());
            h.assertTrue(be.core().energy().stored() > 200, "RF in the buffer");
            h.assertTrue(count(be, 2, Items.COPPER_INGOT) == 1, "the copper cathode stays");
        });
    }

    // ------------------------------------------------------------------ moon, ground and farm power

    /** Lunar Prism: by day it waits, at night it follows the moon; under a block it has no sky. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void lunarPrismFollowsTheNight(GameTestHelper h) {
        ChassisBlockEntity open = chassisAt(h, C, 0);
        install(open, LocalFace.TOP, dev.fieldframe.core.modules.power.LunarPrism.ID);
        BlockPos c2 = C.east(2);
        ChassisBlockEntity covered = chassisAt(h, c2, 0);
        install(covered, LocalFace.TOP, dev.fieldframe.core.modules.power.LunarPrism.ID);
        h.setBlock(c2.above(), Blocks.STONE);
        h.succeedWhen(() -> {
            h.assertTrue("NO_SKY".equals(status(covered, LocalFace.TOP)), "covered: " + status(covered, LocalFace.TOP));
            String s = status(open, LocalFace.TOP);
            if (h.getLevel().isDay()) {
                h.assertTrue("LUNAR_DAY".equals(s), "by day it waits: " + s);
                h.assertTrue(open.core().energy().stored() == 0, "no RF by day");
            } else {
                h.assertTrue("LUNAR_RUNNING".equals(s) || "LUNAR_NEW_MOON".equals(s), "at night: " + s);
            }
        });
    }

    /** Geothermal Probe: deep plain ground gives a little; magma under the machine gives the full output. */
    // Own batch: probes within 24 blocks share ground heat, so a neighbouring test's probe skewed the ratio.
    @GameTest(template = T, timeoutTicks = 400, batch = "ff_geothermal")
    public static void geothermalProbeFeelsMagmaBelow(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.BOTTOM, dev.fieldframe.core.modules.power.GeothermalProbe.ID);
        long[] plain = {-1};
        h.runAfterDelay(10, () -> {
            plain[0] = ((dev.fieldframe.core.modules.power.GeothermalProbe) be.core().module(LocalFace.BOTTOM).behavior()).rate();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    h.setBlock(C.offset(dx, -1, dz), Blocks.MAGMA_BLOCK);
                }
            }
        });
        h.runAfterDelay(20, () -> h.succeedWhen(() -> {
            long now = ((dev.fieldframe.core.modules.power.GeothermalProbe) be.core().module(LocalFace.BOTTOM).behavior()).rate();
            h.assertTrue(plain[0] > 0, "plain deep ground gives some heat: " + status(be, LocalFace.BOTTOM));
            // Both rates are rounded (half depth: 7.5 -> 8, then 30), so allow the rounding.
            h.assertTrue(Math.abs(now - 4 * plain[0]) <= 2, "magma below gives four times as much (" + plain[0] + " then " + now + ")");
            h.assertTrue(be.core().energy().stored() > 0, "RF in the buffer");
        }));
    }

    /** Treadmill: a cow in front of the machine walks for RF while it eats wheat from Purple storage. */
    @GameTest(template = T, timeoutTicks = 300)
    public static void treadmillCowWalksForWheat(GameTestHelper h) {
        h.assertTrue(dev.fieldframe.core.material.TreadmillMobs.entries().size() >= 8, "treadmill data loaded");
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.FRONT, dev.fieldframe.core.modules.power.Treadmill.ID);
        BlockPos belt = C.north();
        h.setBlock(belt.below(), Blocks.STONE);
        put(be, 2, new ItemStack(Items.WHEAT, 2));
        h.spawn(net.minecraft.world.entity.EntityType.COW, belt);
        h.succeedWhen(() -> {
            h.assertTrue("TREADMILL_RUNNING".equals(status(be, LocalFace.FRONT)), "running: " + be.core().module(LocalFace.FRONT).status());
            h.assertTrue(be.core().energy().stored() >= 60, "the cow made RF: " + be.core().energy().stored());
            h.assertTrue(count(be, 2, Items.WHEAT) == 1, "it ate one wheat");
        });
    }

    /** Bio-Digester: seeds and water become biogas in the Green tank, and a Fluid Engine burns it for RF. */
    @GameTest(template = T, timeoutTicks = 900)
    public static void bioDigesterFeedsAFluidEngine(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 2_000);
        install(be, LocalFace.LEFT, BuiltinModules.BIO_DIGESTER);
        install(be, LocalFace.RIGHT, BuiltinModules.FLUID_ENGINE);
        h.assertTrue(be.core().setBinding(LocalFace.RIGHT, "fuel", 1).ok(), "engine burns from the Green tank");
        put(be, 0, new ItemStack(Items.WHEAT_SEEDS, 16));
        be.core().tank(0).fill(fv(Fluids.WATER), 2_000, false);
        long[] most = {0};
        h.onEachTick(() -> most[0] = Math.max(most[0], be.core().energy().stored()));
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 0, Items.WHEAT_SEEDS) <= 8, "seeds digested: " + count(be, 0, Items.WHEAT_SEEDS));
            h.assertTrue("WORKING".equals(status(be, LocalFace.RIGHT)), "the engine burns biogas: " + be.core().module(LocalFace.RIGHT).status());
            h.assertTrue(most[0] > 2_000, "more RF than it started with");
        });
    }

    // ------------------------------------------------------------------ handy modules

    /** Storage Sorter: coal in Green storage moves to Purple, stone (not in its filter) stays. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void storageSorterMovesByFilter(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        FfData cfg = config(dev.fieldframe.core.modules.SmallModules.STORAGE_SORTER);
        cfg.put("filter", dev.fieldframe.api.resource.ItemFilter.allowIds("minecraft:coal").toData());
        install(be, LocalFace.LEFT, dev.fieldframe.core.modules.SmallModules.STORAGE_SORTER, cfg);
        put(be, 1, new ItemStack(Items.COAL, 5));
        put(be, 1, new ItemStack(Items.STONE, 3));
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 2, Items.COAL) == 5, "coal moved to Purple: " + count(be, 2, Items.COAL));
            h.assertTrue(count(be, 1, Items.STONE) == 3, "stone stayed in Green");
        });
    }

    /** Tag Unifier: items it has no other variant for pass through to Green storage unchanged. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void tagUnifierPassesItemsOn(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 1_000);
        install(be, LocalFace.LEFT, dev.fieldframe.core.modules.SmallModules.TAG_UNIFIER);
        put(be, 0, new ItemStack(Items.IRON_INGOT, 4));
        put(be, 0, new ItemStack(Items.STONE, 2));
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 1, Items.IRON_INGOT) == 4 && count(be, 1, Items.STONE) == 2, "both passed to Green");
            h.assertTrue(be.core().lane(0).totalCount() == 0, "nothing left in Orange");
        });
    }

    /** Tool Repairer: a worn iron pickaxe is mended with liquid experience and moves to Green storage. */
    @GameTest(template = T, timeoutTicks = 200)
    public static void toolRepairerMendsWithExperience(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 0);
        install(be, LocalFace.LEFT, dev.fieldframe.core.modules.SmallModules.TOOL_REPAIRER);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(40);
        put(be, 0, pick);
        Fluid xp = net.minecraft.core.registries.BuiltInRegistries.FLUID.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("fieldframe", "liquid_experience"));
        be.core().tank(0).fill(fv(xp), 1_000, false);
        h.succeedWhen(() -> {
            h.assertTrue(count(be, 1, Items.IRON_PICKAXE) == 1, "mended pickaxe in Green");
            ItemStack out = McItemVariant.toStack(be.core().lane(1).variant(0), 1);
            h.assertTrue(out.getDamageValue() == 0, "like new, not " + out.getDamageValue());
            h.assertTrue(be.core().tank(0).amount(0) == 600, "20 points of experience used: " + be.core().tank(0).amount(0));
        });
    }

    /** Mob Grinder: an iron sword kills a cow in front; its drops end up in Green storage and the sword wears. */
    @GameTest(template = T, timeoutTicks = 400, batch = "ff_mob_grinder")
    public static void mobGrinderKillsAndCollects(GameTestHelper h) {
        ChassisBlockEntity be = chassisAt(h, C, 10_000);
        install(be, LocalFace.FRONT, dev.fieldframe.core.modules.SmallModules.MOB_GRINDER);
        put(be, 2, new ItemStack(Items.IRON_SWORD));
        BlockPos pen = C.north();
        h.setBlock(pen.below(), Blocks.STONE);
        // A one-block pen in front of the grinder.
        for (BlockPos wall : new BlockPos[]{pen.north(), pen.east(), pen.west()}) {
            h.setBlock(wall, Blocks.GLASS);
            h.setBlock(wall.above(), Blocks.GLASS);
        }
        // Glass over the chassis too: a cow that survives the first hit panics and would jump out over it.
        h.setBlock(C.above(), Blocks.GLASS);
        h.spawn(net.minecraft.world.entity.EntityType.COW, pen);
        h.succeedWhen(() -> {
            net.minecraft.world.phys.AABB around = new net.minecraft.world.phys.AABB(h.absolutePos(pen)).inflate(4);
            int cows = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, around).size();
            int items = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, around).size();
            h.assertTrue(count(be, 1, Items.BEEF) + count(be, 1, Items.COOKED_BEEF) + count(be, 1, Items.LEATHER) > 0
                    || be.core().lane(1).totalCount() > 0, "drops collected: " + be.core().module(LocalFace.FRONT).status()
                    + "; " + be.core().module(LocalFace.FRONT).behavior().detail() + ", animals near " + cows + ", items near " + items
                    + ", RF " + be.core().energy().stored());
            ItemStack sword = McItemVariant.toStack(be.core().lane(2).variant(0), 1);
            h.assertTrue(sword.is(Items.IRON_SWORD) && sword.getDamageValue() > 0, "the sword wore");
        });
    }

    // ------------------------------------------------------------------ bolted chassis

    /** Two chassis bolted with a Frame Bolt share storage and RF; breaking one part leaves the rest intact. */
    @GameTest(template = T, timeoutTicks = 400)
    public static void boltedChassisShareStorage(GameTestHelper h) {
        ChassisBlockEntity a = chassisAt(h, C, 0);
        ChassisBlockEntity b = chassisAt(h, C.east(), 0);
        install(a, LocalFace.TOP, BuiltinModules.HEATER);
        install(b, LocalFace.BOTTOM, BuiltinModules.FUEL_ENGINE);
        net.minecraft.world.entity.player.Player player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var r = dev.fieldframe.neoforge.chassis.BoltLinks.use(h.getLevel(), h.absolutePos(C), Direction.EAST, player, new ItemStack(FfContent.FRAME_BOLT.get(), 4));
        h.assertTrue(r.consumesAction(), "bolted: " + r);
        h.assertTrue(a.core().lane(0) == b.core().lane(0), "one Orange storage for both");
        put(a, 0, new ItemStack(Items.RAW_IRON, 2));
        put(b, 2, new ItemStack(Items.COAL, 1));
        h.succeedWhen(() -> {
            h.assertTrue(count(a, 1, Items.IRON_INGOT) == 2, "the heater on one part ran on the engine on the other; " + a.core().statuses());
            if (a.core().group() != null) {
                h.destroyBlock(C.east());
            }
            h.assertTrue(a.core().group() == null, "breaking a part unbolts it");
            h.assertTrue(a.core().boltInfo().isEmpty(), "the lead forgot the part");
            h.assertTrue(count(a, 1, Items.IRON_INGOT) == 2, "the ingots stayed in the machine that is left");
        });
    }

    /** A chassis saved as part of a machine whose lead is gone goes back to being a normal chassis. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void orphanedBoltPartRecovers(GameTestHelper h) {
        ChassisBlockEntity a = chassisAt(h, C, 0);
        a.core().setBoltInfo(new FfData().putString("lead", "0,0,3"));
        a.core().setAwaitingLink(true);
        h.succeedWhen(() -> {
            h.assertTrue(!a.core().isAwaitingLink(), "no longer waiting");
            h.assertTrue(a.core().boltInfo().isEmpty(), "bolt info cleared");
        });
    }

    /** Heater: smelting raw iron also gives Slag at the ore chance; smelting sand does not. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void heaterOreSmeltingGivesSlag(GameTestHelper h) {
        java.util.List<dev.fieldframe.api.process.ProcessRecipe> heating =
                dev.fieldframe.neoforge.recipe.RecipeBridge.forLevel(h.getLevel()).recipes(dev.fieldframe.api.process.ProcessFamilies.HEATING);
        h.assertTrue(slagChance(heating, "raw_iron") == dev.fieldframe.core.material.Materials.ORE_SLAG_CHANCE,
                "raw iron gives Slag at the ore chance, got " + slagChance(heating, "raw_iron"));
        h.assertTrue(slagChance(heating, "sand") == 0, "sand gives no Slag");
        h.succeed();
    }

    /** Slag chance of the first heating recipe whose input shows minecraft:{path}; -1 if there is none. */
    private static double slagChance(java.util.List<dev.fieldframe.api.process.ProcessRecipe> recipes, String path) {
        for (dev.fieldframe.api.process.ProcessRecipe r : recipes) {
            boolean match = r.itemInputs().stream().anyMatch(in -> in.examples().stream()
                    .anyMatch(v -> v.id().namespace().equals("minecraft") && v.id().path().equals(path)));
            if (match) {
                return r.itemResults().stream().filter(res -> res.item().id().toString().equals("fieldframe:slag"))
                        .mapToDouble(dev.fieldframe.api.process.ProcessRecipe.ItemResult::chance).findFirst().orElse(0);
            }
        }
        return -1;
    }
}
