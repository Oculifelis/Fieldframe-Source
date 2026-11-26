package dev.fieldframe.core.material;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends the Treadmill, electrode and temperature tables, the fluid armor effects and the frame-component changes
 * (server data) to clients,
 * so recipe viewers can show them on a dedicated server too. One JSON string; bad input leaves the client's tables
 * unchanged.
 */
public final class TableSync {
    private TableSync() {
    }

    public static String toJson() {
        List<FfData> treadmill = new ArrayList<>();
        for (TreadmillMobs.Entry e : TreadmillMobs.entries().values()) {
            treadmill.add(new FfData().putString("entity", e.entity().toString()).putDouble("rf_per_tick", e.rfPerTick())
                    .putDouble("rf_per_speed", e.rfPerSpeed()).putString("food", e.food()));
        }
        List<FfData> electrodes = new ArrayList<>();
        for (Electrodes.Entry e : Electrodes.entries()) {
            electrodes.add(new FfData().putString("key", e.key()).putDouble("volts", e.volts()));
        }
        List<FfData> temperatures = new ArrayList<>();
        for (Temperatures.Entry e : Temperatures.entries()) {
            temperatures.add(new FfData().putString("key", e.key()).putBool("fluid", e.fluid()).putBool("lit", e.lit())
                    .putInt("kelvin", e.kelvin()));
        }
        List<FfData> frame = new ArrayList<>();
        dev.fieldframe.api.frame.FrameComponents.overrides().forEach((block, c) -> {
            FfData d = new FfData().putString("block", block.toString());
            if (c == null) {
                d.putBool("remove", true);
            } else {
                d.putInt("slots_per_lane", c.slotsPerLane()).putLong("mb_per_tank", c.mbPerTank()).putLong("rf", c.rfCapacity())
                        .putDouble("speed", c.speed()).putString("role", c.role());
            }
            frame.add(d);
        });
        return FfJson.toJsonString(new FfData().putList("treadmill", treadmill).putList("electrodes", electrodes)
                .putList("temperatures", temperatures).putList("frame_components", frame)
                .put("fluid_armor", FfJson.parse(dev.fieldframe.core.flora.FluidArmorEffects.toJson()))
                .put("fluid_armor_pairs", FfJson.parse(dev.fieldframe.core.flora.FloraPairs.toJson())));
    }

    public static void acceptSync(String json) {
        try {
            FfData d = FfJson.parse(json, 1 << 20, 16);
            List<TreadmillMobs.Entry> treadmill = new ArrayList<>();
            for (FfData e : list(d, "treadmill")) {
                treadmill.add(new TreadmillMobs.Entry(FfId.parse(e.getString("entity", "")), e.getDouble("rf_per_tick", 0),
                        e.getDouble("rf_per_speed", 0), e.getString("food", "")));
            }
            List<Electrodes.Entry> electrodes = new ArrayList<>();
            for (FfData e : list(d, "electrodes")) {
                electrodes.add(new Electrodes.Entry(e.getString("key", ""), e.getDouble("volts", 0)));
            }
            List<Temperatures.Entry> temperatures = new ArrayList<>();
            for (FfData e : list(d, "temperatures")) {
                temperatures.add(new Temperatures.Entry(e.getString("key", ""), e.getBool("fluid", false), e.getBool("lit", false),
                        e.getInt("kelvin", Temperatures.MIN_K)));
            }
            java.util.Map<FfId, dev.fieldframe.api.frame.FrameComponents.Component> frame = new java.util.LinkedHashMap<>();
            for (FfData e : list(d, "frame_components")) {
                frame.put(FfId.parse(e.getString("block", "")), e.getBool("remove", false) ? null
                        : new dev.fieldframe.api.frame.FrameComponents.Component(e.getInt("slots_per_lane", 0), e.getLong("mb_per_tank", 0),
                        e.getLong("rf", 0), e.getDouble("speed", 0), e.getString("role", "")));
            }
            // Each table is replaced whole.
            dev.fieldframe.api.frame.FrameComponents.setOverrides(frame);
            TreadmillMobs.replace(treadmill);
            Electrodes.replace(electrodes);
            Temperatures.replace(temperatures);
            FfData armor = d.getDataOrNull("fluid_armor");
            if (armor != null) {
                dev.fieldframe.core.flora.FluidArmorEffects.acceptSync(FfJson.toJsonString(armor));
            }
            FfData pairs = d.getDataOrNull("fluid_armor_pairs");
            if (pairs != null) {
                dev.fieldframe.core.flora.FloraPairs.acceptSync(FfJson.toJsonString(pairs));
            }
        } catch (RuntimeException e) {
            // keep the old tables
        }
    }

    private static List<FfData> list(FfData d, String key) {
        List<FfData> out = new ArrayList<>();
        for (Object o : d.getList(key)) {
            if (o instanceof FfData f) {
                out.add(f);
            }
        }
        return out;
    }
}
