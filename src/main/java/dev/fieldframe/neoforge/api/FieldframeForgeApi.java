package dev.fieldframe.neoforge.api;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.network.InspectorData;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Public platform API for Forge 1.20.1 addons. Logical contracts (definitions, behaviors, events, manual model)
 * live in {@code dev.fieldframe.api}; this class covers the platform-specific hooks.
 */
public final class FieldframeForgeApi {
    private FieldframeForgeApi() {
    }

    public static final String API_VERSION = "0.1";

    /**
     * Runs {@code registration} at the startup registration phase (after all mod constructors, before Fieldframe
     * freezes its module registry and creates module items). Call from your mod constructor; register modules and
     * upgrades with {@link dev.fieldframe.api.FieldframeRegistry} inside the callback.
     */
    public static void onRegisterModules(Runnable registration) {
        Fieldframe.addStartupExtension(registration);
    }

    /** The item created for a module definition, or null if none (unknown id or before registration). */
    public static Item moduleItem(FfId moduleId) {
        return FfContent.moduleItem(moduleId);
    }

    /**
     * A read-only diagnostic snapshot of the chassis at {@code pos} (same data the inspector shows), or null.
     * Server side only. Never exposes mutable storage.
     */
    public static FfData inspect(Level level, BlockPos pos) {
        return inspect0(level, pos);
    }

    /**
     * Server side: points one connection of the module on {@code face} at a storage or tank (0 Orange, 1 Green,
     * 2 Purple) or a signal (0..7), or -1 for none where the connection allows it. Same validation as the machine
     * screen; returns false when refused (no module, unknown connection, wrong kind or out of range).
     */
    public static boolean setBinding(Level level, BlockPos pos, dev.fieldframe.api.face.LocalFace face, String binding, int index) {
        if (level == null || level.isClientSide || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ChassisBlockEntity be)) {
            return false;
        }
        boolean ok = be.core().setBinding(face, binding, index).ok();
        if (ok) {
            be.setChanged();
        }
        return ok;
    }

    /**
     * Server side: changes settings of the module on {@code face}, e.g. a Controller Plate's {@code program}. Keys not
     * given keep their value; the result goes through the same validation as the machine screen (unknown keys dropped,
     * values clamped). Returns false when there is no module there.
     */
    public static boolean setConfig(Level level, BlockPos pos, dev.fieldframe.api.face.LocalFace face, FfData values) {
        if (level == null || level.isClientSide || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ChassisBlockEntity be)) {
            return false;
        }
        var m = be.core().module(face);
        if (m == null || m.isMissing()) {
            return false;
        }
        FfData merged = m.config() == null ? new FfData() : m.config().copy();
        for (var e : values.asMap().entrySet()) {
            Object v = e.getValue();
            String k = e.getKey();
            if (v instanceof String s) {
                merged.putString(k, s);
            } else if (v instanceof Boolean b) {
                merged.putBool(k, b);
            } else if (v instanceof Integer i) {
                merged.putInt(k, i);
            } else if (v instanceof Long l) {
                merged.putLong(k, l);
            } else if (v instanceof Double d) {
                merged.putDouble(k, d);
            } else if (v instanceof FfData d) {
                merged.put(k, d.copy());
            } else if (v instanceof java.util.List<?> list) {
                merged.putList(k, new java.util.ArrayList<>(list));
            }
        }
        boolean ok = be.core().setConfig(face, merged).ok();
        if (ok) {
            be.setChanged();
        }
        return ok;
    }

    // ------------------------------------------------------------------ multiblocks (frame API)

    /** A new, empty shared storage for a multiblock; link its sockets with {@code ModuleSocketBlockEntity.link}. */
    public static dev.fieldframe.api.frame.MachineHub newMachineHub() {
        return new dev.fieldframe.core.chassis.MachineHubImpl(dev.fieldframe.neoforge.adapter.McVariantCodec.INSTANCE);
    }

    /** Saves a hub's contents for your block entity's data (item components need the registries). */
    public static net.minecraft.nbt.CompoundTag hubToTag(dev.fieldframe.api.frame.MachineHub hub, net.minecraft.core.HolderLookup.Provider registries) {
        dev.fieldframe.neoforge.adapter.RegistryHolder.set(registries);
        return dev.fieldframe.neoforge.adapter.NbtBridge.toTag(hub.save());
    }

    /** Loads what {@link #hubToTag} saved; returns problems (skipped entries). */
    public static java.util.List<String> hubFromTag(dev.fieldframe.api.frame.MachineHub hub, net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        dev.fieldframe.neoforge.adapter.RegistryHolder.set(registries);
        return hub.load(dev.fieldframe.neoforge.adapter.NbtBridge.fromTag(tag));
    }

    /** The item stack for a Fieldframe item variant (e.g. a hub spill to drop). */
    public static net.minecraft.world.item.ItemStack toStack(dev.fieldframe.api.resource.ItemVariant v, int count) {
        return dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, count);
    }

    /** The Fieldframe item variant of a stack (one item; components included). */
    public static dev.fieldframe.api.resource.ItemVariant variantOf(net.minecraft.world.item.ItemStack stack) {
        return dev.fieldframe.neoforge.adapter.McItemVariant.of(stack.copyWithCount(1));
    }

    /** The Fieldframe fluid variant of a fluid (no components). */
    public static dev.fieldframe.api.resource.FluidVariant variantOf(net.minecraft.world.level.material.Fluid fluid) {
        return dev.fieldframe.neoforge.adapter.McFluidVariant.of(new net.neoforged.neoforge.fluids.FluidStack(fluid, 1));
    }

    /** The effective Fieldframe recipes of a process family on this server (data, mirrored Metallurgy, scripts). */
    public static java.util.List<dev.fieldframe.api.process.ProcessRecipe> processRecipes(Level level, FfId family) {
        return dev.fieldframe.neoforge.recipe.RecipeBridge.forLevel(level).recipes(family);
    }

    /** Live item, fluid and block tags, for matching recipe inputs ({@code ItemMatcher.test}). */
    public static dev.fieldframe.api.resource.TagLookup tags() {
        return dev.fieldframe.neoforge.adapter.McTagLookup.INSTANCE;
    }

    /**
     * World actions (probe, break, place, use, drain and place fluid, collect) performed at {@code pos} as the given
     * owner, with Fieldframe's protection checks; targets are relative to the faces of a chassis facing {@code front}
     * (e.g. {@code new WorkTarget(LocalFace.BOTTOM, 3, 0, 0)} is three blocks below).
     */
    public static dev.fieldframe.api.world.WorldActions worldActions(net.minecraft.server.level.ServerLevel level, BlockPos pos,
                                                                    net.minecraft.core.Direction front, String ownerId, String ownerName) {
        return new dev.fieldframe.neoforge.chassis.ForgeWorldActions(level, pos,
                new dev.fieldframe.api.face.Orientation(dev.fieldframe.neoforge.adapter.Dirs.toWorld(front)), ownerId, ownerName);
    }

    /** Owner (player UUID and name) of the chassis or socket at {@code pos}, or null. Server side. */
    public static String[] owner(Level level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ChassisBlockEntity be)) {
            return null;
        }
        return new String[]{be.core().ownerId(), be.core().ownerName()};
    }

    /** Item, fluid and energy capabilities for a module socket block entity type (sides answer like a chassis). */
    public static <T extends dev.fieldframe.neoforge.api.frame.ModuleSocketBlockEntity> void registerSocketCapabilities(
            net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent e, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        e.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, type, (be, side) -> ChassisBlockEntity.itemCap(be, side));
        e.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, type, (be, side) -> ChassisBlockEntity.fluidCap(be, side));
        e.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, type, (be, side) -> ChassisBlockEntity.energyCap(be, side));
    }

    private static FfData inspect0(Level level, BlockPos pos) {
        if (level == null || level.isClientSide || !level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof ChassisBlockEntity be ? InspectorData.build(be) : null;
    }
}
