package dev.fieldframe.forge;

import dev.fieldframe.api.id.FfId;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code config/fieldframe-common.toml}. Switches that are easier in a config file than in a datapack:
 * <ul>
 *   <li>{@code [machines] disabled}: module ids switched off server-wide, same effect as a tuning file with
 *       {@code "disabled": true}.</li>
 * </ul>
 * Everything else (recipes, fuels, tunables) is datapack data; ores belong to Metallurgy. See docs/configuration.md.
 */
public final class FfConfig {
    private FfConfig() {
    }

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> DISABLED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Machines switched off on this server (module ids, e.g. \"fieldframe:nullifier\").",
                "Players still see them; installed ones show DISABLED_BY_CONFIG.").push("machines");
        DISABLED = b.defineListAllowEmpty(List.of("disabled"), List::of, o -> o instanceof String s && FfId.tryParse(s) != null);
        b.pop();
        SPEC = b.build();
    }

    public static Set<FfId> disabledModules() {
        Set<FfId> out = new HashSet<>();
        try {
            for (String s : DISABLED.get()) {
                FfId id = FfId.tryParse(s);
                if (id != null) {
                    out.add(id);
                }
            }
        } catch (IllegalStateException notLoaded) {
            // Config not loaded yet: nothing disabled.
        }
        return out;
    }
}
