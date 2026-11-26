package dev.fieldframe.neoforge.compat.ponder;

import net.createmod.ponder.api.client.PonderIndex;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;

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
        modBus.addListener((AddClientReloadListenersEvent e) ->
                e.addListener(Identifier.fromNamespaceAndPath("fieldframe", "ponder_scenes"), new PonderScenes()));
    }

    /** Ponder's screen for an item's scenes (automated client check); null when it has none. */
    public static net.minecraft.client.gui.screens.Screen open(Identifier item) {
        if (!PonderIndex.getSceneAccess().doScenesExistForId(item)) {
            return null;
        }
        return net.createmod.ponder.impl.client.gui.PonderUI.of(item);
    }
}
