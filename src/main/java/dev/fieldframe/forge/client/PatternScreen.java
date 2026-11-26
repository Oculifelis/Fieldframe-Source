package dev.fieldframe.forge.client;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.modules.RecipeAssemblerBehavior;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Picture of a crafting recipe for the Recipe Assembler: click an item in your inventory (or drag one in from JEI),
 * then click or drag over the squares to place it; right-click clears a square. Only item ids are stored, so nothing
 * leaves the inventory. Saving sends the canonical text form through the same validated {@code set_config} request.
 */
public final class PatternScreen extends Screen {
    private static final int CELL = 20;
    private static final int W = 196;
    private int h = 226;
    private final ConfiguratorScreen parent;
    private final String key;
    private final FfId[] cells = new FfId[9];
    private FfId brush;
    private boolean erasing;
    private int x0;
    private int y0;
    private int gx;
    private int gy;
    private int ix;
    private int iy;

    public PatternScreen(ConfiguratorScreen parent, String key, String initial) {
        super(Component.translatable("fieldframe.gui.pattern_title"));
        this.parent = parent;
        this.key = key;
        FfId[] p = RecipeAssemblerBehavior.parsePattern(initial == null ? "" : initial);
        if (p != null) {
            System.arraycopy(p, 0, cells, 0, 9);
        }
    }

    @Override
    protected void init() {
        // Lay out from the top so the wrapped hint never overlaps the squares: hint, squares, "Placing", inventory, buttons.
        int hintLines = font.split(Component.translatable("fieldframe.gui.pattern_hint"), W - 14).size();
        int gridTop = 19 + hintLines * 9 + 3;
        int invTop = gridTop + CELL * 3 + 22;
        h = invTop + 76 + 22;
        x0 = (width - W) / 2;
        y0 = Math.max(2, (height - h) / 2);
        gx = width / 2 - CELL * 3 / 2;
        gy = y0 + gridTop;
        ix = width / 2 - 9 * 18 / 2;
        iy = y0 + invTop;
        if (brush == null && minecraft.player != null) {
            brush = idOf(minecraft.player.getMainHandItem());
        }
        int by = y0 + h - 20;
        addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.save"), b -> save()).bounds(x0 + 8, by, 58, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.clear"), b -> java.util.Arrays.fill(cells, null))
                .bounds(x0 + 69, by, 58, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.cancel"), b -> onClose()).bounds(x0 + 130, by, 58, 14).build());
    }

    private static FfId idOf(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(s.getItem());
        return rl == null ? null : FfId.tryParse(rl.toString());
    }

    private static ItemStack stackOf(FfId id) {
        if (id == null) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id.toString());
        Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item == null || item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
    }

    private static String nameOf(FfId id) {
        return id == null ? "" : ConfiguratorScreen.itemName(id.toString());
    }

    /** Square under the mouse, 0..8, or -1. */
    private int cellAt(double mx, double my) {
        int cx = (int) Math.floor((mx - gx) / CELL);
        int cy = (int) Math.floor((my - gy) / CELL);
        return mx >= gx && my >= gy && cx < 3 && cy < 3 ? cy * 3 + cx : -1;
    }

    /** Inventory slot under the mouse (main rows 9..35 on top, hotbar 0..8 below), or -1. */
    private int slotAt(double mx, double my) {
        int col = (int) Math.floor((mx - ix) / 18);
        if (mx < ix || col < 0 || col >= 9) {
            return -1;
        }
        if (my >= iy && my < iy + 54) {
            return 9 + (int) ((my - iy) / 18) * 9 + col;
        }
        if (my >= iy + 58 && my < iy + 76) {
            return col;
        }
        return -1;
    }

    private int slotX(int slot) {
        return ix + (slot % 9) * 18;
    }

    private int slotY(int slot) {
        return slot < 9 ? iy + 58 : iy + ((slot - 9) / 9) * 18;
    }

    /** Screen rectangles of the nine squares {x, y, w, h} (for dragging items in from JEI). */
    public int[][] cellRects() {
        int[][] out = new int[9][];
        for (int i = 0; i < 9; i++) {
            out[i] = new int[]{gx + (i % 3) * CELL, gy + (i / 3) * CELL, CELL - 2, CELL - 2};
        }
        return out;
    }

    /** Puts an item on a square (JEI drag); also makes it the item being placed. */
    public void setCell(int i, ItemStack stack) {
        FfId id = idOf(stack);
        if (i >= 0 && i < 9 && id != null) {
            cells[i] = id;
            brush = id;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int c = cellAt(mx, my);
        if (c >= 0) {
            erasing = button == 1;
            cells[c] = erasing ? null : brush;
            return true;
        }
        int s = slotAt(mx, my);
        if (s >= 0 && minecraft.player != null) {
            brush = idOf(minecraft.player.getInventory().getItem(s));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        int c = cellAt(mx, my);
        if (c >= 0) {
            cells[c] = erasing ? null : brush;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    private void save() {
        parent.sendProgram(key, RecipeAssemblerBehavior.formatPattern(cells));
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        Schematic.panel(g, x0, y0, W, h);
        super.render(g, mx, my, partial);
        g.drawString(font, title, x0 + 7, y0 + 6, Schematic.INK, false);
        g.drawWordWrap(font, Component.translatable("fieldframe.gui.pattern_hint"), x0 + 7, y0 + 17, W - 14, Schematic.INK_DIM);
        int hover = cellAt(mx, my);
        for (int i = 0; i < 9; i++) {
            int x = gx + (i % 3) * CELL;
            int y = gy + (i / 3) * CELL;
            g.fill(x - 1, y - 1, x + CELL - 1, y + CELL - 1, Schematic.STEEL);
            g.fill(x, y, x + CELL - 2, y + CELL - 2, i == hover ? 0xFF4A6A8A : Schematic.SLOT);
            if (cells[i] != null) {
                g.renderItem(stackOf(cells[i]), x + 1, y + 1);
            }
        }
        // What a click on a square places.
        int py = gy + CELL * 3 + 6;
        String placing = I18n.get("fieldframe.gui.pattern_brush", "");
        g.drawString(font, placing, x0 + 7, py + 4, Schematic.INK_DIM, false);
        int bx = x0 + 9 + font.width(placing);
        if (brush == null) {
            g.drawString(font, I18n.get("fieldframe.gui.pattern_eraser"), bx, py + 4, Schematic.INK, false);
        } else {
            g.renderItem(stackOf(brush), bx, py);
            g.drawString(font, font.plainSubstrByWidth(nameOf(brush), W - (bx - x0) - 26), bx + 19, py + 4, Schematic.INK, false);
        }
        int hoverSlot = slotAt(mx, my);
        {
            for (int s = 0; s < 36; s++) {
                int x = slotX(s);
                int y = slotY(s);
                ItemStack st = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getInventory().getItem(s);
                boolean sel = brush != null && brush.equals(idOf(st));
                g.fill(x, y, x + 17, y + 17, sel ? 0xFF6A8A4A : s == hoverSlot ? 0xFF4A6A8A : 0xFF2B3A4A);
                g.renderItem(st, x, y);
            }
        }
        if (hover >= 0 && cells[hover] != null) {
            g.renderTooltip(font, Component.literal(nameOf(cells[hover])), mx, my);
        } else if (hoverSlot >= 0 && minecraft.player != null && !minecraft.player.getInventory().getItem(hoverSlot).isEmpty()) {
            g.renderTooltip(font, Component.translatable("fieldframe.gui.pattern_pick",
                    minecraft.player.getInventory().getItem(hoverSlot).getHoverName()), mx, my);
        }
    }
}
