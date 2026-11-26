package dev.fieldframe.api.module;

import dev.fieldframe.api.id.FfId;

/**
 * Public access to Fieldframe's reusable behaviour archetypes, so addons can build modules on the shared engines
 * without depending on internal packages. Built-in processors are created through exactly this entry point.
 */
public final class Archetypes {
    private Archetypes() {
    }

    /** Implemented by Fieldframe's core; installed during class initialization of the built-in content. */
    public interface Provider {
        ModuleDefinition.Builder processor(FfId id, FfId family, ModuleDefinition.Stage stage, boolean fluidIn, boolean fluidOut);
    }

    private static volatile Provider provider;

    public static void install(Provider p) {
        provider = p;
    }

    /**
     * A processing module: bindings {@code input}, {@code output} (item lanes A/B), optional {@code fluid_in}/{@code fluid_out},
     * {@code enable} (signal in), {@code active}/{@code done} (signal out); tunables {@code speed}, {@code energy_multiplier}.
     * Recipes come from the effective recipe data for {@code family}.
     */
    public static ModuleDefinition.Builder processor(FfId id, FfId family, ModuleDefinition.Stage stage, boolean fluidIn, boolean fluidOut) {
        Provider p = provider;
        if (p == null) {
            throw new IllegalStateException("Fieldframe core not initialized yet");
        }
        return p.processor(id, family, stage, fluidIn, fluidOut);
    }
}
