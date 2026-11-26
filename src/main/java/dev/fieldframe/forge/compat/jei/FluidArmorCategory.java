package dev.fieldframe.forge.compat.jei;

import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FluidArmorEffects;
import dev.fieldframe.forge.feature.flora.FluidArmorItem;
import dev.fieldframe.forge.feature.flora.FluidArmorRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

/**
 * Fluid armor: a fluid + any fluid armor piece in the Armor Infuser gives the filled piece. Below the slots, one card
 * per effect in a two-column grid: an icon (the effect's own icon, or the partner fluid's bucket for FLORA pairs), the
 * name, and a green or red edge for good or harmful. Hovering a card gives the full line.
 */
final class FluidArmorCategory implements IRecipeCategory<FluidArmorRows.Row> {
    private static final int W = 176;
    private static final int GRID_Y = 24;
    private static final int COLS = 2, ROWS = 4, GAP = 4;
    private static final int CELL_W = (W - GAP) / COLS, CELL_H = 20;
    private static final int CARD = 0xFFD6D6D6, CARD_EDGE = 0xFF8B8B8B, INK = 0xFF3A3A3A, INK_DIM = 0xFF6A6A6A;
    private static final int GOOD = 0xFF3C8A3C, BAD = 0xFFB03434;

    private final RecipeType<FluidArmorRows.Row> type;
    private final IDrawable icon;
    private final Item infuser;

    FluidArmorCategory(RecipeType<FluidArmorRows.Row> type, Item icon, Item infuser, IGuiHelper gui) {
        this.type = type;
        this.icon = gui.createDrawableItemLike(icon);
        this.infuser = infuser;
    }

    @Override
    public RecipeType<FluidArmorRows.Row> getRecipeType() {
        return type;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("fieldframe.jei.fluid_armor");
    }

    @Override
    public int getWidth() {
        return W;
    }

    @Override
    public int getHeight() {
        return GRID_Y + ROWS * (CELL_H + 2);
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder b, FluidArmorRows.Row r, IFocusGroup focuses) {
        long mb = r.showMb();
        b.addInputSlot(1, 1).setStandardSlotBackground().addFluidStack(r.fluid(), mb);
        b.addInputSlot(21, 1).setStandardSlotBackground().addIngredients(VanillaTypes.ITEM_STACK, FluidArmorRows.armor());
        if (infuser != null) {
            b.addSlot(RecipeIngredientRole.CATALYST, 51, 1).setStandardSlotBackground()
                    .addIngredients(VanillaTypes.ITEM_STACK, java.util.List.of(new ItemStack(infuser)));
        }
        b.addOutputSlot(81, 1).setStandardSlotBackground().addIngredients(VanillaTypes.ITEM_STACK, FluidArmorRows.filled(r, mb));
        // FLORA cards show the partner fluid's own texture: a bare render-only fluid slot (JEI draws and names the
        // fluid), with the card's effect lines added to that slot's tooltip.
        List<Card> cards = cards(r);
        int cols = cols(r, cards.size());
        for (int i = 0; i < Math.min(cards.size(), cols * ROWS); i++) {
            Card c = cards.get(i);
            if (c.fluid() != null) {
                b.addSlot(RecipeIngredientRole.RENDER_ONLY, cellX(i, cols) + 5, cellY(i, cols) + 2)
                        .addFluidStack(c.fluid(), 1000)
                        .addRichTooltipCallback((view, t) -> t.addAll(c.tooltip()));
            }
        }
    }

    /** One card: what to draw and what the tooltip says. One of {@code sprite} / {@code item} / {@code fluid} is set (fluid: drawn by a JEI slot). */
    private record Card(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, ItemStack item, Fluid fluid, Component name, Component sub,
                        boolean harmful, List<Component> tooltip) {
    }

    private static List<Card> cards(FluidArmorRows.Row r) {
        List<Card> out = new ArrayList<>();
        if (r.flora()) {
            for (Flora.Effect e : r.pairs()) {
                Flora.Fluid other = e.a().id().equals(r.id()) ? e.b() : e.a();
                Component partner = Component.translatable("fieldframe.flora.short." + other.name().toLowerCase(Locale.ROOT));
                Component effect = Component.translatable("fieldframe.flora.effect." + e.key());
                Fluid f = FluidArmorRows.fluid(other.id());
                out.add(new Card(null, f == null ? new ItemStack(Items.BUCKET) : null, f, effect, Component.literal("+ ").append(partner), e.negative(), List.of(
                        Component.translatable("fieldframe.jei.fluid_armor.pair", partner, effect)
                                .withStyle(e.negative() ? ChatFormatting.RED : ChatFormatting.DARK_GREEN),
                        Component.translatable("fieldframe.jei.fluid_armor.flora").withStyle(ChatFormatting.GRAY))));
            }
            return out;
        }
        for (FluidArmorEffects.Entry e : r.effects()) {
            var holder = FluidArmorItem.mobEffect(e);
            boolean cleanse = e.effect().equals(FluidArmorEffects.CLEANSE) || holder.isEmpty();
            String level = FluidArmorItem.level(e);
            Component name = cleanse ? Component.translatable("fieldframe.effect.cleanse.short")
                    : FluidArmorItem.effectName(e).copy().append(level.isEmpty() ? "" : " " + level);
            Component sub = Component.literal(e.minMb() + " mB" + (e.mbPerLevel() > 0 ? ", +1/" + e.mbPerLevel() : ""));
            out.add(new Card(cleanse ? null : Minecraft.getInstance().getMobEffectTextures().get(holder.get()), cleanse ? new ItemStack(Items.MILK_BUCKET) : null, null,
                    name, sub, FluidArmorItem.harmful(e), List.of(FluidArmorItem.effectLine(e),
                    Component.translatable("fieldframe.flora.worn_total").withStyle(ChatFormatting.GRAY))));
        }
        return out;
    }

    /** Table fluids with few effects get full-width cards, so the mB line fits. */
    private static int cols(FluidArmorRows.Row r, int n) {
        return !r.flora() && n <= ROWS ? 1 : COLS;
    }

    private static int cellW(int cols) {
        return cols == 1 ? W : CELL_W;
    }

    private static int cellX(int i, int cols) {
        return (i % cols) * (CELL_W + GAP);
    }

    private static int cellY(int i, int cols) {
        return GRID_Y + (i / cols) * (CELL_H + 2);
    }

    @Override
    public void draw(FluidArmorRows.Row r, IRecipeSlotsView view, GuiGraphics g, double mx, double my) {
        Font font = Minecraft.getInstance().font;
        g.drawString(font, "→", 41, 5, 0xFF404040, false);
        g.drawString(font, "→", 71, 5, 0xFF404040, false);
        g.fill(0, GRID_Y - 4, W, GRID_Y - 3, CARD_EDGE);
        List<Card> cards = cards(r);
        int cols = cols(r, cards.size()), cw = cellW(cols);
        for (int i = 0; i < Math.min(cards.size(), cols * ROWS); i++) {
            Card c = cards.get(i);
            int x = cellX(i, cols), y = cellY(i, cols);
            boolean hover = mx >= x && mx < x + cw && my >= y && my < y + CELL_H;
            g.fill(x, y, x + cw, y + CELL_H, CARD_EDGE);
            g.fill(x + 1, y + 1, x + cw - 1, y + CELL_H - 1, hover ? 0xFFE4E4E4 : CARD);
            g.fill(x + 1, y + 1, x + 3, y + CELL_H - 1, c.harmful() ? BAD : GOOD);
            if (c.sprite() != null) {
                g.blit(x + 5, y + 2, 0, 16, 16, c.sprite());
            } else if (c.item() != null) {
                g.renderItem(c.item(), x + 5, y + 2);
            }
            int tx = x + 23, tw = cw - 25;
            List<FormattedCharSequence> name = font.split(c.name(), tw);
            if (name.size() == 1) {
                g.drawString(font, name.get(0), tx, y + 2, INK, false);
                g.drawString(font, font.plainSubstrByWidth(c.sub().getString(), tw), tx, y + 11, INK_DIM, false);
            } else {
                // Long names take both lines; the partner / mB is in the tooltip.
                g.drawString(font, name.get(0), tx, y + 2, INK, false);
                String rest = font.plainSubstrByWidth(name.size() > 2 ? text(name.get(1)) + "…" : text(name.get(1)), tw);
                g.drawString(font, rest, tx, y + 11, INK, false);
            }
        }
    }

    private static String text(FormattedCharSequence s) {
        StringBuilder b = new StringBuilder();
        s.accept((i, style, cp) -> {
            b.appendCodePoint(cp);
            return true;
        });
        return b.toString();
    }

    @Override
    public void getTooltip(ITooltipBuilder t, FluidArmorRows.Row r, IRecipeSlotsView view, double mx, double my) {
        List<Card> cards = cards(r);
        int cols = cols(r, cards.size()), cw = cellW(cols);
        for (int i = 0; i < Math.min(cards.size(), cols * ROWS); i++) {
            int x = cellX(i, cols), y = cellY(i, cols);
            if (mx >= x && mx < x + cw && my >= y && my < y + CELL_H) {
                t.addAll(cards.get(i).tooltip());
                return;
            }
        }
    }
}
