package dev.fieldframe_example;

import dev.fieldframe.api.manual.ManualModel;
import dev.fieldframe.neoforge.api.client.ManualClientApi;
import dev.fieldframe.neoforge.api.client.ManualPageRenderer;

/** Client-only registration: a renderer for the addon's {@code fieldframe_example:gauge} manual page type. */
public final class ExampleAddonClient {
    private ExampleAddonClient() {
    }

    static void init() {
        ManualClientApi.registerRenderer(ExampleAddon.GAUGE_PAGE, new ManualPageRenderer() {
            @Override
            public int render(Context ctx, ManualModel.Page page, int x, int y, int width) {
                double v = Math.max(0, Math.min(1, page.data().getDouble("value", 0)));
                String label = page.data().getString("label", "");
                int h = ctx.text("**" + label + "**", x, y, width, 0xFFD8E4EA);
                int barY = y + h + 2;
                int w = Math.min(width, 120);
                ctx.graphics().fill(x, barY, x + w, barY + 8, 0xFF121A20);
                ctx.graphics().fill(x + 1, barY + 1, x + 1 + (int) ((w - 2) * v), barY + 7, 0xFFD08A4E);
                ctx.graphics().drawString(ctx.font(), Math.round(v * 100) + "%", x + w + 4, barY, 0xFF8FA3AF, false);
                return h + 14;
            }

            @Override
            public String textEquivalent(ManualModel.Page page) {
                return page.data().getString("label", "") + ": " + Math.round(page.data().getDouble("value", 0) * 100) + "%";
            }
        });
    }
}
