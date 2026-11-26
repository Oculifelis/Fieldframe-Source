package dev.fieldframe.frameworks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GantryStatusTest {
    @Test
    void everyStatusRoundTripsByKey() {
        for (GantryStatus s : GantryStatus.values()) {
            GantryStatus back = GantryStatus.fromKey(s.key());
            assertEquals(s == GantryStatus.UNCHECKED ? GantryStatus.IDLE : s, back, s.name());
        }
        assertEquals(GantryStatus.UNCHECKED, GantryStatus.fromKey("bogus"));
        assertEquals(GantryStatus.UNCHECKED, GantryStatus.fromKey(null));
    }

    @Test
    void formedIsDerived() {
        assertFalse(GantryStatus.RING_PROBLEM.formed());
        assertFalse(GantryStatus.UNCHECKED.formed());
        assertTrue(GantryStatus.DONE.formed());
    }

    @Test
    void doneSurvivesARecheckAndABrokenRingWins() {
        // A loaded done quarry re-forming must not restart: the wanted IDLE becomes DONE.
        assertEquals(GantryStatus.DONE, GantryStatus.next(GantryStatus.UNCHECKED, GantryStatus.IDLE, true));
        assertEquals(GantryStatus.RING_PROBLEM, GantryStatus.next(GantryStatus.DONE, GantryStatus.RING_PROBLEM, true));
        assertEquals(GantryStatus.MOVING, GantryStatus.next(GantryStatus.IDLE, GantryStatus.MOVING, false));
        assertTrue(GantryStatus.layersDone(-5, -4));
        assertFalse(GantryStatus.layersDone(Integer.MIN_VALUE, -4));
        assertFalse(GantryStatus.layersDone(-4, -4));
    }
}
