package dev.fieldframe.frameworks;

import dev.fieldframe.api.frame.FrameComponents;
import dev.fieldframe.api.frame.FrameStructure;
import dev.fieldframe.api.id.FfId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Grand Frame rules: a hollow box of Frame Casings, 5 to 9 blocks on each side, with one Frame Core somewhere in its
 * shell. Inside: air or frame components. The shared storage starts at a chassis' size and every component inside
 * adds to it.
 */
public final class GrandFrame {
    private GrandFrame() {
    }

    /** Tuning {@code grand_frame.min_size}. */
    public static int minSize() {
        return FwTuning.getInt("grand_frame.min_size");
    }

    /** Tuning {@code grand_frame.max_size}. */
    public static int maxSize() {
        return FwTuning.getInt("grand_frame.max_size");
    }

    /** Base capacity (a Basic chassis' storage). */
    public static int baseSlots() {
        return FwTuning.getInt("grand_frame.base_slots");
    }

    /** Tuning {@code grand_frame.base_mb}. */
    public static long baseMb() {
        return FwTuning.getLong("grand_frame.base_mb");
    }

    /** Tuning {@code grand_frame.base_rf}. */
    public static long baseRf() {
        return FwTuning.getLong("grand_frame.base_rf");
    }

    public static final FfId CELL = FfId.of(Frameworks.MOD_ID, "frame_cell");
    public static final FfId TANK = FfId.of(Frameworks.MOD_ID, "frame_tank");
    public static final FfId VAULT = FfId.of(Frameworks.MOD_ID, "frame_vault");
    public static final FfId CRUCIBLE = FfId.of(Frameworks.MOD_ID, "frame_crucible");
    public static final FfId COIL = FfId.of(Frameworks.MOD_ID, "starcore_coil");
    public static final FfId FIN = FfId.of(Frameworks.MOD_ID, "cooling_fin");
    public static final FfId ELECTRODE = FfId.of(Frameworks.MOD_ID, "arc_electrode");

    static void registerComponents() {
        FrameComponents.register(CELL, new FrameComponents.Component(0, 0, 1_000_000, 0, "cell"));
        FrameComponents.register(TANK, new FrameComponents.Component(0, 16_000, 0, 0, "tank"));
        FrameComponents.register(VAULT, new FrameComponents.Component(3, 0, 0, 0, "vault"));
        FrameComponents.register(CRUCIBLE, new FrameComponents.Component(0, 0, 0, 0, "crucible"));
        FrameComponents.register(COIL, new FrameComponents.Component(0, 0, 0, 0, "coil"));
        FrameComponents.register(FIN, new FrameComponents.Component(0, 0, 0, 0, "cooling_fin"));
        FrameComponents.register(ELECTRODE, new FrameComponents.Component(0, 0, 0, 0, "arc_electrode"));
    }

    static FfId id(BlockState s) {
        Identifier rl = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        return FfId.of(rl.getNamespace(), rl.getPath());
    }

    /** What the frame sees at a position; never loads chunks (unloaded counts as something else). */
    static FrameStructure.Part part(Level level, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        if (!level.isLoaded(p)) {
            return FrameStructure.Part.OTHER;
        }
        BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof FrameCasingBlock) {
            return FrameStructure.Part.SHELL;
        }
        if (s.getBlock() instanceof FrameCoreBlock) {
            return FrameStructure.Part.CORE;
        }
        if (s.isAir()) {
            return FrameStructure.Part.AIR;
        }
        return FrameComponents.get(id(s)) != null ? FrameStructure.Part.INTERIOR : FrameStructure.Part.OTHER;
    }

    /**
     * Capacity of the frame: base plus every component inside. Returns {slots, mb, rf, crucibles, coils, cooling fins, arc electrodes}; the positions
     * of containment coils are added to {@code coilsOut}.
     */
    static long[] capacity(Level level, FrameStructure.Result r, java.util.List<BlockPos> coilsOut) {
        long slots = baseSlots();
        long mb = baseMb();
        long rf = baseRf();
        long crucibles = 0;
        long coils = 0;
        long fins = 0;
        long electrodes = 0;
        for (int x = r.minX() + 1; x < r.maxX(); x++) {
            for (int y = r.minY() + 1; y < r.maxY(); y++) {
                for (int z = r.minZ() + 1; z < r.maxZ(); z++) {
                    BlockPos at = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(at);
                    if (s.isAir()) {
                        continue;
                    }
                    FrameComponents.Component c = FrameComponents.get(id(s));
                    if (c != null) {
                        slots += c.slotsPerLane();
                        mb += c.mbPerTank();
                        rf += c.rfCapacity();
                        crucibles += c.role().equals("crucible") ? 1 : 0;
                        fins += c.role().equals("cooling_fin") ? 1 : 0;
                        electrodes += c.role().equals("arc_electrode") ? 1 : 0;
                        if (c.role().equals("coil")) {
                            coils++;
                            coilsOut.add(at);
                        }
                    }
                }
            }
        }
        return new long[]{slots, mb, rf, crucibles, coils, fins, electrodes};
    }
}
