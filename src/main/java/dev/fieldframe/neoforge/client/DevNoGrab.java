package dev.fieldframe.neoforge.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev test clients only (left out of the release jar, see build.gradle): the smoke clients must never take the owner's
 * mouse. Vanilla grabs the cursor when a world opens if the window counts as focused, and Window starts out focused, so
 * this clears the native window focus flag each tick and releases any grab that still happens.
 */
public final class DevNoGrab {
    private DevNoGrab() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(DevNoGrab::tick);
    }

    private static boolean unfocused;

    private static void tick(ClientTickEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) {
            return;
        }
        {
            unfocused = true;
            mc.options.pauseOnLostFocus = false;
            try {
                var f = com.mojang.blaze3d.platform.Window.class.getDeclaredField("focused");
                f.setAccessible(true);
                f.setBoolean(mc.getWindow(), false);
            } catch (ReflectiveOperationException ex) {
                dev.fieldframe.neoforge.Fieldframe.LOG.warn("DevNoGrab: could not clear window focus", ex);
            }
        }
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
    }
}
