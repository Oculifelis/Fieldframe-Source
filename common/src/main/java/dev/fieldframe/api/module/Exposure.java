package dev.fieldframe.api.module;

/**
 * What a module makes available on the outside of its face. A face only exposes the kinds its installed
 * module declares; an empty or covered face exposes nothing, and side-unspecified queries expose nothing.
 */
public enum Exposure {
    ITEM, FLUID, ENERGY, REDSTONE_IN, REDSTONE_OUT, PLAYER_ACCESS
}
