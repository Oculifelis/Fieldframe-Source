package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.material.Unification;
import dev.fieldframe.core.modules.SmallModules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Storage Sorter, Tag Unifier, Tool Repairer and Mob Grinder. */
class HandyModulesTest {
    static final ItemVariant COAL = ItemVariant.Simple.of("minecraft:coal");
    static final ItemVariant STONE = ItemVariant.Simple.of("minecraft:stone");
    static final ItemVariant OTHER_COPPER = ItemVariant.Simple.of("othermod:copper_ingot");
    static final ItemVariant COPPER = ItemVariant.Simple.of("minecraft:copper_ingot");
    static final ItemVariant PICK = ItemVariant.Simple.of("minecraft:iron_pickaxe");
    static final ItemVariant SWORD = ItemVariant.Simple.of("minecraft:iron_sword");
    static final FluidVariant XP = FluidVariant.Simple.of("fieldframe:liquid_experience");
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
        Unification.invalidate();
    }

    @AfterEach
    void tearDown() {
        MachineData.resetContributorsForTests();
        Unification.invalidate();
    }

    private void install(LocalFace f, FfId id, FfData config) {
        assertTrue(c.install(f, FieldframeRegistry.module(id), config).ok(), id + " on " + f);
    }

    private String status(LocalFace f) {
        return c.module(f).status().code();
    }

    @Test
    void sorterMovesOnlyWhatItsFilterAllows() {
        FfData cfg = FieldframeRegistry.module(SmallModules.STORAGE_SORTER).defaultConfig();
        cfg.put("filter", ItemFilter.allowIds("minecraft:coal").toData());
        install(LocalFace.LEFT, SmallModules.STORAGE_SORTER, cfg);
        c.lane(1).insert(COAL, 20, false);
        c.lane(1).insert(STONE, 5, false);
        TestSupport.run(c, w, 1);
        assertEquals(8, c.lane(2).countOf(COAL), "8 a tick");
        TestSupport.run(c, w, 5);
        assertEquals(20, c.lane(2).countOf(COAL));
        assertEquals(5, c.lane(1).countOf(STONE), "stone is not in the filter");
        assertEquals(0, c.lane(2).countOf(STONE));
    }

    @Test
    void sorterRefusesTheSameStorageBothWays() {
        install(LocalFace.LEFT, SmallModules.STORAGE_SORTER, null);
        assertTrue(c.setBinding(LocalFace.LEFT, "to", 1).ok());
        TestSupport.run(c, w, 2);
        assertEquals("SORTER_SAME_STORAGE", status(LocalFace.LEFT));
    }

    @Test
    void unifierTurnsOtherModsVariantsIntoThePreferredOne() {
        w.unify.put(OTHER_COPPER.id(), Map.of("minecraft", COPPER.id(), "othermod", OTHER_COPPER.id()));
        install(LocalFace.LEFT, SmallModules.TAG_UNIFIER, null);
        c.energy().receive(10_000, false);
        c.lane(0).insert(OTHER_COPPER, 6, false);
        c.lane(0).insert(STONE, 2, false);
        TestSupport.run(c, w, 3);
        assertEquals(6, c.lane(1).countOf(COPPER), "unified to vanilla copper");
        assertEquals(2, c.lane(1).countOf(STONE), "others pass through");
        assertEquals(10_000 - 6 * 20, c.energy().stored(), "20 RF per unified item, nothing for passing through");
    }

    @Test
    void unifierPreferencesComeFromScriptsAndTheModuleSetting() {
        assertEquals(Unification.DEFAULT, Unification.preferred());
        MachineData.addUnificationContributor(s -> s.prefer("othermod"));
        Unification.invalidate();
        assertEquals("othermod", Unification.preferred().get(0));
        w.unify.put(COPPER.id(), Map.of("minecraft", COPPER.id(), "othermod", OTHER_COPPER.id()));
        FfData cfg = FieldframeRegistry.module(SmallModules.TAG_UNIFIER).defaultConfig();
        cfg.putString("preferred", "minecraft");
        install(LocalFace.LEFT, SmallModules.TAG_UNIFIER, cfg);
        c.energy().receive(10_000, false);
        c.lane(0).insert(COPPER, 3, false);
        TestSupport.run(c, w, 2);
        assertEquals(3, c.lane(1).countOf(COPPER), "the module's own setting wins over the global list");
    }

    @Test
    void repairerMendsAtTheMendingRateAndPassesFinishedItemsOn() {
        w.maxDamage.put(PICK.id(), 250);
        install(LocalFace.LEFT, SmallModules.TOOL_REPAIRER, null);
        ItemVariant worn = w.withDamage(PICK, 40);
        c.lane(0).insert(worn, 1, false);
        c.tank(0).fill(XP, 1_000, false);
        TestSupport.run(c, w, 30);
        assertEquals(1, c.lane(1).countOf(PICK), "like new, in Green storage");
        assertEquals(600, c.tank(0).amountOf(XP), "40 durability = 20 points = 400 mB");
    }

    @Test
    void repairerWaitsForExperience() {
        w.maxDamage.put(PICK.id(), 250);
        install(LocalFace.LEFT, SmallModules.TOOL_REPAIRER, null);
        c.lane(0).insert(w.withDamage(PICK, 40), 1, false);
        TestSupport.run(c, w, 3);
        assertEquals("NO_EXPERIENCE", status(LocalFace.LEFT));
    }

    @Test
    void grinderAttacksOnItsIntervalWithPowerAndWearsTheWeapon() {
        w.actions.mobs.put(LocalFace.FRONT, 5);
        install(LocalFace.FRONT, SmallModules.MOB_GRINDER, null);
        c.lane(2).insert(SWORD, 1, false);
        TestSupport.run(c, w, 2);
        assertEquals("NO_ENERGY", status(LocalFace.FRONT), "a swing costs RF");
        c.energy().receive(1_000, false);
        TestSupport.run(c, w, 41);
        assertTrue(w.actions.mobs.get(LocalFace.FRONT) < 5, "it attacked");
        assertTrue(c.energy().stored() < 1_000, "and paid for it");
        w.actions.mobs.put(LocalFace.FRONT, 0);
        TestSupport.run(c, w, 21);
        assertEquals("NO_MOBS", status(LocalFace.FRONT));
    }

    @Test
    void grinderLeavesPlayersAndNamedMobsAloneByDefault() {
        FfData cfg = FieldframeRegistry.module(SmallModules.MOB_GRINDER).defaultConfig();
        assertTrue(!cfg.getBool("named", true), "named mobs are off by default");
        assertEquals(List.of("metallurgy", "minecraft", "fieldframe"), Unification.DEFAULT);
    }
}
