package dev.fieldframe.api.resource;

/** Fluid predicate used by recipe fluid inputs; see {@link ItemMatcher}. */
@FunctionalInterface
public interface FluidMatcher {
    boolean test(FluidVariant v, TagLookup tags);
}
