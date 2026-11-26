package dev.fieldframe.core;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.energy.Energy;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.core.storage.EnergyBuffer;
import dev.fieldframe.core.storage.FluidReservoir;
import dev.fieldframe.core.storage.ItemLane;
import dev.fieldframe.core.storage.RateBudget;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientationAndStorageTest {
    @Test
    void orientationIsABijectionForEveryFacing() {
        for (WorldDir d : new WorldDir[]{WorldDir.NORTH, WorldDir.EAST, WorldDir.SOUTH, WorldDir.WEST}) {
            Orientation o = new Orientation(d);
            Set<WorldDir> seen = new HashSet<>();
            for (LocalFace f : LocalFace.ALL) {
                WorldDir w = o.toWorld(f);
                assertTrue(seen.add(w), "duplicate mapping");
                assertEquals(f, o.toLocal(w));
                assertEquals(o.toWorld(f.opposite()), w.opposite());
            }
            assertEquals(WorldDir.UP, o.toWorld(LocalFace.TOP));
        }
    }

    @Test
    void rotationKeepsLocalFacesAndTurnsWorldDirections() {
        Orientation o = Orientation.DEFAULT;
        assertEquals(WorldDir.WEST, o.toWorld(LocalFace.LEFT));
        assertEquals(WorldDir.EAST, o.toWorld(LocalFace.RIGHT));
        Orientation r = o.rotateClockwise();
        assertEquals(WorldDir.EAST, r.toWorld(LocalFace.FRONT));
        assertEquals(WorldDir.SOUTH, r.toWorld(LocalFace.RIGHT));
        assertEquals(1, r.quarterTurns());
        assertEquals(o, o.rotated(4));
        assertEquals(o.rotated(3), o.rotated(-1));
    }

    @Test
    void laneConservesAndSimulationIsPure() {
        ItemLane lane = new ItemLane("A", 2);
        ItemVariant v = ItemVariant.Simple.of("minecraft:stone");
        assertEquals(100, lane.insert(v, 100, true));
        assertEquals(0, lane.totalCount());
        assertEquals(100, lane.insert(v, 100, false));
        assertEquals(28, lane.insert(v, 100, false));
        assertEquals(128, lane.totalCount());
        assertEquals(0, lane.insert(ItemVariant.Simple.of("minecraft:dirt"), 1, false));
        assertEquals(64, lane.extract(v, 64, false));
        assertEquals(64, lane.totalCount());
    }

    @Test
    void componentSensitiveIdentity() {
        ItemLane lane = new ItemLane("A", 1);
        ItemVariant plain = ItemVariant.Simple.of("minecraft:diamond_sword");
        ItemVariant named = new ItemVariant.Simple(plain.id(), "{display:{Name:'x'}}", 1);
        assertEquals(1, lane.insert(named, 1, false));
        assertEquals(0, lane.insert(plain, 1, false));
        assertEquals(0, lane.extract(plain, 1, false));
        assertEquals(1, lane.extract(named, 1, false));
    }

    @Test
    void laneShrinkRefusesTruncationButCompacts() {
        ItemLane lane = new ItemLane("A", 4);
        ItemVariant a = ItemVariant.Simple.of("minecraft:stone");
        lane.setSlot(3, a, 10);
        assertTrue(lane.resize(1));
        assertEquals(10, lane.countOf(a));
        lane.resize(4);
        lane.insert(ItemVariant.Simple.of("minecraft:dirt"), 64, false);
        assertFalse(lane.resize(1));
        assertEquals(74, lane.totalCount());
    }

    @Test
    void laneSaveLoadRoundTrip() {
        ItemLane lane = new ItemLane("A", 4);
        lane.insert(ItemVariant.Simple.of("minecraft:stone"), 70, false);
        FfData d = lane.save(VariantCodec.SIMPLE);
        ItemLane copy = new ItemLane("A", 1);
        assertEquals(0, copy.load(d, VariantCodec.SIMPLE));
        assertEquals(4, copy.slots());
        assertEquals(70, copy.totalCount());
    }

    @Test
    void reservoirNeverMixesFluids() {
        FluidReservoir r = new FluidReservoir("A", 1000);
        FluidVariant water = FluidVariant.Simple.of("minecraft:water");
        FluidVariant lava = FluidVariant.Simple.of("minecraft:lava");
        assertEquals(600, r.fill(water, 600, false));
        assertEquals(0, r.fill(lava, 100, false));
        assertEquals(400, r.fill(water, 900, false));
        assertEquals(0, r.drain(lava, 100, false));
        assertFalse(r.setCapacity(500));
        assertEquals(1000, r.drain(null, 5000, false));
        assertEquals(null, r.fluid());
        assertEquals(100, r.fill(lava, 100, false));
    }

    @Test
    void energyIsOverflowSafe() {
        EnergyBuffer b = new EnergyBuffer(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, b.receive(Long.MAX_VALUE, false));
        assertEquals(0, b.receive(Long.MAX_VALUE, false));
        assertEquals(Integer.MAX_VALUE, Energy.clampToInt(b.stored()));
        assertEquals(Long.MAX_VALUE, Energy.saturatingAdd(Long.MAX_VALUE, 5));
        assertEquals(Long.MAX_VALUE, Energy.saturatingMul(Long.MAX_VALUE / 2, 3));
        assertFalse(b.consumeExactly(-1));
        EnergyBuffer small = new EnergyBuffer(100);
        assertEquals(100, small.receive(1000, true) + small.stored());
        assertFalse(small.consumeExactly(1));
    }

    @Test
    void jsSafeIntegerValidation() {
        assertEquals(5L, Energy.requireSafeNonNegative(5.0, "x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Energy.requireSafeNonNegative(1.5, "x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Energy.requireSafeNonNegative(Double.NaN, "x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Energy.requireSafeNonNegative(9.1e15, "x"));
    }

    @Test
    void budgetSpendsOnlyActualAndResetsPerTick() {
        RateBudget b = new RateBudget(10);
        b.resetFor(1);
        assertEquals(10, b.allow(50));
        b.spend(7);
        assertEquals(3, b.remaining());
        b.resetFor(1);
        assertEquals(3, b.remaining());
        b.resetFor(2);
        assertEquals(10, b.remaining());
    }
}
