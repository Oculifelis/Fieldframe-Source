package dev.fieldframe.core.chassis;

import dev.fieldframe.api.chassis.ChassisTier;
import dev.fieldframe.api.module.ModuleCategory;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TickPhase;

import java.util.ArrayList;
import java.util.List;

/**
 * The Fieldframe advancement tab: which advancements machine activity grants. The advancements themselves are data
 * ({@code data/fieldframe/advancement/<id>.json}, written by tools/gen_resources.py); each one granted from code has a
 * single {@code minecraft:impossible} criterion named {@link #CRITERION}. The tree follows the Field Manual from the
 * first chassis to a Resonant machine, so a new player always has a next step.
 */
public final class Milestones {
    private Milestones() {
    }

    public static final String CRITERION = "done";

    public static final String ROOT = "root";
    public static final String FIRST_MODULE = "first_module";
    public static final String IT_WORKS = "it_works";
    public static final String SELF_POWERED = "self_powered";
    public static final String HANDS_OFF = "hands_off";
    public static final String UPGRADED = "upgraded";
    public static final String BETTER_FRAME = "better_frame";
    public static final String RESONANT = "resonant";
    public static final String SIGNALS = "signals";
    public static final String PROGRAMMER = "programmer";
    public static final String ENDER_TANK = "ender_tank";

    /** Every advancement in the tab, root first. */
    public static final List<String> ALL = List.of(ROOT, FIRST_MODULE, IT_WORKS, SELF_POWERED, HANDS_OFF, UPGRADED, BETTER_FRAME,
            RESONANT, SIGNALS, PROGRAMMER, ENDER_TANK);

    /** Tier level that counts as the top of the built-in line (Resonant). */
    public static final int TOP_TIER_LEVEL = 4;

    /** Granted when a module starts working: any module, engines and generators, ports moving things on their own. */
    public static List<String> whenWorking(ModuleDefinition def) {
        List<String> out = new ArrayList<>();
        if (def == null) {
            return out;
        }
        out.add(IT_WORKS);
        if (def.category() == ModuleCategory.GENERATION) {
            out.add(SELF_POWERED);
        }
        if (def.category() == ModuleCategory.HANDLING && (def.phase() == TickPhase.IMPORT || def.phase() == TickPhase.EXPORT)) {
            out.add(HANDS_OFF);
        }
        return out;
    }

    /** Granted when an upgrade kit raises a chassis to {@code tier}. */
    public static List<String> whenTier(ChassisTier tier) {
        List<String> out = new ArrayList<>();
        if (tier != null && tier.level() > 0) {
            out.add(BETTER_FRAME);
            if (tier.level() >= TOP_TIER_LEVEL) {
                out.add(RESONANT);
            }
        }
        return out;
    }
}
