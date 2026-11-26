package dev.fieldframe.neoforge.api.client;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualModel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Public client API (Forge 1.20.1): renders one manual page type. Register with
 * {@link ManualClientApi#registerRenderer}. Renderers must be pure presentation: no world access, no server calls.
 */
public interface ManualPageRenderer {
    /** Draws the page at (x, y) within {@code width}; returns the height used. */
    int render(Context ctx, ManualModel.Page page, int x, int y, int width);

    /** Click inside the page area last rendered at (x, y, width). Return true when handled. */
    default boolean mouseClicked(ManualModel.Page page, double mouseX, double mouseY, int button) {
        return false;
    }

    /** Plain-text equivalent for search, narration and the no-animation mode. */
    default String textEquivalent(ManualModel.Page page) {
        return page.data().getString("text", "");
    }

    /** Drawing services offered to renderers. */
    interface Context {
        GuiGraphicsExtractor graphics();

        Font font();

        int mouseX();

        int mouseY();

        /** Monotonic tick counter for optional animation; renderers must also make sense when it is frozen. */
        long ticks();

        /** Draws manual markup ({@code **bold**}, {@code [[ns:entry|label]]} links); returns height. */
        int text(String markup, int x, int y, int width, int color);

        /** Registers a clickable region that navigates to a manual entry. */
        void link(FfId entry, int x, int y, int w, int h);

        void item(ItemStack stack, int x, int y);

        void tooltip(Component text);
    }
}
