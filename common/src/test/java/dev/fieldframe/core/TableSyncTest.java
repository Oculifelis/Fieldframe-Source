package dev.fieldframe.core;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.material.Electrodes;
import dev.fieldframe.core.material.TableSync;
import dev.fieldframe.core.material.Temperatures;
import dev.fieldframe.core.material.TreadmillMobs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TableSyncTest {
    @AfterEach
    void clear() {
        TreadmillMobs.replace(List.of());
        Electrodes.replace(List.of());
        Temperatures.replace(List.of());
    }

    @Test
    void tablesSurviveTheTripToTheClient() {
        TreadmillMobs.replace(List.of(new TreadmillMobs.Entry(FfId.parse("minecraft:cow"), 6, 0, "minecraft:wheat"),
                new TreadmillMobs.Entry(FfId.parse("minecraft:horse"), 0, 40, "#c:foods/golden")));
        Electrodes.replace(List.of(new Electrodes.Entry("#c:ingots/zinc", -0.76)));
        Temperatures.replace(List.of(new Temperatures.Entry("#minecraft:campfires", false, true, 600),
                new Temperatures.Entry("minecraft:water", true, false, 285)));
        String json = TableSync.toJson();
        var treadmill = TreadmillMobs.entries();
        var electrodes = Electrodes.entries();
        var temperatures = Temperatures.entries();
        clear();
        TableSync.acceptSync(json);
        assertEquals(treadmill, TreadmillMobs.entries());
        assertEquals(electrodes, Electrodes.entries());
        assertEquals(temperatures, Temperatures.entries());
    }

    @Test
    void badInputKeepsTheTables() {
        Electrodes.replace(List.of(new Electrodes.Entry("minecraft:gold_ingot", 1.5)));
        TableSync.acceptSync("{not json");
        assertEquals(1, Electrodes.entries().size());
    }
}
