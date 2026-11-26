package dev.fieldframe.forge.test;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.util.PerfProbe;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.adapter.McItemVariant;
import dev.fieldframe.forge.adapter.NbtBridge;
import dev.fieldframe.forge.chassis.ChassisBlock;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import dev.fieldframe.forge.network.FfNetwork;
import dev.fieldframe.forge.network.InspectorData;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * In-game performance: N working chassis (heater + fuel engine) on a real server, each test in its own batch so
 * the server tick time is not shared with other tests. Logs FIELDFRAME_PERF lines (see docs/performance.md).
 */
@GameTestHolder(Fieldframe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PerfGameTests {
    private static final String T = "perf24";

    /** Duration of the previous server tick in ms. */
    static double lastTickMs(GameTestHelper h) {
        var server = h.getLevel().getServer();
        return server.tickTimes[(server.getTickCount() + 99) % 100] / 1e6;
    }

    static int bytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(out));
            return out.size();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void run(GameTestHelper h, int n) {
        List<ChassisBlockEntity> all = new ArrayList<>();
        double[] before = {0, 0};
        double[] after = {0, 0};
        // Ticks 5..44: server tick time before the chassis exist. Chassis placed at tick 45.
        // Ticks 105..264: tick time with N working chassis, and the exact cost of exactly those chassis.
        h.onEachTick(() -> {
            long t = h.getTick();
            if (t >= 5 && t < 45) {
                before[0] += lastTickMs(h);
                before[1]++;
            } else if (t >= 105 && t < 265) {
                after[0] += lastTickMs(h);
                after[1]++;
            }
        });
        h.runAtTickTime(45, () -> {
            for (int i = 0; i < n; i++) {
                BlockPos p = new BlockPos(i % 23, 1, i / 23);
                h.setBlock(p, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
                ChassisBlockEntity be = (ChassisBlockEntity) h.getBlockEntity(p);
                be.core().energy().receive(16_000, false);
                be.core().install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
                be.core().install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null);
                be.core().setBinding(LocalFace.BOTTOM, "fuel", 2);
                be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.RAW_IRON)), 64, false);
                be.core().lane(2).insert(McItemVariant.of(new ItemStack(Items.COAL)), 64, false);
                all.add(be);
                PerfProbe.TRACKED.add(be);
            }
        });
        long[] updates = {0};
        h.runAtTickTime(105, () -> {
            updates[0] = dev.fieldframe.core.util.MpProbe.CHASSIS_UPDATES.get();
            PerfProbe.reset();
            PerfProbe.enabled = n > 0; // an empty tracked set would time every chassis on the server
        });
        h.runAtTickTime(265, () -> {
            PerfProbe.enabled = false;
            PerfProbe.TRACKED.clear();
            double ns = PerfProbe.NANOS.get() / (double) Math.max(1, PerfProbe.TICKS.get());
            int updateTag = n == 0 ? 0 : bytes(all.get(0).getUpdateTag());
            int snapshot = n == 0 ? 0 : bytes(NbtBridge.toTag(InspectorData.build(all.get(0))));
            // Block updates of every chassis on the server in those 160 ticks (other tests' too, so an upper bound).
            double perSecond = n == 0 ? 0 : (dev.fieldframe.core.util.MpProbe.CHASSIS_UPDATES.get() - updates[0]) / (double) n / 8.0;
            double b = before[0] / Math.max(1, before[1]);
            double a = after[0] / Math.max(1, after[1]);
            Fieldframe.LOG.info("FIELDFRAME_PERF edition=forge-1.20.1 chassis={} mspt_before={} mspt_with={} mspt_delta={} chassis_ms_per_tick={} "
                            + "ns_per_chassis_tick={} chassis_ticks_timed={} be_update_bytes={} be_updates_per_chassis_second={} be_bytes_per_chassis_second={} "
                            + "inspector_snapshot_bytes={} inspector_bytes_per_second={}",
                    n, String.format("%.3f", b), String.format("%.3f", a), String.format("%.3f", a - b), String.format("%.3f", ns * n / 1e6),
                    Math.round(ns), PerfProbe.TICKS.get(), updateTag, String.format("%.3f", perSecond), Math.round(updateTag * perSecond), snapshot, snapshot * 20 / FfNetwork.SNAPSHOT_INTERVAL);
            h.assertTrue(n == 0 || PerfProbe.TICKS.get() == 160L * n, "timed exactly the test's chassis: " + PerfProbe.TICKS.get());
            h.succeed();
        });
    }

    @GameTest(template = T, timeoutTicks = 320, batch = "ff_perf_000")
    public static void perfBaseline(GameTestHelper h) {
        run(h, 0);
    }

    @GameTest(template = T, timeoutTicks = 320, batch = "ff_perf_100")
    public static void perf100(GameTestHelper h) {
        run(h, 100);
    }

    @GameTest(template = T, timeoutTicks = 320, batch = "ff_perf_500")
    public static void perf500(GameTestHelper h) {
        run(h, 500);
    }
}
