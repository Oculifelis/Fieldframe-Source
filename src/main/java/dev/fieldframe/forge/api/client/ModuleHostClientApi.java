package dev.fieldframe.forge.api.client;

import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Public client API: blocks whose sides show module faceplates, storage rings and lit overlays the way a chassis
 * does (module sockets of multiblocks). Register from your client setup before models bake; the block's own model is
 * wrapped, so it keeps its textures on sides without a module.
 */
public final class ModuleHostClientApi {
    private ModuleHostClientApi() {
    }

    private static final List<Supplier<? extends Block>> BLOCKS = new CopyOnWriteArrayList<>();

    public static void composeModules(Supplier<? extends Block> block) {
        BLOCKS.add(block);
    }

    public static List<Supplier<? extends Block>> blocks() {
        return List.copyOf(BLOCKS);
    }
}
