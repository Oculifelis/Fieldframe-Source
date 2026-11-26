package dev.fieldframe.neoforge.compat.jade;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.core.chassis.ViewerSummary;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.adapter.NbtBridge;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.feature.endertech.PadBlock;
import dev.fieldframe.neoforge.feature.endertech.PadBlockEntity;
import dev.fieldframe.neoforge.feature.endertech.TankControllerEntity;
import dev.fieldframe.neoforge.feature.endertech.TankPartBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade integration (loaded by Jade only). The server sends a display-only summary ({@link ViewerSummary}); the client
 * formats it: per face the module, its status and progress; the RF buffer; filled lanes and reservoirs. Also Charge /
 * Health Pads (stored RF) and the Ender Tank controller (formed size, contents, RF).
 */
@WailaPlugin(Fieldframe.MOD_ID)
public final class FieldframeJadePlugin implements IWailaPlugin {
    static final Identifier CHASSIS = Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, "chassis");
    static final Identifier PAD = Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, "pad");
    static final Identifier TANK = Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, "ender_tank");
    static final Identifier CHASSIS_DATA = Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, "chassis_data");
    static final Identifier PAD_DATA = Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, "pad_data");
    static final Identifier TANK_DATA = Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, "ender_tank_data");

    @Override
    public void register(IWailaCommonRegistration r) {
        r.registerBlockDataProvider(ChassisData.INSTANCE, ChassisBlockEntity.class);
        r.registerBlockDataProvider(PadData.INSTANCE, PadBlockEntity.class);
        r.registerBlockDataProvider(TankData.INSTANCE, TankControllerEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration r) {
        r.registerBlockComponent(Chassis.INSTANCE, ChassisBlock.class);
        r.registerBlockComponent(Pad.INSTANCE, PadBlock.class);
        r.registerBlockComponent(Tank.INSTANCE, TankPartBlock.class);
    }

    static ChatFormatting color(String severity) {
        return switch (severity) {
            case "FAULT" -> ChatFormatting.RED;
            case "BLOCKED" -> ChatFormatting.GOLD;
            case "WORKING" -> ChatFormatting.GREEN;
            case "DISABLED" -> ChatFormatting.DARK_GRAY;
            default -> ChatFormatting.GRAY;
        };
    }

    static Component fluidName(String id) {
        var f = BuiltInRegistries.FLUID.getOptional(Identifier.parse(id));
        return f.isPresent() ? f.get().getFluidType().getDescription() : Component.literal(id);
    }

    static Component rf(long stored, long cap) {
        return Component.translatable("fieldframe.jade.rf", String.format("%,d", stored), String.format("%,d", cap)).withStyle(ChatFormatting.RED);
    }

    /** Client side: formats the data sent by {@link ChassisData}. */
    enum Chassis implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return CHASSIS;
        }

        @Override
        public void appendTooltip(ITooltip t, BlockAccessor a, IPluginConfig config) {
            CompoundTag raw = a.getServerData().getCompoundOrEmpty("ff");
            if (raw.isEmpty()) {
                return;
            }
            FfData d = NbtBridge.fromTag(raw);
            if (d.getInt("tier_level", 0) > 0) {
                String[] tid = d.getString("tier", "fieldframe:basic").split(":", 2);
                t.add(Component.translatable("fieldframe.jade.tier", Component.translatable("tier." + tid[0] + "." + (tid.length > 1 ? tid[1] : "")))
                        .withStyle(ChatFormatting.AQUA));
            }
            if (d.getInt("bolt_parts", 1) > 1) {
                t.add(Component.translatable("fieldframe.jade.bolted", d.getInt("bolt_parts", 1)).withStyle(ChatFormatting.AQUA));
            }
            if (d.getBool("paused", false)) {
                t.add(Component.translatable("fieldframe.jade.paused").withStyle(ChatFormatting.YELLOW));
            }
            if (d.has("heat")) {
                int pct = (int) Math.round(d.getDouble("overclock", 0) * 100);
                t.add(d.getBool("overheated", false)
                        ? Component.translatable("fieldframe.jade.overheated", d.getInt("heat", 0), dev.fieldframe.core.chassis.ChassisCore.MAX_HEAT).withStyle(ChatFormatting.RED)
                        : Component.translatable("fieldframe.jade.overclock", pct, d.getInt("heat", 0), dev.fieldframe.core.chassis.ChassisCore.MAX_HEAT).withStyle(ChatFormatting.GOLD));
            }
            for (Object o : d.getList("faces")) {
                FfData f = (FfData) o;
                String[] mod = f.getString("module", "?:?").split(":", 2);
                MutableComponent line = Component.translatable("fieldframe.face." + f.getString("face", "front")).append(": ")
                        .append(Component.translatable("module." + mod[0] + "." + (mod.length > 1 ? mod[1] : mod[0])).withStyle(ChatFormatting.WHITE));
                if (f.has("status")) {
                    // Item and fluid ids in status arguments are shown by name, as on the machine screen.
                    Object[] args = f.getList("args").stream().map(arg -> dev.fieldframe.neoforge.client.ConfiguratorScreen.prettyArg(String.valueOf(arg))).toArray();
                    line.append(" - ").append(Component.translatable("fieldframe.status." + f.getString("status", "idle").toLowerCase(java.util.Locale.ROOT), args)
                            .withStyle(color(f.getString("severity", ""))));
                }
                if (f.has("progress")) {
                    line.append(" " + f.getInt("progress", 0) + "%");
                }
                t.add(line);
                // A stuck module also says what to do about it, as the machine screen does.
                String severity = f.getString("severity", "");
                String fix = "fieldframe.status." + f.getString("status", "idle").toLowerCase(java.util.Locale.ROOT) + ".fix";
                if ((severity.equals("BLOCKED") || severity.equals("FAULT")) && net.minecraft.locale.Language.getInstance().has(fix)) {
                    Object[] args = f.getList("args").stream().map(arg -> dev.fieldframe.neoforge.client.ConfiguratorScreen.prettyArg(String.valueOf(arg))).toArray();
                    t.add(Component.literal("  ").append(Component.translatable(fix, args)).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
            }
            t.add(rf(d.getLong("rf", 0), d.getLong("rf_max", 0)));
            for (Object o : d.getList("lanes")) {
                FfData l = (FfData) o;
                t.add(Component.translatable("fieldframe.jade.lane", Component.translatable("fieldframe.lane." + l.getInt("lane", 0)), l.getLong("count", 0)));
            }
            for (Object o : d.getList("tanks")) {
                FfData k = (FfData) o;
                t.add(Component.translatable("fieldframe.jade.tank", Component.translatable("fieldframe.tank." + k.getInt("tank", 0)), fluidName(k.getString("fluid", "")),
                        String.format("%,d", k.getLong("amount", 0)), String.format("%,d", k.getLong("capacity", 0))).withStyle(ChatFormatting.AQUA));
            }
        }
    }

    /** Server side: the data Jade sends to the client (Jade 1.21.6+ needs separate data and client providers). */
    enum ChassisData implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return CHASSIS_DATA;
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor a) {
            if (a.getBlockEntity() instanceof ChassisBlockEntity be) {
                tag.put("ff", NbtBridge.toTag(ViewerSummary.of(be.core())));
            }
        }
    }

    /** Client side: formats the data sent by {@link PadData}. */
    enum Pad implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return PAD;
        }

        @Override
        public void appendTooltip(ITooltip t, BlockAccessor a, IPluginConfig config) {
            CompoundTag d = a.getServerData();
            if (d.contains("ff_rf")) {
                t.add(rf(d.getLongOr("ff_rf", 0), d.getLongOr("ff_rf_max", 0)));
            }
        }
    }

    /** Server side: the data Jade sends to the client (Jade 1.21.6+ needs separate data and client providers). */
    enum PadData implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return PAD_DATA;
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor a) {
            if (a.getBlockEntity() instanceof PadBlockEntity be) {
                tag.putLong("ff_rf", be.buffer().stored());
                tag.putLong("ff_rf_max", be.buffer().capacity());
            }
        }
    }

    /** Client side: formats the data sent by {@link TankData}. */
    enum Tank implements IBlockComponentProvider {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return TANK;
        }

        @Override
        public void appendTooltip(ITooltip t, BlockAccessor a, IPluginConfig config) {
            CompoundTag d = a.getServerData();
            if (!d.contains("ff_formed")) {
                return;
            }
            if (!d.getBooleanOr("ff_formed", false)) {
                t.add(Component.translatable("fieldframe.tank.not_connected").withStyle(ChatFormatting.GOLD));
            }
            if (d.contains("ff_fluid")) {
                t.add(Component.translatable("fieldframe.jade.tank_contents", fluidName(d.getStringOr("ff_fluid", "")),
                        String.format("%,d", d.getLongOr("ff_amount", 0)), String.format("%,d", d.getLongOr("ff_capacity", 0))).withStyle(ChatFormatting.AQUA));
            }
            t.add(rf(d.getLongOr("ff_rf", 0), d.getLongOr("ff_rf_max", 0)));
        }
    }

    /** Server side: the data Jade sends to the client (Jade 1.21.6+ needs separate data and client providers). */
    enum TankData implements IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return TANK_DATA;
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor a) {
            if (a.getBlockEntity() instanceof TankControllerEntity be) {
                var core = be.core();
                tag.putBoolean("ff_formed", core.formed());
                if (core.fluid().fluid() != null) {
                    tag.putString("ff_fluid", core.fluid().fluid().id().toString());
                }
                tag.putLong("ff_amount", core.fluid().amount());
                tag.putLong("ff_capacity", core.fluid().capacity());
                tag.putLong("ff_rf", core.energy().stored());
                tag.putLong("ff_rf_max", core.energy().capacity());
            }
        }
    }
}
