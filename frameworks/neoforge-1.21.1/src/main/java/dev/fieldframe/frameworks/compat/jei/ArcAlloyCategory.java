package dev.fieldframe.frameworks.compat.jei;

import dev.fieldframe.frameworks.ArcAlloys;
import mezz.jei.api.constants.VanillaTypes;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Arc alloys: items (or any item of a tag) in Orange storage, worked by the Arc Furnace's electrodes into the result. */
final class ArcAlloyCategory implements IRecipeCategory<ArcAlloys.Alloy> {
    private final RecipeType<ArcAlloys.Alloy> type;
    private final IDrawable icon;
    private final IDrawable arrow;

    ArcAlloyCategory(RecipeType<ArcAlloys.Alloy> type, Item icon, IGuiHelper gui) {
        this.type = type;
        this.icon = gui.createDrawableItemLike(icon);
        this.arrow = gui.getRecipeArrow();
    }

    @Override
    public RecipeType<ArcAlloys.Alloy> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("fieldframe_frameworks.jei.arc_alloys");
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
    public void setRecipe(IRecipeLayoutBuilder b, ArcAlloys.Alloy a, IFocusGroup focuses) {
        for (int i = 0; i < a.inputs().size(); i++) {
            // Four a row; a fifth input or more goes on the second row, over the hint.
            b.addInputSlot(1 + (i % 4) * 18, 1 + (i / 4) * 18).setStandardSlotBackground()
                    .addIngredients(VanillaTypes.ITEM_STACK, stacks(a.inputs().get(i)));
        }
        b.addOutputSlot(111, 1).setStandardSlotBackground().addIngredients(VanillaTypes.ITEM_STACK, stacks(a.result()));
    }

    @Override
    public void draw(ArcAlloys.Alloy a, IRecipeSlotsView view, GuiGraphics g, double mx, double my) {
        arrow.draw(g, 80, 1);
        if (a.inputs().size() <= 4) {
            var font = Minecraft.getInstance().font;
            g.drawString(font, Component.translatable("fieldframe_frameworks.jei.arc_alloy_hint").getString(), 1, 22, 0xFF606060, false);
        }
    }

    static List<ItemStack> stacks(ArcAlloys.Part p) {
        List<ItemStack> st = new ArrayList<>();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(p.id().namespace(), p.id().path());
        if (p.tag()) {
            TagKey<Item> key = TagKey.create(Registries.ITEM, id);
            BuiltInRegistries.ITEM.stream().filter(i -> i != Items.AIR && new ItemStack(i).is(key)).forEach(i -> st.add(new ItemStack(i, p.count())));
        } else {
            BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR).ifPresent(i -> st.add(new ItemStack(i, p.count())));
        }
        return st;
    }
}
