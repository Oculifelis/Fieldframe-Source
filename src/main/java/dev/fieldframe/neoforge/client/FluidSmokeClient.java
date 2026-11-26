package dev.fieldframe.neoforge.client;

import dev.fieldframe.neoforge.Fieldframe;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only fluid look check ({@code -Dfieldframe.fluidSmoke=true}): creates a fresh flat world, pours an 8x8 pool of
 * every Fieldframe source fluid, checks each one's 16 position sprites are in the block atlas, saves a top-down
 * screenshot per pool, one angled overview and hotbar shots of every bucket
 * ({@code run/screenshots/fieldframe-fluid-*.png}) and quits.
 */
public final class FluidSmokeClient {
    private FluidSmokeClient() {
    }

    private static final int SPACING = 12;
    private static final int COLUMNS = 5;
    private static final int Y = -61;

    private static boolean creating;
    private static boolean done;
    private static int ticks;
    private static int waiting;
    private static final List<Identifier> FLUIDS = new ArrayList<>();
    private static int missing;
    private static int checked;

    public static void init() {
        if (!Boolean.getBoolean("fieldframe.fluidSmoke")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(FluidSmokeClient::tick);
    }

    private static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (done) {
            return;
        }
        if (!creating && mc.player == null && mc.gui.overlay() == null && (mc.gui.screen() instanceof TitleScreen
                || mc.gui.screen() instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)) {
            creating = true;
            String name = "ff_fluid_smoke_" + System.currentTimeMillis();
            Fieldframe.LOG.info("FIELDFRAME_FLUIDS creating world {}", name);
            mc.createWorldOpenFlows().createFreshLevel(name,
                    new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                            WorldDataConfiguration.DEFAULT),
                    WorldOptions.defaultWithRandomSeed(), registries -> registries.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
            return;
        }
        if (mc.player == null || mc.level == null) {
            if (++waiting > 6000) {
                result(mc, false, (creating ? "world did not open" : "no title screen") + " within 5 minutes");
            }
            return;
        }
        int t = ++ticks;
        if (t == 60) {
            pour(mc);
            return;
        }
        // Each pool: teleport at 80 + 60i, screenshot 50 ticks later (chunks rebuilt, animation running).
        int i = (t - 80) / 60;
        int phase = (t - 80) % 60;
        if (t >= 80 && i < FLUIDS.size()) {
            int x = (i % COLUMNS) * SPACING + 4;
            int z = (i / COLUMNS) * SPACING + 4;
            if (phase == 0) {
                command(mc, "tp " + mc.player.getName().getString() + " " + x + " " + (Y + 10) + " " + z + " 0 90");
            } else if (phase == 50) {
                ViewerShots.shot(mc, "fieldframe-fluid-" + FLUIDS.get(i).getPath() + ".png");
            }
            return;
        }
        int end = 80 + 60 * FLUIDS.size();
        if (t == end) {
            command(mc, "tp " + mc.player.getName().getString() + " -8 " + (Y + 12) + " -8 -45 35");
        } else if (t == end + 60) {
            ViewerShots.shot(mc, "fieldframe-fluid-overview.png");
        } else if (t >= end + 70 && (t - end - 70) % 40 == 0 && (t - end - 70) / 40 * 9 < FLUIDS.size()) {
            hotbar(mc, (t - end - 70) / 40 * 9);
        } else if (t >= end + 70 && (t - end - 70) % 40 == 30 && (t - end - 70) / 40 * 9 < FLUIDS.size()) {
            ViewerShots.shot(mc, "fieldframe-fluid-buckets-" + (t - end - 70) / 40 + ".png");
        } else if (t >= end + 70 && (t - end - 70) / 40 * 9 >= FLUIDS.size()) {
            result(mc, missing == 0 && checked > 0, checked + " fluids checked, " + FLUIDS.size() + " poured, " + missing + " missing position sprites");
        }
    }

    private static void pour(Minecraft mc) {
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
            if (!"fieldframe".equals(id.getNamespace()) || !fluid.isSource(fluid.defaultFluidState())) {
                continue;
            }
            int n = 0;
            for (int k = 0; k < 16; k++) {
                Identifier part = Identifier.fromNamespaceAndPath("fieldframe", "block/fluid/" + id.getPath() + "_still_" + (k >> 2) + "_" + (k & 3));
                if (atlas.getSprite(part) == atlas.missingSprite()) {
                    n++;
                }
            }
            missing += n;
            checked++;
            var block = fluid.defaultFluidState().createLegacyBlock();
            if (block.isAir() || block.is(Blocks.WATER) || block.is(Blocks.LAVA)) {
                Fieldframe.LOG.info("FIELDFRAME_FLUIDS {} tank-only (no world block), position sprites missing {}/16", id, n);
                continue;
            }
            int i = FLUIDS.size();
            FLUIDS.add(id);
            int x = (i % COLUMNS) * SPACING;
            int z = (i / COLUMNS) * SPACING;
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(block.getBlock());
            command(mc, "fill " + x + " " + Y + " " + z + " " + (x + 7) + " " + Y + " " + (z + 7) + " " + blockId);
            Fieldframe.LOG.info("FIELDFRAME_FLUIDS {} block {} at {} {}, position sprites missing {}/16", id, blockId, x, z, n);
        }
        command(mc, "time set noon");
        command(mc, "weather clear");
    }

    /** Fills the hotbar with the buckets of nine poured fluids from {@code from}, GUI shown. */
    private static void hotbar(Minecraft mc, int from) {
        if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        command(mc, "clear " + mc.player.getName().getString());
        for (int k = 0; k < 9 && from + k < FLUIDS.size(); k++) {
            Fluid f = BuiltInRegistries.FLUID.getValue(FLUIDS.get(from + k));
            Identifier bucket = BuiltInRegistries.ITEM.getKey(f.getBucket());
            command(mc, "item replace entity " + mc.player.getName().getString() + " hotbar." + k + " with " + bucket);
            Fieldframe.LOG.info("FIELDFRAME_FLUIDS {} bucket {}", FLUIDS.get(from + k), bucket);
        }
    }

    private static void command(Minecraft mc, String cmd) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            Fieldframe.LOG.error("FIELDFRAME_FLUIDS no integrated server for: {}", cmd);
            return;
        }
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }

    private static void result(Minecraft mc, boolean ok, String what) {
        done = true;
        Fieldframe.LOG.info("FIELDFRAME_FLUIDS result={} {}", ok ? "PASS" : "FAIL", what);
        mc.gui.setScreen(null);
        if (mc.level != null) {
            mc.disconnectFromWorld(Component.literal("Fieldframe fluid smoke done"));
        }
        mc.stop();
    }
}
