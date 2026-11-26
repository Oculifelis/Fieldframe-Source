package dev.fieldframe.core.chassis;

import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.module.ModuleBehavior;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.status.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * A compact, display-only summary of a chassis for block-info overlays (Jade): one entry per installed face with its
 * status code and progress, the RF buffer, and non-empty lanes and reservoirs. Built on the server, sent as data,
 * formatted on the client; nothing here can change the chassis.
 */
public final class ViewerSummary {
    private ViewerSummary() {
    }

    public static FfData of(ChassisCore core) {
        FfData out = new FfData();
        List<FfData> faces = new ArrayList<>();
        for (LocalFace f : LocalFace.values()) {
            InstalledModule m = core.module(f);
            if (m == null) {
                continue;
            }
            FfData d = new FfData().putString("face", f.key()).putString("module", m.moduleId().toString())
                    .putBool("missing", m.isMissing());
            Status s = m.status();
            if (s != null) {
                d.putString("status", s.code()).putString("severity", s.severity().name()).putList("args", new ArrayList<>(s.args()));
            }
            ModuleBehavior b = m.behavior();
            if (b != null) {
                double p = b.progress();
                if (p > 0 && p <= 1) {
                    d.putInt("progress", (int) Math.round(p * 100));
                }
            }
            faces.add(d);
        }
        out.putList("faces", faces);
        out.putLong("rf", core.energy().stored()).putLong("rf_max", core.energy().capacity());
        out.putBool("paused", core.isPaused());
        out.putInt("bolt_parts", core.group() == null ? 1 : core.group().parts().size());
        out.putString("tier", core.tier().id().toString()).putInt("tier_level", core.tier().level());
        List<FfData> lanes = new ArrayList<>();
        for (int i = 0; i < ChassisSpec.ITEM_LANES; i++) {
            long n = core.lane(i).totalCount();
            if (n > 0) {
                lanes.add(new FfData().putInt("lane", i).putLong("count", n));
            }
        }
        out.putList("lanes", lanes);
        List<FfData> tanks = new ArrayList<>();
        for (int i = 0; i < ChassisSpec.ITEM_LANES; i++) {
            FluidVariant v = core.tank(i).fluid();
            if (v != null && core.tank(i).amount() > 0) {
                tanks.add(new FfData().putInt("tank", i).putString("fluid", v.id().toString())
                        .putLong("amount", core.tank(i).amount()).putLong("capacity", core.tank(i).capacity()));
            }
        }
        out.putList("tanks", tanks);
        return out;
    }
}
