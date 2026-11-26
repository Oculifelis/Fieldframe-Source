package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.ChassisTier;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.MachineData;
import dev.fieldframe.api.process.ProcessFamilies;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.material.Auras;
import dev.fieldframe.core.material.Fuels;
import dev.fieldframe.core.material.RecipeFilters;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chassis tiers, recipe filters, and the data / Java / script layers of fuels and auras (MachineData). */
class MachineDataAndTiersTest {
    static final FluidVariant LAVA = FluidVariant.Simple.of("minecraft:lava");
    static final FluidVariant WATER = FluidVariant.Simple.of("minecraft:water");
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
        clear();
    }

    @AfterEach
    void clear() {
        MachineData.resetContributorsForTests();
        Fuels.load(Map.of());
        Auras.load(Map.of());
        Auras.setMirrored(List.of());
        RecipeFilters.load(Map.of());
    }

    private ChassisTier tier(String path) {
        return FieldframeRegistry.tier(FfId.ff(path));
    }

    // ------------------------------------------------------------------ tiers

    @Test
    void builtInTiersRiseOneLevelAtATime() {
        assertEquals(List.of("basic", "hardened", "reinforced", "signalum", "resonant"),
                FieldframeRegistry.tiers().stream().map(t -> t.id().path()).toList());
        assertEquals("basic", c.tier().id().path());
        assertEquals(3, c.serviceBays());
        long rf = c.energy().capacity();
        int slots = c.lane(0).slots();
        assertEquals("WRONG_TIER", c.upgradeTier(tier("reinforced")).code(), "kits cannot skip a tier");
        assertEquals("WRONG_TIER", c.upgradeTier(tier("basic")).code());
        assertTrue(c.upgradeTier(tier("hardened")).ok());
        assertEquals("WRONG_TIER", c.upgradeTier(tier("hardened")).code(), "no second hardened kit");
        assertEquals(4, c.serviceBays());
        assertEquals(slots + 1, c.lane(0).slots());
        assertEquals(rf + 32_000, c.energy().capacity());
        assertTrue(c.upgradeTier(tier("reinforced")).ok());
        assertTrue(c.upgradeTier(tier("signalum")).ok());
        assertTrue(c.upgradeTier(tier("resonant")).ok());
        assertEquals(6, c.serviceBays(), "at most six bays");
        assertEquals(List.of(FfId.ff("hardened_upgrade_kit"), FfId.ff("reinforced_upgrade_kit"), FfId.ff("signalum_upgrade_kit"),
                FfId.ff("resonant_upgrade_kit")), c.tierKits(), "every kit is given back on break");
        assertTrue(c.installUpgrade(5, FieldframeRegistry.upgrade(FfId.ff("cell_upgrade"))).ok(), "the sixth bay is usable");
    }

    @Test
    void tierAndItsBaysSurviveSaveAndLoad() {
        c.upgradeTier(tier("hardened"));
        c.upgradeTier(tier("reinforced"));
        assertTrue(c.installUpgrade(4, FieldframeRegistry.upgrade(FfId.ff("storage_upgrade"))).ok());
        ChassisCore copy = TestSupport.chassis(w);
        assertTrue(copy.load(c.save()).isEmpty());
        assertEquals("reinforced", copy.tier().id().path());
        assertEquals(FfId.ff("storage_upgrade"), copy.upgrade(4));
        assertEquals(c.lane(0).slots(), copy.lane(0).slots());
    }

    @Test
    void higherTierProcessesFasterForTheSameEnergy() {
        long[] basic = heat(null);
        assertTrue(c.upgradeTier(tier("hardened")).ok() && c.upgradeTier(tier("reinforced")).ok() && c.upgradeTier(tier("signalum")).ok());
        long[] fast = heat(c);
        assertTrue(basic[0] >= 100, "basic: 100 ticks, took " + basic[0]);
        assertTrue(fast[0] <= 40, "signalum (3x): about 34 ticks, took " + fast[0]);
        assertTrue(Math.abs(fast[1] - basic[1]) <= basic[1] / 20, "same RF per job: " + basic[1] + " vs " + fast[1]);
    }

    /** Ticks and RF to smelt one raw iron in a Heater on {@code chassis} (a fresh one when null). */
    private long[] heat(ChassisCore chassis) {
        ChassisCore k = chassis == null ? TestSupport.chassis(w) : chassis;
        k.energy().receive(10_000, false);
        k.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        k.lane(0).insert(TestSupport.IRON_ORE, 1, false);
        long before = k.energy().stored();
        for (int t = 1; t <= 400; t++) {
            TestSupport.run(k, w, 1);
            if (k.lane(1).countOf(TestSupport.IRON) == 1) {
                return new long[]{t, before - k.energy().stored()};
            }
        }
        throw new AssertionError("never finished: " + k.statuses());
    }

    @Test
    void tiersAreExtensibleAndValidated() {
        FieldframeRegistry.resetForTests();
        BuiltinModules.registerAll();
        FieldframeRegistry.registerTier(new ChassisTier(FfId.of("addon", "mythic"), 5, 6.0, 3, 6, 16_000, 1_000_000, 4_096));
        assertEquals(FfId.of("addon", "mythic_upgrade_kit"), FieldframeRegistry.tierAt(5).kitId());
        assertThrows(IllegalArgumentException.class, () -> FieldframeRegistry.registerTier(
                new ChassisTier(FfId.of("addon", "other"), 5, 2.0, 0, 0, 0, 0, 0)), "levels are unique");
        assertThrows(IllegalArgumentException.class, () -> new ChassisTier(FfId.of("addon", "bad"), 6, 0, 0, 0, 0, 0, 0));
        FieldframeRegistry.freeze();
        assertThrows(IllegalStateException.class, () -> FieldframeRegistry.registerTier(
                new ChassisTier(FfId.of("addon", "late"), 7, 2.0, 0, 0, 0, 0, 0)));
    }

    // ------------------------------------------------------------------ recipe filters

    @Test
    void recipeFiltersRemoveByIdPatternAndFamily() {
        var errors = RecipeFilters.load(Map.of(
                "p:a", "{\"remove\": [\"fieldframe:alloying/metallurgy/bronze\", \"fieldframe:pressing/*\"]}",
                "p:b", "{\"remove_families\": [\"fieldframe:heating\"]}",
                "p:bad", "{\"remove\": [\"*\"]}"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(RecipeFilters.removed(FfId.parse("fieldframe:alloying/metallurgy/bronze"), ProcessFamilies.ALLOYING));
        assertFalse(RecipeFilters.removed(FfId.parse("fieldframe:alloying/metallurgy/brass"), ProcessFamilies.ALLOYING));
        assertTrue(RecipeFilters.removed(FfId.parse("fieldframe:pressing/iron_plate"), ProcessFamilies.PRESSING));
        assertTrue(RecipeFilters.removed(FfId.parse("minecraft:iron_ingot"), ProcessFamilies.HEATING));
        String synced = RecipeFilters.toJson();
        RecipeFilters.load(Map.of(), List.of());
        RecipeFilters.acceptSync(synced);
        assertTrue(RecipeFilters.removed(FfId.parse("fieldframe:pressing/gold_gear"), ProcessFamilies.PRESSING), "client gets the filters");
    }

    @Test
    void javaAndScriptContributorsRunAfterData() {
        MachineData.addRecipeFilterContributor(s -> s.remove("fieldframe:crushing/metallurgy/tin"));
        RecipeFilters.load(Map.of());
        assertTrue(RecipeFilters.removed(FfId.parse("fieldframe:crushing/metallurgy/tin"), ProcessFamilies.CRUSHING));
    }

    // ------------------------------------------------------------------ fuels

    @Test
    void fuelsCanBeRemovedClearedAndContributed() {
        Consumer<MachineData.FuelSink> addon = s -> {
            s.fluid(BuiltinModules.FLUID_ENGINE, "minecraft:water", 2);
            s.remove(BuiltinModules.FLUID_ENGINE, "minecraft:lava");
        };
        var errors = Fuels.load(Map.of(
                "a:1", "{\"fuels\": [{\"engine\":\"fieldframe:fluid_engine\",\"fluid\":\"minecraft:lava\",\"rf_per_mb\":50},"
                        + "{\"engine\":\"fieldframe:gem_engine\",\"item\":\"minecraft:diamond\",\"rf\":400000}]}",
                "a:2", "{\"engine\":\"fieldframe:gem_engine\",\"clear\":true}",
                "a:3", "{\"fuels\": [{\"engine\":\"fieldframe:fluid_engine\",\"fluid\":\"minecraft:lava\",\"rf_per_mb\":60}, {\"engine\":\"x:y\"}]}"),
                List.of(addon));
        assertEquals(1, errors.size(), "the broken file is skipped whole: " + errors);
        assertEquals(0, Fuels.fluidValue(BuiltinModules.FLUID_ENGINE, LAVA, w.tags()), "the addon removed lava");
        assertEquals(2, Fuels.fluidValue(BuiltinModules.FLUID_ENGINE, WATER, w.tags()));
        assertTrue(Fuels.all().stream().noneMatch(e -> e.engine().equals(BuiltinModules.GEM_ENGINE)), "clear");
        assertEquals(1, MachineData.fuels().size(), "published to the API");
        MachineData.addFuelContributor(s -> {
            throw new IllegalStateException("must not run on clients");
        });
        Fuels.acceptSync(Fuels.toJson());
        assertEquals(2, Fuels.fluidValue(BuiltinModules.FLUID_ENGINE, WATER, w.tags()), "sync installs the server's table only");
    }

    // ------------------------------------------------------------------ auras

    @Test
    void aurasLayerMirroredDataAndContributorsById() {
        Auras.setMirrored(List.of(
                new Auras.Entry("metallurgy:adamantine_block", 3, FfId.parse("minecraft:health_boost"), 18_000, 3, "fieldframe:metallurgy_adamantine"),
                new Auras.Entry("metallurgy:ignatius_block", 3, FfId.parse("minecraft:fire_resistance"), 18_000, 0, "fieldframe:metallurgy_ignatius")));
        Auras.load(Map.of(
                "fieldframe:metallurgy_adamantine", "{\"ingredient\":\"metallurgy:adamantine_block\",\"count\":1,\"effect\":\"minecraft:speed\",\"duration\":200}",
                "pack:none", "{\"remove\": [\"fieldframe:metallurgy_ignatius\"]}"),
                List.of(s -> s.add(new MachineData.Aura(FfId.of("addon", "gold"), "minecraft:gold_block", 2, FfId.parse("minecraft:haste"), 600, 1))));
        Map<String, Auras.Entry> byId = new java.util.HashMap<>();
        Auras.all().forEach(e -> byId.put(e.source(), e));
        assertEquals(2, byId.size(), byId.keySet().toString());
        assertEquals("minecraft:speed", byId.get("fieldframe:metallurgy_adamantine").effect().toString(), "data replaces the mirrored recipe");
        assertFalse(byId.containsKey("fieldframe:metallurgy_ignatius"), "data removes a mirrored recipe");
        assertNotNull(byId.get("addon:gold"));
        Auras.setMirrored(List.of(new Auras.Entry("metallurgy:ignatius_block", 3, FfId.parse("minecraft:fire_resistance"), 18_000, 0,
                "fieldframe:metallurgy_ignatius")));
        assertTrue(Auras.all().stream().noneMatch(e -> e.source().equals("fieldframe:metallurgy_ignatius")), "removal survives a recipe reload");
        assertEquals(2, MachineData.auras().size());
    }
}
