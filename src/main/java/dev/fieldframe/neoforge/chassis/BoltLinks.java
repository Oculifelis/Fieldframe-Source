package dev.fieldframe.neoforge.chassis;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.BoltGroup;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.neoforge.adapter.Dirs;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Bolted Chassis on the world side (the shared stores are {@link BoltGroup}): the Frame Bolt, where the parts are,
 * linking them again after loading, and what happens when a part is broken or unloaded. Each chassis' bolt info holds
 * offsets: the lead lists {@code parts}, every other part points to its {@code lead}.
 */
public final class BoltLinks {
    private BoltLinks() {
    }

    static String offset(BlockPos from, BlockPos to) {
        return (to.getX() - from.getX()) + "," + (to.getY() - from.getY()) + "," + (to.getZ() - from.getZ());
    }

    static BlockPos apply(BlockPos from, String off) {
        String[] p = off == null ? new String[0] : off.split(",");
        if (p.length != 3) {
            return null;
        }
        try {
            return from.offset(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> parts(ChassisCore core) {
        List<String> out = new ArrayList<>();
        for (Object o : core.boltInfo().getList("parts")) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static void setParts(ChassisBlockEntity lead, List<String> parts) {
        lead.core().setBoltInfo(parts.isEmpty() ? new FfData() : new FfData().putList("parts", new ArrayList<>(parts)));
        lead.setChanged();
    }

    private static ChassisBlockEntity at(Level level, BlockPos p) {
        return p != null && level.isLoaded(p) && level.getBlockEntity(p) instanceof ChassisBlockEntity be ? be : null;
    }

    /** The block entity of the machine's lead, seen from any of its parts (null if not loaded). */
    static ChassisBlockEntity leadOf(Level level, ChassisBlockEntity be) {
        BoltGroup g = be.core().group();
        if (g == null || g.lead() == be.core()) {
            return be;
        }
        return at(level, apply(be.getBlockPos(), be.core().boltInfo().getString("lead", "")));
    }

    private static void msg(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable(key, args), true);
    }

    private static void give(Player p, int count) {
        ItemStack s = new ItemStack(FfContent.FRAME_BOLT.get(), count);
        if (!p.getInventory().add(s)) {
            p.drop(s, false);
        }
    }

    // ------------------------------------------------------------------ the Frame Bolt

    /**
     * Frame Bolt used on {@code side} of the chassis at {@code pos}: bolts the chassis touching that side onto this
     * machine (both touching sides must be free). Sneaking takes this chassis out of its machine, or splits the
     * machine up when used on its lead, and gives the bolts back.
     */
    public static InteractionResult use(Level level, BlockPos pos, Direction side, Player player, ItemStack held) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ChassisBlockEntity be = at(level, pos);
        if (be == null) {
            return InteractionResult.PASS;
        }
        if (!ChassisAccess.mayEdit(player, be.core())) {
            msg(player, "fieldframe.msg.not_owner", be.core().ownerName());
            return InteractionResult.FAIL;
        }
        if (player.isShiftKeyDown()) {
            return unbolt(level, be, player);
        }
        ChassisBlockEntity nb = at(level, pos.relative(side));
        if (nb == null) {
            msg(player, "fieldframe.msg.bolt_no_chassis");
            return InteractionResult.FAIL;
        }
        if (!ChassisAccess.mayEdit(player, nb.core())) {
            msg(player, "fieldframe.msg.not_owner", nb.core().ownerName());
            return InteractionResult.FAIL;
        }
        LocalFace mine = be.core().orientation().toLocal(Dirs.toWorld(side));
        LocalFace theirs = nb.core().orientation().toLocal(Dirs.toWorld(side.getOpposite()));
        if (be.core().module(mine) != null || nb.core().module(theirs) != null) {
            msg(player, "fieldframe.msg.bolt_face_used");
            return InteractionResult.FAIL;
        }
        ChassisBlockEntity onto = be;
        ChassisBlockEntity joiner = nb;
        if (nb.core().group() != null && be.core().group() == null) {
            onto = nb;
            joiner = be;
        }
        ChassisBlockEntity lead = leadOf(level, onto);
        if (lead == null) {
            msg(player, "fieldframe.result.parts_missing", "", "");
            return InteractionResult.FAIL;
        }
        ChassisCore.Result r = BoltGroup.bolt(onto.core(), joiner.core());
        if (!r.ok()) {
            msg(player, "fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), "", r.detail());
            return InteractionResult.FAIL;
        }
        List<String> list = parts(lead.core());
        list.add(offset(lead.getBlockPos(), joiner.getBlockPos()));
        setParts(lead, list);
        joiner.core().setBoltInfo(new FfData().putString("lead", offset(joiner.getBlockPos(), lead.getBlockPos())));
        joiner.setChanged();
        onto.setChanged();
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        level.playSound(null, pos, FfContent.sound("machine.upgrade"), SoundSource.BLOCKS, 1f, 0.8f);
        msg(player, "fieldframe.msg.bolted", lead.core().group().parts().size());
        return InteractionResult.CONSUME;
    }

    private static InteractionResult unbolt(Level level, ChassisBlockEntity be, Player player) {
        BoltGroup g = be.core().group();
        if (g == null) {
            msg(player, "fieldframe.msg.not_bolted");
            return InteractionResult.FAIL;
        }
        ChassisBlockEntity lead = leadOf(level, be);
        if (lead == null) {
            msg(player, "fieldframe.result.parts_missing", "", "");
            return InteractionResult.FAIL;
        }
        boolean isLead = lead == be;
        List<String> list = parts(lead.core());
        ChassisCore.Result r = BoltGroup.unbolt(be.core());
        if (!r.ok()) {
            msg(player, "fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), "", r.detail());
            return InteractionResult.FAIL;
        }
        int bolts;
        if (isLead) {
            for (String off : list) {
                ChassisBlockEntity p = at(level, apply(lead.getBlockPos(), off));
                if (p != null) {
                    p.core().setBoltInfo(new FfData());
                    p.setChanged();
                }
            }
            setParts(lead, List.of());
            bolts = list.size();
        } else {
            list.remove(offset(lead.getBlockPos(), be.getBlockPos()));
            setParts(lead, list);
            be.core().setBoltInfo(new FfData());
            be.setChanged();
            bolts = 1;
        }
        if (!player.getAbilities().instabuild) {
            give(player, bolts);
        }
        level.playSound(null, be.getBlockPos(), FfContent.sound("machine.remove"), SoundSource.BLOCKS, 1f, 0.8f);
        msg(player, "fieldframe.msg.unbolted");
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------------ loading, breaking, unloading

    /**
     * Called now and then while a chassis waits for its machine's parts: a part links itself to its lead; a lead
     * forgets parts whose place no longer holds a chassis pointing back at it (spilling what no longer fits).
     */
    static void tryLink(Level level, ChassisBlockEntity be) {
        ChassisCore core = be.core();
        FfData info = core.boltInfo();
        if (info.has("lead")) {
            BlockPos lp = apply(be.getBlockPos(), info.getString("lead", ""));
            if (lp == null) {
                orphan(level, be);
                return;
            }
            if (!level.isLoaded(lp)) {
                return;
            }
            ChassisBlockEntity lead = at(level, lp);
            String me = offset(lp, be.getBlockPos());
            if (lead == null || !parts(lead.core()).contains(me)) {
                orphan(level, be);
                return;
            }
            if (core.group() == null) {
                BoltGroup.relink(lead.core(), core, parts(lead.core()).size() + 1);
                lead.setChanged();
            }
            return;
        }
        List<String> list = parts(core);
        List<String> keep = new ArrayList<>();
        for (String off : list) {
            BlockPos pp = apply(be.getBlockPos(), off);
            if (pp == null) {
                continue;
            }
            if (!level.isLoaded(pp)) {
                keep.add(off);
                continue;
            }
            ChassisBlockEntity p = at(level, pp);
            if (p != null && offset(pp, be.getBlockPos()).equals(p.core().boltInfo().getString("lead", ""))) {
                keep.add(off);
            }
        }
        if (keep.size() == list.size()) {
            return;
        }
        setParts(be, keep);
        List<Object[]> spill = core.group() != null ? BoltGroup.forgetMissing(core, keep.size() + 1) : keep.isEmpty() ? core.shrinkToOwn() : List.of();
        drop(level, be.getBlockPos(), spill);
    }

    private static void orphan(Level level, ChassisBlockEntity be) {
        be.core().setBoltInfo(new FfData());
        be.core().setAwaitingLink(false);
        be.setChanged();
    }

    /** The block is being broken (not unloaded): the machine shrinks or splits up. */
    static void onBroken(Level level, ChassisBlockEntity be) {
        ChassisCore core = be.core();
        FfData info = core.boltInfo();
        if (info.isEmpty()) {
            return;
        }
        if (info.has("lead")) {
            List<Object[]> spill = core.group() != null ? BoltGroup.breakPart(core) : List.of();
            drop(level, be.getBlockPos(), spill);
            BlockPos lp = apply(be.getBlockPos(), info.getString("lead", ""));
            ChassisBlockEntity lead = at(level, lp);
            if (lead != null) {
                List<String> list = parts(lead.core());
                list.remove(offset(lp, be.getBlockPos()));
                setParts(lead, list);
            }
        } else {
            List<String> list = parts(core);
            if (core.group() != null) {
                BoltGroup.breakPart(core);
            }
            for (String off : list) {
                ChassisBlockEntity p = at(level, apply(be.getBlockPos(), off));
                if (p != null) {
                    p.core().setBoltInfo(new FfData());
                    p.core().setAwaitingLink(false);
                    p.setChanged();
                }
            }
        }
        core.setBoltInfo(new FfData());
    }

    static void onUnload(ChassisBlockEntity be) {
        BoltGroup.unload(be.core());
    }

    private static void drop(Level level, BlockPos pos, List<Object[]> spill) {
        for (Object[] s : spill) {
            if (s[0] instanceof ItemVariant v && s[1] instanceof Integer n) {
                ItemStack st = McItemVariant.toStack(v, n);
                if (!st.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, st);
                }
            }
        }
    }

    /** Whether the side {@code side} of this chassis touches another part of the same machine (no module may go there). */
    public static boolean touchesOwnMachine(Level level, ChassisBlockEntity be, Direction side) {
        ChassisBlockEntity nb = at(level, be.getBlockPos().relative(side));
        return nb != null && be.core().group() != null && nb.core().group() == be.core().group();
    }
}
