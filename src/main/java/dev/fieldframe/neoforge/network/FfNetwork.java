package dev.fieldframe.neoforge.network;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.adapter.NbtBridge;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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


    private record Session(ResourceKey<Level> dim, BlockPos pos, int face) {
    }

    private static final Map<UUID, Session> INSPECTORS = new HashMap<>();
    private static final Map<UUID, long[]> RATE = new HashMap<>();

    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar(VERSION);
        r.playToClient(Snapshot.TYPE, Snapshot.CODEC, Snapshot::handle);
        r.playToServer(Action.TYPE, Action.CODEC, Action::handle);
        r.playToServer(Close.TYPE, Close.CODEC, (c, ctx) -> INSPECTORS.remove(ctx.player().getUUID()));
        r.playToServer(FeatureKey.TYPE, FeatureKey.CODEC, FeatureKey::handle);
        r.playToServer(ManualBlueprint.TYPE, ManualBlueprint.CODEC, ManualBlueprint::handle);
        r.playToClient(FuelSync.TYPE, FuelSync.CODEC, FuelSync::handle);
    }

    /**
     * Server -> client: engine fuels, Aura Chamber recipes, recipe filters and the Treadmill / electrode / temperature
     * tables (server data), for JEI and the manual. Sent on join and reload.
     */
    public record FuelSync(String json, String auras, String filters, String tables) implements CustomPacketPayload {
        public static final Type<FuelSync> TYPE = new Type<>(id("fuel_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FuelSync> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1 << 20), FuelSync::json, ByteBufCodecs.stringUtf8(1 << 20), FuelSync::auras,
                ByteBufCodecs.stringUtf8(1 << 20), FuelSync::filters, ByteBufCodecs.stringUtf8(1 << 20), FuelSync::tables, FuelSync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(FuelSync m, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                dev.fieldframe.core.material.Fuels.acceptSync(m.json());
                dev.fieldframe.core.material.Auras.acceptSync(m.auras());
                dev.fieldframe.core.material.RecipeFilters.acceptSync(m.filters());
                dev.fieldframe.core.material.TableSync.acceptSync(m.tables());
            });
        }

        public static void send(ServerPlayer player) {
            if (!player.connection.hasChannel(TYPE)) {
                return; // fake players (GameTest mocks, automation) have no Fieldframe channel
            }
            PacketDistributor.sendToPlayer(player, new FuelSync(dev.fieldframe.core.material.Fuels.toJson(),
                    dev.fieldframe.core.material.Auras.toJson(),
                    dev.fieldframe.core.material.RecipeFilters.toJson(), dev.fieldframe.core.material.TableSync.toJson()));
        }
    }

    // ------------------------------------------------------------------ inspector sessions

    /**
     * Opens the machine screen on {@code face}: the Workshop (a container screen with the module's slots) for players
     * with the Fieldframe channel, the snapshot-only screen otherwise.
     */
    public static void openInspector(ServerPlayer player, ChassisBlockEntity be, LocalFace face) {
        INSPECTORS.put(player.getUUID(), new Session(player.level().dimension(), be.getBlockPos(), face.ordinal()));
        if (player.connection.hasChannel(Snapshot.TYPE)) {
            if (player.containerMenu instanceof dev.fieldframe.neoforge.menu.WorkshopMenu wm && wm.pos().equals(be.getBlockPos())) {
                wm.setFace(face.ordinal());
            } else {
                // The snapshot goes first so the screen has it when it opens.
                send(player, be, face.ordinal(), false);
                player.openMenu(dev.fieldframe.neoforge.menu.WorkshopMenu.provider(be, face),
                        buf -> dev.fieldframe.neoforge.menu.WorkshopMenu.writeOpenData(buf, be, face));
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
        boolean workshop = player.containerMenu instanceof dev.fieldframe.neoforge.menu.WorkshopMenu wm && wm.pos().equals(be.getBlockPos());
        if (workshop) {
            ((dev.fieldframe.neoforge.menu.WorkshopMenu) player.containerMenu).setFace(face.ordinal());
        }
        send(player, be, face.ordinal(), !workshop);
    }

    /** Opens the machine screen on a carried Field Kit. */
    public static void openKit(ServerPlayer player, dev.fieldframe.neoforge.chassis.FieldKitRuntime.Kit kit, LocalFace face) {
        dev.fieldframe.neoforge.chassis.FieldKitRuntime.open(player, kit);
        INSPECTORS.put(player.getUUID(), new Session(player.level().dimension(), dev.fieldframe.neoforge.item.FieldKitItem.KIT_POS, face.ordinal()));
        sendKit(player, kit, face.ordinal(), true);
    }

    private static void sendKit(ServerPlayer player, dev.fieldframe.neoforge.chassis.FieldKitRuntime.Kit kit, int face, boolean open) {
        if (!player.connection.hasChannel(Snapshot.TYPE)) {
            return;
        }
        FfData data = InspectorData.build(kit.core);
        data.putBool("field_kit", true);
        PacketDistributor.sendToPlayer(player, new Snapshot(dev.fieldframe.neoforge.item.FieldKitItem.KIT_POS, face, open, NbtBridge.toTag(data)));
    }

    private static void send(ServerPlayer player, ChassisBlockEntity be, int face, boolean open) {
        if (!player.connection.hasChannel(Snapshot.TYPE)) {
            return; // fake players (GameTest mocks, automation) have no Fieldframe channel
        }
        PacketDistributor.sendToPlayer(player, new Snapshot(be.getBlockPos(), face, open, NbtBridge.toTag(InspectorData.build(be))));
    }

    public static void tickInspectors(MinecraftServer server) {
        if (server.getTickCount() % SNAPSHOT_INTERVAL != 0 || INSPECTORS.isEmpty()) {
            return;
        }
        INSPECTORS.entrySet().removeIf(e -> {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Session s = e.getValue();
            if (p != null && s.pos().equals(dev.fieldframe.neoforge.item.FieldKitItem.KIT_POS)) {
                var kit = dev.fieldframe.neoforge.chassis.FieldKitRuntime.openKit(p);
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
        long now = p.level().getServer().getTickCount();
        long[] r = RATE.computeIfAbsent(p.getUUID(), k -> new long[]{now, 0});
        if (now - r[0] >= 20) {
            r[0] = now;
            r[1] = 0;
        }
        return ++r[1] <= MAX_ACTIONS_PER_SECOND;
    }

    // ------------------------------------------------------------------ packets

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, path);
    }

    public record Snapshot(BlockPos pos, int face, boolean open, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(id("snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Snapshot::pos, ByteBufCodecs.VAR_INT, Snapshot::face, ByteBufCodecs.BOOL, Snapshot::open,
                ByteBufCodecs.COMPOUND_TAG, Snapshot::data, Snapshot::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(Snapshot s, IPayloadContext ctx) {
            if (FMLEnvironment.getDist() == Dist.CLIENT) {
                dev.fieldframe.neoforge.client.FfClient.onSnapshot(s.pos, s.face, s.open, NbtBridge.fromTag(s.data));
            }
        }
    }

    /**
     * A requested change. {@code action}: set_binding, set_config, remove_upgrade, pause, select_face.
     * {@code revision} must match the server configuration revision or the request is rejected and a fresh
     * snapshot is sent (concurrent edit detection). The NBT payload is size-limited while decoding.
     */
    public record Action(BlockPos pos, long revision, String action, int face, String key, int value, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(id("action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of((buf, a) -> {
            buf.writeBlockPos(a.pos);
            buf.writeVarLong(a.revision);
            buf.writeUtf(a.action, 32);
            buf.writeVarInt(a.face);
            buf.writeUtf(a.key, 64);
            buf.writeVarInt(a.value);
            buf.writeNbt(a.data);
        }, buf -> {
            BlockPos pos = buf.readBlockPos();
            long rev = buf.readVarLong();
            String action = buf.readUtf(32);
            int face = buf.readVarInt();
            String key = buf.readUtf(64);
            int value = buf.readVarInt();
            var tag = buf.readNbt(NbtAccounter.create(MAX_PAYLOAD_BYTES));
            return new Action(pos, rev, action, face, key, value, tag instanceof CompoundTag c ? c : new CompoundTag());
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void send(BlockPos pos, long revision, String action, int face, String key, int value, FfData data) {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new Action(pos, revision, action, face, key, value, data == null ? new CompoundTag() : NbtBridge.toTag(data)));
        }

        static void handle(Action a, IPayloadContext ctx) {
            if (ctx.player() instanceof ServerPlayer p) {
                apply(p, a);
            }
        }

        /** Validates and applies one request from {@code p}, exactly as if it arrived over the network (tests call it). */
        public static void apply(ServerPlayer p, Action a) {
            if (!rateOk(p)) {
                return;
            }
            if (a.pos.equals(dev.fieldframe.neoforge.item.FieldKitItem.KIT_POS)) {
                applyKit(p, a);
                return;
            }
            if (!p.level().isLoaded(a.pos) || !inRange(p, a.pos) || !(p.level().getBlockEntity(a.pos) instanceof ChassisBlockEntity be)) {
                return;
            }
            if (!p.level().mayInteract(p, a.pos) || a.face < 0 || a.face >= 6) {
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
            if (!dev.fieldframe.neoforge.chassis.ChassisAccess.mayEdit(p, core)) {
            dev.fieldframe.core.util.MpProbe.REFUSED.incrementAndGet();
                p.sendOverlayMessage(Component.translatable("fieldframe.msg.not_owner", core.ownerName()));
                return;
            }
            if (a.revision != core.revision()) {
                p.sendOverlayMessage(Component.translatable("fieldframe.msg.stale_edit"));
                FfNetwork.send(p, be, a.face, false);
                return;
            }
            ChassisCore.Result r = perform(p, a, face, core, be.world(), a.pos);
            if (!r.ok()) {
                p.sendOverlayMessage(Component.translatable("fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), face.key(), r.detail()));
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
                        dev.fieldframe.neoforge.chassis.FfAdvancements.award(p, dev.fieldframe.core.chassis.Milestones.PROGRAMMER);
                    }
                }
                case "remove_upgrade" -> {
                    FfId up = a.value >= 0 && a.value < core.serviceBays() ? core.upgrade(a.value) : null;
                    r = core.removeUpgrade(a.value);
                    if (r.ok() && up != null && FfContent.upgradeItem(up) != null) {
                        ItemStack s = new ItemStack(FfContent.upgradeItem(up));
                        if (!p.getInventory().add(s)) {
                            p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
                        }
                    }
                }
                case "remove_module" -> {
                    // The machine screen's Remove button: the module item and anything it was holding go to the player.
                    var installed = core.module(face);
                    if (installed == null) {
                        r = ChassisCore.Result.fail("FACE_EMPTY", face.key());
                    } else {
                        ItemStack moduleStack = dev.fieldframe.neoforge.item.ModuleItem.stackFor(installed);
                        for (Object[] s : core.remove(face, world)) {
                            if (s[0] instanceof dev.fieldframe.api.resource.ItemVariant v && s[1] instanceof Integer n) {
                                ItemStack st = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, n);
                                if (!p.getInventory().add(st)) {
                                    p.drop(st, false, net.minecraft.util.Prediction.SERVER_ONLY);
                                }
                            }
                        }
                        if (!p.getInventory().add(moduleStack)) {
                            p.drop(moduleStack, false, net.minecraft.util.Prediction.SERVER_ONLY);
                        }
                        p.level().playSound(null, soundPos, dev.fieldframe.neoforge.registry.FfContent.sound("machine.remove"), net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
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

        /** A request aimed at the player's open Field Kit ({@link dev.fieldframe.neoforge.item.FieldKitItem#KIT_POS}). */
        private static void applyKit(ServerPlayer p, Action a) {
            var kit = dev.fieldframe.neoforge.chassis.FieldKitRuntime.openKit(p);
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
                dev.fieldframe.neoforge.menu.LaneAccessMenu.openKit(p, kit);
                return;
            }
            if (a.revision != core.revision()) {
                p.sendOverlayMessage(Component.translatable("fieldframe.msg.stale_edit"));
                sendKit(p, kit, a.face, false);
                return;
            }
            ChassisCore.Result r = perform(p, a, face, core, new dev.fieldframe.neoforge.chassis.KitWorld(sl, p), p.blockPosition());
            if (!r.ok()) {
                p.sendOverlayMessage(Component.translatable("fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), face.key(), r.detail()));
            }
            dev.fieldframe.neoforge.chassis.FieldKitRuntime.saveNow(p, kit);
            sendKit(p, kit, a.face, false);
        }
    }

    /**
     * Key press for EnderTech/FLORA tools. Actions: 0 armor blink/sink, 1 armor fireball, 10/11 exchanger radius +1/-1,
     * 12/13 exchanger radius max/min. The server re-checks everything (worn armor, held tool, cooldowns, rate).
     */
    public record FeatureKey(int action) implements CustomPacketPayload {
        public static final Type<FeatureKey> TYPE = new Type<>(id("feature_key"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FeatureKey> CODEC = StreamCodec.of(
                (buf, k) -> buf.writeVarInt(k.action), buf -> new FeatureKey(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void send(int action) {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new FeatureKey(action));
        }

        static void handle(FeatureKey k, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
            if (!(ctx.player() instanceof ServerPlayer p) || !rateOk(p)) {
                return;
            }
            dev.fieldframe.core.util.MpProbe.FEATURE_KEYS.incrementAndGet();
            if (k.action == 20) {
                // Field Kit key: the machine screen of the first kit carried (also one worn in a Curios slot).
                ItemStack kitStack = dev.fieldframe.neoforge.chassis.FieldKitRuntime.firstCarried(p);
                if (!kitStack.isEmpty() && p.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                    var kit = dev.fieldframe.neoforge.chassis.FieldKitRuntime.get(kitStack, sl);
                    openKit(p, kit, dev.fieldframe.neoforge.item.FieldKitItem.firstFace(kit.core));
                }
            } else if (k.action == 0 || k.action == 1) {
                dev.fieldframe.neoforge.feature.flora.FloraEvents.keyAction(p, k.action);
            } else if (k.action >= 10 && k.action <= 13
                    && p.getMainHandItem().getItem() instanceof dev.fieldframe.neoforge.feature.endertech.ExchangerItem) {
                var stack = p.getMainHandItem();
                dev.fieldframe.neoforge.feature.endertech.ExchangerItem.adjustRadius(stack, k.action);
                p.sendOverlayMessage(Component.translatable("fieldframe.exchanger.radius",
                        dev.fieldframe.neoforge.feature.endertech.ExchangerItem.radius(stack)));
            }
        }
    }

    /**
     * "Copy to sheet" on a manual blueprint page. The design comes from the client's manual, so the server treats it like
     * any captured design: parsed and bounded, written only onto a Blueprint Sheet the player holds. A blueprint holds no
     * contents and builds only from the player's inventory, so a crafted request gains nothing.
     */
    public record ManualBlueprint(String json) implements CustomPacketPayload {
        public static final int MAX_JSON = 32_000;
        public static final Type<ManualBlueprint> TYPE = new Type<>(id("manual_blueprint"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ManualBlueprint> CODEC = StreamCodec.of(
                (buf, m) -> buf.writeUtf(m.json, MAX_JSON), buf -> new ManualBlueprint(buf.readUtf(MAX_JSON)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void send(String json) {
            if (json.length() <= MAX_JSON) {
                net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new ManualBlueprint(json));
            }
        }

        static void handle(ManualBlueprint m, net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
            if (!(ctx.player() instanceof ServerPlayer p) || !rateOk(p)) {
                return;
            }
            net.minecraft.world.item.ItemStack sheet = p.getMainHandItem().getItem() instanceof dev.fieldframe.neoforge.item.BlueprintItem
                    ? p.getMainHandItem() : p.getOffhandItem();
            if (!(sheet.getItem() instanceof dev.fieldframe.neoforge.item.BlueprintItem)) {
                p.sendOverlayMessage(Component.translatable("fieldframe.manual.blueprint_hold"));
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
                net.minecraft.world.item.ItemStack one = sheet.split(1);
                dev.fieldframe.neoforge.item.BlueprintItem.write(one, bp);
                if (!p.getInventory().add(one)) {
                    p.drop(one, false, net.minecraft.util.Prediction.SERVER_ONLY);
                }
            } else {
                dev.fieldframe.neoforge.item.BlueprintItem.write(sheet, bp);
            }
            p.sendOverlayMessage(Component.translatable("fieldframe.blueprint.copied", bp.name(), bp.chassis().size()));
        }
    }

    public record Close() implements CustomPacketPayload {
        public static final Type<Close> TYPE = new Type<>(id("close"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Close> CODEC = StreamCodec.unit(new Close());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void send() {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new Close());
        }
    }
}
