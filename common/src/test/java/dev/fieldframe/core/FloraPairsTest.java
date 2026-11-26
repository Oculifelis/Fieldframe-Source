package dev.fieldframe.core;

import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FloraEffects;
import dev.fieldframe.core.flora.FloraPairs;
import dev.fieldframe.core.material.TableSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloraPairsTest {
    private static final String FILE = "src/main/resources/data/fieldframe/fieldframe/fluid_armor_pairs/flora.json";

    @AfterEach
    void reset() {
        FloraPairs.reset();
    }

    private static float[][] matrix(Flora.Fluid a, long mbA, Flora.Fluid b, long mbB) {
        Flora.Charge c = new Flora.Charge();
        c.fill(a, mbA, Flora.Tier.ENDER_ALLOY.capacity, false);
        c.fill(b, mbB, Flora.Tier.ENDER_ALLOY.capacity, false);
        return Flora.matrix(List.of(c));
    }

    @Test
    void shippedFileIsTheOriginal28OnEveryEdition() throws Exception {
        Path root = Path.of("..");
        String text = Files.readString(root.resolve("neoforge-26.1.2").resolve(FILE));
        for (String ed : List.of("neoforge-1.21.1", "forge-1.20.1")) {
            assertEquals(text, Files.readString(root.resolve(ed).resolve(FILE)), ed + " flora.json differs");
        }
        List<String> errors = FloraPairs.load(Map.of("fieldframe:flora", text));
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(Flora.EFFECTS, FloraPairs.effects());
    }

    @Test
    void packsCanMoveTuneAndRemovePairs() {
        String base = "{\"pairs\":[{\"fluids\":[\"liquid_coal\",\"liquid_coal\"],\"effect\":\"fireball\"},"
                + "{\"fluids\":[\"fieldframe:blazing_fluid\",\"fieldframe:arcane_fluid\"],\"effect\":\"speed\"},"
                + "{\"fluids\":[\"liquid_redstone\",\"liquid_glowstone\"],\"effect\":\"self_combustion\"}]}";
        // Later file: speed moves to Ender+Ender at half strength, self-combustion is off, one broken file is skipped.
        String pack = "{\"pairs\":[{\"fluids\":[\"resonant_ender\",\"resonant_ender\"],\"effect\":\"speed\",\"strength\":0.5},"
                + "{\"fluids\":[\"arcane_fluid\",\"blazing_fluid\"],\"remove\":true},{\"effect\":\"self_combustion\",\"remove\":true}]}";
        String broken = "{\"fluids\":[\"liquid_coal\",\"liquid_coal\"],\"effect\":\"no_such_effect\"}";
        List<String> errors = FloraPairs.load(Map.of("fieldframe:a", base, "pack:b", pack, "pack:c", broken));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("pack:c"));
        assertEquals(2, FloraPairs.all().size());

        float[][] enderOnly = matrix(Flora.Fluid.ENDER, 10_000, Flora.Fluid.COAL, 0);
        assertEquals(50f, FloraPairs.intensity(enderOnly, "speed"), 1e-3); // 10 * 10 buckets * 0.5
        assertTrue(FloraEffects.tick(enderOnly, 1, false, new java.util.Random(1))
                .contains(new FloraEffects.ApplyPotion(FloraEffects.Potion.SPEED, 1, 2)));
        assertEquals(0, FloraEffects.blinkRange(enderOnly), "blink was not listed, so it is off");
        float[][] pyroMana = matrix(Flora.Fluid.BLAZING, 10_000, Flora.Fluid.ARCANE, 10_000);
        assertEquals(0f, FloraPairs.intensity(pyroMana, "speed"), 1e-6);
        assertEquals(0f, FloraPairs.intensity(matrix(Flora.Fluid.REDSTONE, 9000, Flora.Fluid.GLOWSTONE, 9000), "self_combustion"));
        assertTrue(FloraEffects.fireballCooldown(matrix(Flora.Fluid.COAL, 1000, Flora.Fluid.ENDER, 0)) > 0);
    }

    @Test
    void defaultsMatchTheOriginalRules() {
        float[][] m = matrix(Flora.Fluid.BLAZING, 10_000, Flora.Fluid.ARCANE, 10_000);
        assertTrue(FloraEffects.tick(m, 1, false, new java.util.Random(1))
                .contains(new FloraEffects.ApplyPotion(FloraEffects.Potion.SPEED, 2, 2)));
        assertEquals(40, FloraEffects.blinkRange(matrix(Flora.Fluid.ENDER, 4000, Flora.Fluid.COAL, 0)));
    }

    @Test
    void tableReachesClientsWithTheOtherTables() {
        FloraPairs.load(Map.of("pack:x", "{\"fluids\":[\"liquid_glowstone\",\"liquid_glowstone\"],\"effect\":\"night_vision\",\"strength\":2}"));
        String json = TableSync.toJson();
        FloraPairs.reset();
        assertEquals(28, FloraPairs.all().size());
        TableSync.acceptSync(json);
        assertEquals(1, FloraPairs.all().size());
        assertEquals(2f, FloraPairs.all().get(0).strength(), 1e-6);
        assertFalse(FloraPairs.all().get(0).effect().negative());
    }
}
