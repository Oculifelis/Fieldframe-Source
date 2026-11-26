package dev.fieldframe.core;

import dev.fieldframe.api.frame.FrameComponents;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.material.FrameComponentData;
import dev.fieldframe.core.material.TableSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameComponentDataTest {
    private static final FfId VAULT = FfId.parse("fctest:vault");
    private static final FfId CELL = FfId.parse("fctest:cell");

    @AfterEach
    void clear() {
        FrameComponents.setOverrides(Map.of());
    }

    @Test
    void dataChangesKeepUnsetFieldsAndCanRemove() {
        FrameComponents.register(VAULT, new FrameComponents.Component(3, 0, 0, 0, "vault"));
        FrameComponents.register(CELL, new FrameComponents.Component(0, 0, 1_000_000, 0, "cell"));
        List<String> errors = FrameComponentData.load(Map.of(
                "a.json", "{\"block\": \"fctest:vault\", \"slots_per_lane\": 6}",
                "b.json", "{\"components\": [{\"block\": \"fctest:cell\", \"remove\": true},"
                        + " {\"block\": \"fctest:battery\", \"rf\": 4000000, \"role\": \"cell\"}]}"), List.of());
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(new FrameComponents.Component(6, 0, 0, 0, "vault"), FrameComponents.get(VAULT));
        assertNull(FrameComponents.get(CELL));
        assertEquals(4_000_000, FrameComponents.get(FfId.parse("fctest:battery")).rfCapacity());
        assertTrue(!FrameComponents.effective().containsKey(CELL));
    }

    @Test
    void contributorsRunAfterTheFiles() {
        FrameComponents.register(VAULT, new FrameComponents.Component(3, 0, 0, 0, "vault"));
        FrameComponentData.load(Map.of("a.json", "{\"block\": \"fctest:vault\", \"slots_per_lane\": 6}"),
                List.of(sink -> sink.component("fctest:vault", 9, 0, 0, 0, "vault")));
        assertEquals(9, FrameComponents.get(VAULT).slotsPerLane());
    }

    @Test
    void changesReachTheClient() {
        FrameComponents.register(CELL, new FrameComponents.Component(0, 0, 1_000_000, 0, "cell"));
        FrameComponentData.load(Map.of("a.json", "{\"block\": \"fctest:cell\", \"remove\": true}"), List.of());
        String json = TableSync.toJson();
        FrameComponents.setOverrides(Map.of());
        assertEquals(1_000_000, FrameComponents.get(CELL).rfCapacity());
        TableSync.acceptSync(json);
        assertNull(FrameComponents.get(CELL));
    }
}
