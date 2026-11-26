package dev.fieldframe.api.resource;

/**
 * Item predicate used by recipe inputs. {@link ItemFilter} implements it for explicit id/tag lists; platform
 * adapters implement it over the game's own ingredient type so recipe matching, the inspector and the manual
 * all use the same matching rules as the effective server recipe.
 */
@FunctionalInterface
public interface ItemMatcher {
    boolean test(ItemVariant v, TagLookup tags);
}
