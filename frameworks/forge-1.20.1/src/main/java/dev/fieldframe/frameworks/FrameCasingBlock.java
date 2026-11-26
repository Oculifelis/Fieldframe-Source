package dev.fieldframe.frameworks;

import dev.fieldframe.forge.api.frame.ModuleSocketBlock;

/** Frame Casing: the shell of a Grand Frame. In a wall (not an edge or corner) of a formed frame it takes a module. */
public final class FrameCasingBlock extends ModuleSocketBlock {
    public FrameCasingBlock(Properties props) {
        super(props, FwContent.CASING_BE);
    }
}
