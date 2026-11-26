package dev.fieldframe.neoforge.chassis;

import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.Milestones;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/** Grants the Fieldframe advancement tab (see {@link Milestones}) to real players; fake players never get them. */
public final class FfAdvancements {
    private FfAdvancements() {
    }

    public static void award(Player player, String id) {
        award(player, List.of(id));
    }

    public static void award(Player player, List<String> ids) {
        if (ids.isEmpty() || !(player instanceof ServerPlayer p) || p instanceof net.neoforged.neoforge.common.util.FakePlayer) {
            return;
        }
        for (String id : ids) {
            AdvancementHolder h = p.level().getServer().getAdvancements().get(Identifier.fromNamespaceAndPath("fieldframe", id));
            if (h != null) {
                p.getAdvancements().award(h, Milestones.CRITERION);
            }
        }
    }

    /** Grants to the machine's owner when they are online (machines keep working while their owner is away). */
    public static void awardOwner(Level level, ChassisCore core, List<String> ids) {
        if (ids.isEmpty() || !(level instanceof ServerLevel sl) || core.ownerId().isEmpty()) {
            return;
        }
        UUID owner;
        try {
            owner = UUID.fromString(core.ownerId());
        } catch (IllegalArgumentException e) {
            return;
        }
        award(sl.getServer().getPlayerList().getPlayer(owner), ids);
    }
}
