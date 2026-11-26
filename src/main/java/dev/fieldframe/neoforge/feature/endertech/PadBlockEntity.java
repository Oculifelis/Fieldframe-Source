package dev.fieldframe.neoforge.feature.endertech;

import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.endertech.PadLogic;
import dev.fieldframe.core.storage.EnergyBuffer;
import dev.fieldframe.neoforge.adapter.NeoHandlers;
import dev.fieldframe.neoforge.feature.FeatureContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;

import java.util.ArrayList;
import java.util.List;

/** Shared block entity of both pad kinds; kind and tier come from the block. */
public final class PadBlockEntity extends BlockEntity {
    private final EnergyBuffer buffer;
    private int ticksSinceHeal;
    private final EnergyHandler energyCap;

    public PadBlockEntity(BlockPos pos, BlockState state) {
        super(FeatureContent.PAD_BE.get(), pos, state);
        PadBlock b = (PadBlock) state.getBlock();
        buffer = new EnergyBuffer(b.tier().creative() ? 0 : b.tier().capacity);
        buffer.setOnChange(this::setChanged);
        EnergyStore receiveOnly = new EnergyStore() {
            @Override
            public long stored() {
                return buffer.stored();
            }

            @Override
            public long capacity() {
                return buffer.capacity();
            }

            @Override
            public long receive(long amount, boolean simulate) {
                return buffer.receive(Math.min(amount, tier().receivePerTick), simulate);
            }

            @Override
            public long extract(long amount, boolean simulate) {
                return 0;
            }

            @Override
            public boolean canExtract() {
                return false;
            }
        };
        energyCap = new NeoHandlers.ExposedEnergy(() -> receiveOnly, new NeoHandlers.ChassisJournal(() -> buffer.snapshot(), s -> buffer.restore((long[]) s)));
    }

    private PadBlock block() {
        return (PadBlock) getBlockState().getBlock();
    }

    private EnderTechRules.Tier tier() {
        return block().tier();
    }

    public EnergyBuffer buffer() {
        return buffer;
    }

    private Direction facing() {
        return getBlockState().getValue(PadBlock.FACING);
    }

    /** Box {@link EnderTechRules#PAD_RANGE} blocks deep in front of the face (original getAABBInFront(2)). */
    public AABB area() {
        Direction d = facing();
        int r = EnderTechRules.PAD_RANGE - 1;
        return new AABB(worldPosition.relative(d)).expandTowards(d.getStepX() * r, d.getStepY() * r, d.getStepZ() * r);
    }

    void serverTick() {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        long activity;
        if (block().health()) {
            List<PadLogic.Patient> patients = new ArrayList<>();
            for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, area(), LivingEntity::isAlive)) {
                patients.add(new PadLogic.Patient() {
                    public boolean hurt() {
                        return e.getHealth() < e.getMaxHealth();
                    }

                    public void healHalfHeart() {
                        e.heal(1);
                        sl.sendParticles(ParticleTypes.HEART, e.getX(), e.getY() + e.getBbHeight(), e.getZ(), 1, 0.2, 0.1, 0.2, 0);
                    }
                });
            }
            ticksSinceHeal = PadLogic.heal(tier(), buffer, patients, ticksSinceHeal, () -> sl.getRandom().nextInt(Integer.MAX_VALUE));
            activity = patients.size();
        } else {
            List<PadLogic.Receiver> receivers = new ArrayList<>();
            for (Entity e : sl.getEntities((Entity) null, area(), x -> x.isAlive() && (x instanceof Player || x instanceof ItemEntity))) {
                List<EnergyStore> items = new ArrayList<>();
                if (e instanceof Player p) {
                    for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
                        add(items, s);
                    }
                    for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                            net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                        add(items, p.getItemBySlot(slot));
                    }
                } else if (e instanceof ItemEntity ie && ie.getItem().getCount() == 1) {
                    add(items, ie.getItem());
                }
                if (!items.isEmpty()) {
                    receivers.add(new PadLogic.Receiver(items));
                }
            }
            activity = PadLogic.charge(tier(), buffer, receivers);
        }
        if (activity > 0 && sl.getGameTime() % 10 == 0) {
            Direction d = facing();
            sl.sendParticles(block().health() ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ELECTRIC_SPARK,
                    worldPosition.getX() + 0.5 + d.getStepX() * 0.6, worldPosition.getY() + 0.5 + d.getStepY() * 0.6,
                    worldPosition.getZ() + 0.5 + d.getStepZ() * 0.6, 3, 0.2, 0.2, 0.2, 0.01);
        }
    }

    private static void add(List<EnergyStore> out, ItemStack s) {
        if (s.isEmpty()) {
            return;
        }
        EnergyHandler h = net.neoforged.neoforge.transfer.access.ItemAccess.forStack(s).getCapability(Capabilities.Energy.ITEM);
        if (h != null && new NeoHandlers.ExternalEnergy(h).receive(1, true) == 1) {
            out.add(new NeoHandlers.ExternalEnergy(h));
        }
    }

    /** Registered for {@code Capabilities.Energy.BLOCK}: every side except the face, never for Creative. */
    public static EnergyHandler energyCap(PadBlockEntity be, Direction side) {
        return side == null || side == be.facing() || be.tier().creative() ? null : be.energyCap;
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput out) {
        super.saveAdditional(out);
        out.putLong("energy", buffer.stored());
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput in) {
        super.loadAdditional(in);
        buffer.receive(Math.max(0, in.getLongOr("energy", 0)) - buffer.stored(), false);
    }
}
