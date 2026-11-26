package dev.fieldframe.neoforge.test;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.core.blueprint.BlueprintOps;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.util.MpProbe;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.feature.FeatureContent;
import dev.fieldframe.neoforge.feature.endertech.ExchangerItem;
import dev.fieldframe.neoforge.item.BlueprintItem;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Server half of the two-process multiplayer smoke test ({@code -Dfieldframe.mpSmoke=server}, dedicated server).
 * <ol>
 *   <li>The first player (the owner) gets an owned chassis two blocks east of spawn with an Item Port and a Controller
 *       Plate, a Charge Pad, a Redstone Exchanger in slot 0, and a Blueprint Sheet of that chassis plus its parts.</li>
 *   <li>When the owner leaves, the server checks the network edits and the blueprint build.</li>
 *   <li>A second player (guest) then tries to change the owner's chassis; when they leave the server checks that every
 *       attempt was refused and nothing changed, logs the result and stops.</li>
 * </ol>
 * Not active in normal play.
 */
public final class MultiplayerSmokeServer {
    private MultiplayerSmokeServer() {
    }

    /** Program the owner client sends; checked verbatim. */
    public static final String PROGRAM = "# set by the multiplayer smoke client\nwhen power > 10% then set ch 4 = 15\n";

    private static BlockPos chassisPos;
    private static String owner;
    private static boolean ownerOk;
    private static int haltIn = -1;

    public static void init() {
        if (!"server".equals(System.getProperty("fieldframe.mpSmoke"))) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(MultiplayerSmokeServer::started);
        NeoForge.EVENT_BUS.addListener(MultiplayerSmokeServer::join);
        NeoForge.EVENT_BUS.addListener(MultiplayerSmokeServer::leave);
        NeoForge.EVENT_BUS.addListener(MultiplayerSmokeServer::tick);
        Fieldframe.LOG.info("FIELDFRAME_MP server smoke armed");
    }

    private static void started(ServerStartedEvent e) {
        // Everyone spawns on the exact spawn block, so both clients can find the test chassis.
        e.getServer().getGameRules().set(GameRules.RESPAWN_RADIUS, 0, e.getServer());
    }

    /** Where the owner client builds the blueprint: on the ground three blocks west of spawn. */
    public static BlockPos buildPos(BlockPos spawnFeet) {
        return spawnFeet.west(3);
    }

    private static void join(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        ServerLevel level = (ServerLevel) p.level();
        if (owner == null) {
            owner = p.getGameProfile().name();
            chassisPos = p.blockPosition().east(2);
            level.setBlock(chassisPos, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH), 3);
            ChassisBlockEntity be = (ChassisBlockEntity) level.getBlockEntity(chassisPos);
            be.core().setOwner(p.getUUID().toString(), owner);
            be.core().install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.ITEM_PORT), null);
            be.core().install(LocalFace.BOTTOM, FieldframeRegistry.module(BuiltinModules.CONTROLLER), null);
            be.setChanged();
            level.setBlock(chassisPos.south(2), FeatureContent.PADS.get(0).get().defaultBlockState(), 3);
            p.getInventory().setItem(0, new ItemStack(FeatureContent.EXCHANGERS.get(EnderTechRules.Tier.REDSTONE).get()));
            ItemStack sheet = new ItemStack(FfContent.BLUEPRINT.get());
            BlueprintItem.write(sheet, BlueprintOps.capture("smoke", List.of(new BlueprintOps.Source(0, 0, 0, be.core()))));
            p.getInventory().setItem(1, sheet);
            // After the blueprint capture: modules whose storage marks show on the sides in the client screenshot.
            be.core().install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.HEATER), null);
            be.core().install(LocalFace.RIGHT, FieldframeRegistry.module(BuiltinModules.MIXER), null);
            // Keep the heater busy so the client screenshot shows a lit faceplate and smoke.
            be.core().lane(0).insert(dev.fieldframe.neoforge.adapter.McItemVariant.of(new ItemStack(net.minecraft.world.item.Items.RAW_IRON)), 64, false);
            be.core().energy().receive(be.core().energy().capacity(), false);
            be.setChanged();
            p.getInventory().setItem(2, new ItemStack(FfContent.tierKit(dev.fieldframe.api.id.FfId.ff("hardened"))));
            p.getInventory().add(new ItemStack(FfContent.CHASSIS_ITEM.get()));
            p.getInventory().add(new ItemStack(FfContent.moduleItem(BuiltinModules.ITEM_PORT)));
            p.getInventory().add(new ItemStack(FfContent.moduleItem(BuiltinModules.CONTROLLER)));
            Fieldframe.LOG.info("FIELDFRAME_MP server join owner={} chassis={}", owner, chassisPos);
        } else {
            p.getInventory().setItem(0, new ItemStack(FfContent.moduleItem(BuiltinModules.ITEM_PORT)));
            Fieldframe.LOG.info("FIELDFRAME_MP server join guest={}", p.getGameProfile().name());
        }
    }

    private static void leave(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        ServerLevel level = (ServerLevel) p.level();
        ChassisBlockEntity be = chassisPos != null && level.getBlockEntity(chassisPos) instanceof ChassisBlockEntity c ? c : null;
        if (p.getGameProfile().name().equals(owner)) {
            ItemStack ex = p.getInventory().getItem(0);
            int radius = ex.getItem() instanceof ExchangerItem ? ExchangerItem.radius(ex) : -1;
            String program = be == null || be.core().module(LocalFace.BOTTOM) == null ? "" : be.core().module(LocalFace.BOTTOM).config().getString("program", "");
            BlockPos built = buildPos(p.blockPosition());
            boolean blueprint = level.getBlockEntity(built) instanceof ChassisBlockEntity b
                    && b.core().module(LocalFace.TOP) != null && b.core().module(LocalFace.BOTTOM) != null
                    && p.getGameProfile().name().equals(b.core().ownerName());
            Fieldframe.LOG.info("FIELDFRAME_MP server owner-leave actions={} feature_keys={} exchanger_radius={} program_set={} blueprint_built={} at {}",
                    MpProbe.ACTIONS.get(), MpProbe.FEATURE_KEYS.get(), radius, PROGRAM.equals(program), blueprint, built);
            ownerOk = be != null && MpProbe.ACTIONS.get() >= 2 && MpProbe.FEATURE_KEYS.get() >= 2
                    && radius == EnderTechRules.EXCHANGER_DEFAULT_RADIUS + 2 && PROGRAM.equals(program) && blueprint;
            Fieldframe.LOG.info("FIELDFRAME_MP server owner result={}", ownerOk ? "PASS" : "FAIL");
        } else {
            String program = be == null ? "" : be.core().module(LocalFace.BOTTOM).config().getString("program", "");
            boolean frontEmpty = be != null && be.core().module(LocalFace.FRONT) == null;
            Fieldframe.LOG.info("FIELDFRAME_MP server guest-leave refused={} program_unchanged={} front_still_empty={}",
                    MpProbe.REFUSED.get(), PROGRAM.equals(program), frontEmpty);
            boolean guestOk = MpProbe.REFUSED.get() >= 2 && PROGRAM.equals(program) && frontEmpty;
            Fieldframe.LOG.info("FIELDFRAME_MP server guest result={}", guestOk ? "PASS" : "FAIL");
            Fieldframe.LOG.info("FIELDFRAME_MP server result={}", ownerOk && guestOk ? "PASS" : "FAIL");
            haltIn = 20;
        }
    }

    private static void tick(ServerTickEvent.Post e) {
        if (haltIn >= 0 && haltIn-- == 0) {
            e.getServer().halt(false);
        }
    }
}
