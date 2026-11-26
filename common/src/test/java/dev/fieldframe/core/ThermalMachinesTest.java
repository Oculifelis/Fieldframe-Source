package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions.Drop;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedActions;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.material.Fuels;
import dev.fieldframe.core.material.Materials;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.modules.MachineBehaviors;
import dev.fieldframe.core.modules.world.DeviceBehaviors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Thermal-style machines, materials table and data fuels (docs/thermal_parity.md). */
class ThermalMachinesTest {
    static final FluidVariant LAVA = FluidVariant.Simple.of("minecraft:lava");
    static final FluidVariant WATER = FluidVariant.Simple.of("minecraft:water");
    static final FluidVariant XP = FluidVariant.Simple.of("fieldframe:liquid_experience");

    SimulatedWorld w;
    SimulatedActions a;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        a = w.actions;
        c = TestSupport.chassis(w);
        c.energy().receive(100_000, false);
        Fuels.load(Map.of());
    }

    @AfterEach
    void tearDown() {
        Fuels.load(Map.of());
    }

    private void install(LocalFace f, FfId id, Object... kv) {
        FfData d = FieldframeRegistry.module(id).defaultConfig();
        for (int i = 0; i < kv.length; i += 2) {
            d.putAny((String) kv[i], kv[i + 1]);
        }
        assertTrue(c.install(f, FieldframeRegistry.module(id), d).ok(), "install " + id);
    }

    private String status(LocalFace f) {
        return c.statuses().get(f).code();
    }

    // ------------------------------------------------------------------ data

    @Test
    void shippedMaterialsTableParsesAndNamesAreUnique() throws Exception {
        String json = Files.readString(Path.of("..", "shared", "fieldframe_tables", "materials.json"), StandardCharsets.UTF_8);
        Materials.Table t = Materials.parse(json);
        // Only Fieldframe's own alloys; common metals come from Metallurgy.
        assertEquals(Set.of("signal_alloy", "lumen_alloy", "ender_alloy"),
                Set.copyOf(t.metals().stream().map(Materials.Metal::name).toList()));
        var paths = Materials.itemPaths(t);
        assertEquals(paths.size(), Set.copyOf(paths).size(), "every registry path is unique");
        assertTrue(paths.contains("signal_alloy_ingot") && paths.contains("ender_alloy_block") && paths.contains("liquid_experience_bucket"));
        assertFalse(paths.contains("ender_alloy_ingot"), "the existing Ender Alloy ingot is not registered twice");
        assertFalse(paths.stream().anyMatch(p -> p.endsWith("_ore") || p.equals("tin_ingot") || p.equals("copper_dust")), "no Metallurgy metals or ores");
        assertTrue(paths.contains("iron_plate") && paths.contains("signal_alloy_gear"), "plates and gears are Fieldframe's");
        assertThrows(IllegalArgumentException.class, () -> Materials.parse("{\"metals\":[{\"name\":\"x\",\"forms\":[\"gear\"]}]}"));
        assertThrows(IllegalArgumentException.class, () -> Materials.parse(
                "{\"metals\":[{\"name\":\"x\",\"forms\":[\"ingot\"],\"ore\":{}}],\"items\":[],\"fluids\":[]}"), "ores belong to Metallurgy");
    }

    @Test
    void fuelsLoadWithPrecedenceAndSkipBadFiles() {
        var errors = Fuels.load(Map.of(
                "a:one", "{\"engine\":\"fieldframe:fluid_engine\",\"fluid\":\"minecraft:lava\",\"rf_per_mb\":10}",
                "b:two", "{\"fuels\":[{\"engine\":\"fieldframe:fluid_engine\",\"fluid\":\"minecraft:lava\",\"rf_per_mb\":30}]}",
                "c:bad", "{\"engine\":\"fieldframe:fluid_engine\",\"fluid\":\"minecraft:water\",\"rf_per_mb\":-5}"));
        assertEquals(1, errors.size());
        assertEquals(30, Fuels.fluidValue(BuiltinModules.FLUID_ENGINE, LAVA, w.tags()), "later file wins");
        assertEquals(0, Fuels.fluidValue(BuiltinModules.FLUID_ENGINE, WATER, w.tags()));
        assertEquals(0, Fuels.fluidValue(BuiltinModules.GEM_ENGINE, LAVA, w.tags()), "fuels are per engine");
        String synced = Fuels.toJson();
        Fuels.load(Map.of());
        Fuels.acceptSync(synced);
        assertEquals(30, Fuels.fluidValue(BuiltinModules.FLUID_ENGINE, LAVA, w.tags()), "client gets the same table");
        Fuels.acceptSync("not json");
        assertEquals(30, Fuels.fluidValue(BuiltinModules.FLUID_ENGINE, LAVA, w.tags()), "bad sync is ignored");
    }

    // ------------------------------------------------------------------ engines

    @Test
    void fluidEngineBurnsOnlyDataFuelsAndConserves() {
        Fuels.load(Map.of("f", "{\"engine\":\"fieldframe:fluid_engine\",\"fluid\":\"minecraft:lava\",\"rf_per_mb\":50}"));
        c.energy().extract(c.energy().stored(), false);
        install(LocalFace.TOP, BuiltinModules.FLUID_ENGINE);
        c.tank(2).fill(WATER, 1000, false);
        TestSupport.run(c, w, 20);
        assertEquals(0, c.energy().stored(), "water is not a fuel");
        assertEquals(1000, c.tank(2).amount(), "and is not consumed");
        c.tank(2).drain(WATER, 1000, false);
        c.tank(2).fill(LAVA, 100, false);
        TestSupport.run(c, w, 200);
        assertEquals(0, c.tank(2).amount());
        assertEquals(100 * 50, c.energy().stored(), "exactly rf_per_mb x mB burned");
    }

    @Test
    void foodAndArcaneEnginesValueByPlatform() {
        ItemVariant bread = ItemVariant.Simple.of("minecraft:bread");
        ItemVariant book = ItemVariant.Simple.of("minecraft:enchanted_book");
        w.foods.put(bread.id(), 5);
        w.enchantLevels.put(book.id(), 3);
        c.energy().extract(c.energy().stored(), false);
        install(LocalFace.TOP, BuiltinModules.FOOD_ENGINE);
        install(LocalFace.BOTTOM, BuiltinModules.ARCANE_ENGINE);
        c.setBinding(LocalFace.BOTTOM, "fuel", 1);
        c.lane(2).insert(bread, 1, false);
        TestSupport.run(c, w, 1000);
        assertEquals(0, c.lane(2).totalCount());
        assertEquals(5 * 1600, c.energy().stored(), "hunger points x rf_per_point");
        c.energy().extract(c.energy().stored(), false);
        c.lane(1).insert(book, 1, false);
        TestSupport.run(c, w, 1000);
        assertEquals(0, c.lane(1).totalCount());
        assertEquals(3 * 4000, c.energy().stored(), "enchantment levels x rf_per_level");
    }

    // ------------------------------------------------------------------ devices

    @Test
    void chargerChargesUntilFullThenMovesToOutput() {
        ItemVariant tool = new ItemVariant.Simple(FfId.parse("ex:drill"), "", 1);
        w.chargeCapacity.put(tool.id(), 2500L);
        install(LocalFace.TOP, BuiltinModules.ITEM_CHARGER);
        c.lane(0).insert(tool, 1, false);
        long before = c.energy().stored();
        TestSupport.run(c, w, 10);
        assertEquals(0, c.lane(0).totalCount());
        assertEquals(1, c.lane(1).totalCount());
        assertEquals("rf=2500", c.lane(1).variant(0).componentKey());
        assertEquals(2500, before - c.energy().stored(), "RF moved, not created");
    }

    @Test
    void brewerUsesOneIngredientForUpToThreeBases() {
        ItemVariant awkward = new ItemVariant.Simple(FfId.parse("minecraft:potion"), "awkward", 1);
        ItemVariant wart = ItemVariant.Simple.of("minecraft:sugar");
        ItemVariant swift = new ItemVariant.Simple(FfId.parse("minecraft:potion"), "swiftness", 1);
        w.brewing.put("minecraft:potion|minecraft:sugar", swift);
        install(LocalFace.TOP, BuiltinModules.BREWER);
        c.lane(0).insert(awkward, 1, false);
        c.lane(0).insert(awkward, 1, false);
        c.lane(0).insert(awkward, 1, false);
        c.lane(0).insert(wart, 2, false);
        TestSupport.run(c, w, 5);
        assertEquals(3, c.lane(1).countOf(swift));
        assertEquals(1, c.lane(0).countOf(wart));
    }

    @Test
    void enchanterPaysWithExperienceFluid() {
        ItemVariant sword = new ItemVariant.Simple(FfId.parse("minecraft:iron_sword"), "", 1);
        w.enchantable.add(sword.id());
        w.fluidTags.put(MachineBehaviors.EXPERIENCE_FLUIDS, Set.of(XP.id()));
        install(LocalFace.TOP, BuiltinModules.ENCHANTER);
        c.lane(0).insert(sword, 1, false);
        c.tank(0).fill(XP, 1000, false);
        TestSupport.run(c, w, 5);
        assertEquals("NO_FLUID", status(LocalFace.TOP), "30 levels x 100 mB needed");
        c.tank(0).fill(XP, 3000, false);
        assertTrue(c.tank(0).amount() <= c.tank(0).capacity(), "a level-30 enchant fits a plain chassis tank");
        TestSupport.run(c, w, 60);
        assertTrue(c.lane(1).variant(0) != null, "status " + c.statuses().get(LocalFace.TOP) + " tank " + c.tank(0).amount() + " in " + c.lane(0).totalCount());
        assertEquals("enchanted:30", c.lane(1).variant(0).componentKey());
        assertEquals(4000 - 30 * 100, c.tank(0).amount());
    }

    @Test
    void stoneExtruderKeepsFluidsExceptForObsidian() {
        install(LocalFace.TOP, BuiltinModules.STONE_EXTRUDER);
        c.tank(0).fill(LAVA, 1000, false);
        c.tank(1).fill(WATER, 1000, false);
        TestSupport.run(c, w, 100);
        assertTrue(c.lane(1).countOf(ItemVariant.Simple.of("minecraft:cobblestone")) >= 4);
        assertEquals(1000, c.tank(0).amount());
        c.remove(LocalFace.TOP, w);
        install(LocalFace.TOP, BuiltinModules.STONE_EXTRUDER, "product", "obsidian");
        c.tank(0).fill(LAVA, 1000, false);
        TestSupport.run(c, w, 100);
        assertEquals(2, c.lane(1).countOf(ItemVariant.Simple.of("minecraft:obsidian")), "1000 mB lava per obsidian");
        assertEquals(0, c.tank(0).amount(), "all 2000 mB used, nothing invented");
        assertEquals("NO_FLUID", status(LocalFace.TOP));
    }

    @Test
    void nullifierDoesNothingUntilArmed() {
        ItemVariant junk = ItemVariant.Simple.of("minecraft:dirt");
        install(LocalFace.TOP, BuiltinModules.NULLIFIER);
        c.lane(0).insert(junk, 32, false);
        TestSupport.run(c, w, 10);
        assertEquals(32, c.lane(0).totalCount());
        c.remove(LocalFace.TOP, w);
        install(LocalFace.TOP, BuiltinModules.NULLIFIER, "armed", true);
        TestSupport.run(c, w, 2);
        assertEquals(0, c.lane(0).totalCount());
    }

    // ------------------------------------------------------------------ world devices

    private void waterInFront(LocalFace face, int sources) {
        int n = 0;
        for (int r = -1; r <= 1 && n < sources; r++) {
            for (int u = -1; u <= 1 && n < sources; u++) {
                SimulatedActions.Block b = SimulatedActions.Block.of("minecraft:water", null);
                b.fluid = FfId.parse("minecraft:water");
                b.source = true;
                a.put(new WorkTarget(face, 1, r, u), b);
                n++;
            }
        }
    }

    @Test
    void waterCollectorNeedsTwoSources() {
        install(LocalFace.TOP, BuiltinModules.WATER_COLLECTOR);
        waterInFront(LocalFace.TOP, 1);
        TestSupport.run(c, w, 50);
        assertEquals(0, c.tank(0).amount());
        waterInFront(LocalFace.TOP, 3);
        TestSupport.run(c, w, 100);
        assertTrue(c.tank(0).amount() >= 3 * 25 * 2, "3 sources x 25 mB per interval");
    }

    @Test
    void fisherCatchesIntoOutputAndBaitAddsLuck() {
        w.itemTags.put(DeviceBehaviors.FISHER_BAIT, Set.of(FfId.parse("minecraft:wheat_seeds")));
        a.fishLoot.add(new Drop(ItemVariant.Simple.of("minecraft:cod"), 1));
        install(LocalFace.TOP, BuiltinModules.FISHER);
        TestSupport.run(c, w, 50);
        assertEquals(0, c.lane(1).totalCount(), "no water, no fish");
        waterInFront(LocalFace.TOP, 9);
        c.lane(0).insert(ItemVariant.Simple.of("minecraft:wheat_seeds"), 1, false);
        TestSupport.run(c, w, 300);
        assertEquals(1, c.lane(1).countOf(ItemVariant.Simple.of("minecraft:cod")));
        assertEquals(1, a.lastFishingLuck);
        assertEquals(0, c.lane(0).totalCount(), "bait used");
    }

    @Test
    void treeTapperUsesBlockTagsAndXpCollectorConverts() {
        w.blockTags.put(DeviceBehaviors.TAPPABLE_LOGS, Set.of(FfId.parse("minecraft:oak_log")));
        a.put(new WorkTarget(LocalFace.TOP, 1, 0, 0), SimulatedActions.Block.of("minecraft:oak_log", null));
        install(LocalFace.TOP, BuiltinModules.TREE_TAPPER);
        TestSupport.run(c, w, 100);
        assertTrue(c.tank(0).amountOf(FluidVariant.Simple.of("fieldframe:tree_sap")) >= 20);
        install(LocalFace.BOTTOM, BuiltinModules.XP_COLLECTOR);
        c.setBinding(LocalFace.BOTTOM, "experience", 1);
        a.xp.put(LocalFace.BOTTOM, 7);
        TestSupport.run(c, w, 20);
        assertEquals(7 * 20, c.tank(1).amountOf(XP));
        assertEquals(0, a.xp.get(LocalFace.BOTTOM));
    }

    @Test
    void shippedPartsIncludeMetallurgyMetals() throws Exception {
        String json = Files.readString(Path.of("..", "shared", "fieldframe_tables", "materials.json"), StandardCharsets.UTF_8);
        String metallurgy = Files.readString(Path.of("..", "shared", "fieldframe_tables", "metallurgy_metals.json"), StandardCharsets.UTF_8);
        Materials.Table t = Materials.parse(json, metallurgy);
        assertEquals(java.util.List.of("plate", "gear"), t.partForms());
        Set<String> metals = Set.copyOf(t.parts().stream().map(Materials.Part::metal).toList());
        assertTrue(metals.containsAll(Set.of("iron", "gold", "signal_alloy", "copper", "adamantine", "bronze")), metals.toString());
        assertEquals(t.parts().size(), metals.size(), "one part set per metal");
        assertTrue(Materials.itemPaths(t).containsAll(Set.of("iron_plate", "adamantine_gear", "plate_die", "gear_die")));
    }

    @Test
    void auraDataLoadsSyncsAndMatchesTags() {
        try {
            var errors = dev.fieldframe.core.material.Auras.load(Map.of(
                    "a:one", "{\"ingredient\":\"metallurgy:adamantine_block\",\"count\":3,\"effect\":\"minecraft:health_boost\",\"duration\":200,\"amplifier\":1}",
                    "a:two", "{\"auras\":[{\"ingredient\":\"#c:storage_blocks/haderoth\",\"effect\":\"minecraft:fire_resistance\"}]}",
                    "a:bad", "{\"ingredient\":\"\",\"effect\":\"minecraft:speed\"}"));
            assertEquals(1, errors.size(), errors.toString());
            dev.fieldframe.core.material.Auras.setMirrored(java.util.List.of(new dev.fieldframe.core.material.Auras.Entry(
                    "metallurgy:ignatius_block", 3, FfId.parse("minecraft:fire_resistance"), 600, 0, "mirror")));
            assertEquals(3, dev.fieldframe.core.material.Auras.all().size());
            String synced = dev.fieldframe.core.material.Auras.toJson();
            dev.fieldframe.core.material.Auras.load(Map.of());
            dev.fieldframe.core.material.Auras.setMirrored(java.util.List.of());
            dev.fieldframe.core.material.Auras.acceptSync(synced);
            var all = dev.fieldframe.core.material.Auras.all();
            assertEquals(3, all.size(), "client gets data and mirrored entries");
            w.itemTags.put(FfId.parse("c:storage_blocks/haderoth"), Set.of(FfId.parse("metallurgy:haderoth_block")));
            var tagged = all.stream().filter(e -> e.ingredient().startsWith("#")).findFirst().orElseThrow();
            assertTrue(tagged.matches(ItemVariant.Simple.of("metallurgy:haderoth_block"), w.tags()));
            assertFalse(tagged.matches(ItemVariant.Simple.of("metallurgy:adamantine_block"), w.tags()));
        } finally {
            dev.fieldframe.core.material.Auras.load(Map.of());
            dev.fieldframe.core.material.Auras.setMirrored(java.util.List.of());
        }
    }

    @Test
    void auraChamberConsumesBlocksAndAppliesTheEffect() {
        try {
            dev.fieldframe.core.material.Auras.load(Map.of("a:one",
                    "{\"ingredient\":\"metallurgy:adamantine_block\",\"count\":3,\"effect\":\"minecraft:health_boost\",\"duration\":100,\"amplifier\":2}"));
            install(LocalFace.TOP, BuiltinModules.AURA_CHAMBER);
            c.lane(0).insert(ItemVariant.Simple.of("metallurgy:adamantine_block"), 2, false);
            TestSupport.run(c, w, 60);
            assertTrue(a.effects.isEmpty(), "two blocks are not a batch");
            c.lane(0).insert(ItemVariant.Simple.of("metallurgy:adamantine_block"), 2, false);
            TestSupport.run(c, w, 200);
            assertEquals(1, c.lane(0).totalCount(), "one batch of three used");
            assertEquals(5, a.effects.size(), "100 ticks of aura in 20-tick pulses: " + a.effects);
            assertEquals("minecraft:health_boost/120/0/6", a.effects.get(0), "amplifier off by default");
        } finally {
            dev.fieldframe.core.material.Auras.load(Map.of());
        }
    }

    @Test
    void packRulesCanVetoFishing() {
        Object owner = new Object();
        FieldframeEvents.register(FieldframeEvents.WorldAction.class, e -> {
            if (e.action().equals("fish")) {
                e.cancel("no_fishing");
            }
        }, owner);
        try {
            a.fishLoot.add(new Drop(ItemVariant.Simple.of("minecraft:cod"), 1));
            waterInFront(LocalFace.TOP, 9);
            install(LocalFace.TOP, BuiltinModules.FISHER);
            TestSupport.run(c, w, 1300);
            assertEquals(0, c.lane(1).totalCount());
            assertFalse(a.lastFishingLuck >= 0, "the platform was never asked");
        } finally {
            FieldframeEvents.unregisterOwner(owner);
        }
    }
}
