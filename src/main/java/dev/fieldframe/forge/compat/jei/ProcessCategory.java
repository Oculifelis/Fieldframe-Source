package dev.fieldframe.forge.compat.jei;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.forge.recipe.FfProcessRecipe;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * One JEI page per process recipe: up to four inputs (catalysts marked), an optional fluid input, up to six results
 * with their chances, an optional fluid result, and the duration and RF cost underneath.
 */
final class ProcessCategory implements IRecipeCategory<FfProcessRecipe> {
    static final int W = 162;
    static final int H = 56;
    private final RecipeType<FfProcessRecipe> type;
    private final FfId family;
    private final IDrawable icon;
    private final IDrawable arrow;
    private final Item module;
    private final IGuiHelper gui;

    ProcessCategory(RecipeType<FfProcessRecipe> type, FfId family, Item module, IGuiHelper gui) {
        this.type = type;
        this.family = family;
        this.module = module;
        this.gui = gui;
        this.icon = gui.createDrawableItemStack(new ItemStack(module));
        this.arrow = gui.getRecipeArrow();
    }

    @Override
    public RecipeType<FfProcessRecipe> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        // Module items share their module's id; the module name key is what the rest of Fieldframe shows.
        var key = BuiltInRegistries.ITEM.getKey(module);
        return Component.translatable("module." + key.getNamespace() + "." + key.getPath());
    }

    @Override
    public IDrawable getBackground() {
        return gui.createBlankDrawable(W, H);
    }

    @Override
    public int getWidth() {
        return W;
    }

    @Override
    public int getHeight() {
        return H;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    static List<Fluid> fluids(FfProcessRecipe.FluidIn in) {
        List<Fluid> out = new ArrayList<>();
        if (in.fluid() != null) {
            BuiltInRegistries.FLUID.getOptional(in.fluid()).ifPresent(out::add);
        }
        java.util.Optional.ofNullable(in.tag()).ifPresent(id -> {
            TagKey<Fluid> tag = TagKey.create(net.minecraft.core.registries.Registries.FLUID, id);
            BuiltInRegistries.FLUID.stream().filter(f -> f != Fluids.EMPTY && f.isSource(f.defaultFluidState())
                    && f.defaultFluidState().is(tag)).forEach(out::add);
        });
        return out;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder b, FfProcessRecipe r, IFocusGroup focuses) {
        List<FfProcessRecipe.Input> inputs = r.inputs();
        for (int i = 0; i < inputs.size(); i++) {
            FfProcessRecipe.Input in = inputs.get(i);
            List<ItemStack> stacks = java.util.Arrays.stream(in.ingredient().getItems()).map(st -> st.copyWithCount(in.count())).toList();
            IRecipeSlotBuilder slot = b.addSlot(mezz.jei.api.recipe.RecipeIngredientRole.INPUT, 1 + (i % 2) * 18, 1 + (i / 2) * 18).setBackground(gui.getSlotDrawable(), -1, -1)
                    .addIngredients(VanillaTypes.ITEM_STACK, stacks);
            if (in.catalyst()) {
                slot.addTooltipCallback((view, tip) -> tip.add(Component.translatable("fieldframe.jei.catalyst").withStyle(ChatFormatting.GOLD)));
            }
        }
        if (r.fluidInput() != null) {
            FfProcessRecipe.FluidIn fin = r.fluidInput();
            IRecipeSlotBuilder slot = b.addSlot(mezz.jei.api.recipe.RecipeIngredientRole.INPUT, 41, 10).setBackground(gui.getSlotDrawable(), -1, -1);
            for (Fluid f : fluids(fin)) {
                slot.addFluidStack(f, fin.amount());
            }
        }
        List<FfProcessRecipe.Result> results = r.results();
        for (int i = 0; i < results.size(); i++) {
            FfProcessRecipe.Result res = results.get(i);
            IRecipeSlotBuilder slot = b.addSlot(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT, 91 + (i % 3) * 18, 1 + (i / 3) * 18).setBackground(gui.getSlotDrawable(), -1, -1)
                    .addIngredients(VanillaTypes.ITEM_STACK, List.of(res.stack()));
            if (res.chance() < 1f) {
                int pct = Math.round(res.chance() * 100);
                slot.addTooltipCallback((view, tip) -> tip.add(Component.translatable("fieldframe.jei.chance", pct).withStyle(ChatFormatting.GOLD)));
            }
        }
        if (!r.fluidResult().isEmpty()) {
            b.addSlot(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT, 145, 10).setBackground(gui.getSlotDrawable(), -1, -1).addFluidStack(r.fluidResult().getFluid(), r.fluidResult().getAmount());
        }
    }

    @Override
    public void draw(FfProcessRecipe r, IRecipeSlotsView view, GuiGraphics g, double mx, double my) {
        arrow.draw(g, 62, 10);
        String text = Component.translatable("fieldframe.jei.cost", String.format("%.1f", r.duration() / 20.0), r.energy(),
                (long) r.duration() * r.energy()).getString();
        g.drawString(Minecraft.getInstance().font, text, 1, H - 9, 0xFF606060, false);
    }
}
