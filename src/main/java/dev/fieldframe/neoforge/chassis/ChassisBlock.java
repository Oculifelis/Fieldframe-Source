package dev.fieldframe.neoforge.chassis;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.neoforge.adapter.Dirs;
import dev.fieldframe.neoforge.item.ConfiguratorItem;
import dev.fieldframe.neoforge.item.ModuleItem;
import dev.fieldframe.neoforge.item.UpgradeItem;
import dev.fieldframe.neoforge.menu.LaneAccessMenu;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.ItemInteractionResult;

/**
 * The chassis block. Interactions are explicit and server-validated:
 * <ul>
 *   <li>module item on a face: install (consumes the item in survival);</li>
 *   <li>upgrade item: install into the first free service bay;</li>
 *   <li>configurator: open the inspector for the clicked face; sneak on an occupied face: remove the module;
 *       sneak on an empty face: rotate the chassis;</li>
 *   <li>empty hand on an access panel face: open the lanes that panel permits.</li>
 * </ul>
 */
public class ChassisBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<ChassisBlock> CODEC = simpleCodec(ChassisBlock::new);

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    public ChassisBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChassisBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != blockEntityType()) {
            return null;
        }
        return (l, p, s, be) -> ChassisBlockEntity.serverTick(l, p, s, (ChassisBlockEntity) be);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player p && !(p instanceof net.neoforged.neoforge.common.util.FakePlayer)
                && level.getBlockEntity(pos) instanceof ChassisBlockEntity be) {
            be.core().setOwner(p.getUUID().toString(), p.getGameProfile().getName());
            be.setChanged();
        }
    }

    public static LocalFace localFace(ChassisBlockEntity be, Direction hitSide) {
        return be.core().orientation().toLocal(Dirs.toWorld(hitSide));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return interact(state, level, pos, player, InteractionHand.MAIN_HAND, hit, ItemStack.EMPTY);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof dev.fieldframe.neoforge.item.ProgramCardItem && level.getBlockEntity(pos) instanceof ChassisBlockEntity be) {
            if (!level.isClientSide) {
                dev.fieldframe.neoforge.item.ProgramCardItem.useOn(be, localFace(be, hit.getDirection()), player, stack);
            }
            return ItemInteractionResult.SUCCESS;
        }
        if (!(stack.getItem() instanceof ModuleItem || stack.getItem() instanceof UpgradeItem || stack.getItem() instanceof ConfiguratorItem
                || stack.getItem() instanceof dev.fieldframe.neoforge.item.TierKitItem
                || stack.getItem() instanceof dev.fieldframe.neoforge.item.FrameBoltItem)) {
            // Tank Access Panel: fill or empty the permitted reservoirs with any fluid container.
            if (level.getBlockEntity(pos) instanceof ChassisBlockEntity be
                    && net.neoforged.neoforge.fluids.FluidUtil.getFluidHandler(stack).isPresent()) {
                var panelTanks = be.core().panelTanks(localFace(be, hit.getDirection()));
                if (panelTanks != null) {
                    if (!level.isClientSide) {
                        var handler = new dev.fieldframe.neoforge.adapter.NeoHandlers.ExposedFluids(() -> panelTanks);
                        if (net.neoforged.neoforge.fluids.FluidUtil.interactWithFluidHandler(player, hand, handler)) {
                            be.setChanged();
                        }
                    }
                    return ItemInteractionResult.sidedSuccess(level.isClientSide);
                }
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        InteractionResult r = interact(state, level, pos, player, hand, hit, stack);
        return switch (r) {
            case SUCCESS, SUCCESS_NO_ITEM_USED -> ItemInteractionResult.SUCCESS;
            case CONSUME, CONSUME_PARTIAL -> ItemInteractionResult.CONSUME;
            case FAIL -> ItemInteractionResult.FAIL;
            default -> ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        };
    }

    /** The block entity type this block uses (subclasses with their own type override it, see the frame API). */
    protected net.minecraft.world.level.block.entity.BlockEntityType<?> blockEntityType() {
        return FfContent.CHASSIS_BE.get();
    }

    /** Whether players may install, configure or remove modules on {@code side}; module sockets allow one side only. */
    protected boolean usableFace(Level level, BlockPos pos, ChassisBlockEntity be, Direction side) {
        return true;
    }

    /** Whether sneak-using the Configurator on an empty side rotates this block. */
    protected boolean rotatable() {
        return true;
    }

    private InteractionResult interact(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit, ItemStack held) {
        if (!(level.getBlockEntity(pos) instanceof ChassisBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!usableFace(level, pos, be, hit.getDirection())) {
            return InteractionResult.PASS;
        }
        LocalFace face = localFace(be, hit.getDirection());
        ChassisCore core = be.core();
        if (held.getItem() instanceof dev.fieldframe.neoforge.item.FrameBoltItem) {
            return BoltLinks.use(level, pos, hit.getDirection(), player, held);
        }
        boolean editing = held.getItem() instanceof ModuleItem || held.getItem() instanceof UpgradeItem
                || held.getItem() instanceof dev.fieldframe.neoforge.item.TierKitItem
                || held.getItem() instanceof ConfiguratorItem && player.isShiftKeyDown();
        if (editing && !ChassisAccess.mayEdit(player, core)) {
            dev.fieldframe.core.util.MpProbe.REFUSED.incrementAndGet();
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("fieldframe.msg.not_owner", core.ownerName()), true);
            }
            return InteractionResult.FAIL;
        }
        if (held.getItem() instanceof ModuleItem mi) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            ModuleDefinition def = FieldframeRegistry.module(mi.moduleId());
            if (def == null) {
                player.displayClientMessage(Component.translatable("fieldframe.msg.unknown_module", mi.moduleId().toString()), true);
                return InteractionResult.FAIL;
            }
            if (BoltLinks.touchesOwnMachine(level, be, hit.getDirection())) {
                player.displayClientMessage(Component.translatable("fieldframe.msg.face_bolted"), true);
                return InteractionResult.FAIL;
            }
            ChassisCore.Result r = core.install(face, def, ModuleItem.storedConfig(held));
            if (!r.ok()) {
                player.displayClientMessage(Component.translatable("fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), face.key(), r.detail()), true);
                return InteractionResult.FAIL;
            }
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            level.playSound(null, pos, FfContent.sound("machine.install"), SoundSource.BLOCKS, 1f, 1f);
            FfAdvancements.award(player, dev.fieldframe.core.chassis.Milestones.FIRST_MODULE);
            return InteractionResult.CONSUME;
        }
        if (held.getItem() instanceof UpgradeItem ui) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            var up = FieldframeRegistry.upgrade(ui.upgradeId());
            for (int bay = 0; bay < core.serviceBays(); bay++) {
                if (core.upgrade(bay) == null && up != null) {
                    ChassisCore.Result r = core.installUpgrade(bay, up);
                    if (r.ok() && !player.getAbilities().instabuild) {
                        held.shrink(1);
                    }
                    if (r.ok()) {
                        level.playSound(null, pos, FfContent.sound("machine.upgrade"), SoundSource.BLOCKS, 1f, 1f);
                        FfAdvancements.award(player, dev.fieldframe.core.chassis.Milestones.UPGRADED);
                    }
                    player.displayClientMessage(Component.translatable(r.ok() ? "fieldframe.msg.upgrade_installed" : "fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), bay + 1, r.detail()), true);
                    return r.ok() ? InteractionResult.CONSUME : InteractionResult.FAIL;
                }
            }
            player.displayClientMessage(Component.translatable("fieldframe.msg.no_free_bay"), true);
            return InteractionResult.FAIL;
        }
        if (held.getItem() instanceof dev.fieldframe.neoforge.item.TierKitItem kit) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            var next = FieldframeRegistry.tier(kit.tierId());
            var before = core.tier();
            ChassisCore.Result r = core.upgradeTier(next);
            if (!r.ok()) {
                var needed = next == null ? null : FieldframeRegistry.tierAt(next.level() - 1);
                player.displayClientMessage(Component.translatable("fieldframe.msg.wrong_tier",
                        needed == null ? Component.literal("?") : dev.fieldframe.neoforge.item.TierKitItem.tierName(needed),
                        dev.fieldframe.neoforge.item.TierKitItem.tierName(before)), true);
                return InteractionResult.FAIL;
            }
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            level.playSound(null, pos, FfContent.sound("machine.tier_up"), SoundSource.BLOCKS, 1f, 1f);
            FfAdvancements.award(player, dev.fieldframe.core.chassis.Milestones.whenTier(next));
            player.displayClientMessage(Component.translatable("fieldframe.msg.tier_upgraded", dev.fieldframe.neoforge.item.TierKitItem.tierName(next)), true);
            return InteractionResult.CONSUME;
        }
        if (held.getItem() instanceof ConfiguratorItem) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            ServerPlayer sp = (ServerPlayer) player;
            InstalledModule m = core.module(face);
            if (player.isShiftKeyDown()) {
                if (m != null) {
                    var spill = core.remove(face, be.world());
                    giveOrDrop(sp, ModuleItem.stackFor(m));
                    for (Object[] s : spill) {
                        if (s[0] instanceof dev.fieldframe.api.resource.ItemVariant v && s[1] instanceof Integer n) {
                            giveOrDrop(sp, dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, n));
                        }
                    }
                    level.playSound(null, pos, FfContent.sound("machine.remove"), SoundSource.BLOCKS, 1f, 1f);
                } else if (rotatable()) {
                    BlockState rotated = state.setValue(FACING, state.getValue(FACING).getClockWise());
                    level.setBlock(pos, rotated, 3);
                    be.setFront(rotated.getValue(FACING));
                    player.displayClientMessage(Component.translatable("fieldframe.msg.rotated", rotated.getValue(FACING).getName()), true);
                }
                return InteractionResult.CONSUME;
            }
            dev.fieldframe.neoforge.network.FfNetwork.openInspector(sp, be, face);
            return InteractionResult.CONSUME;
        }
        if (held.isEmpty()) {
            // Hand Crank and other modules that take an empty-hand use: turning costs the player a little hunger.
            if (!level.isClientSide && core.playerUse(face, player.isShiftKeyDown())) {
                player.causeFoodExhaustion(0.05f);
                be.setChanged();
                return InteractionResult.SUCCESS;
            }
            if (level.isClientSide && core.module(face) != null
                    && core.module(face).moduleId().equals(dev.fieldframe.core.modules.power.HandCrank.ID)) {
                return InteractionResult.SUCCESS;
            }
            InstalledModule m = core.module(face);
            if (m != null && !m.isMissing() && m.definition().exposes(dev.fieldframe.api.module.Exposure.PLAYER_ACCESS)) {
                if (!level.isClientSide) {
                    ((ServerPlayer) player).openMenu(LaneAccessMenu.provider(be), buf -> LaneAccessMenu.writeOpenData(buf, be));
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        if (held.isEmpty() && !player.isShiftKeyDown()) {
            // Right-click with an empty hand: show the machine screen (anyone may look; changes need the owner).
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                dev.fieldframe.neoforge.network.FfNetwork.openInspector(sp, be, face);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    private static void giveOrDrop(ServerPlayer p, ItemStack s) {
        if (!s.isEmpty() && !p.getInventory().add(s)) {
            p.drop(s, false);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ChassisBlockEntity be && !level.isClientSide) {
            BoltLinks.onBroken(level, be);
            for (ItemStack s : be.releaseContents()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
            }
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos from, boolean moving) {
        super.neighborChanged(state, level, pos, block, from, moving);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ChassisBlockEntity be) {
            be.onNeighborChanged();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (level.getBlockEntity(pos) instanceof ChassisBlockEntity be) {
            return be.core().redstoneOutput(Dirs.toWorld(direction.getOpposite()));
        }
        return 0;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** Comparator strength from the source picked on the machine screen (Green storage by default). */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ChassisBlockEntity be ? be.core().comparatorLevel() : 0;
    }

    /** Client: puffs and occasional sounds from each working module (MachineLook), like a lit furnace. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof ChassisBlockEntity be) || be.clientWorking() == 0) {
            return;
        }
        dev.fieldframe.api.face.Orientation o = new dev.fieldframe.api.face.Orientation(Dirs.toWorld(state.getValue(FACING)));
        for (LocalFace f : LocalFace.ALL) {
            if ((be.clientWorking() & (1 << f.ordinal())) == 0 || be.clientModule(f) == null) {
                continue;
            }
            ModuleDefinition def = FieldframeRegistry.module(be.clientModule(f));
            if (def == null) {
                continue;
            }
            Direction d = Dirs.toMc(o.toWorld(f));
            double x = pos.getX() + 0.5 + d.getStepX() * 0.52 + (d.getStepX() == 0 ? random.nextDouble() * 0.6 - 0.3 : 0);
            double y = pos.getY() + 0.5 + d.getStepY() * 0.52 + (d.getStepY() == 0 ? random.nextDouble() * 0.6 - 0.3 : 0);
            double z = pos.getZ() + 0.5 + d.getStepZ() * 0.52 + (d.getStepZ() == 0 ? random.nextDouble() * 0.6 - 0.3 : 0);
            net.minecraft.core.particles.ParticleOptions particle = particle(dev.fieldframe.core.chassis.MachineLook.particle(def));
            if (particle != null && random.nextInt(3) == 0) {
                level.addParticle(particle, x, y, z, d.getStepX() * 0.02, 0.01 + Math.max(0, d.getStepY()) * 0.02, d.getStepZ() * 0.02);
            }
            net.minecraft.sounds.SoundEvent sound = be.clientQuiet() ? null : sound(dev.fieldframe.core.chassis.MachineLook.sound(def));
            if (sound != null && random.nextDouble() < 0.08) {
                level.playLocalSound(x, y, z, sound, SoundSource.BLOCKS, 1f, 0.9f + random.nextFloat() * 0.2f, false);
            }
        }
    }

    private static final java.util.Map<String, java.util.Optional<net.minecraft.core.particles.ParticleOptions>> PARTICLES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, java.util.Optional<net.minecraft.sounds.SoundEvent>> SOUNDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static net.minecraft.core.particles.ParticleOptions particle(String id) {
        return id.isEmpty() ? null : PARTICLES.computeIfAbsent(id, k -> {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(k);
            var type = rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.get(rl);
            return java.util.Optional.ofNullable(type instanceof net.minecraft.core.particles.SimpleParticleType s ? s : null);
        }).orElse(null);
    }

    private static net.minecraft.sounds.SoundEvent sound(String id) {
        return id.isEmpty() ? null : SOUNDS.computeIfAbsent(id, k -> {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(k);
            if (rl == null) {
                return java.util.Optional.empty();
            }
            var registered = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(rl);
            return java.util.Optional.of(registered != null ? registered : net.minecraft.sounds.SoundEvent.createVariableRangeEvent(rl));
        }).orElse(null);
    }

    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (direction == null) {
            return false;
        }
        return level.getBlockEntity(pos) instanceof ChassisBlockEntity be && be.core().acceptsRedstone(Dirs.toWorld(direction.getOpposite()));
    }
}
