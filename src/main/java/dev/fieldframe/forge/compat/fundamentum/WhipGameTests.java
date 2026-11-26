package dev.fieldframe.forge.compat.fundamentum;

import com.fundamentum.whip.WhipDefinition;
import com.fundamentum.whip.Whips;
import com.mojang.authlib.GameProfile;
import dev.fieldframe.forge.feature.FeatureContent;
import dev.fieldframe.forge.feature.ItemEnergy;
import dev.fieldframe.forge.feature.endertech.PadBlockEntity;
import dev.fieldframe.forge.item.ChargedGearItem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

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
        eq(h, ItemEnergy.get(fp.getMainHandItem()), (long) (item.charge().capacity() - item.charge().perUse()), "RF after one swing");
        Whips.end(fp);
        h.assertFalse(FieldWhips.paid(fp), "paid flag clears when the swing ends");

        fp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FieldWhips.ARC_WHIP.get()));
        h.assertTrue(Whips.swing(fp), "empty whip still swings");
        h.assertFalse(FieldWhips.paid(fp), "empty swing is unpaid");
        eq(h, ItemEnergy.get(fp.getMainHandItem()), 0L, "RF stays at zero");
        Whips.end(fp);

        BlockPos pad = new BlockPos(3, 1, 3);
        h.setBlock(pad, FeatureContent.PADS.get(1).get().defaultBlockState()); // charge_pad_resonant
        PadBlockEntity be = (PadBlockEntity) h.getBlockEntity(pad);
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
        eq(h, def.count(), 3, "hooks out at once");
        h.assertTrue(def.texture() != null && def.texture().getNamespace().equals("fieldframe"), "own hook texture");
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-hook-test]"));
        h.assertFalse(FieldHooks.floating(fp), "no field without a latched hook");
        h.succeed();
    }


    /** Recipes are always present, and the native Tesla upgrade conserves the Arc Whip's state. */
    public static void gearRecipesKeepUpgradeState(GameTestHelper h) {
        var manager = h.getLevel().getRecipeManager();
        for (String name : new String[] {"arc_whip", "tesla_whip", "graviton_hook"}) {
            h.assertTrue(manager.byKey(new net.minecraft.resources.ResourceLocation("fieldframe", "crafting/" + name)).isPresent(),
                    name + " recipe loads without an optional-mod condition");
        }
        var menu = new net.minecraft.world.inventory.AbstractContainerMenu(null, 0) {
            @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
        };
        var input = new net.minecraft.world.inventory.TransientCraftingContainer(menu, 3, 3);
        ItemStack arc = FieldWhips.ARC_WHIP.get().charged();
        arc.setHoverName(net.minecraft.network.chat.Component.literal("Retained whip"));
        arc.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 2);
        arc.getOrCreateTag().putString("upgrade_test", "retained");
        long energy = ItemEnergy.get(arc);
        h.assertTrue(energy > 0, "source whip is charged");
        var kit = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation("fieldframe", "reinforced_upgrade_kit"));
        h.assertTrue(kit != null, "upgrade kit is registered");
        input.setItem(8, arc);
        input.setItem(0, new ItemStack(kit));
        var recipe = manager.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, h.getLevel()).orElseThrow();
        h.assertTrue(recipe instanceof dev.fieldframe.forge.recipe.KeepingShapelessRecipe, "native keeping recipe selected");
        ItemStack result = recipe.assemble(input, h.getLevel().registryAccess());
        h.assertTrue(result.is(FieldWhips.TESLA_WHIP.get()), "upgrade makes a Tesla Whip");
        eq(h, ItemEnergy.get(result), energy, "upgrade retains RF");
        eq(h, result.getTag(), arc.getTag(), "upgrade retains name, enchantment and custom tag");
        h.assertTrue(arc.is(FieldWhips.ARC_WHIP.get()), "craft preview does not mutate the source");
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var serializer = dev.fieldframe.forge.recipe.KeepingShapelessRecipe.SERIALIZER;
            serializer.toNetwork(buffer, (dev.fieldframe.forge.recipe.KeepingShapelessRecipe) recipe);
            var decoded = serializer.fromNetwork(recipe.getId(), buffer);
            h.assertTrue(decoded.matches(input, h.getLevel()), "recipe matches after network round trip");
            ItemStack synced = decoded.assemble(input, h.getLevel().registryAccess());
            h.assertTrue(synced.is(FieldWhips.TESLA_WHIP.get()), "synced upgrade keeps its output");
            eq(h, synced.getTag(), result.getTag(), "network round trip retains upgrade state");
        } finally {
            buffer.release();
        }
        h.succeed();
    }

    private static void eq(GameTestHelper h, Object actual, Object expected, String what) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(what + ": expected " + expected + ", got " + actual);
        }
    }
}
