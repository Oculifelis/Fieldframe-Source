package dev.fieldframe.neoforge.chassis;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The fake player a Mob Grinder attacks with: always fully charged, with its weapon's attribute modifiers applied
 * while it swings (a fake player never ticks, so held-item modifiers are applied by hand).
 */
final class GrinderPlayer extends net.neoforged.neoforge.common.util.FakePlayer {
    private static final Map<ServerLevel, Map<UUID, GrinderPlayer>> CACHE = new WeakHashMap<>();

    private GrinderPlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    static synchronized GrinderPlayer get(ServerLevel level, UUID owner) {
        return CACHE.computeIfAbsent(level, l -> new HashMap<>()).computeIfAbsent(owner,
                o -> new GrinderPlayer(level, new GameProfile(o, "[Fieldframe]")));
    }

    /** A full-strength swing. */
    void charge() {
        attackStrengthTicker = 1000;
    }

    /** Adds or removes the held weapon's attribute modifiers (attack damage and speed). */
    void weaponModifiers(ItemStack stack, boolean add) {
        if (stack.isEmpty()) {
            return;
        }
        stack.forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND, (attr, mod) -> {
            var inst = getAttribute(attr);
            if (inst == null) {
                return;
            }
            if (add) {
                inst.addOrUpdateTransientModifier(mod);
            } else {
                inst.removeModifier(mod);
            }
        });
    }
}
