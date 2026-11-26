package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessFamilies;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.storage.ItemLane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.fieldframe.core.TestSupport.COAL;
import static dev.fieldframe.core.TestSupport.IRON;
import static dev.fieldframe.core.TestSupport.IRON_ORE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fuel that comes in through a port goes to the storage the engine burns from (ModuleContext#itemsIn). */
class FuelRoutingTest {
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
    }

    private FfData port(String mode) {
        return FieldframeRegistry.module(BuiltinModules.ITEM_PORT).defaultConfig().putString("mode", mode);
    }

    /** One hopper-fed chest with ore and coal, a heater and a fuel engine: no settings needed. */
    @Test
    void importedCoalGoesToTheEnginesStorage() {
        ItemLane chest = new ItemLane("chest", 27);
        chest.insert(IRON_ORE, 4, false);
        chest.insert(COAL, 2, false);
        w.items.put(LocalFace.LEFT, chest);
        assertTrue(c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), port("import")).ok());
        assertTrue(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
        assertTrue(c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        TestSupport.run(c, w, 800);
        assertEquals(4, c.lane(1).countOf(IRON), "ore smelted into Green storage");
        assertEquals(0, c.lane(0).countOf(COAL), "no coal left in Orange storage to block the heater");
        assertEquals(0, chest.totalCount());
    }

    /** Passive ports (a hopper or pipe pushing in) route the same way. */
    @Test
    void passiveInsertRoutesFuelAndKeepsEverythingElse() {
        assertTrue(c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), port("passive_in")).ok());
        assertTrue(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null).ok());
        assertTrue(c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        TestSupport.run(c, w, 1);
        ItemStore side = c.exposedItems(c.orientation().toWorld(LocalFace.LEFT));
        assertNotNull(side, "passive port exposes its storage");
        assertEquals(3, side.insert(COAL, 3, false));
        assertEquals(2, side.insert(IRON_ORE, 2, false));
        assertEquals(3, c.lane(2).countOf(COAL), "coal went to Purple (the engine's fuel)");
        assertTrue(c.lane(0).countOf(IRON_ORE) + c.lane(1).countOf(IRON) >= 2, "ore stayed for the heater");
    }

    /** A machine that processes the fuel item keeps it: coal for a Pyrolyzer stays in Orange. */
    @Test
    void machineThatUsesTheItemKeepsIt() {
        w.addRecipe(new ProcessRecipe(FfId.ff("coke"), ProcessFamilies.PYROLYSIS,
                List.of(new ProcessRecipe.ItemInput(ItemFilter.allowIds("minecraft:coal"), 1, true, "coal")),
                null, List.of(new ProcessRecipe.ItemResult(ItemVariant.Simple.of("fieldframe:coal_coke"), 1, 1.0)), null, 100, 10));
        assertTrue(c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), port("passive_in")).ok());
        assertTrue(c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.PYROLYZER), null).ok());
        assertTrue(c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        TestSupport.run(c, w, 1);
        ItemStore side = c.exposedItems(c.orientation().toWorld(LocalFace.LEFT));
        assertEquals(2, side.insert(COAL, 2, false));
        assertEquals(2, c.lane(0).countOf(COAL), "the pyrolyzer takes coal from Orange, so it stays there");
        assertEquals(0, c.lane(2).countOf(COAL));
    }

    /** An engine that burns from the port's own storage, or no engine at all: nothing changes. */
    @Test
    void noRoutingWithoutAnotherFuelStorage() {
        assertTrue(c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), port("passive_in")).ok());
        assertTrue(c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        c.setBinding(LocalFace.BOTTOM, "fuel", 0);
        TestSupport.run(c, w, 1);
        ItemStore side = c.exposedItems(c.orientation().toWorld(LocalFace.LEFT));
        side.insert(COAL, 2, false);
        assertEquals(2, c.lane(0).countOf(COAL), "engine burns from Orange: coal stays in Orange");
    }

    /** When the engine's storage is full the port takes nothing, rather than clogging the input storage. */
    @Test
    void fullFuelStorageRefusesInsteadOfClogging() {
        assertTrue(c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), port("passive_in")).ok());
        assertTrue(c.install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.FUEL_ENGINE), null).ok());
        c.setPaused(true);
        ItemVariant junk = ItemVariant.Simple.of("minecraft:cobblestone");
        for (int s = 0; s < c.lane(2).slots(); s++) {
            c.lane(2).insert(ItemVariant.Simple.of("minecraft:dirt_" + s), 64, false);
        }
        TestSupport.run(c, w, 1);
        ItemStore side = c.exposedItems(c.orientation().toWorld(LocalFace.LEFT));
        assertEquals(0, side.insert(COAL, 5, true), "Purple is full: coal is not taken");
        assertEquals(5, side.insert(junk, 5, false), "other items still go in");
        assertEquals(0, c.lane(0).countOf(COAL));
    }
}
