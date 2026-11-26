package dev.fieldframe.neoforge.compat.curios;

import dev.fieldframe.neoforge.chassis.FieldKitRuntime;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.function.Predicate;

/**
 * Curios (optional): a Field Kit worn in the belt slot (item tag {@code curios:belt}) works as if it were in the
 * inventory. Only loaded when Curios is present.
 */
public final class KitCurio {
    private KitCurio() {
    }

    public static void register() {
        CuriosApi.registerCurio(FfContent.FIELD_KIT.get(), new ICurioItem() {
            @Override
            public void curioTick(SlotContext ctx, ItemStack stack) {
                if (ctx.entity() instanceof Player p && !p.isSpectator() && p.level() instanceof ServerLevel sl) {
                    FieldKitRuntime.tick(stack, sl, p);
                }
            }
        });
    }

    /** The first worn curio matching {@code filter}, or EMPTY. */
    public static ItemStack find(Player p, Predicate<ItemStack> filter) {
        return CuriosApi.getCuriosInventory(p).flatMap(h -> h.findFirstCurio(filter)).map(SlotResult::stack).orElse(ItemStack.EMPTY);
    }
}
