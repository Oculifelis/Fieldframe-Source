package dev.fieldframe.forge.compat.jei;

import dev.fieldframe.forge.client.PatternScreen;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Drag an item from JEI onto a square of the Recipe Assembler's recipe picture. */
final class PatternGhostHandler implements IGhostIngredientHandler<PatternScreen> {
    @Override
    public <I> List<Target<I>> getTargetsTyped(PatternScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
        if (ingredient.getType() != VanillaTypes.ITEM_STACK) {
            return List.of();
        }
        List<Target<I>> out = new ArrayList<>();
        int[][] rects = gui.cellRects();
        for (int i = 0; i < rects.length; i++) {
            final int cell = i;
            final Rect2i area = new Rect2i(rects[i][0], rects[i][1], rects[i][2], rects[i][3]);
            out.add(new Target<I>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I item) {
                    if (item instanceof ItemStack stack) {
                        gui.setCell(cell, stack);
                    }
                }
            });
        }
        return out;
    }

    @Override
    public void onComplete() {
    }
}
