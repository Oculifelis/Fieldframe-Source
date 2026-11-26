package dev.fieldframe.forge.recipe;

import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** A native shapeless upgrade that retains the first ingredient's stack state. */
public final class KeepingShapelessRecipe extends ShapelessRecipe {
    public KeepingShapelessRecipe(ResourceLocation id, String group, CraftingBookCategory category,
                                 ItemStack result, NonNullList<Ingredient> ingredients) {
        super(id, group, category, result, ingredients);
    }

    private static KeepingShapelessRecipe of(ShapelessRecipe recipe) {
        return new KeepingShapelessRecipe(recipe.getId(), recipe.getGroup(), recipe.category(),
                recipe.getResultItem(null), recipe.getIngredients());
    }

    @Override
    public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
        ItemStack result = super.assemble(input, registries);
        Ingredient source = getIngredients().get(0);
        for (int slot = 0; slot < input.getContainerSize(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (!stack.isEmpty() && source.test(stack)) {
                // Full stack serialization also retains Forge capability state.
                CompoundTag state = stack.save(new CompoundTag());
                state.putString("id", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
                state.putByte("Count", (byte) result.getCount());
                return ItemStack.of(state);
            }
        }
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }

    public static final RecipeSerializer<KeepingShapelessRecipe> SERIALIZER = new RecipeSerializer<>() {
        @Override
        public KeepingShapelessRecipe fromJson(ResourceLocation id, JsonObject json) {
            return of(RecipeSerializer.SHAPELESS_RECIPE.fromJson(id, json));
        }

        @Override
        public KeepingShapelessRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return of(RecipeSerializer.SHAPELESS_RECIPE.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, KeepingShapelessRecipe recipe) {
            RecipeSerializer.SHAPELESS_RECIPE.toNetwork(buffer, recipe);
        }
    };
}
