package dev.fieldframe.core.gui;

import java.util.Locale;

/**
 * Where things sit on the Workshop machine screen (176 x 166, panel coordinates) and which role a module's binding
 * plays there. Shared by the menu (slot positions) and the screen (drawing), on every loader and version.
 */
public final class WorkshopLayout {
    public static final int W = 176, H = 166;
    public static final int INV_LABEL_Y = 73, INV_Y = 84, HOTBAR_Y = 142;
    public static final int ENERGY_X = 9, ENERGY_Y = 19, ENERGY_W = 10, ENERGY_H = 48;
    public static final int INPUT_X = 30, GRID_Y = 21, RESULT_X = 102, PITCH = 18;
    public static final int ARROW_X = 72, ARROW_Y = 31, ARROW_Y_FUEL = 21;
    public static final int FUEL_X = 76, FUEL_Y = 39;
    public static final int TANK_X = 151, TANK_Y = 19, TANK_W = 16, TANK_H = 48;
    public static final int STATUS_X = 30, STATUS_Y = 61;
    /** Slots shown per lane in each 2 x 2 grid. */
    public static final int GRID_SLOTS = 4;

    /** Face tabs on top, in this order: top, bottom, front, back, left, right (LocalFace ordinals). */
    public static final int[] FACE_ORDER = {4, 5, 0, 1, 2, 3};
    public static final int FACE_TAB_W = 28, FACE_TAB_PITCH = 29, FACE_TAB_H = 27, FACE_TAB_H_SEL = 30;

    /** Side tabs: closed size, stacked from y + 4 with a 1 px gap. */
    public static final int SIDE_TAB_W = 24, SIDE_TAB_H = 22, SIDE_TAB_TOP = 4, SIDE_TAB_GAP = 1;

    public enum Role {
        INPUT, RESULT, SUPPLY
    }

    private WorkshopLayout() {
    }

    /** Slot position of grid cell {@code i} (0..3), panel coordinates. */
    public static int gridX(int left, int i) {
        return left + (i % 2) * PITCH;
    }

    public static int gridY(int i) {
        return GRID_Y + (i / 2) * PITCH;
    }

    /**
     * The role of a binding: by its key first ("output", "result" -> result; "fuel", "tool", "catalyst" -> supply),
     * then by its default lane (0 input, 1 result, 2 supply).
     */
    public static Role role(String key, int defaultIndex) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("output") || k.contains("result")) {
            return Role.RESULT;
        }
        if (k.contains("fuel") || k.contains("tool") || k.contains("catalyst")) {
            return Role.SUPPLY;
        }
        if (k.contains("input")) {
            return Role.INPUT;
        }
        return defaultIndex == 1 ? Role.RESULT : defaultIndex == 2 ? Role.SUPPLY : Role.INPUT;
    }
}
