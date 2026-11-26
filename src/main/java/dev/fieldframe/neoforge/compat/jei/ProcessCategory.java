package dev.fieldframe.neoforge.compat.jei;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.neoforge.recipe.FfProcessRecipe;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private final IRecipeType<FfProcessRecipe> type;
    private final FfId family;
    private final IDrawable icon;
    private final IDrawable arrow;
    private final Item module;

    ProcessCategory(IRecipeType<FfProcessRecipe> type, FfId family, Item module, IGuiHelper gui) {
        this.type = type;
        this.family = family;
        this.module = module;
        this.icon = gui.createDrawableItemLike(module);
        this.arrow = gui.getRecipeArrow();
    }

    @Override
    public IRecipeType<FfProcessRecipe> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        // Module items share their module's id; the module name key is what the rest of Fieldframe shows.
        var key = BuiltInRegistries.ITEM.getKey(module);
        return Component.translatable("module." + key.getNamespace() + "." + key.getPath());
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
        in.fluid().ifPresent(id -> BuiltInRegistries.FLUID.getOptional(id).ifPresent(out::add));
        in.tag().ifPresent(id -> {
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
            List<ItemStack> stacks = in.ingredient().items().map(h -> new ItemStack(h, in.count())).toList();
            IRecipeSlotBuilder slot = b.addInputSlot(1 + (i % 2) * 18, 1 + (i / 2) * 18).setStandardSlotBackground()
                    .addIngredients(VanillaTypes.ITEM_STACK, stacks);
            if (in.catalyst()) {
                slot.addRichTooltipCallback((view, tip) -> tip.add(Component.translatable("fieldframe.jei.catalyst").withStyle(ChatFormatting.GOLD)));
            }
        }
        r.fluidInput().ifPresent(fin -> {
            IRecipeSlotBuilder slot = b.addInputSlot(41, 10).setStandardSlotBackground();
            for (Fluid f : fluids(fin)) {
                slot.addFluidStack(f, fin.amount());
            }
        });
        List<FfProcessRecipe.Result> results = r.results();
        for (int i = 0; i < results.size(); i++) {
            FfProcessRecipe.Result res = results.get(i);
            IRecipeSlotBuilder slot = b.addOutputSlot(91 + (i % 3) * 18, 1 + (i / 3) * 18).setStandardSlotBackground()
                    .addIngredients(VanillaTypes.ITEM_STACK, List.of(res.stack()));
            if (res.chance() < 1f) {
                int pct = Math.round(res.chance() * 100);
                slot.addRichTooltipCallback((view, tip) -> tip.add(Component.translatable("fieldframe.jei.chance", pct).withStyle(ChatFormatting.GOLD)));
            }
        }
        if (!r.fluidResult().isEmpty()) {
            b.addOutputSlot(145, 10).setStandardSlotBackground().addFluidStack(r.fluidResult().getFluid(), r.fluidResult().getAmount());
        }
    }

    @Override
    public void draw(FfProcessRecipe r, IRecipeSlotsView view, GuiGraphicsExtractor g, double mx, double my) {
        arrow.draw(g, 62, 10);
        String text = Component.translatable("fieldframe.jei.cost", String.format("%.1f", r.duration() / 20.0), r.energy(),
                (long) r.duration() * r.energy()).getString();
        g.text(Minecraft.getInstance().font, text, 1, H - 9, 0xFF606060, false);
    }
}
