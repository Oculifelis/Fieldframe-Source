package dev.fieldframe.core.gui;

/**
 * Pixel art for the machine screen, drawn in code so every loader and game version gets the same pixels.
 * Everything returns plain ARGB arrays (0 = transparent); each loader only has to put them on screen.
 */
public final class WorkshopArt {
    private WorkshopArt() {
    }

    // palette
    public static final int PANEL_BG = 0xFF3D4043, PANEL_HI = 0xFF63676B, PANEL_LO = 0xFF1E2022, EDGE = 0xFF000000;
    public static final int SLOT_FILL = 0xFF17191B, SLOT_DARK = 0xFF0B0C0D, SLOT_LIGHT = 0xFF6C7074;
    public static final int INPUT = 0xFF0A76D0, RESULT = 0xFFD0650B, SUPPLY = 0xFFD0A60A;
    public static final int TANK_INPUT = 0xFF7DB6E6, TANK_RESULT = 0xFFE6A06A, TANK_SUPPLY = 0xFFE6CF6E;
    public static final int TITLE = 0xFFE6E6E6, SUB = 0xFF9EA2A6, INV_LABEL = 0xFFBFC3C7, OK = 0xFF7CD67C;
    public static final int WARN = 0xFFF0D080, BAD = 0xFFFF7A6A, WHITE = 0xFFFFFFFF, DIM = 0xFFD8D8D8, HEAD = 0xFFFFF2C0;
    public static final int TAB_INFO = 0xFF2F6690, TAB_ENERGY = 0xFFA8521E, TAB_CHASSIS = 0xFF3F6E7A,
            TAB_REDSTONE = 0xFF962A2A, TAB_ROUTING = 0xFF2A7A60, TAB_SETTINGS = 0xFF5E4E8C, TAB_BAYS = 0xFF857226;
    public static final int TAB_OFF_BG = 0xFF2A2C2F, TAB_OFF_HI = 0xFF45484B, TAB_OFF_LO = 0xFF18191B;

    /** Red charge ramp: cool crimson shadows to warm orange highlights (hue-shifted from redstone and glowstone dust). */
    private static final int[] RAMP = {0xFF22060F, 0xFF3E0915, 0xFF620C14, 0xFF8C120E, 0xFFB21C0E, 0xFFD23412,
            0xFFEC5A24, 0xFFFF8E46, 0xFFFFC788, 0xFFFFE9C4};
    /** Energy bar animation: 24 frames, 2 ticks each (2.4 s loop). */
    public static final int FRAMES = 24;

    public static int shade(int c, double f) {
        int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        if (f >= 1) {
            r = Math.min(255, (int) (r + (255 - r) * (f - 1)));
            g = Math.min(255, (int) (g + (255 - g) * (f - 1)));
            b = Math.min(255, (int) (b + (255 - b) * (f - 1)));
        } else {
            r = (int) (r * f);
            g = (int) (g * f);
            b = (int) (b * f);
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int c, int a) {
        return (c & 0xFFFFFF) | (a << 24);
    }

    private static int ramp(double v) {
        long i = Math.round(v);
        return RAMP[(int) Math.max(0, Math.min(RAMP.length - 1, i))];
    }

    /** Cross profile of the charge column: lit left edge, bright core a third in, dark right edge. */
    private static double[] profile(int w) {
        double[][] pts = {{0.0, 5.6}, {0.2, 4.6}, {0.38, 5.0}, {0.6, 3.9}, {0.85, 3.1}, {1.0, 2.0}};
        double[] out = new double[w];
        for (int i = 0; i < w; i++) {
            double t = i / (double) Math.max(1, w - 1);
            for (int k = 0; k + 1 < pts.length; k++) {
                if (pts[k][0] <= t && t <= pts[k + 1][0]) {
                    out[i] = pts[k][1] + (pts[k + 1][1] - pts[k][1]) * (t - pts[k][0]) / (pts[k + 1][0] - pts[k][0]);
                    break;
                }
            }
        }
        return out;
    }

    private static void put(int[] px, int w, int h, int x, int y, int c) {
        if (x >= 0 && y >= 0 && x < w && y < h) {
            px[y * w + x] = c;
        }
    }

    /**
     * The energy gauge's inner area (w x h, without its 1 px inset border). {@code frame} is 0..FRAMES-1: ribs drift up,
     * one soft glow rises per loop, the surface breathes and a spark climbs now and then. Nothing blinks.
     */
    public static int[] energyBar(int w, int h, double frac, int frame) {
        int[] px = new int[w * h];
        for (int r = 0; r < h; r++) {
            int c = r % 3 == 2 ? 0xFF1D0D11 : 0xFF160A0D;
            for (int x = 0; x < w; x++) {
                px[r * w + x] = c;
            }
        }
        for (int q = 1; q <= 3; q++) {
            int y = h - h * q / 4;
            put(px, w, h, 0, y, 0xFF452630);
            put(px, w, h, 1, y, 0xFF452630);
        }
        int filled = (int) (h * Math.max(0, Math.min(1, frac)));
        if (filled <= 0) {
            return px;
        }
        int f = Math.floorMod(frame, FRAMES);
        double[] prof = profile(w);
        int shift = f / 2;
        double band = (f / (double) FRAMES) * (filled + 14) - 7;
        double pulse = 0.25 * Math.sin(2 * Math.PI * f / FRAMES);
        for (int i = 0; i < filled; i++) {
            int yy = h - 1 - i;
            double rib = Math.floorMod(i - shift, 3) == 0 ? -1.2 : 0.0;
            double glow = Math.exp(-Math.pow((i - band) / 2.6, 2));
            double depth = -0.7 * (1 - i / (double) Math.max(1, filled));
            for (int c = 0; c < w; c++) {
                double v = prof[c] + rib * 0.6 + glow + depth * 0.6 + TEX[(i % 64) * 16 + (c % 16)] / 100.0;
                if (c == w - 1) {
                    v = Math.min(v, 2.6);
                }
                px[yy * w + c] = ramp(v);
            }
        }
        int sy = h - filled;
        for (int c = 0; c < w; c++) {
            put(px, w, h, c, sy, ramp(prof[c] + 2.2 + pulse));
            if (filled > 1) {
                put(px, w, h, c, sy + 1, ramp(prof[c] + 1.0));
            }
        }
        put(px, w, h, w - 1, sy, ramp(4.4));
        int life = 16;
        int[] starts = {0, 12};
        for (int k = 0; k < starts.length; k++) {
            int p = Math.floorMod(f - starts[k], FRAMES);
            if (p < life && filled > 10) {
                int sy0 = (int) (filled * 0.15) + k * 3;
                int pos = sy0 + (int) ((filled - 3 - sy0) * p / (double) life);
                int col = Math.min(w - 3, Math.max(1, 2 + (k % 2) * (w / 3)));
                double a = 1.0 - Math.abs(p / (double) life - 0.5) * 2;
                if (a > 0.3) {
                    put(px, w, h, col, h - 1 - pos, ramp(7 + a * 1.5));
                }
            }
        }
        return px;
    }

    /** 24x16 progress arrow; the fill runs from the input colour to the result colour. */
    public static int[] arrow(double frac) {
        boolean[] in = new boolean[24 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 24; x++) {
                int tip = x - 14;
                in[y * 24 + x] = (y >= 5 && y <= 10 && x <= 14)
                        || (tip >= 0 && tip <= 9 && Math.abs(y - 7.5) <= 7.5 - tip * 0.8);
            }
        }
        int a = 0xFF2A8CE0, b = 0xFFE07A20;
        int[] px = new int[24 * 16];
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 24; x++) {
                if (!in[y * 24 + x]) {
                    continue;
                }
                boolean edge = false;
                for (int[] o : d) {
                    int nx = x + o[0], ny = y + o[1];
                    if (nx < 0 || ny < 0 || nx >= 24 || ny >= 16 || !in[ny * 24 + nx]) {
                        edge = true;
                    }
                }
                int c;
                if (x < frac * 24) {
                    double t = x / 23.0;
                    c = 0xFF000000 | (lerp(a >> 16, b >> 16, t) << 16) | (lerp(a >> 8, b >> 8, t) << 8) | lerp(a, b, t);
                    if (edge) {
                        c = shade(c, 0.6);
                    } else if (y <= 6) {
                        c = shade(c, 1.25);
                    }
                } else {
                    c = edge ? 0xFF111213 : 0xFF26282A;
                }
                px[y * 24 + x] = c;
            }
        }
        return px;
    }

    private static int lerp(int a, int b, double t) {
        a &= 255;
        b &= 255;
        return (int) (a + (b - a) * t);
    }

    /**
     * The machine panel: black rim with cut corners, 2 px light top-left, 2 px dark bottom-right, faint brushed grain.
     * Inventory slot insets are drawn in at (8 + c*18, invY + r*18) and the hotbar at invY + 58.
     */
    public static int[] panel(int w, int h, int invY) {
        int[] px = new int[w * h];
        frame(px, w, h, 0, 0, w, h, PANEL_BG, PANEL_HI, PANEL_LO, true);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                inset(px, w, h, 8 + c * 18, invY + r * 18, 16, 16, SLOT_FILL);
            }
        }
        for (int c = 0; c < 9; c++) {
            inset(px, w, h, 8 + c * 18, invY + 58, 16, 16, SLOT_FILL);
        }
        return px;
    }

    /** A bevelled box with a black cut-corner rim, optionally with brushed grain. Used for the panel and face tabs. */
    public static void frame(int[] px, int w, int h, int x, int y, int fw, int fh, int bg, int hi, int lo, boolean grain) {
        rect(px, w, h, x + 2, y, fw - 4, 1, EDGE);
        rect(px, w, h, x + 2, y + fh - 1, fw - 4, 1, EDGE);
        rect(px, w, h, x, y + 2, 1, fh - 4, EDGE);
        rect(px, w, h, x + fw - 1, y + 2, 1, fh - 4, EDGE);
        put(px, w, h, x + 1, y + 1, EDGE);
        put(px, w, h, x + fw - 2, y + 1, EDGE);
        put(px, w, h, x + 1, y + fh - 2, EDGE);
        put(px, w, h, x + fw - 2, y + fh - 2, EDGE);
        rect(px, w, h, x + 2, y + 1, fw - 4, fh - 2, bg);
        rect(px, w, h, x + 1, y + 2, fw - 2, fh - 4, bg);
        rect(px, w, h, x + 2, y + 1, fw - 5, 2, hi);
        rect(px, w, h, x + 1, y + 2, 2, fh - 5, hi);
        rect(px, w, h, x + 3, y + fh - 3, fw - 5, 2, lo);
        rect(px, w, h, x + fw - 3, y + 3, 2, fh - 5, lo);
        if (grain) {
            // faint horizontal streaks plus a fine speckle; fixed seed, so it never shimmers
            java.util.Random rnd = new java.util.Random(fw * 31L + fh);
            for (int yy = 3; yy < fh - 3; yy++) {
                int row = rnd.nextInt(9) - 4;
                for (int xx = 3; xx < fw - 3; xx++) {
                    int dd = row + rnd.nextInt(3) - 1 + ((xx * 7 + yy * 13) % 29 == 0 ? 2 : 0);
                    int i = (y + yy) * w + x + xx;
                    if (x + xx < w && y + yy < h) {
                        px[i] = add(px[i], dd);
                    }
                }
            }
        }
    }

    private static int add(int c, int d) {
        int r = Math.max(0, Math.min(255, ((c >> 16) & 255) + d));
        int g = Math.max(0, Math.min(255, ((c >> 8) & 255) + d));
        int b = Math.max(0, Math.min(255, (c & 255) + d));
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static void rect(int[] px, int w, int h, int x, int y, int rw, int rh, int c) {
        for (int yy = Math.max(0, y); yy < Math.min(h, y + rh); yy++) {
            for (int xx = Math.max(0, x); xx < Math.min(w, x + rw); xx++) {
                px[yy * w + xx] = c;
            }
        }
    }

    /** Recessed box; (x, y, rw, rh) is the inner area, the border sits 1 px outside it. */
    public static void inset(int[] px, int w, int h, int x, int y, int rw, int rh, int fill) {
        rect(px, w, h, x - 1, y - 1, rw + 1, 1, SLOT_DARK);
        rect(px, w, h, x - 1, y - 1, 1, rh + 1, SLOT_DARK);
        rect(px, w, h, x, y + rh, rw + 1, 1, SLOT_LIGHT);
        rect(px, w, h, x + rw, y, 1, rh + 1, SLOT_LIGHT);
        rect(px, w, h, x, y, rw, rh, fill);
    }

    /** Fixed charge surface (blotches, veins, flecks), hundredths, 64 rows x 16 columns. */
    private static final short[] TEX = {
        -44, -22, -38, -35, -26, -42, -18, 11, 25, -32, -146, -204, -93, -197, -161, -121,
        -51, -47, -46, -47, -59, -33, -9, 8, -29, -135, -168, -182, -184, -170, -146, -131,
        -90, -80, -52, -52, -56, -30, -49, -138, -154, -81, -54, -36, -6, -50, -48, -56,
        -90, -80, -50, -48, -151, -155, -83, -55, -63, -57, -64, -38, -19, -18, -23, -42,
        -95, -88, -69, -84, 143, -25, -67, -89, -96, -84, -60, -36, -44, -37, -32, -63,
        -85, -62, -38, -137, 10, -8, -41, -70, -84, -78, -51, -45, -5, -13, -19, -26,
        -53, -25, 12, -109, 37, 5, -9, -38, -60, -70, -63, -36, 7, 8, 9, 15,
        -27, -29, 7, -17, 24, 19, -18, -46, -55, -50, -62, -34, -9, 0, -3, 170,
        -70, -148, -129, 25, 38, 12, -34, -48, -42, -64, -59, -38, -20, -26, -20, 22,
        -32, -22, -3, 27, 49, 24, -27, -34, -3, -31, -51, -52, -52, -38, -11, 28,
        -47, -15, -11, 19, 47, 30, 11, 25, 42, 11, -32, -67, -88, -54, 9, 48,
        -13, -18, -15, 4, 12, 23, 43, 51, 58, 41, -32, -82, -97, -69, -19, 38,
        -62, -12, -14, -2, 16, 23, 48, 57, 189, 31, -28, -72, -90, -82, -28, 32,
        -141, -142, -19, -21, -18, 1, 23, 42, 33, 14, -1, -51, -81, -61, -37, 17,
        -148, -145, -79, -49, -73, -32, 8, -4, -3, 10, 26, -14, -38, -37, 1, -2,
        -63, -164, -76, -47, -84, -46, -12, -21, -37, -8, 10, 12, 30, 22, 1, -35,
        -69, -160, -95, -47, -87, -50, -9, -32, -51, -32, 3, 47, 75, 52, 20, -57,
        -168, -172, -178, -96, -52, -35, -32, -34, -42, -34, -15, 13, 30, 31, -3, -48,
        -82, -170, -176, -166, -152, -78, -83, -74, -71, -68, -85, -169, -168, 66, -29, -49,
        5, -53, -149, -58, 9, -13, -32, -8, -3, -10, -27, -69, -77, -201, -113, -67,
        13, -36, -132, 1, 22, -1, -8, 5, 13, 11, -6, -46, -92, -138, -129, -113,
        -35, 24, -140, -6, 5, 14, 6, 12, -6, -10, 11, -32, -55, -106, -187, -104,
        -122, -131, -17, -2, 11, 9, 17, -7, -32, -9, 12, 4, -14, -70, -156, -166,
        3, 12, 13, -4, -14, -4, -8, -20, -43, -61, -115, -111, -28, -41, -57, -68,
        -16, 23, 41, 2, -30, -16, -6, -28, -92, -150, -20, -108, -110, -38, -11, -23,
        -12, 14, 20, -12, -12, -3, 1, -20, -156, -70, -5, -18, -99, -34, -4, -26,
        8, 9, 5, 7, 24, 17, 12, 128, -72, -162, -84, -130, -78, 15, -5, -27,
        27, 30, 20, -2, 3, 14, -12, -22, -33, -83, -164, -46, 32, 17, 11, 15,
        43, 27, 149, 18, 9, 5, -14, -32, -48, -43, -40, -14, -10, 20, 35, 23,
        60, 38, 31, 7, 8, -8, -21, -33, -37, -25, -10, -1, 3, 17, 31, 16,
        55, 45, 26, -4, -26, -27, -14, -31, -16, 19, 25, 3, -11, -11, 18, -21,
        32, 25, 18, -18, -43, -20, 29, 30, 27, 34, 20, -17, -34, -21, 0, -6,
        35, 33, 5, -21, -47, 5, 56, 72, 47, 30, -1, -30, -45, -31, -9, -11,
        42, 30, 19, -15, -40, -10, 36, 62, 52, 44, 18, -22, -42, -26, -14, -34,
        106, 1, 15, -7, -28, -4, 5, 19, 30, 26, 18, -17, -23, -50, -111, -12,
        -72, -68, -14, 132, -9, -10, -18, -39, -51, -32, -1, 29, 28, -107, 2, 53,
        -92, -92, -37, -11, 4, -19, -30, -63, -103, -68, -14, 33, 61, -70, 189, 61,
        -82, -85, 72, -141, -135, -136, -146, -46, -87, -52, -2, 19, -32, -9, 48, 48,
        19, -35, -131, -47, -20, -7, 5, -153, -50, -20, 17, -6, -142, 9, 31, 17,
        57, -5, -119, -13, -23, -5, 21, 10, -151, -1, 8, 11, -136, 10, 27, 27,
        74, 18, -43, -2, -22, -2, 30, 28, -116, 3, -11, -6, -141, 14, 36, -4,
        68, 16, 133, -11, -12, 7, 44, 26, -119, 11, 10, 4, -143, 7, 22, 5,
        41, -59, 5, 30, 17, 31, 55, 22, -44, -31, 39, -30, -130, 0, -9, 127,
        -47, -60, 44, 57, 56, 51, 36, 4, -17, -132, -16, -118, -94, 5, -7, -31,
        -61, -64, 55, 55, 77, 56, 21, -2, -20, -142, -119, -87, -80, -95, -43, -81,
        -75, 3, 45, 72, 61, 55, 24, -3, -18, 89, -135, -99, -87, -111, -141, -173,
        -97, 35, 34, 56, 72, 57, 21, 14, -9, -124, -139, -128, -125, -136, -155, -175,
        37, 44, -90, -65, 61, 52, 163, -92, -84, 24, -10, -18, -58, -138, -68, 73,
        57, 47, 188, 41, 49, 43, 32, 40, 78, 23, -14, -20, -20, -152, -11, -25,
        8, 10, 22, 41, 37, 36, 27, 36, 60, 23, -27, -42, -44, -176, -46, -36,
        -60, -59, -21, 12, 33, 12, -15, 10, 22, 4, -55, -62, -73, -206, -68, -56,
        -73, -72, -47, -15, 26, 5, -15, -21, -31, -41, -67, -70, -83, -208, -87, -81,
        -80, -68, -30, -1, 15, 13, 2, -23, -40, -58, -91, -71, -76, 20, -88, -87,
        -97, -191, -178, -68, -9, -10, -64, -160, -34, -45, -55, -37, -74, -104, -58, -77,
        -88, -55, -68, -49, -39, -39, -40, 108, -164, -22, -17, 8, 90, -44, -29, -53,
        -40, -50, -64, -48, -28, -28, -27, -27, -159, -17, -5, 34, 14, 5, -3, -43,
        -31, -47, -91, -43, 0, -14, -26, -26, -186, -20, 18, 38, 23, 13, 25, -37,
        -21, -33, -31, -15, 6, -3, -9, -10, -144, -6, 14, 59, 26, 57, 12, -30,
        1, 14, 23, 31, 42, 13, 11, -38, -18, 18, 157, 50, 74, -69, -31, -18,
        11, 19, 46, 66, 78, 69, 1, -78, 47, 34, 59, 56, 60, -74, -95, -49,
        -12, 20, 63, 94, 95, 93, 45, -59, 54, 51, 75, 71, 33, 7, -102, -124,
        -21, 6, 44, 68, 67, 70, 84, -61, 10, 50, 29, 42, 55, -15, -130, -145,
        -82, -3, 23, 50, 65, 73, 81, 58, 4, -107, 104, -40, -12, -153, -182, -171,
        -168, -142, 30, 35, 51, 56, 50, 67, 70, 49, 17, -59, -145, -163, -97, -39,
    };

    /** The energy tab icon, 16x16 ARGB, already centred. */
    public static final int[] BOLT = {
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0xFF8C120E, 0xFF8C120E, 0xFF8C120E, 0xFF8C120E, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0xFF8C120E,
        0xFFFFE9C4, 0xFFFFC788, 0xFFFFC788, 0xFFFF8E46, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0xFF8C120E, 0xFFFFE9C4,
        0xFFFFC788, 0xFFFFC788, 0xFFFF8E46, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0xFF8C120E, 0xFFFFC788, 0xFFFFC788,
        0xFFFFC788, 0xFFFF8E46, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0xFF8C120E, 0xFFFFC788, 0xFFFF8E46, 0xFFFF8E46,
        0xFFEC5A24, 0xFF3E0915, 0xFF8C120E, 0xFF8C120E, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0xFF8C120E, 0xFFFFC788, 0xFFFF8E46, 0xFFFF8E46, 0xFFFF8E46,
        0xFFFF8E46, 0xFFFF8E46, 0xFFFF8E46, 0xFFEC5A24, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0xFF8C120E, 0xFFFFC788, 0xFFFF8E46, 0xFFFF8E46, 0xFFFF8E46, 0xFFFF8E46,
        0xFFFF8E46, 0xFFFF8E46, 0xFFEC5A24, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0xFF3E0915, 0xFF3E0915, 0xFF3E0915, 0xFFFF8E46, 0xFFEC5A24,
        0xFFEC5A24, 0xFFD23412, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0xFF8C120E, 0xFFFF8E46, 0xFFEC5A24, 0xFFEC5A24,
        0xFFD23412, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0xFF8C120E, 0xFFFF8E46, 0xFFEC5A24, 0xFFD23412,
        0xFF3E0915, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0xFF8C120E, 0xFFEC5A24, 0xFFEC5A24, 0xFFB21C0E, 0xFF3E0915,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0xFF8C120E, 0xFFEC5A24, 0xFFB21C0E, 0xFF3E0915, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0xFF8C120E, 0xFFEC5A24, 0xFFB21C0E, 0xFF3E0915, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0xFF8C120E, 0xFFD23412, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0xFF3E0915, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
    };
}
