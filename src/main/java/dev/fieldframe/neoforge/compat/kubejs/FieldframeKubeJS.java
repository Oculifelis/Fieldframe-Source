package dev.fieldframe.neoforge.compat.kubejs;

/** Entry points into the optional KubeJS 7 bridge; only referenced behind a ModList check. */
public final class FieldframeKubeJS {
    private FieldframeKubeJS() {
    }

    public static void postStartupRegistration() {
        FieldframeKubeJSPlugin.postRegisterModules();
    }
}
