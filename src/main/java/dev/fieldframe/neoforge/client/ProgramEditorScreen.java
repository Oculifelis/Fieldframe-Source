package dev.fieldframe.neoforge.client;

import dev.fieldframe.api.data.FfData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Multi-line editor for long text settings such as a Controller Plate program. Saving sends one validated
 * {@code set_config} request; the server re-parses and the configurator shows any PARSE_ERROR with its line.
 */
public final class ProgramEditorScreen extends Screen {
    private final ConfiguratorScreen parent;
    private final String key;
    private final String initial;
    private final int limit;
    private MultiLineEditBox box;

    public ProgramEditorScreen(ConfiguratorScreen parent, String key, String initial, int limit) {
        super(Component.translatable("fieldframe.gui.program_title"));
        this.parent = parent;
        this.key = key;
        this.initial = initial;
        this.limit = limit;
    }

    @Override
    protected void init() {
        int w = Math.min(360, width - 20);
        int h = Math.min(200, height - 60);
        int x = (width - w) / 2;
        int y = 24;
        box = new MultiLineEditBox(font, x, y, w, h, Component.translatable("fieldframe.gui.program_hint"), title);
        box.setCharacterLimit(limit);
        box.setValue(initial);
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.save"), b -> save()).bounds(x, y + h + 6, 80, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.cancel"), b -> onClose()).bounds(x + 86, y + h + 6, 80, 16).build());
        setInitialFocus(box);
    }

    private void save() {
        parent.sendProgram(key, box.getValue());
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
        super.render(g, mx, my, partial);
        g.drawString(font, title, (width - font.width(title)) / 2, 8, Schematic.INK, false);
    }
}
