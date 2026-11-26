package dev.fieldframe.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Dev test clients only (left out of the release jar, see build.gradle): the smoke clients must never take the owner's
 * mouse. Vanilla grabs the cursor when a world opens if the window counts as active, so
 * this marks it inactive, ignores later focus events and releases any grab that still happens.
 */
public final class DevNoGrab {
    private DevNoGrab() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(DevNoGrab::tick);
    }

    private static boolean unfocused;

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) {
            return;
        }
        if (!unfocused) {
            unfocused = true;
            mc.options.pauseOnLostFocus = false;
            GLFW.glfwSetWindowFocusCallback(mc.getWindow().getWindow(), (h, f) -> { });
            GLFW.glfwSetWindowPos(mc.getWindow().getWindow(), -4000, -4000);
        }
        // 1.20.1 keeps focus on Minecraft (windowActive); MouseHandler#grabMouse does nothing while it is false.
        if (mc.isWindowActive()) {
            mc.setWindowActive(false);
        }
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
    }
}
