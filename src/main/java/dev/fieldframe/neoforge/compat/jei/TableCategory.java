package dev.fieldframe.neoforge.compat.jei;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/** A data table (Treadmill animals, Galvanic electrodes, temperatures): one or two slots and two lines per row. */
final class TableCategory implements IRecipeCategory<TableView> {
    private final IRecipeType<TableView> type;
    private final String titleKey;
    private final IDrawable icon;

    TableCategory(IRecipeType<TableView> type, String titleKey, Item icon, IGuiHelper gui) {
        this.type = type;
        this.titleKey = titleKey;
        this.icon = gui.createDrawableItemLike(icon);
    }

    @Override
    public IRecipeType<TableView> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(titleKey);
    }

    @Override
    public int getWidth() {
        return 150;
    }

    @Override
    public int getHeight() {
        return 22;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder b, TableView v, IFocusGroup focuses) {
        IRecipeSlotBuilder slot = b.addInputSlot(1, 3).setStandardSlotBackground();
        slot.addIngredients(VanillaTypes.ITEM_STACK, v.items());
        for (Fluid fl : v.fluids()) {
            slot.addFluidStack(fl, 1000);
        }
        if (!v.second().isEmpty()) {
            b.addInputSlot(20, 3).setStandardSlotBackground().addIngredients(VanillaTypes.ITEM_STACK, v.second());
        }
    }

    @Override
    public void draw(TableView v, IRecipeSlotsView view, GuiGraphicsExtractor g, double mx, double my) {
        var font = Minecraft.getInstance().font;
        int x = v.second().isEmpty() ? 22 : 41;
        g.text(font, font.plainSubstrByWidth(v.line1().getString(), getWidth() - x), x, 2, 0xFF404040, false);
        g.text(font, font.plainSubstrByWidth(v.line2().getString(), getWidth() - x), x, 12, 0xFF606060, false);
    }
}
