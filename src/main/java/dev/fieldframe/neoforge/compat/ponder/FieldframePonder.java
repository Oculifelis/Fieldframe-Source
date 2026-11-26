package dev.fieldframe.neoforge.compat.ponder;

import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Optional Ponder support (client only; called only when Ponder is loaded). No scene is written in Java: scenes are
 * resource files ({@code assets/<ns>/fieldframe_ponder/scenes/*.json}, linked to items by
 * {@code .../fieldframe_ponder/index/*.json}) played by one interpreter, so resource packs, addons and KubeJS asset
 * folders add or replace scenes. See docs/ponder_plan.md.
 */
public final class FieldframePonder {
    private FieldframePonder() {
    }

    public static void init(IEventBus modBus) {
        PonderIndex.addPlugin(new FieldframePonderPlugin());
        modBus.addListener((RegisterClientReloadListenersEvent e) ->
                e.registerReloadListener(new PonderScenes()));
    }

    /** Ponder's screen for an item's scenes (automated client check); null when it has none. */
    public static net.minecraft.client.gui.screens.Screen open(ResourceLocation item) {
        if (!PonderIndex.getSceneAccess().doScenesExistForId(item)) {
            return null;
        }
        return net.createmod.ponder.foundation.ui.PonderUI.of(item);
    }
}
