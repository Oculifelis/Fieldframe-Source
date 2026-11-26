package dev.fieldframe.api.process;

import dev.fieldframe.api.id.FfId;

/** Built-in process family ids. Addons may define further families with their own ids. */
public final class ProcessFamilies {
    private ProcessFamilies() {
    }

    /** Backed by the effective vanilla smelting recipes plus {@code fieldframe:heating} data recipes. */
    public static final FfId HEATING = FfId.ff("heating");
    public static final FfId CRUSHING = FfId.ff("crushing");
    public static final FfId PRESSING = FfId.ff("pressing");
    public static final FfId MIXING = FfId.ff("mixing");
    public static final FfId WASHING = FfId.ff("washing");
    // Thermal-style machines (recipes use type fieldframe:process with "family"); see docs/thermal_parity.md.
    public static final FfId SAWING = FfId.ff("sawing");
    public static final FfId GROWING = FfId.ff("growing");
    public static final FfId SEPARATING = FfId.ff("separating");
    public static final FfId MELTING = FfId.ff("melting");
    public static final FfId TRANSPOSING = FfId.ff("transposing");
    public static final FfId CHILLING = FfId.ff("chilling");
    public static final FfId REFINING = FfId.ff("refining");
    public static final FfId PYROLYSIS = FfId.ff("pyrolysis");
    public static final FfId ALLOYING = FfId.ff("alloying");
    public static final FfId PRECIPITATING = FfId.ff("precipitating");
    /** Bio-Digester: farm waste and water into biogas and compost. */
    public static final FfId DIGESTION = FfId.ff("digestion");
}
