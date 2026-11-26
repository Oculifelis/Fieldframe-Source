package dev.fieldframe.api.process;

import dev.fieldframe.api.id.FfId;

import java.util.List;

/**
 * The effective recipe set per process family. The platform rebuilds it after datapack / KubeJS reloads and
 * bumps {@link #generation()} so caches (processor lookups, manual panels) invalidate together.
 */
public interface RecipeSource {
    List<ProcessRecipe> recipes(FfId family);

    long generation();

    RecipeSource EMPTY = new RecipeSource() {
        @Override
        public List<ProcessRecipe> recipes(FfId family) {
            return List.of();
        }

        @Override
        public long generation() {
            return 0;
        }
    };
}
