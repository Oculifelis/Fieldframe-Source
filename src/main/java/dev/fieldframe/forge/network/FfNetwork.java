package dev.fieldframe.forge.network;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.adapter.NbtBridge;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fieldframe's packets. Clients only ever <em>request</em> changes; the server validates reach, the configuration
 * revision, payload size and rate before applying them through {@link ChassisCore}'s validated API.
 */
public final class FfNetwork {
    private static final String VERSION = "1";
    public static final int MAX_PAYLOAD_BYTES = 16_384;
    public static final int MAX_ACTIONS_PER_SECOND = 20;
    public static final int SNAPSHOT_INTERVAL = 10;
    public static final double MAX_DISTANCE_SQ = 8 * 8;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Fieldframe.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private record Session(ResourceKey<Level> dim, BlockPos pos, int face) {
    }

    private static final Map<UUID, Session> INSPECTORS = new HashMap<>();
    private static final Map<UUID, long[]> RATE = new HashMap<>();

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(Snapshot.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Snapshot::encode).decoder(Snapshot::decode).consumerMainThread(Snapshot::handle).add();
        CHANNEL.messageBuilder(Action.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Action::encode).decoder(Action::decode).consumerMainThread(Action::handle).add();
        CHANNEL.messageBuilder(Close.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Close::encode).decoder(Close::decode).consumerMainThread(Close::handle).add();
        CHANNEL.messageBuilder(FeatureKey.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(FeatureKey::encode).decoder(FeatureKey::decode).consumerMainThread(FeatureKey::handle).add();
        CHANNEL.messageBuilder(FuelSync.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(FuelSync::encode).decoder(FuelSync::decode).consumerMainThread(FuelSync::handle).add();
        CHANNEL.messageBuilder(ManualBlueprint.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ManualBlueprint::encode).decoder(ManualBlueprint::decode).consumerMainThread(ManualBlueprint::handle).add();
    }

    /**
     * Server -> client: engine fuels, Aura Chamber recipes, recipe filters and the Treadmill / electrode / temperature
     * tables (server data), for JEI and the manual. Sent on join and reload.
     */
    public record FuelSync(String json, String auras, String filters, String tables) {
        void encode(net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeUtf(json, 1 << 20);
            buf.writeUtf(auras, 1 << 20);
            buf.writeUtf(filters, 1 << 20);
            buf.writeUtf(tables, 1 << 20);
        }

        static FuelSync decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new FuelSync(buf.readUtf(1 << 20), buf.readUtf(1 << 20), buf.readUtf(1 << 20), buf.readUtf(1 << 20));
        }

        static void handle(FuelSync m, Supplier<NetworkEvent.Context> ctx) {
            dev.fieldframe.core.material.Fuels.acceptSync(m.json());
            dev.fieldframe.core.material.Auras.acceptSync(m.auras());
            dev.fieldframe.core.material.RecipeFilters.acceptSync(m.filters());
            dev.fieldframe.core.material.TableSync.acceptSync(m.tables());
            ctx.get().setPacketHandled(true);
        }

        public static void send(net.minecraft.server.level.ServerPlayer player) {
            if (player.connection == null || !CHANNEL.isRemotePresent(player.connection.connection)) {
                return; // fake players (GameTest mocks, automation) have no Fieldframe channel
            }
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new FuelSync(dev.fieldframe.core.material.Fuels.toJson(), dev.fieldframe.core.material.Auras.toJson(),
                    dev.fieldframe.core.material.RecipeFilters.toJson(), dev.fieldframe.core.material.TableSync.toJson()));
        }
    }

    /**
     * Key press for EnderTech/FLORA tools. Actions: 0 armor blink/sink, 1 armor fireball, 10/11 exchanger radius +1/-1,
     * 12/13 exchanger radius max/min. The server re-checks everything (worn armor, held tool, cooldowns, rate).
     */
    public record FeatureKey(int action) {
        void encode(net.minecraft.network.FriendlyByteBuf b) {
            b.writeVarInt(action);
        }

        static FeatureKey decode(net.minecraft.network.FriendlyByteBuf b) {
            return new FeatureKey(b.readVarInt());
        }

        static void handle(FeatureKey k, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ServerPlayer p = ctx.get().getSender();
            if (p == null || !rateOk(p)) {
                return;
            }
            dev.fieldframe.core.util.MpProbe.FEATURE_KEYS.incrementAndGet();
            if (k.action == 20) {
                // Field Kit key: the machine screen of the first kit carried (also one worn in a Curios slot).
                ItemStack kitStack = dev.fieldframe.forge.chassis.FieldKitRuntime.firstCarried(p);
                if (!kitStack.isEmpty() && p.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                    var kit = dev.fieldframe.forge.chassis.FieldKitRuntime.get(kitStack, sl);
                    openKit(p, kit, dev.fieldframe.forge.item.FieldKitItem.firstFace(kit.core));
                }
            } else if (k.action == 0 || k.action == 1) {
                dev.fieldframe.forge.feature.flora.FloraEvents.keyAction(p, k.action);
            } else if (k.action >= 10 && k.action <= 13
                    && p.getMainHandItem().getItem() instanceof dev.fieldframe.forge.feature.endertech.ExchangerItem) {
                var stack = p.getMainHandItem();
                dev.fieldframe.forge.feature.endertech.ExchangerItem.adjustRadius(stack, k.action);
                p.displayClientMessage(Component.translatable("fieldframe.exchanger.radius",
                        dev.fieldframe.forge.feature.endertech.ExchangerItem.radius(stack)), true);
            }
        }
    }

    // ------------------------------------------------------------------ inspector sessions

    /**
     * Opens the machine screen on {@code face}: the Workshop (a container screen with the module's slots) for players
     * with the Fieldframe channel, the snapshot-only screen otherwise.
     */
    public static void openInspector(ServerPlayer player, ChassisBlockEntity be, LocalFace face) {
        INSPECTORS.put(player.getUUID(), new Session(player.level().dimension(), be.getBlockPos(), face.ordinal()));
        if (!(player instanceof net.minecraftforge.common.util.FakePlayer) && player.connection != null
                && CHANNEL.isRemotePresent(player.connection.connection)) {
            if (player.containerMenu instanceof dev.fieldframe.forge.menu.WorkshopMenu wm && wm.pos().equals(be.getBlockPos())) {
                wm.setFace(face.ordinal());
            } else {
                // The snapshot goes first so the screen has it when it opens.
                send(player, be, face.ordinal(), false);
                net.minecraftforge.network.NetworkHooks.openScreen(player, dev.fieldframe.forge.menu.WorkshopMenu.provider(be, face),
                        buf -> dev.fieldframe.forge.menu.WorkshopMenu.writeOpenData(buf, be, face));
                INSPECTORS.put(player.getUUID(), new Session(player.level().dimension(), be.getBlockPos(), face.ordinal()));
            }
            send(player, be, face.ordinal(), false);
            return;
        }
        send(player, be, face.ordinal(), true);
    }

    /** Another side picked from an open machine screen: never opens a new screen, only moves the open one. */
    public static void selectFace(ServerPlayer player, ChassisBlockEntity be, LocalFace face) {
        INSPECTORS.put(player.getUUID(), new Session(player.level().dimension(), be.getBlockPos(), face.ordinal()));
        boolean workshop = player.containerMenu instanceof dev.fieldframe.forge.menu.WorkshopMenu wm && wm.pos().equals(be.getBlockPos());
        if (workshop) {
            ((dev.fieldframe.forge.menu.WorkshopMenu) player.containerMenu).setFace(face.ordinal());
        }
        send(player, be, face.ordinal(), !workshop);
    }

    /** Opens the machine screen on a carried Field Kit. */
    public static void openKit(ServerPlayer player, dev.fieldframe.forge.chassis.FieldKitRuntime.Kit kit, LocalFace face) {
        dev.fieldframe.forge.chassis.FieldKitRuntime.open(player, kit);
        INSPECTORS.put(player.getUUID(), new Session(player.level().dimension(), dev.fieldframe.forge.item.FieldKitItem.KIT_POS, face.ordinal()));
        sendKit(player, kit, face.ordinal(), true);
    }

    private static void sendKit(ServerPlayer player, dev.fieldframe.forge.chassis.FieldKitRuntime.Kit kit, int face, boolean open) {
        if (player instanceof net.minecraftforge.common.util.FakePlayer || player.connection == null
                || !CHANNEL.isRemotePresent(player.connection.connection)) {
            return;
        }
        FfData data = InspectorData.build(kit.core);
        data.putBool("field_kit", true);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(dev.fieldframe.forge.item.FieldKitItem.KIT_POS, face, open, NbtBridge.toTag(data)));
    }

    private static void send(ServerPlayer player, ChassisBlockEntity be, int face, boolean open) {
        if (player instanceof net.minecraftforge.common.util.FakePlayer || player.connection == null
                || !CHANNEL.isRemotePresent(player.connection.connection)) {
            return; // fake players (GameTest mocks, automation) have no Fieldframe channel
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new Snapshot(be.getBlockPos(), face, open, NbtBridge.toTag(InspectorData.build(be))));
    }

    public static void tickInspectors(MinecraftServer server) {
        if (server.getTickCount() % SNAPSHOT_INTERVAL != 0 || INSPECTORS.isEmpty()) {
            return;
        }
        INSPECTORS.entrySet().removeIf(e -> {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Session s = e.getValue();
            if (p != null && s.pos().equals(dev.fieldframe.forge.item.FieldKitItem.KIT_POS)) {
                var kit = dev.fieldframe.forge.chassis.FieldKitRuntime.openKit(p);
                if (kit == null) {
                    return true;
                }
                sendKit(p, kit, s.face(), false);
                return false;
            }
            if (p == null || p.level().dimension() != s.dim() || !inRange(p, s.pos())) {
                return true;
            }
            if (!(p.level().getBlockEntity(s.pos()) instanceof ChassisBlockEntity be)) {
                return true;
            }
            send(p, be, s.face(), false);
            return false;
        });
    }

    private static boolean inRange(ServerPlayer p, BlockPos pos) {
        return p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= MAX_DISTANCE_SQ;
    }

    private static boolean rateOk(ServerPlayer p) {
        long now = p.server.getTickCount();
        long[] r = RATE.computeIfAbsent(p.getUUID(), k -> new long[]{now, 0});
        if (now - r[0] >= 20) {
            r[0] = now;
            r[1] = 0;
        }
        return ++r[1] <= MAX_ACTIONS_PER_SECOND;
    }

    // ------------------------------------------------------------------ packets

    public record Snapshot(BlockPos pos, int face, boolean open, CompoundTag data) {
        static void encode(Snapshot s, FriendlyByteBuf buf) {
            buf.writeBlockPos(s.pos);
            buf.writeVarInt(s.face);
            buf.writeBoolean(s.open);
            buf.writeNbt(s.data);
        }

        static Snapshot decode(FriendlyByteBuf buf) {
            return new Snapshot(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(), buf.readNbt());
        }

        static void handle(Snapshot s, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    dev.fieldframe.forge.client.FfClient.onSnapshot(s.pos, s.face, s.open, NbtBridge.fromTag(s.data)));
        }
    }

    /**
     * A requested change. {@code action}: set_binding, set_config, remove_upgrade, pause, select_face.
     * {@code revision} must match the server's current configuration revision or the request is rejected and a
     * fresh snapshot is sent (concurrent edit detection).
     */
    public record Action(BlockPos pos, long revision, String action, int face, String key, int value, CompoundTag data) {
        public static void send(BlockPos pos, long revision, String action, int face, String key, int value, FfData data) {
            CHANNEL.sendToServer(new Action(pos, revision, action, face, key, value, data == null ? new CompoundTag() : NbtBridge.toTag(data)));
        }

        static void encode(Action a, FriendlyByteBuf buf) {
            buf.writeBlockPos(a.pos);
            buf.writeVarLong(a.revision);
            buf.writeUtf(a.action, 32);
            buf.writeVarInt(a.face);
            buf.writeUtf(a.key, 64);
            buf.writeVarInt(a.value);
            buf.writeNbt(a.data);
        }

        static Action decode(FriendlyByteBuf buf) {
            BlockPos pos = buf.readBlockPos();
            long rev = buf.readVarLong();
            String action = buf.readUtf(32);
            int face = buf.readVarInt();
            String key = buf.readUtf(64);
            int value = buf.readVarInt();
            if (buf.readableBytes() > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Fieldframe action payload too large");
            }
            CompoundTag data = buf.readNbt();
            return new Action(pos, rev, action, face, key, value, data == null ? new CompoundTag() : data);
        }

        static void handle(Action a, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ServerPlayer p = ctx.get().getSender();
            if (p != null) {
                apply(p, a);
            }
        }

        /** Validates and applies one request from {@code p}, exactly as if it arrived over the network (tests call it). */
        public static void apply(ServerPlayer p, Action a) {
            if (!rateOk(p)) {
                return;
            }
            if (a.pos.equals(dev.fieldframe.forge.item.FieldKitItem.KIT_POS)) {
                applyKit(p, a);
                return;
            }
            if (!p.level().isLoaded(a.pos) || !inRange(p, a.pos) || !(p.level().getBlockEntity(a.pos) instanceof ChassisBlockEntity be)) {
                return;
            }
            if (!p.level().mayInteract(p, a.pos)) {
                return;
            }
            if (a.face < 0 || a.face >= 6) {
                return;
            }
            LocalFace face = LocalFace.ALL[a.face];
            ChassisCore core = be.core();
            dev.fieldframe.core.util.MpProbe.ACTIONS.incrementAndGet();
            if (a.action.equals("select_face")) {
                selectFace(p, be, face);
                return;
            }
            if (a.action.equals("open")) {
                openInspector(p, be, face);
                return;
            }
            if (!dev.fieldframe.forge.chassis.ChassisAccess.mayEdit(p, core)) {
            dev.fieldframe.core.util.MpProbe.REFUSED.incrementAndGet();
                p.displayClientMessage(Component.translatable("fieldframe.msg.not_owner", core.ownerName()), true);
                return;
            }
            if (a.revision != core.revision()) {
                p.displayClientMessage(Component.translatable("fieldframe.msg.stale_edit"), true);
                FfNetwork.send(p, be, a.face, false);
                return;
            }
            ChassisCore.Result r = perform(p, a, face, core, be.world(), a.pos);
            if (!r.ok()) {
                p.displayClientMessage(Component.translatable("fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), face.key(), r.detail()), true);
            }
            be.setChanged();
            INSPECTORS.put(p.getUUID(), new Session(p.level().dimension(), a.pos, a.face));
            FfNetwork.send(p, be, a.face, false);
        }

        /** The change itself, shared by chassis blocks and Field Kits. */
        private static ChassisCore.Result perform(ServerPlayer p, Action a, LocalFace face, ChassisCore core,
                dev.fieldframe.api.module.ChassisWorld world, BlockPos soundPos) {
            ChassisCore.Result r;
            switch (a.action) {
                case "set_binding" -> r = core.setBinding(face, a.key, a.value);
                case "set_config" -> {
                    r = core.setConfig(face, NbtBridge.fromTag(a.data));
                    var installed = core.module(face);
                    if (r.ok() && installed != null && dev.fieldframe.core.modules.BuiltinModules.CONTROLLER.equals(installed.moduleId())
                            && !installed.config().getString("program", "").isBlank()) {
                        dev.fieldframe.forge.chassis.FfAdvancements.award(p, dev.fieldframe.core.chassis.Milestones.PROGRAMMER);
                    }
                }
                case "remove_upgrade" -> {
                    FfId up = a.value >= 0 && a.value < core.serviceBays() ? core.upgrade(a.value) : null;
                    r = core.removeUpgrade(a.value);
                    if (r.ok() && up != null && FfContent.upgradeItem(up) != null) {
                        ItemStack s = new ItemStack(FfContent.upgradeItem(up));
                        if (!p.getInventory().add(s)) {
                            p.drop(s, false);
                        }
                    }
                }
                case "remove_module" -> {
                    // The machine screen's Remove button: the module item and anything it was holding go to the player.
                    var installed = core.module(face);
                    if (installed == null) {
                        r = ChassisCore.Result.fail("FACE_EMPTY", face.key());
                    } else {
                        ItemStack moduleStack = dev.fieldframe.forge.item.ModuleItem.stackFor(installed);
                        for (Object[] s : core.remove(face, world)) {
                            if (s[0] instanceof dev.fieldframe.api.resource.ItemVariant v && s[1] instanceof Integer n) {
                                ItemStack st = dev.fieldframe.forge.adapter.McItemVariant.toStack(v, n);
                                if (!p.getInventory().add(st)) {
                                    p.drop(st, false);
                                }
                            }
                        }
                        if (!p.getInventory().add(moduleStack)) {
                            p.drop(moduleStack, false);
                        }
                        p.level().playSound(null, soundPos, dev.fieldframe.forge.registry.FfContent.sound("machine.remove"), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
                        r = ChassisCore.Result.success();
                    }
                }
                case "pause" -> {
                    core.setPaused(a.value != 0);
                    r = ChassisCore.Result.success();
                }
                case "set_quiet" -> {
                    core.setQuiet(a.value != 0);
                    r = ChassisCore.Result.success();
                }
                case "set_comparator" -> r = a.value >= 0 && a.value < ChassisCore.COMPARATOR_SOURCES.size()
                        ? core.setComparatorSource(ChassisCore.COMPARATOR_SOURCES.get(a.value))
                        : ChassisCore.Result.fail("BAD_VALUE", String.valueOf(a.value));
                default -> r = ChassisCore.Result.fail("UNKNOWN_ACTION", a.action);
            }
            return r;
        }

        /** A request aimed at the player's open Field Kit ({@link dev.fieldframe.forge.item.FieldKitItem#KIT_POS}). */
        private static void applyKit(ServerPlayer p, Action a) {
            var kit = dev.fieldframe.forge.chassis.FieldKitRuntime.openKit(p);
            if (kit == null || a.face < 0 || a.face >= 6 || !(p.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
                return;
            }
            LocalFace face = LocalFace.ALL[a.face];
            ChassisCore core = kit.core;
            if (a.action.equals("select_face")) {
                openKit(p, kit, face);
                return;
            }
            if (a.action.equals("open_lanes")) {
                dev.fieldframe.forge.menu.LaneAccessMenu.openKit(p, kit);
                return;
            }
            if (a.revision != core.revision()) {
                p.displayClientMessage(Component.translatable("fieldframe.msg.stale_edit"), true);
                sendKit(p, kit, a.face, false);
                return;
            }
            ChassisCore.Result r = perform(p, a, face, core, new dev.fieldframe.forge.chassis.KitWorld(sl, p), p.blockPosition());
            if (!r.ok()) {
                p.displayClientMessage(Component.translatable("fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), face.key(), r.detail()), true);
            }
            dev.fieldframe.forge.chassis.FieldKitRuntime.saveNow(p, kit);
            sendKit(p, kit, a.face, false);
        }
    }

    /**
     * "Copy to sheet" on a manual blueprint page. The design comes from the client's manual, so the server treats it like
     * any captured design: parsed and bounded, written only onto a Blueprint Sheet the player holds. A blueprint holds no
     * contents and builds only from the player's inventory, so a crafted request gains nothing.
     */
    public record ManualBlueprint(String json) {
        public static final int MAX_JSON = 32_000;

        public static void send(String json) {
            if (json.length() <= MAX_JSON) {
                CHANNEL.sendToServer(new ManualBlueprint(json));
            }
        }

        static void encode(ManualBlueprint m, FriendlyByteBuf buf) {
            buf.writeUtf(m.json, MAX_JSON);
        }

        static ManualBlueprint decode(FriendlyByteBuf buf) {
            return new ManualBlueprint(buf.readUtf(MAX_JSON));
        }

        static void handle(ManualBlueprint m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ServerPlayer p = ctx.get().getSender();
            if (p == null || !rateOk(p)) {
                return;
            }
            ItemStack sheet = p.getMainHandItem().getItem() instanceof dev.fieldframe.forge.item.BlueprintItem
                    ? p.getMainHandItem() : p.getOffhandItem();
            if (!(sheet.getItem() instanceof dev.fieldframe.forge.item.BlueprintItem)) {
                p.displayClientMessage(Component.translatable("fieldframe.manual.blueprint_hold"), true);
                return;
            }
            dev.fieldframe.api.blueprint.Blueprint bp;
            try {
                bp = dev.fieldframe.api.blueprint.Blueprint.fromData(dev.fieldframe.api.data.FfJson.parse(m.json));
            } catch (RuntimeException e) {
                return;
            }
            if (sheet.getCount() > 1) {
                // Copy onto one sheet of the stack, like capturing does.
                ItemStack one = sheet.split(1);
                dev.fieldframe.forge.item.BlueprintItem.write(one, bp);
                if (!p.getInventory().add(one)) {
                    p.drop(one, false);
                }
            } else {
                dev.fieldframe.forge.item.BlueprintItem.write(sheet, bp);
            }
            p.displayClientMessage(Component.translatable("fieldframe.blueprint.copied", bp.name(), bp.chassis().size()), true);
        }
    }

    public record Close() {
        public static void send() {
            CHANNEL.sendToServer(new Close());
        }

        static void encode(Close c, FriendlyByteBuf buf) {
        }

        static Close decode(FriendlyByteBuf buf) {
            return new Close();
        }

        static void handle(Close c, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            ServerPlayer p = ctx.get().getSender();
            if (p != null) {
                INSPECTORS.remove(p.getUUID());
            }
        }
    }
}
