package dev.fieldframe.core.material;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.frame.FrameComponents;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Pack changes to frame components ({@link FrameComponents}), from {@code data/<ns>/fieldframe/frame_components/*.json}:
 * <pre>
 * { "block": "fieldframe_frameworks:frame_vault", "slots_per_lane": 6 }
 * { "block": "mymod:big_battery", "rf": 4000000, "role": "cell" }
 * { "block": "fieldframe_frameworks:frame_cell", "remove": true }
 * { "components": [ ... several of the above ... ] }
 * </pre>
 * A changed block keeps the values its registration gave for fields the file leaves out. Later files override earlier
 * ones; then Java and KubeJS contributors ({@link FrameComponents#addContributor}) run.
 */
public final class FrameComponentData {
    private FrameComponentData() {
    }

    public static List<String> load(Map<String, String> files) {
        return load(files, FrameComponents.contributors());
    }

    public static List<String> load(Map<String, String> files, List<Consumer<FrameComponents.Sink>> contributors) {
        Table table = new Table();
        List<String> errors = new ArrayList<>();
        new TreeMap<>(files).forEach((id, json) -> {
            try {
                Table staged = new Table();
                staged.changes.putAll(table.changes);
                FfData root = FfJson.parse(json);
                if (root.has("components")) {
                    for (Object o : root.getList("components")) {
                        apply(staged, (FfData) o);
                    }
                } else {
                    apply(staged, root);
                }
                table.changes.clear();
                table.changes.putAll(staged.changes);
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ex.getMessage());
            }
        });
        for (Consumer<FrameComponents.Sink> c : contributors) {
            try {
                c.accept(table);
            } catch (RuntimeException ex) {
                errors.add("contributor: " + ex.getMessage());
            }
        }
        FrameComponents.setOverrides(table.changes);
        return errors;
    }

    private static void apply(Table t, FfData d) {
        String block = d.getString("block", "");
        if (d.getBool("remove", false)) {
            t.remove(block);
            return;
        }
        FrameComponents.Component base = t.current(block);
        t.component(block,
                d.getInt("slots_per_lane", base == null ? 0 : base.slotsPerLane()),
                d.getLong("mb_per_tank", base == null ? 0 : base.mbPerTank()),
                d.getLong("rf", base == null ? 0 : base.rfCapacity()),
                d.getDouble("speed", base == null ? 0 : base.speed()),
                d.getString("role", base == null ? "" : base.role()));
    }

    private static final class Table implements FrameComponents.Sink {
        /** Block -> new component, or null for "not a component any more". */
        final Map<FfId, FrameComponents.Component> changes = new LinkedHashMap<>();

        FrameComponents.Component current(String block) {
            FfId id = FfId.parse(block);
            return changes.containsKey(id) ? changes.get(id) : FrameComponents.registered().get(id);
        }

        @Override
        public void component(String block, int slotsPerLane, long mbPerTank, long rfCapacity, double speed, String role) {
            changes.put(FfId.parse(block), new FrameComponents.Component(slotsPerLane, mbPerTank, rfCapacity, speed, role));
        }

        @Override
        public void remove(String block) {
            changes.put(FfId.parse(block), null);
        }
    }
}
