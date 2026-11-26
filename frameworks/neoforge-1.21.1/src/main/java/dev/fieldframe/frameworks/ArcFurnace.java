package dev.fieldframe.frameworks;

import dev.fieldframe.api.frame.FrameStructure;
import dev.fieldframe.api.frame.MachineHub;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessFamilies;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.neoforge.api.FieldframeForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Arc Furnace: a Grand Frame with Arc Electrodes inside. Every {@code arc_furnace.interval} ticks, when the frame's RF
 * buffer holds {@code arc_furnace.arc_rf} per electrode and there is work, one arc fires: each electrode works up to
 * {@code arc_furnace.items_per_electrode} items from the frame's Orange storage into its Green storage, arc alloys
 * ({@link ArcAlloys}) first, then anything with a heating (smelting) result. The RF is taken only when the arc did
 * something. A Storm Coil on a roof socket shares the frame's RF buffer like any module, so each lightning strike
 * charges it; Frame Cells let it bank the bursts. The work itself is {@link ArcWork}.
 */
public final class ArcFurnace {
    /** Tuning {@code arc_furnace.interval}. */
    public static int interval() {
        return FwTuning.getInt("arc_furnace.interval");
    }

    /** Tuning {@code arc_furnace.arc_rf}. */
    public static long arcRf() {
        return FwTuning.getLong("arc_furnace.arc_rf");
    }

    /** Tuning {@code arc_furnace.items_per_electrode}. */
    public static int itemsPerElectrode() {
        return FwTuning.getInt("arc_furnace.items_per_electrode");
    }

    private int electrodes;
    private ArcWork.Result last = ArcWork.Result.NONE;
    private long arcs;

    public int electrodes() {
        return electrodes;
    }

    void setElectrodes(int n) {
        electrodes = Math.max(0, n);
    }

    /** What the last arc did (not saved). */
    public ArcWork.Result last() {
        return last;
    }

    /** Arcs fired since the frame loaded (not saved). */
    public long arcs() {
        return arcs;
    }

    /** RF one arc of this furnace takes. */
    public long rfPerArc() {
        return arcRf() * electrodes;
    }

    /** One tick of a formed Arc Furnace; returns true when an arc fired. */
    boolean tick(Level level, MachineHub hub, FrameStructure.Result frame) {
        if (electrodes <= 0 || level.getGameTime() % Math.max(1, interval()) != 0) {
            return false;
        }
        long rf = rfPerArc();
        if (hub.energy().extract(rf, true) < rf) {
            return false;
        }
        ArcWork.Result r = ArcWork.run(hub.items(0), hub.items(1), ArcAlloys.all(),
                FieldframeForgeApi.processRecipes(level, ProcessFamilies.HEATING), FieldframeForgeApi.tags(),
                ArcFurnace::variant, electrodes * itemsPerElectrode(), level.getRandom()::nextDouble);
        if (!r.any()) {
            return false;
        }
        hub.energy().extract(rf, false);
        last = r;
        arcs++;
        if (level instanceof ServerLevel sl && frame != null) {
            double x = (frame.minX() + frame.maxX()) / 2.0 + 0.5;
            double y = (frame.minY() + frame.maxY()) / 2.0 + 0.5;
            double z = (frame.minZ() + frame.maxZ()) / 2.0 + 0.5;
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 30, 1.2, 1.2, 1.2, 0.4);
            sl.playSound(null, BlockPos.containing(x, y, z), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, 0.8f, 1.4f);
        }
        return true;
    }

    static ItemVariant variant(FfId id) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path()));
        return item == null || item == Items.AIR ? null : FieldframeForgeApi.variantOf(new ItemStack(item));
    }
}
