package dev.fieldframe.core.chassis;

import dev.fieldframe.api.module.ModuleDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * A level (0..15) shown in the world on a module's faceplate: three lamps (green when on, amber from half, red at
 * full) and a bar that fills with the level, both full-bright. The Indicator Panel shows one by default; any module can
 * switch it on or off with the presentation hint {@code gauge} (true/false). The level is the behaviour's
 * {@code progress()} scaled to 0..15. Purely cosmetic: the client copy of the chassis carries one level per face.
 */
public final class FaceGauge {
    /** No gauge on this face. */
    public static final int NONE = -1;
    /** Lamp colours, by index: green, amber, red. */
    public static final int LAMPS = 3;

    private FaceGauge() {
    }

    public static boolean shows(ModuleDefinition def) {
        if (def == null) {
            return false;
        }
        if (def.presentation().has("gauge")) {
            return def.presentation().getBool("gauge", false);
        }
        return def.id().namespace().equals("fieldframe") && def.id().path().equals("indicator_panel");
    }

    /** The level this module shows, or {@link #NONE}. */
    public static int level(InstalledModule m) {
        if (m == null || m.isMissing() || !shows(m.definition()) || m.behavior() == null) {
            return NONE;
        }
        double p = m.behavior().progress();
        if (Double.isNaN(p)) {
            return 0;
        }
        return (int) Math.round(Math.max(0, Math.min(1, p)) * 15);
    }

    /** Whether lamp {@code i} (0 green, 1 amber, 2 red) is lit at {@code level}. */
    public static boolean lampLit(int i, int level) {
        return switch (i) {
            case 0 -> level > 0;
            case 1 -> level >= 8;
            case 2 -> level >= 15;
            default -> false;
        };
    }

    /** Lamp {@code i} on the 16x16 face, in texels {x0, y0, x1, y1} (over the faceplate's painted lamps). */
    public static float[] lamp(int i) {
        float x = 4 + i * 3;
        return new float[]{x, 4, x + 2, 6};
    }

    /** The bar's full extent on the face, in texels {x0, y0, x1, y1} (inside the faceplate's black display). */
    public static final float[] BAR = {5, 9, 11, 11};

    /** The lit part of the bar at {@code level}, or null when nothing is lit. */
    public static float[] bar(int level) {
        if (level <= 0) {
            return null;
        }
        int l = Math.min(15, level);
        return new float[]{BAR[0], BAR[1], BAR[0] + (BAR[2] - BAR[0]) * l / 15f, BAR[3]};
    }

    /** Lit rectangles at {@code level}: each entry is {sprite index (0..2 lamps, 3 bar), x0, y0, x1, y1}. */
    public static List<float[]> lit(int level) {
        List<float[]> out = new ArrayList<>();
        if (level <= 0) {
            return out;
        }
        for (int i = 0; i < LAMPS; i++) {
            if (lampLit(i, level)) {
                float[] r = lamp(i);
                out.add(new float[]{i, r[0], r[1], r[2], r[3]});
            }
        }
        float[] b = bar(level);
        out.add(new float[]{LAMPS, b[0], b[1], b[2], b[3]});
        return out;
    }

    /**
     * The status lamp in a faceplate's top-right corner (texels, outside the storage ring): amber while the module is
     * BLOCKED, red on a FAULT, dark otherwise, so a stuck machine shows in the world without opening anything.
     */
    public static final float[] STATUS_LAMP = {14, 0, 16, 2};

    /** Sprite index of the status lamp for a severity ordinal (Status.Severity), or -1 for none. */
    public static int statusLamp(int severity) {
        if (severity == dev.fieldframe.api.status.Status.Severity.BLOCKED.ordinal()) {
            return 1;
        }
        if (severity == dev.fieldframe.api.status.Status.Severity.FAULT.ordinal()) {
            return 2;
        }
        return -1;
    }

    /** Sprite paths (block atlas, {@code fieldframe} namespace), by sprite index of {@link #lit}. */
    public static final List<String> TEXTURES = List.of("block/mark/lamp_green", "block/mark/lamp_amber",
            "block/mark/lamp_red", "block/mark/gauge_bar");

    /** All six faces' levels packed 5 bits each (0 = none, else level + 1), to see when the client copy is stale. */
    public static int key(int[] levels) {
        int k = 0;
        for (int i = 0; i < levels.length && i < 6; i++) {
            k |= (levels[i] < 0 ? 0 : Math.min(15, levels[i]) + 1) << (i * 5);
        }
        return k;
    }
}
