package dev.fieldframe.core.chassis;

import dev.fieldframe.api.module.BindingSpec;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The coloured ring on a module's side of the chassis (like Thermal's side rings), so a player can see in the world
 * which storage and tanks it uses, in the machine screen's colours. The ring runs along the second pixel ring from the
 * edge (the faceplate's plain steel band), leaving the corner rivets and the module art free; with several storages it
 * splits into a section per storage. Codes: 0..2 = Orange, Green, Purple storage; 3..5 = the same tanks (paler).
 * In binding order, without repeats, at most {@link #MAX}. Signals are not shown.
 */
public final class FaceMarks {
    public static final int MAX = 4;
    public static final byte[] NONE = new byte[0];

    private FaceMarks() {
    }

    public static byte[] of(InstalledModule m) {
        if (m == null || m.isMissing()) {
            return NONE;
        }
        Set<Byte> codes = new LinkedHashSet<>();
        for (BindingSpec b : m.definition().bindings()) {
            int idx = m.binding(b.key());
            if (idx < 0 || idx > 2 || codes.size() >= MAX) {
                continue;
            }
            if (b.kind() == BindingSpec.Kind.ITEM_LANE) {
                codes.add((byte) idx);
            } else if (b.kind() == BindingSpec.Kind.FLUID_LANE) {
                codes.add((byte) (3 + idx));
            }
        }
        byte[] out = new byte[codes.size()];
        int i = 0;
        for (byte c : codes) {
            out[i++] = c;
        }
        return out;
    }

    /** Texture path of a ring colour, e.g. {@code block/mark/storage_0} or {@code block/mark/tank_2}. */
    public static String texture(int code) {
        return code < 3 ? "block/mark/storage_" + code : "block/mark/tank_" + (code - 3);
    }

    /** The ring's four sides on the 16x16 face, in texels {x0, y0, x1, y1}: top, right, bottom, left (corners free). */
    private static final float[][] EDGES = {{2, 1, 14, 2}, {14, 2, 15, 14}, {2, 14, 14, 15}, {1, 2, 2, 14}};
    /** Ring sides of each mark, by mark count: one colours the whole ring; two split it top+left / right+bottom. */
    private static final int[][][] SPLIT = {
            {},
            {{0, 1, 2, 3}},
            {{0, 3}, {1, 2}},
            {{0}, {1}, {2, 3}},
            {{0}, {1}, {2}, {3}},
    };

    /** The rectangles (texels {x0, y0, x1, y1}) that mark {@code i} of {@code count} colours. */
    public static java.util.List<float[]> ring(int i, int count) {
        int n = Math.max(0, Math.min(MAX, count));
        java.util.List<float[]> out = new java.util.ArrayList<>();
        if (i < 0 || i >= n) {
            return out;
        }
        for (int edge : SPLIT[n][i]) {
            out.add(EDGES[edge].clone());
        }
        return out;
    }

    /** How far a mark sits in front of the face (block units), so it never flickers with it. */
    public static final float LIFT = 0.002f;

    /**
     * Places a mark quad on a face quad. {@code pos} are the face quad's four vertices (block units) and {@code uv} their
     * texture coordinates normalised to 0..1 over the face. The result keeps the vertex order, so winding and facing
     * match the face, covers {@code rect} (texels of the 16x16 face) and is pushed {@link #LIFT} along the normal
     * {@code (nx, ny, nz)}. Returns null for a degenerate quad.
     */
    public static float[][] place(float[][] pos, float[][] uv, float[] rect, float nx, float ny, float nz) {
        float umin = Float.MAX_VALUE;
        float umax = -Float.MAX_VALUE;
        float vmin = Float.MAX_VALUE;
        float vmax = -Float.MAX_VALUE;
        for (float[] t : uv) {
            umin = Math.min(umin, t[0]);
            umax = Math.max(umax, t[0]);
            vmin = Math.min(vmin, t[1]);
            vmax = Math.max(vmax, t[1]);
        }
        if (umax - umin < 1e-6f || vmax - vmin < 1e-6f) {
            return null;
        }
        int a = -1;
        int b = -1;
        int c = -1;
        for (int i = 0; i < 4; i++) {
            boolean lowU = lowU(uv, i);
            boolean lowV = lowV(uv, i);
            if (lowU && lowV) {
                a = i;
            } else if (!lowU && lowV) {
                b = i;
            } else if (lowU) {
                c = i;
            }
        }
        if (a < 0 || b < 0 || c < 0) {
            return null;
        }
        float[] n = {nx * LIFT, ny * LIFT, nz * LIFT};
        float[][] out = new float[4][3];
        for (int i = 0; i < 4; i++) {
            float su = (lowU(uv, i) ? rect[0] : rect[2]) / 16f;
            float sv = (lowV(uv, i) ? rect[1] : rect[3]) / 16f;
            float fu = (su - uv[a][0]) / (uv[b][0] - uv[a][0]);
            float fv = (sv - uv[a][1]) / (uv[c][1] - uv[a][1]);
            for (int k = 0; k < 3; k++) {
                out[i][k] = pos[a][k] + fu * (pos[b][k] - pos[a][k]) + fv * (pos[c][k] - pos[a][k]) + n[k];
            }
        }
        return out;
    }

    /** Whether vertex {@code i} sits on the low-u side of the face (its mark corner then takes the sprite's u0). */
    public static boolean lowU(float[][] uv, int i) {
        float mid = 0;
        for (float[] t : uv) {
            mid += t[0];
        }
        return uv[i][0] < mid / uv.length;
    }

    public static boolean lowV(float[][] uv, int i) {
        float mid = 0;
        for (float[] t : uv) {
            mid += t[1];
        }
        return uv[i][1] < mid / uv.length;
    }
}
