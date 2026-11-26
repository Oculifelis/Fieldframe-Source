package dev.fieldframe.api.chassis;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Field Kit rules: a pocket chassis carried in the player's inventory. It has its own spec (a bigger RF buffer than a
 * chassis, small lanes), opens {@link #BASE_FACES} faces at the base tier and one more per tier kit, and takes only
 * modules that make sense without neighbours (chargers, repairers, small generators). Nothing next to the player is
 * ever reached: a kit has no neighbours, no redstone and no world actions.
 *
 * <p>Addons and KubeJS add their own modules with {@link #allow}. A module allowed here must cope with a world that
 * returns no neighbours.
 */
public final class FieldKits {
    private FieldKits() {
    }

    /** Lanes of 2 slots, 2 B of fluid, 64,000 RF (tiers add to it), 1 service bay. */
    public static final ChassisSpec SPEC = new ChassisSpec(FfId.ff("field_kit"), 2, 2_000, 64_000, 1, 8, 250, 256, 256);
    public static final int BASE_FACES = 3;
    /** Faces open in this order: three at the base tier, then one per tier. */
    public static final LocalFace[] FACE_ORDER = {LocalFace.FRONT, LocalFace.TOP, LocalFace.BACK, LocalFace.LEFT, LocalFace.RIGHT, LocalFace.BOTTOM};

    private static final Set<FfId> ALLOWED = Collections.synchronizedSet(new LinkedHashSet<>());

    static {
        for (String id : new String[]{"item_charger", "tool_repairer", "armor_infuser", "solar_collector", "food_engine", "hand_crank"}) {
            ALLOWED.add(FfId.ff(id));
        }
    }

    /** Lets a module be installed in a Field Kit. */
    public static void allow(FfId module) {
        ALLOWED.add(module);
    }

    public static boolean allowed(FfId module) {
        return ALLOWED.contains(module);
    }

    public static Set<FfId> allowedModules() {
        synchronized (ALLOWED) {
            return Set.copyOf(ALLOWED);
        }
    }

    /** Faces open at a tier level (0 = base). */
    public static int faces(int tierLevel) {
        return Math.max(BASE_FACES, Math.min(FACE_ORDER.length, BASE_FACES + tierLevel));
    }

    public static boolean faceOpen(LocalFace face, int tierLevel) {
        int n = faces(tierLevel);
        for (int i = 0; i < n; i++) {
            if (FACE_ORDER[i] == face) {
                return true;
            }
        }
        return false;
    }
}
