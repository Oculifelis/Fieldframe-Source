package dev.fieldframe.neoforge.feature.endertech;

import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.endertech.ExchangeJob;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs queued Exchanger jobs on the server tick, one job per player. Each swap: protection (break and place events,
 * {@code mayInteract}), energy, a replacement block from the inventory and room for the drops are all checked before
 * anything changes; a cancelled place event restores the original block.
 */
public final class ExchangerService {
    private ExchangerService() {
    }

    private record Running(ServerPlayer player, ServerLevel level, InteractionHand hand, BlockPos origin, BlockState match, BlockState replacement, ExchangeJob job) {
    }

    private static final Map<UUID, Running> JOBS = new HashMap<>();

    public static boolean selectable(Level level, BlockPos pos, BlockState s, EnderTechRules.Tier tier) {
        if (s.isAir() || s.hasBlockEntity() || s.getDestroySpeed(level, pos) < 0 && !(tier.creative() && s.is(Blocks.BEDROCK))) {
            return false;
        }
        if (s.getBlock() instanceof RedstoneLampBlock || !s.getFluidState().isEmpty() || s.canBeReplaced()) {
            return false;
        }
        return s.getBlock().asItem() != Items.AIR;
    }

    public static void queue(ServerPlayer p, InteractionHand hand, BlockPos origin, WorldDir normal, BlockState match, BlockState replacement, int radius) {
        if (match.equals(replacement)) {
            return;
        }
        JOBS.put(p.getUUID(), new Running(p, (ServerLevel) p.level(), hand, origin.immutable(), match, replacement, new ExchangeJob(normal, radius)));
    }

    public static int running() {
        return JOBS.size();
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Running>> it = JOBS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Running> e = it.next();
            Running r = e.getValue();
            ServerPlayer p = r.player();
            ItemStack tool = p.getItemInHand(r.hand());
            if (p.isRemoved() || p.hasDisconnected() || p.level() != r.level() || !(tool.getItem() instanceof ExchangerItem ex)) {
                it.remove();
                continue;
            }
            r.job().tick((dx, dy, dz, ring) -> swap(p, ex, tool, r, r.origin().offset(dx, dy, dz), ring));
            if (r.job().finished()) {
                it.remove();
            }
        }
    }

    /** Fake-player safe: also callable directly by tests. */
    static ExchangeJob.Swap swap(ServerPlayer p, ExchangerItem ex, ItemStack tool, Running r, BlockPos pos, int ring) {
        ServerLevel level = r.level();
        if (!level.isLoaded(pos)) {
            return ExchangeJob.Swap.SKIP;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.equals(r.match()) || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0 && !ex.tier().creative()) {
            return ExchangeJob.Swap.SKIP;
        }
        if (ring > 1 && !exposed(level, pos)) {
            return ExchangeJob.Swap.SKIP;
        }
        if (!level.mayInteract(p, pos) || net.neoforged.neoforge.common.CommonHooks.fireBlockBreak(level, net.minecraft.world.level.GameType.SURVIVAL, p, pos, state).isCanceled()) {
            return ExchangeJob.Swap.SKIP;
        }
        long cost = EnderTechRules.exchangeCost(ring, state.getDestroySpeed(level, pos));
        if (!ex.tier().creative() && ItemEnergy.get(tool) < cost) {
            return ExchangeJob.Swap.STOP;
        }
        Inventory inv = p.getInventory();
        int slot = -1;
        if (!ex.tier().creative() && !p.getAbilities().instabuild) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(r.replacement().getBlock().asItem())) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                return ExchangeJob.Swap.STOP;
            }
        }
        // Drops: silk-touch result when the block allows it (original default), otherwise normal loot.
        ItemStack silk = new ItemStack(Items.NETHERITE_PICKAXE);
        silk.enchant(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH), 1);
        List<ItemStack> drops = ex.tier().creative() ? List.of() : Block.getDrops(state, level, pos, null, p, silk);
        if (!fits(inv, drops, slot)) {
            return ExchangeJob.Swap.STOP;
        }
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        level.setBlock(pos, r.replacement(), Block.UPDATE_ALL);
        if (EventHooks.onBlockPlace(p, snapshot, Direction.UP)) {
            snapshot.restore(Block.UPDATE_CLIENTS);
            return ExchangeJob.Swap.SKIP;
        }
        if (slot >= 0) {
            inv.removeItem(slot, 1);
        }
        for (ItemStack d : drops) {
            inv.add(d.copy());
        }
        ItemEnergy.consume(tool, cost, ex.capacity());
        level.levelEvent(2001, pos, Block.getId(state));
        return ExchangeJob.Swap.DONE;
    }

    /** Exposed: a neighbour is air or does not occlude, or is a fence / gate / torch (original exceptions). */
    private static boolean exposed(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(pos.relative(d));
            if (n.isAir() || !n.canOcclude() || n.getBlock() instanceof FenceBlock || n.getBlock() instanceof FenceGateBlock
                    || n.getBlock() instanceof TorchBlock) {
                return true;
            }
        }
        return false;
    }

    /** Simulates inserting the drops (after the replacement leaves {@code freedSlot}) into a copy of the inventory. */
    private static boolean fits(Inventory inv, List<ItemStack> drops, int freedSlot) {
        List<ItemStack> copy = new ArrayList<>();
        for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
            ItemStack s = inv.getNonEquipmentItems().get(i).copy();
            if (i == freedSlot) {
                s.shrink(1);
            }
            copy.add(s);
        }
        for (ItemStack d : drops) {
            int left = d.getCount();
            for (int i = 0; i < copy.size() && left > 0; i++) {
                ItemStack s = copy.get(i);
                if (s.isEmpty()) {
                    copy.set(i, d.copyWithCount(Math.min(left, d.getMaxStackSize())));
                    left -= Math.min(left, d.getMaxStackSize());
                } else if (ItemStack.isSameItemSameComponents(s, d)) {
                    int add = Math.min(left, s.getMaxStackSize() - s.getCount());
                    s.grow(add);
                    left -= add;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }
}
