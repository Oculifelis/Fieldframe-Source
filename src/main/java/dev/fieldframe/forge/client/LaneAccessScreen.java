package dev.fieldframe.forge.client;

import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.forge.menu.LaneAccessMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Lane access panel GUI: each permitted lane is a labelled bank of slots on schematic paper. */
public final class LaneAccessScreen extends AbstractContainerScreen<LaneAccessMenu> {
    public LaneAccessScreen(LaneAccessMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        int y = 18;
        for (int lane = 0; lane < ChassisSpec.ITEM_LANES; lane++) {
            if ((menu.mask() & (1 << lane)) != 0) {
                y += ((menu.laneSlots(lane) + 8) / 9) * 18 + 12;
            }
        }
        this.imageWidth = 176;
        this.imageHeight = y + 4 + 76 + 6;
        this.inventoryLabelY = y - 6;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        Schematic.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (Slot s : menu.slots) {
            Schematic.slot(g, leftPos + s.x, topPos + s.y);
        }
        int y = 18;
        for (int lane = 0; lane < ChassisSpec.ITEM_LANES; lane++) {
            if ((menu.mask() & (1 << lane)) == 0) {
                continue;
            }
            String label = net.minecraft.client.resources.language.I18n.get("fieldframe.lane." + lane);
            int colour = ConfiguratorScreen.LANE_COLOURS[lane];
            g.fill(leftPos + 4, topPos + y - 9, leftPos + 6, topPos + y - 2, colour);
            g.drawString(font, label, leftPos + 8, topPos + y - 9, colour, false);
            y += ((menu.laneSlots(lane) + 8) / 9) * 18 + 12;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, 5, Schematic.INK, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Schematic.INK_DIM, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        super.render(g, mx, my, partial);
        renderTooltip(g, mx, my);
    }
}
