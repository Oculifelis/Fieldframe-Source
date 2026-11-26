package dev.fieldframe.neoforge.recipe;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * A shapeless recipe whose result keeps the components (charge, enchantments, name) of the stack that matches its
 * first ingredient: the 1.21.1 stand-in for 26.x's {@code minecraft:crafting_transmute}, used by the Arc Whip to
 * Tesla Whip upgrade. JSON is a plain shapeless recipe with type {@code fieldframe:keeping_shapeless}.
 */
public final class KeepingShapelessRecipe extends ShapelessRecipe {
    public KeepingShapelessRecipe(String group, CraftingBookCategory category, ItemStack result, NonNullList<Ingredient> ingredients) {
        super(group, category, result, ingredients);
    }

    private static KeepingShapelessRecipe of(ShapelessRecipe r) {
        return new KeepingShapelessRecipe(r.getGroup(), r.category(), r.getResultItem(null), r.getIngredients());
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack base = super.assemble(input, registries);
        Ingredient source = getIngredients().get(0);
        for (int i = 0; i < input.size(); i++) {
            ItemStack in = input.getItem(i);
            if (!in.isEmpty() && source.test(in)) {
                return in.transmuteCopy(base.getItem(), base.getCount());
            }
        }
        return base;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }

    public static final RecipeSerializer<KeepingShapelessRecipe> SERIALIZER = new RecipeSerializer<>() {
        private final MapCodec<KeepingShapelessRecipe> codec =
                RecipeSerializer.SHAPELESS_RECIPE.codec().xmap(KeepingShapelessRecipe::of, r -> r);
        private final StreamCodec<RegistryFriendlyByteBuf, KeepingShapelessRecipe> stream =
                RecipeSerializer.SHAPELESS_RECIPE.streamCodec().map(KeepingShapelessRecipe::of, r -> r);

        @Override
        public MapCodec<KeepingShapelessRecipe> codec() {
            return codec;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, KeepingShapelessRecipe> streamCodec() {
            return stream;
        }
    };
}
