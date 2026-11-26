package dev.fieldframe.neoforge.test;

import com.mojang.authlib.GameProfile;
import dev.fieldframe.neoforge.api.WorldFluids;
import dev.fieldframe.neoforge.feature.FeatureContent;
import dev.fieldframe.neoforge.feature.MaterialContent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Every Fieldframe fluid can be poured into the world with its bucket, picked back up, and flows like water or lava. */
public final class PlaceableFluidGameTests {
    private PlaceableFluidGameTests() {
    }

    record Case(String name, Item bucket, Fluid source, FluidType type) {
        boolean hot() {
            return WorldFluids.hot(type.getTemperature());
        }
    }

    static List<Case> cases() {
        List<Case> out = new ArrayList<>();
        FeatureContent.FLUID_SETS.forEach((f, s) -> out.add(new Case(f.path, s.bucket().get(), s.source().get(), s.type().get())));
        MaterialContent.FLUID_SETS.forEach((n, s) -> out.add(new Case(n, s.bucket().get(), s.source().get(), s.type().get())));
        return out;
    }

    /** Uses the held stack like a right click and returns what ends up in the hand. */
    static ItemStack use(ServerLevel level, Player p, ItemStack stack) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResult r = stack.use(level, p, InteractionHand.MAIN_HAND);
        if (r instanceof InteractionResult.Success s && s.heldItemTransformedTo() != null) {
            return s.heldItemTransformedTo();
        }
        return p.getItemInHand(InteractionHand.MAIN_HAND);
    }

    /** A player standing on {@code floor} looking straight down at it. */
    static FakePlayer lookingDown(GameTestHelper h, BlockPos floor) {
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-fluid-bucket]"));
        BlockPos abs = h.absolutePos(floor.above());
        fp.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        fp.setYRot(0f);
        fp.setXRot(90f);
        return fp;
    }

    /** Pours {@code bucket} onto {@code floor}, checks the source, light and empty bucket, then scoops it back up. */
    public static void pourAndScoop(GameTestHelper h, BlockPos floor, String name, Item bucket, Fluid source, int light, List<String> bad) {
        BlockPos at = floor.above();
        h.setBlock(floor, Blocks.STONE);
        h.setBlock(at, Blocks.AIR);
        FakePlayer fp = lookingDown(h, floor);
        ItemStack after = use(h.getLevel(), fp, new ItemStack(bucket));
        FluidState fs = h.getBlockState(at).getFluidState();
        if (!fs.isSource() || !fs.getType().isSame(source)) {
            bad.add(name + ": bucket placed " + h.getBlockState(at));
            return;
        }
        if (!after.is(Items.BUCKET)) {
            bad.add(name + ": bucket left " + after);
        }
        if (h.getBlockState(at).getLightEmission() != light) {
            bad.add(name + ": light " + h.getBlockState(at).getLightEmission() + " != " + light);
        }
        ItemStack filled = use(h.getLevel(), fp, new ItemStack(Items.BUCKET));
        if (!filled.is(bucket)) {
            bad.add(name + ": empty bucket picked up " + filled);
        }
        if (!h.getBlockState(at).getFluidState().isEmpty()) {
            bad.add(name + ": fluid still there after pickup");
        }
    }

    public static void bucketsPlaceAndPickUp(GameTestHelper h) {
        List<Case> all = cases();
        h.assertTrue(all.size() >= 10, "fluids registered: " + all.size());
        List<String> bad = new ArrayList<>();
        for (Case c : all) {
            pourAndScoop(h, new BlockPos(3, 1, 3), c.name(), c.bucket(), c.source(), c.type().getLightLevel(), bad);
        }
        h.assertTrue(bad.isEmpty(), "buckets: " + bad);
        h.succeed();
    }

    /** A one-wide stone trough along x at row {@code z}, floor at y=1. */
    public static void trough(GameTestHelper h, int z) {
        for (int x = 0; x <= 6; x++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            h.setBlock(new BlockPos(x, 2, z - 1), Blocks.STONE);
            h.setBlock(new BlockPos(x, 3, z - 1), Blocks.STONE);
            h.setBlock(new BlockPos(x, 2, z + 1), Blocks.STONE);
            h.setBlock(new BlockPos(x, 3, z + 1), Blocks.STONE);
        }
        h.setBlock(new BlockPos(0, 2, z), Blocks.STONE);
        h.setBlock(new BlockPos(6, 2, z), Blocks.STONE);
    }

    /** Cold fluid spreads within a few ticks like water; hot fluid waits like lava, then spreads and burns a pig. */
    public static void hotFlowsSlowColdFlowsFast(GameTestHelper h) {
        Case cold = cases().stream().filter(c -> !c.hot()).findFirst().orElseThrow();
        Case hot = cases().stream().filter(Case::hot).findFirst().orElseThrow();
        trough(h, 1);
        trough(h, 5);
        BlockPos cs = new BlockPos(2, 2, 1);
        BlockPos hs = new BlockPos(2, 2, 5);
        h.setBlock(cs, cold.source().defaultFluidState().createLegacyBlock());
        h.setBlock(hs, hot.source().defaultFluidState().createLegacyBlock());
        var pig = h.spawn(EntityType.PIG, hs);
        String[] early = {null};
        h.succeedWhen(() -> {
            if (early[0] == null) {
                h.assertTrue(h.getTick() >= 12, "waiting for tick 12");
                StringBuilder b = new StringBuilder();
                if (!h.getBlockState(cs.east()).getFluidState().getType().isSame(cold.source())) {
                    b.append(cold.name()).append(" did not spread within 12 ticks; ");
                }
                if (!h.getBlockState(hs.east()).getFluidState().isEmpty()) {
                    b.append(hot.name()).append(" spread faster than lava; ");
                }
                early[0] = b.toString();
            }
            h.assertTrue(early[0].isEmpty(), early[0]);
            h.assertTrue(h.getBlockState(hs.east()).getFluidState().getType().isSame(hot.source()), hot.name() + " spreads after its slow tick");
            h.assertTrue(pig.isOnFire() || pig.getHealth() < pig.getMaxHealth(), "pig in " + hot.name() + " burns");
        });
    }
}
