package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.FaceMarks;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The coloured storage marks drawn on chassis sides. */
class FaceMarksTest {
    @Test
    void marksListStorageAndTanksInBindingOrder() {
        TestSupport.freshRegistry();
        SimulatedWorld w = TestSupport.world();
        ChassisCore c = TestSupport.chassis(w);
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null);
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.MIXER), null);
        assertArrayEquals(new byte[]{0, 1}, FaceMarks.of(c.module(LocalFace.TOP)), "heater: Orange in, Green out");
        assertArrayEquals(new byte[]{2}, FaceMarks.of(c.module(LocalFace.BOTTOM)), "engine: Purple fuel; signals are not marked");
        assertArrayEquals(new byte[]{0, 1, 3, 4}, FaceMarks.of(c.module(LocalFace.LEFT)), "mixer: storages, then Orange and Green tanks");
        c.setBinding(LocalFace.TOP, "output", 0);
        assertArrayEquals(new byte[]{0}, FaceMarks.of(c.module(LocalFace.TOP)), "no repeats");
        assertEquals("block/mark/tank_1", FaceMarks.texture(4));
    }

    @Test
    void placeKeepsVertexOrderAndSitsInFrontOfTheFace() {
        // North face of a full block (normal -z), vertices in the usual order with u/v corners.
        float[][] pos = {{0, 1, 0}, {0, 0, 0}, {1, 0, 0}, {1, 1, 0}};
        float[][] uv = {{1, 0}, {1, 1}, {0, 1}, {0, 0}};
        float[][] out = FaceMarks.place(pos, uv, new float[]{0, 12, 4, 16}, 0, 0, -1);
        // Texels u 0..4 map to x 1..0.75 (u runs right to left on this face), v 12..16 map to y 0.25..0.
        assertArrayEquals(new float[]{0.75f, 0.25f, -FaceMarks.LIFT}, out[0], 1e-5f);
        assertArrayEquals(new float[]{0.75f, 0f, -FaceMarks.LIFT}, out[1], 1e-5f);
        assertArrayEquals(new float[]{1f, 0f, -FaceMarks.LIFT}, out[2], 1e-5f);
        assertArrayEquals(new float[]{1f, 0.25f, -FaceMarks.LIFT}, out[3], 1e-5f);
        assertNull(FaceMarks.place(pos, new float[][]{{0, 0}, {0, 0}, {0, 0}, {0, 0}}, new float[]{0, 0, 1, 1}, 0, 0, 1));
    }

    @Test
    void ringSplitsIntoASectionPerStorage() {
        assertEquals(4, FaceMarks.ring(0, 1).size(), "one storage colours the whole ring");
        assertEquals(2, FaceMarks.ring(0, 2).size());
        assertEquals(2, FaceMarks.ring(1, 2).size());
        assertEquals(1, FaceMarks.ring(3, 4).size());
        assertEquals(0, FaceMarks.ring(2, 2).size());
        for (int n = 1; n <= FaceMarks.MAX; n++) {
            int sides = 0;
            for (int i = 0; i < n; i++) {
                for (float[] r : FaceMarks.ring(i, n)) {
                    sides++;
                    // Second pixel ring from the edge only: never over the corner rivets or the module art (texels 3..12).
                    boolean onRing = (r[1] == 1 && r[3] == 2) || (r[1] == 14 && r[3] == 15) || (r[0] == 1 && r[2] == 2) || (r[0] == 14 && r[2] == 15);
                    assertEquals(true, onRing, java.util.Arrays.toString(r));
                }
            }
            assertEquals(4, sides, "the whole ring is used once for " + n + " marks");
        }
    }
}
