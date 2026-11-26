package dev.fieldframe.neoforge.api.client;

import dev.fieldframe.api.id.FfId;

import java.util.HashMap;
import java.util.Map;

/**
 * Public client API (Forge 1.20.1) for manual page rendering. The shipped manual's own page types are registered
 * through this same API. Unknown types render a descriptive fallback.
 */
public final class ManualClientApi {
    private ManualClientApi() {
    }

    private static final Map<FfId, ManualPageRenderer> RENDERERS = new HashMap<>();

    public static synchronized void registerRenderer(FfId type, ManualPageRenderer renderer) {
        if (RENDERERS.putIfAbsent(type, renderer) != null) {
            throw new IllegalArgumentException("Renderer for " + type + " already registered");
        }
    }

    public static synchronized ManualPageRenderer renderer(FfId type) {
        return RENDERERS.get(type);
    }
}
