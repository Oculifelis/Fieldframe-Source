package dev.fieldframe.forge.compat.kubejs;

/**
 * Entry points from Fieldframe into the optional KubeJS bridge. Only referenced behind a ModList check, so this
 * class (and everything referencing KubeJS types) is never loaded when KubeJS is absent.
 */
public final class FieldframeKubeJS {
    private FieldframeKubeJS() {
    }

    /** Fires the startup {@code FieldframeEvents.registerModules} script event before the module registry freezes. */
    public static void postStartupRegistration() {
        FieldframeKubeJSPlugin.postRegisterModules();
    }
}
