package dev.fieldframe.forge.compat.jei;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/** Engine fuels (from data/<ns>/fieldframe/fuels): the fuel, the engine that burns it and the RF it gives. */
final class FuelCategory implements IRecipeCategory<FuelView> {
    private final RecipeType<FuelView> type;
    private final IDrawable icon;
    private final IGuiHelper gui;

    FuelCategory(RecipeType<FuelView> type, Item icon, IGuiHelper gui) {
        this.type = type;
        this.gui = gui;
        this.icon = gui.createDrawableItemStack(new net.minecraft.world.item.ItemStack(icon));
    }

    @Override
    public RecipeType<FuelView> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("fieldframe.jei.fuels");
    }

    @Override
    public IDrawable getBackground() {
        return gui.createBlankDrawable(150, 22);
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
    public void setRecipe(IRecipeLayoutBuilder b, FuelView f, IFocusGroup focuses) {
        IRecipeSlotBuilder slot = b.addSlot(mezz.jei.api.recipe.RecipeIngredientRole.INPUT, 1, 3).setBackground(gui.getSlotDrawable(), -1, -1);
        if (f.fluid()) {
            for (Fluid fl : f.fluids()) {
                slot.addFluidStack(fl, 1000);
            }
        } else {
            slot.addIngredients(VanillaTypes.ITEM_STACK, f.items());
        }
    }

    @Override
    public void draw(FuelView f, IRecipeSlotsView view, GuiGraphics g, double mx, double my) {
        var font = Minecraft.getInstance().font;
        g.drawString(font, Component.translatable("module." + f.engine().namespace() + "." + f.engine().path()).getString(), 22, 2, 0xFF404040, false);
        String rf = f.fluid() ? Component.translatable("fieldframe.jei.rf_per_mb", f.rf()).getString()
                : Component.translatable("fieldframe.jei.rf_total", f.rf()).getString();
        g.drawString(font, rf, 22, 12, 0xFF606060, false);
    }
}
