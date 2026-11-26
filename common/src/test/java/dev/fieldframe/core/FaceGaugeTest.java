package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.FaceGauge;
import dev.fieldframe.core.chassis.MachineLook;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lamps and bar an Indicator Panel shows in the world. */
class FaceGaugeTest {
    @Test
    void indicatorPanelShowsItsChannelOnlyOnItsOwnFace() {
        TestSupport.freshRegistry();
        SimulatedWorld w = TestSupport.world();
        ChassisCore c = TestSupport.chassis(w);
        // A NOT gate with nothing on its input drives channel 2 at 15; the panel reads channel 2.
        FfData not = FieldframeRegistry.module(BuiltinModules.LOGIC_PLATE).defaultConfig().putString("op", "not");
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.LOGIC_PLATE), not);
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.INDICATOR), null);
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        int[] before = c.gaugeLevels();
        assertEquals(0, before[LocalFace.TOP.ordinal()], "nothing read yet");
        c.setBinding(LocalFace.TOP, "in", 2);
        for (int i = 0; i < 3; i++) {
            w.time++;
            c.tick(w);
        }
        int[] levels = c.gaugeLevels();
        assertEquals(15, levels[LocalFace.TOP.ordinal()]);
        assertEquals(FaceGauge.NONE, levels[LocalFace.LEFT.ordinal()], "a heater shows no gauge");
        assertEquals(FaceGauge.NONE, levels[LocalFace.FRONT.ordinal()]);
        assertEquals(FaceGauge.NONE, levels[LocalFace.BACK.ordinal()], "empty face");
        assertNotEquals(FaceGauge.key(before), FaceGauge.key(levels), "the client copy goes stale");
        assertEquals("", MachineLook.glowTexture(FieldframeRegistry.module(BuiltinModules.INDICATOR)),
                "the panel lights lamps, not its whole face");
    }

    @Test
    void lampsAndBarFollowTheLevel() {
        assertTrue(FaceGauge.lit(0).isEmpty(), "level 0: all dark");
        List<float[]> one = FaceGauge.lit(1);
        assertEquals(2, one.size(), "green lamp and a sliver of bar");
        assertEquals(0, one.get(0)[0]);
        assertEquals(FaceGauge.LAMPS, (int) one.get(1)[0]);
        assertEquals(3, FaceGauge.lit(8).size(), "amber from half");
        assertEquals(4, FaceGauge.lit(15).size(), "red at full");
        assertArrayEquals(FaceGauge.BAR, FaceGauge.bar(15), 1e-6f, "full bar");
        assertEquals(7f, FaceGauge.bar(5)[2], 1e-5f, "a third of the 6-texel bar from x 5");
        assertNull(FaceGauge.bar(0));
        assertFalse(FaceGauge.lampLit(2, 14));
    }

    @Test
    void anyModuleCanOptIn() {
        TestSupport.freshRegistry();
        assertFalse(FaceGauge.shows(FieldframeRegistry.module(BuiltinModules.HEATER)));
        assertTrue(FaceGauge.shows(FieldframeRegistry.module(BuiltinModules.INDICATOR)));
        assertEquals(0, FaceGauge.key(new int[]{-1, -1, -1, -1, -1, -1}));
        assertEquals(16 << 5, FaceGauge.key(new int[]{-1, 15, -1, -1, -1, -1}));
    }
}
