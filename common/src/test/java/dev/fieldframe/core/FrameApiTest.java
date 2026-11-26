package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.frame.FrameStructure;
import dev.fieldframe.api.frame.MachineHub;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.MachineHubImpl;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.fieldframe.core.TestSupport.COAL;
import static dev.fieldframe.core.TestSupport.IRON;
import static dev.fieldframe.core.TestSupport.IRON_ORE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frame API: hollow-box validation and the shared-storage hub that frame sockets link to. */
class FrameApiTest {
    SimulatedWorld w;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
    }

    /** A world of parts keyed "x,y,z"; anything missing is air. */
    static final class Box {
        final Map<String, FrameStructure.Part> parts = new HashMap<>();

        Box shell(int size, int cx, int cy, int cz) {
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        boolean outside = x == 0 || y == 0 || z == 0 || x == size - 1 || y == size - 1 || z == size - 1;
                        if (outside) {
                            parts.put(x + "," + y + "," + z, FrameStructure.Part.SHELL);
                        }
                    }
                }
            }
            parts.put(cx + "," + cy + "," + cz, FrameStructure.Part.CORE);
            return this;
        }

        FrameStructure.Part at(int x, int y, int z) {
            return parts.getOrDefault(x + "," + y + "," + z, FrameStructure.Part.AIR);
        }
    }

    @Test
    void validFiveCubeHasFiftyFourSockets() {
        Box b = new Box().shell(5, 2, 0, 0);
        FrameStructure.Result r = FrameStructure.validate(2, 0, 0, b::at, 5, 9);
        assertTrue(r.valid(), r.problem());
        assertEquals(54, r.sockets());
        assertEquals(27, r.interiorVolume());
        assertTrue(r.socket(2, 2, 0), "middle of a wall");
        assertFalse(r.socket(0, 0, 2), "an edge");
        assertFalse(r.socket(2, 2, 2), "inside");
        assertArrayEquals(new int[]{0, 0, -1}, r.outward(2, 2, 0));
        assertArrayEquals(new int[]{0, 1, 0}, r.outward(2, 4, 2));
    }

    @Test
    void problemsAreNamed() {
        Box hole = new Box().shell(5, 2, 0, 0);
        hole.parts.remove("4,2,2");
        FrameStructure.Result r = FrameStructure.validate(2, 0, 0, hole::at, 5, 9);
        assertEquals("shell_missing", r.problem());
        assertArrayEquals(new int[]{4, 2, 2}, r.args());

        Box blocked = new Box().shell(5, 2, 0, 0);
        blocked.parts.put("2,2,2", FrameStructure.Part.OTHER);
        assertEquals("interior_blocked", FrameStructure.validate(2, 0, 0, blocked::at, 5, 9).problem());

        Box filled = new Box().shell(5, 2, 0, 0);
        filled.parts.put("2,2,2", FrameStructure.Part.INTERIOR);
        assertTrue(FrameStructure.validate(2, 0, 0, filled::at, 5, 9).valid(), "components inside are fine");

        assertEquals("too_small", FrameStructure.validate(1, 0, 0, new Box().shell(3, 1, 0, 0)::at, 5, 9).problem());
        assertEquals("too_big", FrameStructure.validate(5, 0, 0, new Box().shell(11, 5, 0, 0)::at, 5, 9).problem());

        Box two = new Box().shell(5, 2, 0, 0);
        two.parts.put("2,4,4", FrameStructure.Part.CORE);
        assertEquals("cores", FrameStructure.validate(2, 0, 0, two::at, 5, 9).problem());
    }

    @Test
    void socketsShareTheHubAndSaveNothingThemselves() {
        MachineHubImpl hub = new MachineHubImpl(VariantCodec.SIMPLE);
        hub.resize(9, 16_000, 100_000);
        ChassisCore a = TestSupport.chassis(w);
        ChassisCore b = TestSupport.chassis(w);
        hub.link(a);
        hub.link(b);
        assertSame(a.lane(0), b.lane(0));
        assertEquals(9, a.lane(0).slots());
        assertEquals(16_000, b.tank(0).capacity());
        assertTrue(a.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
        assertTrue(b.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        a.lane(0).insert(IRON_ORE, 2, false);
        b.lane(2).insert(COAL, 1, false);
        for (int i = 0; i < 400; i++) {
            w.time++;
            a.tick(w);
            b.tick(w);
        }
        assertEquals(2, hub.itemCount(1), "the heater on one socket ran on the engine on another");
        assertTrue(a.storesBorrowed());
        FfData saved = a.save();
        ChassisCore again = TestSupport.chassis(w);
        assertTrue(again.load(saved).isEmpty());
        assertEquals(0, again.lane(1).totalCount(), "a socket never saves the hub's contents");
        assertEquals(1, again.lane(1).slots() > 0 ? 1 : 0);

        MachineHubImpl back = new MachineHubImpl(VariantCodec.SIMPLE);
        assertTrue(back.load(hub.save()).isEmpty());
        assertEquals(2, back.itemCount(1));
        assertEquals(9, back.slotsPerLane());
        assertEquals(100_000, back.rfCapacity());
    }

    @Test
    void shrinkingSpillsWhatNoLongerFitsAndUnlinkedSocketsGoDormant() {
        MachineHubImpl hub = new MachineHubImpl(VariantCodec.SIMPLE);
        hub.resize(9, 16_000, 100_000);
        ChassisCore a = TestSupport.chassis(w);
        hub.link(a);
        for (int i = 0; i < 9; i++) {
            a.lane(0).insert(ItemVariant.Simple.of("minecraft:dirt_" + i), 64, false);
        }
        a.tank(0).fill(FluidVariant.Simple.of("minecraft:water"), 10_000, false);
        List<MachineHub.Spill> spill = hub.resize(4, 4_000, 16_000);
        int dropped = spill.stream().mapToInt(MachineHub.Spill::count).sum();
        assertEquals(64 * 5, dropped);
        assertEquals(64 * 4, hub.itemCount(0));
        assertEquals(4_000, hub.fluidAmount(0), "fluid beyond the new size is lost");
        hub.unlink(a);
        assertTrue(a.isAwaitingLink(), "dormant until the frame forms again");
        assertFalse(a.storesBorrowed());
        assertNull(a.exposedItems(dev.fieldframe.api.face.WorldDir.NORTH));
        assertEquals(64 * 4, hub.drainAll().stream().mapToInt(MachineHub.Spill::count).sum());
        assertEquals(0, hub.itemCount(0));
        a.lane(0).insert(IRON, 1, false);
        assertEquals(1, a.lane(0).totalCount(), "its own stores again");
    }
}
