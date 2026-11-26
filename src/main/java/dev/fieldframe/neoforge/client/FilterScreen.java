package dev.fieldframe.neoforge.client;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The filter editor: click items in your inventory (or drag them in from JEI) to add them as entries; nothing leaves
 * the inventory. Click an entry to pick one of its tags instead of the item, or to match its exact data (enchantments,
 * names); right-click removes it. Allow or deny the listed items. Saving sends the filter through the same validated
 * {@code set_config} request as every other setting.
 */
public final class FilterScreen extends Screen {
    private static final int W = 236;
    private static final int CELL = 20;
    private static final int COLS = 8;
    private int h = 240;
    private final ConfiguratorScreen parent;
    private final String key;
    private final List<ItemFilter.Entry> entries = new ArrayList<>();
    /** Exact data of entries added from a stack (by index), so "exact" can be switched on later. */
    private final List<String> components = new ArrayList<>();
    private boolean deny;
    private int selected = -1;
    private int x0;
    private int y0;
    private int gx;
    private int gy;
    private int ix;
    private int iy;
    private int tagY;

    public FilterScreen(ConfiguratorScreen parent, String key, FfData initial) {
        super(Component.translatable("fieldframe.gui.filter_title"));
        this.parent = parent;
        this.key = key;
        ItemFilter f = ItemFilter.fromData(initial);
        if (!f.isAny()) {
            entries.addAll(f.entries());
            for (ItemFilter.Entry e : f.entries()) {
                components.add(e.componentKey());
            }
            deny = f.denyList();
        }
    }

    @Override
    protected void init() {
        int hintLines = font.split(Component.translatable("fieldframe.gui.filter_hint2"), W - 14).size();
        int gridTop = 19 + hintLines * 9 + 3;
        tagY = gridTop + CELL * 2 + 13;
        int invTop = tagY + 28;
        h = invTop + 76 + 22;
        x0 = (width - W) / 2;
        y0 = Math.max(2, (height - h) / 2);
        gx = width / 2 - CELL * COLS / 2;
        gy = y0 + gridTop;
        ix = width / 2 - 9 * 18 / 2;
        iy = y0 + invTop;
        addRenderableWidget(Button.builder(Component.translatable(deny ? "fieldframe.gui.filter_deny" : "fieldframe.gui.filter_allow"), b -> {
            deny = !deny;
            rebuildWidgets();
        }).bounds(x0 + W - 105, y0 + 3, 98, 13).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.filter_mode_tip"))).build());
        if (selected >= 0 && selected < entries.size()) {
            buildEntryOptions();
        }
        int by = y0 + h - 20;
        addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.save"), b -> save()).bounds(x0 + 8, by, 70, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.clear"), b -> {
            entries.clear();
            components.clear();
            selected = -1;
            rebuildWidgets();
        }).bounds(x0 + 83, by, 70, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.cancel"), b -> onClose()).bounds(x0 + 158, by, 70, 14).build());
    }

    /** For the selected entry: the item itself, one of its tags, or the item with its exact data. */
    private void buildEntryOptions() {
        ItemFilter.Entry e = entries.get(selected);
        FfId item = e.tag() ? null : e.id();
        List<FfId> tags = item == null ? List.of() : tagsOf(item);
        int x = x0 + 7;
        int y = y0 + tagY;
        if (item != null) {
            String comp = components.get(selected);
            boolean exact = !e.componentKey().isEmpty();
            if (!comp.isEmpty()) {
                addRenderableWidget(Button.builder(Component.translatable(exact ? "fieldframe.gui.filter_exact_on" : "fieldframe.gui.filter_exact_off"), b -> {
                    entries.set(selected, new ItemFilter.Entry(false, item, exact ? "" : comp));
                    rebuildWidgets();
                }).bounds(x, y, 96, 12).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.filter_exact_tip"))).build());
            }
        }
        int tx = x + 100;
        int shown = 0;
        for (FfId t : tags) {
            if (shown >= 4) {
                break;
            }
            String label = "#" + t;
            int bw = Math.min(W - 14 - (tx - x0 - 7), font.width(label) + 8);
            if (bw < 30) {
                tx = x;
                y += 14;
                bw = Math.min(W - 14, font.width(label) + 8);
            }
            FfId tag = t;
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, bw - 6)), b -> {
                entries.set(selected, new ItemFilter.Entry(true, tag, ""));
                rebuildWidgets();
            }).bounds(tx, y, bw, 12).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.filter_tag_tip", label))).build());
            tx += bw + 3;
            shown++;
        }
    }

    private static List<FfId> tagsOf(FfId item) {
        ItemStack st = stackOf(item);
        List<FfId> out = new ArrayList<>();
        st.getTags().forEach(t -> {
            FfId id = FfId.tryParse(t.location().toString());
            if (id != null) {
                out.add(id);
            }
        });
        // Common tags (c:ingots/copper) are the useful ones; put them first.
        out.sort((a, b) -> Boolean.compare(!a.namespace().equals("c"), !b.namespace().equals("c")));
        return out;
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

    /** Adds an item as an entry (from the inventory or JEI); with {@code exact} it must match the stack's data too. */
    public void add(ItemStack stack, boolean exact) {
        FfId id = idOf(stack);
        if (id == null || entries.size() >= ItemFilter.MAX_ENTRIES) {
            return;
        }
        McItemVariant v = McItemVariant.of(stack.copyWithCount(1));
        String comp = v == null ? "" : v.componentKey();
        for (ItemFilter.Entry e : entries) {
            if (!e.tag() && e.id().equals(id) && (!exact || e.componentKey().equals(comp))) {
                return;
            }
        }
        entries.add(new ItemFilter.Entry(false, id, exact ? comp : ""));
        components.add(comp);
        selected = entries.size() - 1;
        rebuildWidgets();
    }

    /** The entry grid on screen {x, y, w, h} (dragging items in from JEI). */
    public int[] gridRect() {
        return new int[]{gx, gy, CELL * COLS, CELL * 2};
    }

    private int cellAt(double mx, double my) {
        int cx = (int) Math.floor((mx - gx) / CELL);
        int cy = (int) Math.floor((my - gy) / CELL);
        return mx >= gx && my >= gy && cx < COLS && cy < 2 ? cy * COLS + cx : -1;
    }

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

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int c = cellAt(mx, my);
        if (c >= 0) {
            if (c < entries.size()) {
                if (button == 1) {
                    entries.remove(c);
                    components.remove(c);
                    selected = -1;
                } else {
                    selected = c == selected ? -1 : c;
                }
                rebuildWidgets();
            }
            return true;
        }
        int s = slotAt(mx, my);
        if (s >= 0 && minecraft.player != null) {
            ItemStack st = minecraft.player.getInventory().getItem(s);
            if (!st.isEmpty()) {
                add(st, hasShiftDown());
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void save() {
        parent.sendFilter(key, entries.isEmpty() ? ItemFilter.ANY.toData() : new ItemFilter(entries, deny).toData());
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
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        super.renderBackground(g, mx, my, partial);
        Schematic.panel(g, x0, y0, W, h);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        super.render(g, mx, my, partial);
        g.drawString(font, title, x0 + 7, y0 + 6, Schematic.INK, false);
        g.drawWordWrap(font, Component.translatable("fieldframe.gui.filter_hint2"), x0 + 7, y0 + 17, W - 14, Schematic.INK_DIM);
        String count = entries.size() + " / " + ItemFilter.MAX_ENTRIES;
        g.drawString(font, count, x0 + W - 8 - font.width(count), gy + CELL * 2 + 2, Schematic.INK_DIM, false);
        int hover = cellAt(mx, my);
        for (int i = 0; i < COLS * 2; i++) {
            int x = gx + (i % COLS) * CELL;
            int y = gy + (i / COLS) * CELL;
            g.fill(x - 1, y - 1, x + CELL - 1, y + CELL - 1, i == selected ? Schematic.COPPER : Schematic.STEEL);
            g.fill(x, y, x + CELL - 2, y + CELL - 2, i == hover && i < entries.size() ? 0xFF4A6A8A : Schematic.SLOT);
            if (i < entries.size()) {
                ItemFilter.Entry e = entries.get(i);
                if (e.tag()) {
                    g.drawString(font, "#", x + 6, y + 5, Schematic.COPPER, false);
                } else {
                    g.renderItem(stackOf(e.id()), x + 1, y + 1);
                    if (!e.componentKey().isEmpty()) {
                        g.drawString(font, "=", x + 12, y + 10, Schematic.INK, true);
                    }
                }
            }
        }
        if (selected >= 0 && selected < entries.size()) {
            ItemFilter.Entry e = entries.get(selected);
            String what = e.tag() ? "#" + e.id() : ConfiguratorScreen.itemName(e.id().toString());
            g.drawString(font, font.plainSubstrByWidth(I18n.get("fieldframe.gui.filter_selected", what), W - 50), x0 + 7, gy + CELL * 2 + 2, Schematic.INK, false);
        }
        int hoverSlot = slotAt(mx, my);
        for (int s = 0; s < 36; s++) {
            int x = slotX(s);
            int y = slotY(s);
            ItemStack st = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getInventory().getItem(s);
            g.fill(x, y, x + 17, y + 17, s == hoverSlot ? 0xFF4A6A8A : 0xFF2B3A4A);
            g.renderItem(st, x, y);
        }
        if (hover >= 0 && hover < entries.size()) {
            ItemFilter.Entry e = entries.get(hover);
            String what = e.tag() ? "#" + e.id() : ConfiguratorScreen.itemName(e.id().toString());
            g.renderTooltip(font, Component.translatable("fieldframe.gui.filter_entry_tip", what), mx, my);
        } else if (hoverSlot >= 0 && minecraft.player != null && !minecraft.player.getInventory().getItem(hoverSlot).isEmpty()) {
            g.renderTooltip(font, Component.translatable("fieldframe.gui.filter_add_tip",
                    minecraft.player.getInventory().getItem(hoverSlot).getHoverName()), mx, my);
        }
    }
}
