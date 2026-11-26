package dev.fieldframe.neoforge.test;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * A real claim mod, not a stand-in: FTB Chunks (run with -PwithClaims). A server team claims the chunk, fake players
 * are off for it, and a Block Breaker owned by someone outside the team is refused there with PROTECTED.
 */
public final class ClaimGameTests {
    private ClaimGameTests() {
    }

    public static void breakerRefusedInFtbChunksClaim(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        BlockPos target = h.absolutePos(c.north());
        MinecraftServer server = h.getLevel().getServer();
        String team = "fftest" + Integer.toHexString(target.hashCode() & 0xFFFF);
        CommandSourceStack at = server.createCommandSourceStack().withLevel(h.getLevel()).withPosition(Vec3.atCenterOf(target)).withSuppressedOutput();
        run(server, at, "ftbteams server create " + team);
        // FTB Teams addresses a team by its short name, "<name>#<first 8 of its UUID>".
        String ref = shortName(team);
        run(server, at, "ftbteams server settings " + ref + " ftbchunks:allow_fake_players false");
        run(server, at, "ftbchunks admin claim_as " + ref);

        h.setBlock(c, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
        ChassisBlockEntity be = h.getBlockEntity(c, ChassisBlockEntity.class);
        be.core().energy().receive(16_000, false);
        be.core().setOwner(UUID.randomUUID().toString(), "Stranger");
        h.setBlock(c.north(), Blocks.DIRT);
        ChassisCore.Result r = be.core().install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.BLOCK_BREAKER), null);
        h.assertTrue(r.ok(), "install " + r);
        h.runAtTickTime(40, () -> {
            h.assertBlockPresent(Blocks.DIRT, c.north());
            var s = be.core().statuses().get(LocalFace.FRONT);
            String code = s == null ? "-" : s.code();
            h.assertTrue(code.equals("PROTECTED"), "status " + code);
            try {
                // Hand the chunk back so neighbouring tests are not inside the claim.
                server.getCommands().getDispatcher().execute("ftbchunks admin unclaim_as " + ref, at);
            } catch (CommandSyntaxException ignored) {
            }
            h.succeed();
        });
    }

    /** Looked up by reflection so the mod never compiles against FTB Teams. */
    private static String shortName(String team) {
        try {
            Object api = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI").getMethod("api").invoke(null);
            Object manager = api.getClass().getMethod("getManager").invoke(api);
            for (Object t : (java.util.Collection<?>) manager.getClass().getMethod("getTeams").invoke(manager)) {
                String s = (String) t.getClass().getMethod("getShortName").invoke(t);
                if (s.startsWith(team + "#")) {
                    return s;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("FTB Teams lookup: " + e, e);
        }
        throw new IllegalStateException("team " + team + " was not created");
    }

    private static void run(MinecraftServer server, CommandSourceStack src, String cmd) {
        try {
            int n = server.getCommands().getDispatcher().execute(cmd, src);
            if (n <= 0) {
                throw new IllegalStateException("command did nothing: /" + cmd);
            }
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("/" + cmd + ": " + e.getMessage(), e);
        }
    }
}
