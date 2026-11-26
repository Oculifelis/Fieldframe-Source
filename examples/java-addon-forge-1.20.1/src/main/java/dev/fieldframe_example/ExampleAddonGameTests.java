package dev.fieldframe_example;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.forge.api.FieldframeForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;


/**
 * Addon GameTests. They only use public API plus vanilla interaction: a mock player installs modules by using the
 * module items on the chassis, exactly like survival play, and results are observed through
 * {@link FieldframeForgeApi#inspect}.
 */
@GameTestHolder(ExampleAddon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExampleAddonGameTests {
    private static final BlockPos C = new BlockPos(2, 1, 2);

    private static void place(GameTestHelper h) {
        Block chassis = ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("fieldframe", "chassis"));
        h.setBlock(C, chassis);
    }

    private static void use(GameTestHelper h, Player p, ItemStack stack, net.minecraft.core.Direction side) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos abs = h.absolutePos(C);
        Vec3 hit = Vec3.atCenterOf(abs).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        h.getLevel().getBlockState(abs).use(h.getLevel(), p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, abs, false));
    }

    private static FfData face(GameTestHelper h, String face) {
        FfData d = FieldframeForgeApi.inspect(h.getLevel(), h.absolutePos(C));
        return d == null ? null : d.getData("faces").getDataOrNull(face);
    }

    @GameTest(template = "empty7")
    public static void addonModulesInstallAndReport(GameTestHelper h) {
        h.assertTrue(FieldframeRegistry.module(ExampleAddon.KILN) != null, "kiln registered through public API");
        h.assertTrue(FieldframeForgeApi.moduleItem(ExampleAddon.KILN) != null, "kiln item generated");
        place(h);
        Player p = h.makeMockPlayer();
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(ExampleAddon.KILN)), net.minecraft.core.Direction.UP);
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(ExampleAddon.WEATHER_VANE)), net.minecraft.core.Direction.DOWN);
        h.runAfterDelay(5, () -> {
            FfData top = face(h, "top");
            FfData bottom = face(h, "bottom");
            h.assertTrue(top != null && top.getString("module", "").equals(ExampleAddon.KILN.toString()), "kiln on top: " + top);
            h.assertTrue(bottom != null && bottom.getString("status", "").equals("SIGNAL_OFF"), "vane reports calm: " + bottom);
            h.succeed();
        });
    }

    /** Hopper -> passive Item Port (lane A) -> Kiln (addon recipe family) -> lane B, powered by a Fuel Engine. */
    @GameTest(template = "empty7", timeoutTicks = 400)
    public static void kilnUsesAddonRecipeFamily(GameTestHelper h) {
        place(h);
        Player p = h.makeMockPlayer();
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(FfId.parse("fieldframe:item_port"))), net.minecraft.core.Direction.UP);
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(ExampleAddon.KILN)), net.minecraft.core.Direction.WEST);
        use(h, p, new ItemStack(FieldframeForgeApi.moduleItem(FfId.parse("fieldframe:fuel_engine"))), net.minecraft.core.Direction.DOWN);
        // One hopper feeds clay and coal into Orange storage, so this engine burns from Orange (by default fuel comes
        // from Purple). Addons set connections through the public API.
        h.assertTrue(FieldframeForgeApi.setBinding(h.getLevel(), h.absolutePos(C), dev.fieldframe.api.face.LocalFace.BOTTOM, "fuel", 0),
                "engine fuel connection set through the API");
        h.setBlock(C.above(), net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HopperBlock.FACING, net.minecraft.core.Direction.DOWN));
        var hopper = (net.minecraft.world.level.block.entity.HopperBlockEntity) h.getBlockEntity(C.above());
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
    @GameTest(template = "empty7")
    public static void addonMachineDataAndTier(GameTestHelper h) {
        h.assertTrue(dev.fieldframe.api.process.MachineData.fuels().stream().anyMatch(f -> f.key().equals("minecraft:amethyst_cluster")
                && f.engine().equals(FfId.parse("fieldframe:gem_engine")) && f.rf() == 50_000), "addon fuel in the effective table");
        h.assertTrue(dev.fieldframe.api.process.MachineData.auras().stream().anyMatch(a -> a.id().equals(ExampleAddon.KILN_WARMTH)),
                "addon aura in the effective table");
        var tier = FieldframeRegistry.tier(ExampleAddon.TEMPERED);
        h.assertTrue(tier != null && tier.level() == 5, "addon tier registered");
        FfId kit = tier.kitId();
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(kit.namespace(), kit.path()));
        h.assertTrue(item != null && item != net.minecraft.world.item.Items.AIR, "kit item " + kit + " generated");
        h.succeed();
    }
}
