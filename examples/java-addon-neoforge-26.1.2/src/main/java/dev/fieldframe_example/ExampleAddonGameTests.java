package dev.fieldframe_example;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.neoforge.api.FieldframeForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Consumer;

/**
 * Addon GameTests. They only use public API plus vanilla interaction: a mock player installs modules by using the
 * module items on the chassis, exactly like survival play, and results are observed through
 * {@link FieldframeForgeApi#inspect}.
 */
public final class ExampleAddonGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, ExampleAddon.MOD_ID);

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ExampleAddon.MOD_ID, path);
    }

    /** 26.1 GameTests are registry entries: one test function and one test instance per test. */
    static void register(IEventBus bus) {
        FUNCTIONS.register("addon_modules_install_and_report", () -> ExampleAddonGameTests::addonModulesInstallAndReport);
        FUNCTIONS.register("kiln_uses_addon_recipe_family", () -> ExampleAddonGameTests::kilnUsesAddonRecipeFamily);
        FUNCTIONS.register("addon_machine_data_and_tier", () -> ExampleAddonGameTests::addonMachineDataAndTier);
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(id("default"), new TestEnvironmentDefinition.AllOf(List.of()));
            for (String[] t : new String[][] {{"addon_modules_install_and_report", "100"}, {"kiln_uses_addon_recipe_family", "400"}, {"addon_machine_data_and_tier", "40"}}) {
                event.registerTest(id(t[0]), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(t[0])),
                        new TestData<>(env, id("empty7"), Integer.parseInt(t[1]), 0, true)));
            }
        });
    }

    private static final BlockPos C = new BlockPos(2, 1, 2);

    private static void place(GameTestHelper h) {
        h.setBlock(C, BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("fieldframe", "chassis")));
    }

    private static void use(GameTestHelper h, Player p, ItemStack stack, Direction side) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos abs = h.absolutePos(C);
        Vec3 hit = Vec3.atCenterOf(abs).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        h.getLevel().getBlockState(abs).useItemOn(stack, h.getLevel(), p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, abs, false));
    }

    private static FfData face(GameTestHelper h, String face) {
        FfData d = FieldframeForgeApi.inspect(h.getLevel(), h.absolutePos(C));
        return d == null ? null : d.getData("faces").getDataOrNull(face);
    }

    public static void addonModulesInstallAndReport(GameTestHelper h) {
        h.assertTrue(FieldframeRegistry.module(ExampleAddon.KILN) != null, "kiln registered through public API");
        h.assertTrue(FieldframeForgeApi.moduleItem(ExampleAddon.KILN) != null, "kiln item generated");
        place(h);
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(ExampleAddon.KILN)), Direction.UP);
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(ExampleAddon.WEATHER_VANE)), Direction.DOWN);
        h.runAfterDelay(5, () -> {
            FfData top = face(h, "top");
            FfData bottom = face(h, "bottom");
            h.assertTrue(top != null && top.getString("module", "").equals(ExampleAddon.KILN.toString()), "kiln on top: " + top);
            h.assertTrue(bottom != null && bottom.getString("status", "").equals("SIGNAL_OFF"), "vane reports calm: " + bottom);
            h.succeed();
        });
    }

    /** Hopper -> passive Item Port (lane A) -> Kiln (addon recipe family) -> lane B, powered by a Fuel Engine. */
    public static void kilnUsesAddonRecipeFamily(GameTestHelper h) {
        place(h);
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(FfId.parse("fieldframe:item_port"))), Direction.UP);
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(ExampleAddon.KILN)), Direction.WEST);
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(FfId.parse("fieldframe:fuel_engine"))), Direction.DOWN);
        // One hopper feeds clay and coal into Orange storage, so this engine burns from Orange (by default fuel comes
        // from Purple). Addons set connections through the public API.
        h.assertTrue(FieldframeForgeApi.setBinding(h.getLevel(), h.absolutePos(C), dev.fieldframe.api.face.LocalFace.BOTTOM, "fuel", 0),
                "engine fuel connection set through the API");
        h.setBlock(C.above(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        HopperBlockEntity hopper = h.getBlockEntity(C.above(), HopperBlockEntity.class);
        hopper.setItem(0, new ItemStack(Items.COAL, 1));
        hopper.setItem(1, new ItemStack(Items.CLAY_BALL, 3));
        h.succeedWhen(() -> {
            FfData d = FieldframeForgeApi.inspect(h.getLevel(), h.absolutePos(C));
            h.assertTrue(d != null, "chassis present");
            long bricks = 0;
            Object laneB = d.getList("lanes").get(1);
            for (Object o : ((FfData) laneB).getList("items")) {
                FfData it = (FfData) o;
                if (it.getString("id", "").equals("minecraft:brick")) {
                    bricks += it.getLong("count", 0);
                }
            }
            h.assertTrue(bricks == 3, "3 bricks expected in lane B, have " + bricks + "; kiln " + d.getData("faces").getDataOrNull("left"));
        });
    }

    /** Machine data and a chassis tier from Java: a fuel, an Aura Chamber recipe and a sixth tier with its kit item. */
    public static void addonMachineDataAndTier(GameTestHelper h) {
        h.assertTrue(dev.fieldframe.api.process.MachineData.fuels().stream().anyMatch(f -> f.key().equals("minecraft:amethyst_cluster")
                && f.engine().equals(FfId.parse("fieldframe:gem_engine")) && f.rf() == 50_000), "addon fuel in the effective table");
        h.assertTrue(dev.fieldframe.api.process.MachineData.auras().stream().anyMatch(a -> a.id().equals(ExampleAddon.KILN_WARMTH)),
                "addon aura in the effective table");
        var tier = FieldframeRegistry.tier(ExampleAddon.TEMPERED);
        h.assertTrue(tier != null && tier.level() == 5, "addon tier registered");
        FfId kit = tier.kitId();
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath(kit.namespace(), kit.path()));
        h.assertTrue(item != null && item != net.minecraft.world.item.Items.AIR, "kit item " + kit + " generated");
        h.succeed();
    }
}
