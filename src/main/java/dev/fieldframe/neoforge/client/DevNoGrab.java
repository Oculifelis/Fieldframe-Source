package dev.fieldframe.neoforge.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Dev test clients only (left out of the release jar, see build.gradle): the smoke clients must never take the user's
 * mouse. Vanilla grabs the cursor when a world opens if the window counts as active, so
 * this marks it inactive, ignores later focus events and releases any grab that still happens.
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
        if (!unfocused) {
            unfocused = true;
            mc.options.pauseOnLostFocus = false;
            GLFW.glfwSetWindowFocusCallback(mc.getWindow().getWindow(), (h, f) -> { });
            // 1.21.1 keeps focus on Minecraft (windowActive), not on Window; vanilla only grabs while it is set.
            mc.setWindowActive(false);
        }
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
    }
}
