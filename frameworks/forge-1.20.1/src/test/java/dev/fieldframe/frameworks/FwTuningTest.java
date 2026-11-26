package dev.fieldframe.frameworks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FwTuningTest {
    @AfterEach
    void reset() {
        FwTuning.reset();
    }

    @Test
    void defaultsAreTheDocumentedValues() {
        assertEquals(5_000_000, FwTuning.getLong("starcore.ignition_rf"));
        assertEquals(9, FwTuning.getInt("grand_frame.max_size"));
        assertEquals(60, Plasma.fieldPerCoil());
    }

    @Test
    void filesChangeOnlyTheKeysTheyName() {
        int before = FwTuning.revision();
        List<String> errors = FwTuning.load(Map.of("a.json", "{\"foundry\": {\"heat_rf\": 60}, \"starcore\": {\"field_per_coil\": 80}}"));
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(60, FwTuning.getLong("foundry.heat_rf"));
        assertEquals(80, Plasma.fieldPerCoil());
        assertEquals(144, FwTuning.getLong("foundry.pour_per_tick"));
        assertNotEquals(before, FwTuning.revision());
    }

    @Test
    void badFilesAreSkippedWhole() {
        List<String> errors = FwTuning.load(Map.of(
                "a.json", "{\"foundry\": {\"heat_rf\": 60, \"pour_per_tick\": -1}}",
                "b.json", "{\"gantry\": {\"nonsense\": 1}}",
                "c.json", "{\"grand_frame\": {\"min_size\": 12}}"));
        assertEquals(3, errors.size(), errors.toString());
        assertEquals(40, FwTuning.getLong("foundry.heat_rf"), "the bad file's good key is not applied either");
        assertEquals(5, FwTuning.getInt("grand_frame.min_size"));
    }

    @Test
    void clientsGetTheServersValues() {
        FwTuning.load(Map.of("a.json", "{\"gantry\": {\"max_side\": 100}}"));
        String json = FwTuning.toJson();
        FwTuning.reset();
        FwTuning.acceptSync(json);
        assertEquals(100, FwTuning.getInt("gantry.max_side"));
    }
}
