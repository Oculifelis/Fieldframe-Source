package dev.fieldframe.neoforge.compat.fundamentum;

import com.fundamentum.whip.WhipDefinition;
import com.fundamentum.whip.Whips;
import com.mojang.authlib.GameProfile;
import dev.fieldframe.neoforge.feature.FeatureContent;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import dev.fieldframe.neoforge.feature.endertech.PadBlockEntity;
import dev.fieldframe.neoforge.item.ChargedGearItem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

/** Fieldframe's required-library gear checks run in every GameTest suite. */
public final class WhipGameTests {
    private WhipGameTests() {}

    /**
     * Both whips are Fundamentum whips with their own reach; a charged swing pays its RF and is charged, an empty one
     * swings unpaid without going below zero; a charge pad fills a whip lying on it.
     */
    public static void whipsPayPerSwingAndCharge(GameTestHelper h) {
        ItemStack arc = new ItemStack(FieldWhips.ARC_WHIP.get()), tesla = new ItemStack(FieldWhips.TESLA_WHIP.get());
        WhipDefinition a = Whips.definition(arc), t = Whips.definition(tesla);
        h.assertTrue(a != null && t != null, "both are whips");
        h.assertTrue(t.tuning.length() > a.tuning.length() && t.tuning.damage() > a.tuning.damage(), "tier 2 reaches further and hits harder");

        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-whip-test]"));
        ChargedGearItem item = FieldWhips.TESLA_WHIP.get();
        ItemStack held = item.charged();
        fp.setItemInHand(InteractionHand.MAIN_HAND, held);
        h.assertTrue(Whips.swing(fp), "charged swing starts");
        h.assertTrue(FieldWhips.paid(fp), "charged swing is paid");
        h.assertValueEqual(ItemEnergy.get(fp.getMainHandItem()), (long) (item.charge().capacity() - item.charge().perUse()), "RF after one swing");
        Whips.end(fp);
        h.assertFalse(FieldWhips.paid(fp), "paid flag clears when the swing ends");

        fp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FieldWhips.ARC_WHIP.get()));
        h.assertTrue(Whips.swing(fp), "empty whip still swings");
        h.assertFalse(FieldWhips.paid(fp), "empty swing is unpaid");
        h.assertValueEqual(ItemEnergy.get(fp.getMainHandItem()), 0L, "RF stays at zero");
        Whips.end(fp);

        BlockPos pad = new BlockPos(3, 1, 3);
        h.setBlock(pad, FeatureContent.PADS.get(1).get().defaultBlockState()); // charge_pad_resonant
        PadBlockEntity be = h.getBlockEntity(pad, PadBlockEntity.class);
        be.buffer().receive(100_000, false);
        ItemEntity ie = new ItemEntity(h.getLevel(), h.absolutePos(pad).getX() + 0.5, h.absolutePos(pad).getY() + 1.2,
                h.absolutePos(pad).getZ() + 0.5, new ItemStack(FieldWhips.ARC_WHIP.get()));
        ie.setNoPickUpDelay();
        h.getLevel().addFreshEntity(ie);
        h.succeedWhen(() -> {
            long e = ItemEnergy.get(ie.getItem());
            h.assertTrue(e > 0 && e + be.buffer().stored() == 100_000, "pad charges the whip, conserved: whip " + e + " pad " + be.buffer().stored());
        });
    }

    /** The Graviton Hook's data loads as a three-hook Fundamentum hook with its own texture, and it starts with no field. */
    public static void gravitonHookIsAFundamentumHook(GameTestHelper h) {
        com.fundamentum.hook.Hooks.Def def = com.fundamentum.hook.Hooks.def(new ItemStack(FieldHooks.GRAVITON_HOOK.get()));
        h.assertTrue(def != null, "graviton_hook has a hook definition");
        h.assertValueEqual(def.count(), 3, "hooks out at once");
        h.assertTrue(def.texture() != null && def.texture().getNamespace().equals("fieldframe"), "own hook texture");
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-hook-test]"));
        h.assertFalse(FieldHooks.floating(fp), "no field without a latched hook");
        h.succeed();
    }
}
