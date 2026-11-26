package dev.fieldframe.frameworks.compat.jei;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.frameworks.FoundryAlloys;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Foundry alloys: up to four molten metals in their ratio make the alloy (all in the frame's molten pool). */
final class AlloyCategory implements IRecipeCategory<FoundryAlloys.Alloy> {
    private final RecipeType<FoundryAlloys.Alloy> type;
    private final IDrawable icon;
    private final IDrawable arrow;

    AlloyCategory(RecipeType<FoundryAlloys.Alloy> type, Item icon, IGuiHelper gui) {
        this.type = type;
        this.icon = gui.createDrawableItemLike(icon);
        this.arrow = gui.getRecipeArrow();
    }

    @Override
    public RecipeType<FoundryAlloys.Alloy> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("fieldframe_frameworks.jei.alloys");
    }

    @Override
    public int getWidth() {
        return 150;
    }

    @Override
    public int getHeight() {
        return 32;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder b, FoundryAlloys.Alloy a, IFocusGroup focuses) {
        for (int i = 0; i < a.inputs().size(); i++) {
            FoundryAlloys.Part p = a.inputs().get(i);
            // Each slot is drawn full: its amount is the whole slot (JEI would otherwise scale it against a bucket).
            b.addInputSlot(1 + i * 18, 1).setStandardSlotBackground().setFluidRenderer(p.amount(), false, 16, 16)
                    .addFluidStack(fluid(p.fluid()), p.amount());
        }
        b.addOutputSlot(111, 1).setStandardSlotBackground().setFluidRenderer(a.result().amount(), false, 16, 16)
                .addFluidStack(fluid(a.result().fluid()), a.result().amount());
    }

    @Override
    public void draw(FoundryAlloys.Alloy a, IRecipeSlotsView view, GuiGraphics g, double mx, double my) {
        arrow.draw(g, 80, 1);
        var font = Minecraft.getInstance().font;
        g.drawString(font, Component.translatable("fieldframe_frameworks.jei.alloy_hint").getString(), 1, 22, 0xFF606060, false);
    }

    static Fluid fluid(FfId id) {
        return BuiltInRegistries.FLUID.getOptional(ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path())).orElse(Fluids.EMPTY);
    }
}
