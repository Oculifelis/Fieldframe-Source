package dev.fieldframe.neoforge.item;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.blueprint.Blueprint;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.blueprint.BlueprintOps;
import dev.fieldframe.neoforge.adapter.Dirs;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blueprint Sheet. Stores a {@link Blueprint} (construction and configuration only).
 * <ul>
 *   <li>Sneak-use on a chassis: capture it and every directly connected chassis (up to 16, within 8 blocks).</li>
 *   <li>Use on a chassis with a stored design: show the differences and copy settings to matching modules only.</li>
 *   <li>Use on another block: build the next stage of the design from your own inventory (one chassis per use,
 *       then its modules and upgrades), rotated to the direction you face. Occupied or protected spots are skipped.</li>
 *   <li>Sneak-use in the air: clear the sheet.</li>
 * </ul>
 */
public final class BlueprintItem extends Item {
    private static final String KEY = "FieldframeBlueprint";

    public BlueprintItem(Properties props) {
        super(props);
    }

    public static Blueprint read(ItemStack stack) {
        var cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return null;
        }
        String json = cd.copyTag().getStringOr(KEY, "");
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return Blueprint.fromData(FfJson.parse(json));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void write(ItemStack stack, Blueprint bp) {
        if (bp == null) {
            stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            return;
        }
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putString(KEY, FfJson.toJsonString(bp.toData()));
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(t));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack s = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && read(s) != null) {
            if (!level.isClientSide()) {
                write(s, null);
                player.sendOverlayMessage(Component.translatable("fieldframe.blueprint.cleared"));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Player sp = player;
        ItemStack stack = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos();
        if (!level.mayInteract(player, pos)) {
            return InteractionResult.FAIL;
        }
        Blueprint stored = read(stack);
        boolean onChassis = level.getBlockEntity(pos) instanceof ChassisBlockEntity;
        if (player.isShiftKeyDown() && onChassis) {
            Blueprint bp = captureConnected(level, pos);
            write(stack, bp);
            sp.sendSystemMessage(Component.translatable("fieldframe.blueprint.captured", bp.chassis().size()));
            return InteractionResult.CONSUME;
        }
        if (stored == null) {
            sp.sendOverlayMessage(Component.translatable("fieldframe.blueprint.empty_hint"));
            return InteractionResult.FAIL;
        }
        if (onChassis) {
            ChassisBlockEntity be = (ChassisBlockEntity) level.getBlockEntity(pos);
            if (!dev.fieldframe.neoforge.chassis.ChassisAccess.mayEdit(player, be.core())) {
                sp.sendOverlayMessage(Component.translatable("fieldframe.msg.not_owner", be.core().ownerName()));
                return InteractionResult.FAIL;
            }
            Blueprint.Chassis plan = closestEntry(stored);
            List<String> diff = BlueprintOps.diff(plan, be.core());
            BlueprintOps.ApplyReport r = BlueprintOps.applyConfiguration(plan, be.core());
            be.setChanged();
            sp.sendSystemMessage(Component.translatable("fieldframe.blueprint.applied", r.applied(), r.skipped().size()));
            for (String d : diff) {
                sp.sendSystemMessage(Component.literal(" - " + d).withStyle(ChatFormatting.GRAY));
            }
            return InteractionResult.CONSUME;
        }
        return buildNextStage(ctx, sp, stored);
    }

    /** The entry at the origin (or the first one) is the reference for configuration copy. */
    private static Blueprint.Chassis closestEntry(Blueprint bp) {
        for (Blueprint.Chassis c : bp.chassis()) {
            if (c.dx() == 0 && c.dy() == 0 && c.dz() == 0) {
                return c;
            }
        }
        return bp.chassis().get(0);
    }

    private static Blueprint captureConnected(Level level, BlockPos origin) {
        List<BlueprintOps.Source> sources = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        while (!queue.isEmpty() && sources.size() < Blueprint.MAX_CHASSIS) {
            BlockPos p = queue.poll();
            if (!(level.getBlockEntity(p) instanceof ChassisBlockEntity be)) {
                continue;
            }
            sources.add(new BlueprintOps.Source(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ(), be.core()));
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (Math.abs(n.getX() - origin.getX()) <= Blueprint.MAX_OFFSET && Math.abs(n.getY() - origin.getY()) <= Blueprint.MAX_OFFSET
                        && Math.abs(n.getZ() - origin.getZ()) <= Blueprint.MAX_OFFSET && seen.add(n) && level.isLoaded(n)) {
                    queue.add(n);
                }
            }
        }
        return BlueprintOps.capture("Design", sources);
    }

    /**
     * Builds one stage: the first planned chassis position that is empty gets a chassis (from inventory); then every
     * already placed chassis of the plan gets its missing modules/upgrades if the player carries them; settings are
     * copied. Nothing is overwritten; protected positions are skipped.
     */
    private InteractionResult buildNextStage(UseOnContext ctx, Player player, Blueprint stored) {
        Level level = ctx.getLevel();
        BlockPos origin = ctx.getClickedPos().relative(ctx.getClickedFace());
        int turns = quarterTurnsFrom(player.getDirection());
        Blueprint bp = stored.rotated(turns);
        List<String> missing = new ArrayList<>();
        int placed = 0;
        int installed = 0;
        for (Blueprint.Chassis c : bp.chassis()) {
            BlockPos p = origin.offset(c.dx(), c.dy(), c.dz());
            if (!level.isLoaded(p) || !level.mayInteract(player, p)) {
                missing.add("blocked at " + p.toShortString());
                continue;
            }
            BlockState here = level.getBlockState(p);
            if (!(level.getBlockEntity(p) instanceof ChassisBlockEntity)) {
                if (placed > 0) {
                    continue; // one chassis per use: staged
                }
                if (!here.canBeReplaced()) {
                    missing.add("occupied at " + p.toShortString());
                    continue;
                }
                if (!takeItem(player, FfContent.CHASSIS_ITEM.get())) {
                    missing.add("chassis");
                    continue;
                }
                BlockPlaceContext bctx = new BlockPlaceContext(player, ctx.getHand(), new ItemStack(FfContent.CHASSIS_ITEM.get()),
                        new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false));
                BlockState state = FfContent.CHASSIS.get().getStateForPlacement(bctx);
                if (state == null || !level.setBlock(p, state.setValue(ChassisBlock.FACING, Dirs.toMc(c.front())), 3)) {
                    giveBack(player, new ItemStack(FfContent.CHASSIS_ITEM.get()));
                    missing.add("could not place at " + p.toShortString());
                    continue;
                }
                placed++;
                if (level.getBlockEntity(p) instanceof ChassisBlockEntity fresh) {
                    fresh.core().setOwner(player.getUUID().toString(), player.getGameProfile().name());
                }
            }
            if (!(level.getBlockEntity(p) instanceof ChassisBlockEntity be)) {
                continue;
            }
            if (!dev.fieldframe.neoforge.chassis.ChassisAccess.mayEdit(player, be.core())) {
                missing.add("not yours at " + p.toShortString());
                continue;
            }
            be.setFront(Dirs.toMc(c.front()));
            for (Map.Entry<LocalFace, Blueprint.Face> e : c.faces().entrySet()) {
                if (be.core().module(e.getKey()) != null) {
                    continue;
                }
                var def = FieldframeRegistry.module(e.getValue().module());
                ModuleItem item = FfContent.moduleItem(e.getValue().module());
                if (def == null || item == null) {
                    missing.add("unknown " + e.getValue().module());
                    continue;
                }
                if (!takeItem(player, item)) {
                    missing.add(e.getValue().module().toString());
                    continue;
                }
                if (be.core().install(e.getKey(), def, e.getValue().config()).ok()) {
                    installed++;
                } else {
                    giveBack(player, new ItemStack(item));
                }
            }
            for (FfId up : c.upgrades()) {
                boolean present = false;
                for (int b = 0; b < be.core().serviceBays(); b++) {
                    present |= up.equals(be.core().upgrade(b));
                }
                var def = FieldframeRegistry.upgrade(up);
                if (present || def == null || FfContent.upgradeItem(up) == null) {
                    continue;
                }
                if (!takeItem(player, FfContent.upgradeItem(up))) {
                    missing.add(up.toString());
                    continue;
                }
                boolean ok = false;
                for (int b = 0; b < be.core().serviceBays() && !ok; b++) {
                    ok = be.core().upgrade(b) == null && be.core().installUpgrade(b, def).ok();
                }
                if (!ok) {
                    giveBack(player, new ItemStack(FfContent.upgradeItem(up)));
                }
            }
            BlueprintOps.applyConfiguration(c, be.core());
            be.setChanged();
        }
        player.sendSystemMessage(Component.translatable("fieldframe.blueprint.stage", placed, installed));
        if (!missing.isEmpty()) {
            player.sendSystemMessage(Component.translatable("fieldframe.blueprint.missing", String.join(", ", missing.subList(0, Math.min(8, missing.size())))));
        }
        return InteractionResult.CONSUME;
    }

    private static int quarterTurnsFrom(Direction facing) {
        return switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    private static boolean takeItem(Player p, Item item) {
        if (p.getAbilities().instabuild) {
            return true;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item) && s.isComponentsPatchEmpty()) {
                s.shrink(1);
                return true;
            }
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    private static void giveBack(Player p, ItemStack s) {
        if (!p.getAbilities().instabuild && !p.getInventory().add(s)) {
            p.drop(s, false);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tip, TooltipFlag flag) {
        Blueprint bp = read(stack);
        if (bp == null) {
            tip.accept(Component.translatable("fieldframe.blueprint.empty_hint").withStyle(ChatFormatting.GRAY));
            return;
        }
        tip.accept(Component.translatable("fieldframe.blueprint.summary", bp.chassis().size()).withStyle(ChatFormatting.AQUA));
        bp.billOfMaterials(FfId.ff("chassis")).forEach((id, n) -> tip.accept(Component.literal(" " + n + "x " + id).withStyle(ChatFormatting.GRAY)));
        for (String p : bp.compatibilityProblems()) {
            tip.accept(Component.literal(" ! " + p).withStyle(ChatFormatting.RED));
        }
    }
}
