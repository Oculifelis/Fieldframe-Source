package dev.fieldframe.forge.client;

import dev.fieldframe.api.process.ProcessFamilies;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;

/** Automated client check helpers for the JEI / Jade integrations (only used by the multiplayer smoke client). */
final class ViewerShots {
    private ViewerShots() {
    }

    static boolean showJei() {
        return dev.fieldframe.forge.compat.jei.FieldframeJeiPlugin.showFamily(ProcessFamilies.ALLOYING);
    }

    static boolean showAuras() {
        return dev.fieldframe.forge.compat.jei.FieldframeJeiPlugin.showAuras();
    }

    static boolean showTable(String which) {
        return dev.fieldframe.forge.compat.jei.FieldframeJeiPlugin.showTable(which);
    }

    static int showFluidArmor() {
        return dev.fieldframe.forge.compat.jei.FieldframeJeiPlugin.showFluidArmor();
    }

    static boolean showFluidArmor(java.util.Set<String> fluids) {
        return dev.fieldframe.forge.compat.jei.FieldframeJeiPlugin.showFluidArmor(fluids);
    }

    static int showPage(net.minecraft.resources.ResourceLocation id) {
        return dev.fieldframe.forge.compat.jei.FieldframeJeiPlugin.showPage(id);
    }

    static boolean showInfo(net.minecraft.world.item.Item item) {
        return dev.fieldframe.forge.compat.jei.FieldframeJeiPlugin.showInfo(item);
    }

    static void shot(Minecraft mc, String file) {
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), msg -> { });
        dev.fieldframe.forge.Fieldframe.LOG.info("FIELDFRAME_MP client screenshot {}", file);
    }

    /** Turns the player to look at the centre of a block (so Jade shows its tooltip). */
    static void lookAt(Minecraft mc, BlockPos pos) {
        var eye = mc.player.getEyePosition();
        var c = pos.getCenter();
        double dx = c.x - eye.x;
        double dy = c.y - eye.y;
        double dz = c.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }
}
