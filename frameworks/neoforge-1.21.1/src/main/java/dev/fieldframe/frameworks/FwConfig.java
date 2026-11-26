package dev.fieldframe.frameworks;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server settings of Fieldframe Frameworks ({@code fieldframe_frameworks-server.toml} in the world's config folder). */
public final class FwConfig {
    private FwConfig() {
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue STARCORE_EXPLODES;
    public static final ModConfigSpec.DoubleValue STARCORE_EXPLOSION_POWER;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("starcore");
        STARCORE_EXPLODES = b.comment("When the plasma escapes, the Starcore explodes (off: it only goes out and wears its coils).")
                .define("explodes", false);
        STARCORE_EXPLOSION_POWER = b.comment("Explosion power when explosions are on (TNT is 4).")
                .defineInRange("explosion_power", 6.0, 0.0, 20.0);
        b.pop();
        SPEC = b.build();
    }
}
