package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.MachineLook;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static dev.fieldframe.core.TestSupport.COAL;
import static dev.fieldframe.core.TestSupport.IRON_ORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comparator output, the machine-sounds switch and the working signal behind lit faceplates. */
class MachinePolishTest {
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
    }

    @Test
    void comparatorReadsGreenStorageByDefaultWithTheVanillaRule() {
        assertEquals(ChassisCore.DEFAULT_COMPARATOR, c.comparatorSource());
        assertEquals(0, c.comparatorLevel(), "empty reads 0");
        c.lane(1).insert(IRON_ORE, 1, false);
        assertEquals(1, c.comparatorLevel(), "anything at all reads at least 1");
        int slots = c.lane(1).slots();
        c.lane(1).insert(IRON_ORE, 64 * slots, false);
        assertEquals(15, c.comparatorLevel(), "full reads 15");
        c.lane(0).insert(COAL, 64, false);
        assertTrue(c.setComparatorSource("storage_0").ok());
        assertEquals(1 + (int) Math.floor(14.0 / slots), c.comparatorLevel(), "one full slot of " + slots);
    }

    @Test
    void comparatorCanReadTanksAndRfAndRefusesNonsense() {
        assertFalse(c.setComparatorSource("lava").ok());
        assertTrue(c.setComparatorSource("energy").ok());
        assertEquals(0, c.comparatorLevel());
        c.energy().receive(c.energy().capacity() / 2, false);
        assertEquals(8, c.comparatorLevel(), "half full: 1 + 14 x 0.5");
        assertTrue(c.setComparatorSource("off").ok());
        assertEquals(0, c.comparatorLevel());
    }

    @Test
    void comparatorSourceAndQuietSurviveSaveAndLoad() {
        assertTrue(c.setComparatorSource("tank_2").ok());
        c.setQuiet(true);
        FfData d = c.save();
        ChassisCore back = TestSupport.chassis(w);
        assertTrue(back.load(d).isEmpty());
        assertEquals("tank_2", back.comparatorSource());
        assertTrue(back.isQuiet());
        ChassisCore fresh = TestSupport.chassis(w);
        assertTrue(fresh.load(TestSupport.chassis(w).save()).isEmpty());
        assertEquals(ChassisCore.DEFAULT_COMPARATOR, fresh.comparatorSource());
        assertFalse(fresh.isQuiet());
    }

    @Test
    void workingMaskLightsWhileWorkingAndFadesAfterTheLinger() {
        assertTrue(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
        assertTrue(c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        c.lane(0).insert(IRON_ORE, 1, false);
        c.lane(2).insert(COAL, 1, false);
        TestSupport.run(c, w, 20);
        int top = 1 << LocalFace.TOP.ordinal();
        int bottom = 1 << LocalFace.BOTTOM.ordinal();
        assertEquals(top | bottom, c.workingMask() & (top | bottom), "heater and engine both lit");
        TestSupport.run(c, w, 2000);
        assertEquals(0, c.workingMask() & top, "the heater has finished and gone dark");
    }

    @Test
    void advancementRulesFollowTheManual() {
        assertEquals(java.util.List.of(dev.fieldframe.core.chassis.Milestones.IT_WORKS),
                dev.fieldframe.core.chassis.Milestones.whenWorking(FieldframeRegistry.module(BuiltinModules.HEATER)));
        assertTrue(dev.fieldframe.core.chassis.Milestones.whenWorking(FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE))
                .contains(dev.fieldframe.core.chassis.Milestones.SELF_POWERED));
        assertTrue(dev.fieldframe.core.chassis.Milestones.whenWorking(FieldframeRegistry.module(BuiltinModules.ITEM_PORT))
                .contains(dev.fieldframe.core.chassis.Milestones.HANDS_OFF));
        var tiers = FieldframeRegistry.tiers();
        assertTrue(dev.fieldframe.core.chassis.Milestones.whenTier(FieldframeRegistry.tierAt(0)).isEmpty(), "the base tier grants nothing");
        assertEquals(java.util.List.of(dev.fieldframe.core.chassis.Milestones.BETTER_FRAME),
                dev.fieldframe.core.chassis.Milestones.whenTier(FieldframeRegistry.tierAt(1)));
        assertTrue(dev.fieldframe.core.chassis.Milestones.whenTier(tiers.get(tiers.size() - 1))
                .contains(dev.fieldframe.core.chassis.Milestones.RESONANT));
    }

    @Test
    void builtInLooksAndHintsOverrideThem() {
        ModuleDefinition heater = FieldframeRegistry.module(BuiltinModules.HEATER);
        assertEquals("fieldframe:module.heat", MachineLook.sound(heater));
        assertEquals("minecraft:smoke", MachineLook.particle(heater));
        assertEquals("fieldframe:block/module_glow/heater", MachineLook.glowTexture(heater));
        ModuleDefinition port = FieldframeRegistry.module(BuiltinModules.ITEM_PORT);
        assertEquals("", MachineLook.sound(port), "ports make no working sound");
        ModuleDefinition custom = heater.toBuilder()
                .presentation(new FfData().putString("work_sound", "").putString("work_particle", "minecraft:flame")).build();
        assertEquals("", MachineLook.sound(custom));
        assertEquals("minecraft:flame", MachineLook.particle(custom));
        for (String s : MachineLook.SOUND_EVENTS) {
            assertTrue(s.matches("[a-z_.]+"), s);
        }
    }
}
