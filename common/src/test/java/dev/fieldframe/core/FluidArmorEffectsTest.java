package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.flora.ArmorInfuserBehavior;
import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FluidArmorEffects;
import dev.fieldframe.core.material.TableSync;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidArmorEffectsTest {
    private static final FfId LAVA = FfId.parse("minecraft:lava");
    private static final FfId WATER = FfId.parse("minecraft:water");
    private static final FfId SILVER = FfId.parse("metallurgy:silver");

    /** Only metallurgy:silver is in #c:molten_silver. */
    private static final TagLookup TAGS = new TagLookup() {
        @Override
        public boolean itemInTag(ItemVariant item, FfId tag) {
            return false;
        }

        @Override
        public boolean fluidInTag(FluidVariant fluid, FfId tag) {
            return tag.toString().equals("c:molten_silver") && fluid.id().equals(SILVER);
        }
    };

    @AfterEach
    void reset() {
        FluidArmorEffects.setModLoaded(null);
        FluidArmorEffects.load(Map.of(), List.of());
    }

    private static List<String> loadDefault() {
        return FluidArmorEffects.load(Map.of(
                "fieldframe:vanilla", """
                        {"effects": [
                          {"fluid": "minecraft:lava", "effect": "minecraft:fire_resistance"},
                          {"fluid": "minecraft:water", "effect": "minecraft:water_breathing"},
                          {"fluid": "minecraft:water", "effect": "minecraft:dolphins_grace", "min_mb": 4000}
                        ]}""",
                "fieldframe:metallurgy", """
                        {"requires_mod": "metallurgy", "effects": [
                          {"fluid": "#c:molten_silver", "effect": "minecraft:regeneration", "mb_per_level": 2000, "max_amplifier": 2}
                        ]}""",
                "fieldframe:zz_broken", "{\"fluid\": \"minecraft:lava\", \"effect\": \"minecraft:speed\", \"amplifier\": 40}"), List.of());
    }

    @Test
    void lookupByIdAndTagWithLevels() {
        List<String> errors = loadDefault();
        assertEquals(1, errors.size(), "the broken file is skipped and named: " + errors);
        assertEquals(4, FluidArmorEffects.all().size());
        assertTrue(FluidArmorEffects.accepts(LAVA, TAGS));
        assertTrue(FluidArmorEffects.accepts(SILVER, TAGS), "tag match");
        assertTrue(FluidArmorEffects.accepts(Flora.Fluid.COAL.id(), TAGS), "FLORA fluids always");
        assertFalse(FluidArmorEffects.accepts(FfId.parse("minecraft:milk"), TAGS));

        List<FluidArmorEffects.Active> a = FluidArmorEffects.active(Map.of(WATER, 1000L), TAGS);
        assertEquals(1, a.size(), "dolphins grace needs 4000 mB");
        a = FluidArmorEffects.active(Map.of(WATER, 4000L, LAVA, 999L), TAGS);
        assertEquals(2, a.size(), "lava below 1000 mB gives nothing");
        a = FluidArmorEffects.active(Map.of(SILVER, 4000L), TAGS);
        assertEquals(1, a.get(0).amplifier(), "1000 base + 2000 per level -> level 1 at 4000");
        a = FluidArmorEffects.active(Map.of(SILVER, 100_000L), TAGS);
        assertEquals(2, a.get(0).amplifier(), "capped at max_amplifier");
    }

    @Test
    void requiresModAndSyncAndContributors() {
        FluidArmorEffects.setModLoaded(m -> !m.equals("metallurgy"));
        loadDefault();
        assertFalse(FluidArmorEffects.accepts(SILVER, TAGS), "metallurgy entries dropped without metallurgy");
        FluidArmorEffects.setModLoaded(null);
        FluidArmorEffects.load(Map.of("a:b", "{\"fluid\": \"minecraft:lava\", \"effect\": \"minecraft:fire_resistance\"}"),
                List.of(s -> {
                    s.remove("minecraft:lava");
                    s.effect("minecraft:water", FfId.parse("minecraft:luck"), 1, 500, 0, 1);
                }));
        assertEquals(1, FluidArmorEffects.all().size());
        String json = TableSync.toJson();
        FluidArmorEffects.load(Map.of(), List.of());
        TableSync.acceptSync(json);
        assertEquals(1, FluidArmorEffects.all().size(), "synced to the client through TableSync");
        assertEquals(500, FluidArmorEffects.all().get(0).minMb());
    }

    @Test
    void chargeHoldsAnyFluidAndInfuserAcceptsTableFluids() {
        Flora.Charge c = new Flora.Charge();
        assertEquals(200, c.fill(LAVA, 200, 250, false));
        assertEquals(50, c.fill(Flora.Fluid.COAL.id(), 999, 250, false), "shared capacity");
        Flora.Charge back = Flora.Charge.load(c.save(), 250);
        assertEquals(200, back.amount(LAVA));
        assertEquals(50, back.amount(Flora.Fluid.COAL));
        assertEquals(250, back.total());

        loadDefault();
        TestSupport.freshRegistry();
        SimulatedWorld w = TestSupport.world();
        ChassisCore ch = TestSupport.chassis(w);
        ch.energy().receive(10_000, false);
        assertTrue(ch.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.ARMOR_INFUSER), null).ok());
        ch.lane(0).insert(new ItemVariant.Simple(FfId.ff(ArmorInfuserBehavior.itemPath(Flora.Tier.COPPER, Flora.Piece.BOOTS)), "", 1), 1, false);
        ch.tank(0).fill(FluidVariant.Simple.of("minecraft:lava"), 1000, false);
        TestSupport.run(ch, w, 10);
        assertEquals(750, ch.tank(0).amount(), "lava has an armor effect, so it is infused");
        Flora.Charge filled = Flora.Charge.load(w.itemData(ch.lane(0).variant(0), Flora.Charge.DATA_KEY), Flora.Tier.COPPER.capacity);
        assertEquals(250, filled.amount(LAVA));
    }
}
