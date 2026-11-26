package dev.fieldframe.neoforge.chassis;

import dev.fieldframe.core.chassis.ChassisCore;
import net.minecraft.world.entity.player.Player;

/**
 * Multiplayer authorization for changing a chassis. A chassis remembers who placed it; only that player and
 * operators (permission level 2) may install, remove, reconfigure or rotate it. Anyone may look (inspector,
 * manual). Chassis without a recorded owner (placed by machines or older saves) are open to everyone.
 */
public final class ChassisAccess {
    private ChassisAccess() {
    }

    public static boolean mayEdit(Player player, ChassisCore core) {
        String owner = core.ownerId();
        return owner.isEmpty() || owner.equals(player.getUUID().toString()) || player.hasPermissions(2);
    }
}
