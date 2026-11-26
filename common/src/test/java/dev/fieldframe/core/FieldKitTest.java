package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.FieldKits;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.api.resource.VariantCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Field Kit: faces per tier, the allow list, and a pocket solar panel filling the bigger buffer. */
class FieldKitTest {
    SimulatedWorld w;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
    }

    @Test
    void threeFacesAtBaseThenOnePerTier() {
        assertEquals(3, FieldKits.faces(0));
        assertEquals(4, FieldKits.faces(1));
        assertEquals(6, FieldKits.faces(5));
        assertTrue(FieldKits.faceOpen(LocalFace.TOP, 0));
        assertFalse(FieldKits.faceOpen(LocalFace.LEFT, 0));
        assertTrue(FieldKits.faceOpen(LocalFace.LEFT, 1));
        assertFalse(FieldKits.faceOpen(LocalFace.BOTTOM, 2));
    }

    @Test
    void onlyAllowedModulesAndAddonsCanAddTheirs() {
        assertTrue(FieldKits.allowed(BuiltinModules.SOLAR));
        assertTrue(FieldKits.allowed(BuiltinModules.ITEM_CHARGER));
        assertFalse(FieldKits.allowed(BuiltinModules.HEATER));
        FfId addon = FfId.parse("example:pocket_thing");
        FieldKits.allow(addon);
        assertTrue(FieldKits.allowed(addon));
    }

    @Test
    void pocketSolarFillsTheKitBuffer() {
        ChassisCore kit = new ChassisCore(FieldKits.SPEC, VariantCodec.SIMPLE, () -> w.time);
        kit.setAddress("kit:test");
        assertTrue(kit.energy().capacity() > dev.fieldframe.api.chassis.ChassisSpec.STANDARD.energyCapacity());
        assertTrue(kit.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.SOLAR), null).ok());
        w.sky.add(LocalFace.TOP);
        TestSupport.run(kit, w, 200);
        assertTrue(kit.energy().stored() > 0, "stored " + kit.energy().stored());
    }
}
