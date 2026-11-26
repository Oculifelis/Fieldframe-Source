package dev.fieldframe.forge.compat.jei;

import dev.fieldframe.forge.client.FilterScreen;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Drag an item from JEI onto the filter editor's entries to add it. */
final class FilterGhostHandler implements IGhostIngredientHandler<FilterScreen> {
    @Override
    public <I> List<Target<I>> getTargetsTyped(FilterScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
        if (ingredient.getType() != VanillaTypes.ITEM_STACK) {
            return List.of();
        }
        List<Target<I>> out = new ArrayList<>();
        int[] r = gui.gridRect();
        final Rect2i area = new Rect2i(r[0], r[1], r[2], r[3]);
        out.add(new Target<I>() {
            @Override
            public Rect2i getArea() {
                return area;
            }

            @Override
            public void accept(I item) {
                if (item instanceof ItemStack stack) {
                    gui.add(stack, false);
                }
            }
        });
        return out;
    }

    @Override
    public void onComplete() {
    }
}
