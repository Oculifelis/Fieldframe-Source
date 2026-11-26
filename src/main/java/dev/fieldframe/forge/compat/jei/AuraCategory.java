package dev.fieldframe.forge.compat.jei;

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
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/** Aura Chamber recipes: the batch of items and the effect it gives nearby players, and for how long. */
final class AuraCategory implements IRecipeCategory<AuraView> {
    private final RecipeType<AuraView> type;
    private final IDrawable icon;

    AuraCategory(RecipeType<AuraView> type, Item icon, IGuiHelper gui) {
        this.type = type;
        this.icon = gui.createDrawableItemStack(new net.minecraft.world.item.ItemStack(icon));
    }

    @Override
    public RecipeType<AuraView> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("fieldframe.jei.aura");
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
    public void setRecipe(IRecipeLayoutBuilder b, AuraView f, IFocusGroup focuses) {
        b.addInputSlot(1, 3).setStandardSlotBackground().addIngredients(VanillaTypes.ITEM_STACK, f.items());
    }

    @Override
    public void draw(AuraView f, IRecipeSlotsView view, GuiGraphics g, double mx, double my) {
        var font = Minecraft.getInstance().font;
        var effect = java.util.Optional.ofNullable(net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getValue(
                new net.minecraft.resources.ResourceLocation(f.effect().namespace(), f.effect().path())));
        Component name = effect.isPresent() ? Component.translatable(effect.get().getDescriptionId()) : Component.literal(f.effect().toString());
        g.drawString(font, Component.translatable("fieldframe.jei.aura_effect", name, (f.duration() / 20) + "s").getString(), 22, 7, 0xFF404040, false);
    }
}
