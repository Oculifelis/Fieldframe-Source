package dev.fieldframe.neoforge.client;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.network.FfNetwork;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The machine screen (opened with the Configurator, or an empty hand on a side without an access panel).
 * Left: the six sides with their modules, then what is in the machine's storage and tanks, named by colour.
 * Right: the chosen side's module in plain words — what it does, how it is doing and what to do about it, and where it
 * takes from and puts into (pick from a list that shows what each storage holds). Signals, speed, service bays and
 * the activity log live under Advanced. Every edit is a request; the server validates it and sends a fresh snapshot.
 * Keys: 1-6 choose a side, Tab next side, H opens the manual, A or T toggles Advanced, P pauses.
 */
public final class ConfiguratorScreen extends Screen {
    private static final int MAX_W = 392;
    private static final int MAX_H = 232;
    /** Sides in reading order, as pairs: top/bottom, front/back, left/right. */
    private static final LocalFace[] SIDE_ORDER = {LocalFace.TOP, LocalFace.BOTTOM, LocalFace.FRONT, LocalFace.BACK, LocalFace.LEFT, LocalFace.RIGHT};
    /** Orange, green, purple: the storage and tank colours (same order as lanes 0..2). */
    public static final int[] LANE_COLOURS = {0xFFE58A3A, 0xFF5DBB63, 0xFFA77BDB};
    private static final int AQUA = 0xFF6FD3E0;

    private final BlockPos pos;
    private int face;
    private FfData data;
    private boolean advanced;
    private int x0, y0, w, h;

    /** Clickable regions drawn by this screen (sides, storage rows, pickers), with their tooltips. */
    private final List<Hit> hits = new ArrayList<>();
    private final List<Object[]> labels = new ArrayList<>();
    private final List<Object[]> textBoxes = new ArrayList<>();
    private Dropdown dropdown;

    private record Hit(int x, int y, int w, int h, Runnable click, List<Component> tooltip) {
        boolean over(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Option(String text, int colour, int value) {
    }

    private record Dropdown(int x, int y, int w, List<Option> options, IntConsumer pick) {
        static final int ROW = 11;

        int height() {
            return options.size() * ROW + 2;
        }
    }

    public ConfiguratorScreen(BlockPos pos, int face, FfData data) {
        super(Component.translatable("fieldframe.gui.machine"));
        this.pos = pos;
        this.face = face;
        this.data = data;
    }

    /** Opens on the Advanced view (automated client check). */
    public ConfiguratorScreen showAdvanced() {
        advanced = true;
        return this;
    }

    public BlockPos pos() {
        return pos;
    }

    public void update(int face, FfData data) {
        this.data = data;
        this.face = face;
        rebuild();
    }

    @Override
    protected void init() {
        rebuild();
    }

    @Override
    public void removed() {
        if (minecraft != null && minecraft.getConnection() != null) {
            FfNetwork.Close.send();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private long revision() {
        return data.getLong("revision", 0);
    }

    private FfData faceData(LocalFace f) {
        return data.getData("faces").getDataOrNull(f.key());
    }

    private FfData faceData() {
        return faceData(LocalFace.ALL[face]);
    }

    private void select(int f) {
        FfNetwork.Action.send(pos, revision(), "select_face", f, "", 0, null);
        this.face = f;
        dropdown = null;
        rebuild();
    }

    // ------------------------------------------------------------------ names

    static String sideName(LocalFace f) {
        return I18n.get("fieldframe.face." + f.key());
    }

    static String laneName(int i) {
        return I18n.get("fieldframe.lane." + i);
    }

    static String tankName(int i) {
        return I18n.get("fieldframe.tank." + i);
    }

    static String roleName(String key, String kind) {
        String k = "fieldframe.binding." + key;
        if (net.minecraft.locale.Language.getInstance().has(k)) {
            return I18n.get(k);
        }
        return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1).replace('_', ' ');
    }

    static String moduleName(FfData fd) {
        return fd == null ? I18n.get("fieldframe.gui.empty") : I18n.get(fd.getString("name", fd.getString("module", "?")));
    }

    static ItemStack stackOf(String id) {
        Identifier rl = Identifier.tryParse(id);
        var item = rl == null ? null : BuiltInRegistries.ITEM.getValue(rl);
        return item == null || item == Items.AIR ? Schematic.safeStack(Items.BARRIER) : Schematic.safeStack(item);
    }

    /** An item's display name, or a tidy form of its id when the game cannot name it (never throws). */
    static String itemName(String id) {
        try {
            ItemStack st = stackOf(id);
            if (!st.isEmpty() && !st.is(Items.BARRIER)) {
                return st.getHoverName().getString();
            }
        } catch (RuntimeException e) {
            // fall through to the id
        }
        String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        return path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    static String fluidName(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) {
            return id;
        }
        var fluid = BuiltInRegistries.FLUID.getValue(rl);
        try {
            return fluid == null ? id : fluid.getFluidType().getDescription().getString();
        } catch (RuntimeException e) {
            return id;
        }
    }

    /** Contents of a storage lane, merged by item: id -> count, in first-seen order. */
    private Map<String, Long> laneContents(int lane) {
        Map<String, Long> out = new LinkedHashMap<>();
        List<Object> lanes = data.getList("lanes");
        if (lane >= 0 && lane < lanes.size() && lanes.get(lane) instanceof FfData ld) {
            for (Object o : ld.getList("items")) {
                if (o instanceof FfData it) {
                    out.merge(it.getString("id", "minecraft:air"), (long) it.getInt("count", 0), Long::sum);
                }
            }
        }
        return out;
    }

    private String laneSummary(int lane) {
        Map<String, Long> c = laneContents(lane);
        if (c.isEmpty()) {
            return I18n.get("fieldframe.gui.empty");
        }
        var first = c.entrySet().iterator().next();
        String s = I18n.get("fieldframe.gui.lane_items", itemName(first.getKey()), first.getValue());
        return c.size() > 1 ? s + " " + I18n.get("fieldframe.gui.lane_more", c.size() - 1) : s;
    }

    private String tankSummary(int tank) {
        List<Object> tanks = data.getList("tanks");
        if (tank < 0 || tank >= tanks.size() || !(tanks.get(tank) instanceof FfData td) || td.getLong("amount", 0) <= 0) {
            return I18n.get("fieldframe.gui.tank_empty");
        }
        return I18n.get("fieldframe.gui.tank_amount", String.format("%,d", td.getLong("amount", 0)), fluidName(td.getString("fluid", "?")));
    }

    /** "Heater (Takes from)" for every module connected to this storage (kind ITEM_LANE) or tank (FLUID_LANE). */
    private List<String> usedBy(String kind, int index) {
        List<String> out = new ArrayList<>();
        for (LocalFace f : LocalFace.ALL) {
            FfData fd = faceData(f);
            if (fd == null) {
                continue;
            }
            for (Object o : fd.getList("bindings")) {
                if (o instanceof FfData b && kind.equals(b.getString("kind", "")) && b.getInt("index", -1) == index) {
                    out.add(moduleName(fd) + " (" + roleName(b.getString("key", ""), kind).toLowerCase(Locale.ROOT) + ")");
                }
            }
        }
        return out;
    }

    private String targetText(String kind, int index) {
        return targetText(kind, index, "");
    }

    private String targetText(String kind, int index, String key) {
        if (index < 0) {
            return I18n.get(kind.startsWith("SIGNAL") ? ("enable".equals(key) ? "fieldframe.gui.always_on" : "fieldframe.gui.no_signal")
                    : "fieldframe.gui.not_connected");
        }
        return switch (kind) {
            case "ITEM_LANE" -> laneName(index) + " — " + laneSummary(index);
            case "FLUID_LANE" -> tankName(index) + " — " + tankSummary(index);
            default -> I18n.get("fieldframe.gui.signal_n", index + 1);
        };
    }

    private static int targetColour(String kind, int index) {
        return index >= 0 && index < 3 && (kind.equals("ITEM_LANE") || kind.equals("FLUID_LANE")) ? LANE_COLOURS[index] : Schematic.STEEL;
    }

    // ------------------------------------------------------------------ layout

    private void rebuild() {
        clearWidgets();
        hits.clear();
        labels.clear();
        textBoxes.clear();
        w = Math.min(MAX_W, width - 8);
        h = Math.min(MAX_H, height - 8);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        boolean paused = data.getBool("paused", false);
        addRenderableWidget(Button.builder(Component.literal("?"), b -> openHelp()).bounds(x0 + w - 19, y0 + 4, 14, 12)
                .tooltip(Tooltip.create(Component.translatable("fieldframe.gui.help"))).build());
        addRenderableWidget(Button.builder(Component.literal(paused ? "▶" : "❚❚"), b -> togglePause()).bounds(x0 + w - 35, y0 + 4, 14, 12)
                .tooltip(Tooltip.create(Component.translatable(paused ? "fieldframe.gui.resume_machine" : "fieldframe.gui.pause_machine"))).build());
        boolean quiet = data.getBool("quiet", false);
        Component note = quiet ? Component.literal("♪").withStyle(net.minecraft.ChatFormatting.STRIKETHROUGH, net.minecraft.ChatFormatting.DARK_GRAY) : Component.literal("♪");
        addRenderableWidget(Button.builder(note, b -> FfNetwork.Action.send(pos, revision(), "set_quiet", face, "", quiet ? 0 : 1, null))
                .bounds(x0 + w - 51, y0 + 4, 14, 12)
                .tooltip(Tooltip.create(Component.translatable(quiet ? "fieldframe.gui.sounds_off" : "fieldframe.gui.sounds_on"))).build());
        if (data.getBool("field_kit", false)) {
            // A Field Kit has no Lane Access Panel: its lanes open from here.
            addRenderableWidget(Button.builder(Component.literal("▦"), b -> FfNetwork.Action.send(pos, revision(), "open_lanes", face, "", 0, null))
                    .bounds(x0 + w - 67, y0 + 4, 14, 12)
                    .tooltip(Tooltip.create(Component.translatable("fieldframe.gui.kit_lanes"))).build());
        }
        buildSides();
        buildStorage();
        if (advanced) {
            buildAdvanced();
        } else {
            buildBasic();
        }
    }

    private int rightX() {
        return x0 + 144;
    }

    private int rightW() {
        return w - 151;
    }

    private void buildSides() {
        Orientation o = new Orientation(WorldDir.valueOf(data.getString("front", "NORTH")));
        for (int i = 0; i < SIDE_ORDER.length; i++) {
            LocalFace f = SIDE_ORDER[i];
            int tx = x0 + 7;
            int ty = y0 + 32 + i * 16;
            FfData fd = faceData(f);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(sideName(f) + " — " + moduleName(fd)));
            tip.add(Component.translatable("fieldframe.gui.faces_dir", o.toWorld(f).name().toLowerCase(Locale.ROOT)).withStyle(net.minecraft.ChatFormatting.GRAY));
            if (fd != null) {
                tip.add(Component.translatable("fieldframe.gui.choose").withStyle(net.minecraft.ChatFormatting.DARK_AQUA));
            }
            hits.add(new Hit(tx, ty, 128, 15, () -> select(f.ordinal()), tip));
        }
    }

    private void buildStorage() {
        for (int l = 0; l < 3; l++) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(laneName(l)).withColor(LANE_COLOURS[l]));
            Map<String, Long> c = laneContents(l);
            if (c.isEmpty()) {
                tip.add(Component.translatable("fieldframe.gui.empty").withStyle(net.minecraft.ChatFormatting.GRAY));
            }
            int n = 0;
            for (var e : c.entrySet()) {
                if (n++ >= 8) {
                    tip.add(Component.literal("…").withStyle(net.minecraft.ChatFormatting.GRAY));
                    break;
                }
                tip.add(Component.literal(itemName(e.getKey()) + " ×" + e.getValue()).withStyle(net.minecraft.ChatFormatting.GRAY));
            }
            tip.add(usedByLine("ITEM_LANE", l));
            hits.add(new Hit(x0 + 7, y0 + 141 + l * 18, 128, 17, null, tip));
        }
        for (int t = 0; t < 3; t++) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(tankName(t)).withColor(LANE_COLOURS[t]));
            tip.add(Component.literal(tankSummary(t)).withStyle(net.minecraft.ChatFormatting.GRAY));
            tip.add(usedByLine("FLUID_LANE", t));
            hits.add(new Hit(x0 + 7 + t * 43, y0 + 209, 40, 10, null, tip));
        }
    }

    private Component usedByLine(String kind, int index) {
        List<String> users = usedBy(kind, index);
        return (users.isEmpty() ? Component.translatable("fieldframe.gui.used_by_none")
                : Component.translatable("fieldframe.gui.used_by", String.join(", ", users))).withStyle(net.minecraft.ChatFormatting.DARK_AQUA);
    }

    /** Connections (not signals) and settings of a module, in the order the basic view shows them. */
    private static List<FfData> basicRows(FfData fd) {
        List<FfData> rows = new ArrayList<>();
        for (Object o : fd.getList("bindings")) {
            if (o instanceof FfData b && !b.getString("kind", "").startsWith("SIGNAL")) {
                rows.add(b);
            }
        }
        for (Object o : fd.getList("config")) {
            if (o instanceof FfData c) {
                rows.add(c);
            }
        }
        return rows;
    }

    /** How many basic rows fit above the bottom buttons; the rest are shown under Advanced. */
    private int basicRoom() {
        return Math.max(0, (y0 + h - 24 - (y0 + 100)) / 14);
    }

    private void row(FfData r, FfData config, FfData fd, int rx, int y, int rw) {
        if (r.has("kind")) {
            bindingRow(r, rx, y, rw);
        } else {
            configRow(r, config, fd, rx, y, rw);
        }
    }

    private void buildBasic() {
        FfData fd = faceData();
        int rx = rightX();
        int rw = rightW();
        int bottom = y0 + h - 20;
        if (fd != null && !fd.getBool("missing", false)) {
            List<FfData> rows = basicRows(fd);
            FfData config = currentConfig(fd);
            int y = y0 + 100;
            for (int i = 0; i < Math.min(rows.size(), basicRoom()); i++) {
                row(rows.get(i), config, fd, rx, y, rw);
                y += 14;
            }
            addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.how_it_works"), b -> openHelp())
                    .bounds(rx, bottom, 78, 14).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.help"))).build());
            addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.remove_module"),
                            b -> FfNetwork.Action.send(pos, revision(), "remove_module", face, "", 0, null))
                    .bounds(rx + 82, bottom, 60, 14).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.remove_module.tip"))).build());
            // What can it make? Opens JEI on this processor's recipes (only with JEI installed).
            FfId moduleId = FfId.tryParse(fd.getString("module", ""));
            var def = moduleId == null ? null : dev.fieldframe.api.FieldframeRegistry.module(moduleId);
            if (def != null && def.processFamily() != null && net.neoforged.fml.ModList.get().isLoaded("jei")
                    && dev.fieldframe.neoforge.compat.jei.FieldframeJeiPlugin.hasFamily(def.processFamily())) {
                FfId family = def.processFamily();
                addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.recipes"),
                                b -> dev.fieldframe.neoforge.compat.jei.FieldframeJeiPlugin.showFamily(family))
                        .bounds(rx + rw - 46, y0 + 88, 46, 12).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.recipes.tip"))).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal(I18n.get("fieldframe.gui.advanced") + " ›"), b -> {
            advanced = true;
            dropdown = null;
            rebuild();
        }).bounds(x0 + w - 74, bottom, 68, 14).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.advanced_title"))).build());
    }

    private void buildAdvanced() {
        FfData fd = faceData();
        int rx = rightX();
        int rw = rightW();
        int y = y0 + 34;
        if (fd != null && !fd.getBool("missing", false)) {
            boolean any = false;
            for (Object o : fd.getList("bindings")) {
                if (o instanceof FfData b && b.getString("kind", "").startsWith("SIGNAL")) {
                    if (!any) {
                        labels.add(new Object[]{rx, y, I18n.get("fieldframe.gui.signals"), Schematic.COPPER});
                        y += 11;
                        any = true;
                    }
                    bindingRow(b, rx, y, rw);
                    y += 14;
                }
            }
            FfData config = currentConfig(fd);
            List<FfData> rows = basicRows(fd);
            for (int i = basicRoom(); i < rows.size(); i++) {
                row(rows.get(i), config, fd, rx, y, rw);
                y += 14;
            }
            List<Object> tun = fd.getList("tunables");
            if (!tun.isEmpty()) {
                labels.add(new Object[]{rx, y + 2, I18n.get("fieldframe.gui.speed_settings"), Schematic.COPPER});
                y += 13;
                StringBuilder sb = new StringBuilder();
                for (Object o : tun) {
                    if (o instanceof FfData t) {
                        sb.append(t.getString("key", "").replace('_', ' ')).append(' ').append(trim(t.getDouble("value", 0))).append(t.getString("unit", "")).append("   ");
                    }
                }
                for (var line : font.split(Component.literal(sb.toString().trim()), rw)) {
                    labels.add(new Object[]{rx, y, line, Schematic.INK_DIM});
                    y += 9;
                }
            }
        }
        // Whole machine: what a comparator reads.
        labels.add(new Object[]{rx, y + 3, I18n.get("fieldframe.gui.whole_machine"), Schematic.COPPER});
        y += 14;
        comparatorRow(rx, y, rw);
        y += 14;
        // Service bays.
        labels.add(new Object[]{rx, y + 3, I18n.get("fieldframe.gui.service_bays"), Schematic.COPPER});
        y += 14;
        List<Object> ups = data.getList("upgrades");
        for (int bay = 0; bay < ups.size(); bay++) {
            String id = String.valueOf(ups.get(bay));
            int bx = rx + (bay % 3) * (rw / 3);
            int by = y + (bay / 3) * 12;
            if (id.isEmpty()) {
                labels.add(new Object[]{bx, by + 2, (bay + 1) + ": " + I18n.get("fieldframe.gui.bay_empty"), Schematic.INK_DIM});
            } else {
                FfId uid = FfId.tryParse(id);
                String name = uid == null ? id : I18n.get("upgrade." + uid.namespace() + "." + uid.path());
                labels.add(new Object[]{bx + 12, by + 2, font.plainSubstrByWidth((bay + 1) + ": " + name, rw / 3 - 14), Schematic.INK});
                final int b = bay;
                addRenderableWidget(Button.builder(Component.literal("−"), x -> FfNetwork.Action.send(pos, revision(), "remove_upgrade", face, "", b, null))
                        .bounds(bx, by, 10, 10).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.remove_upgrade", name))).build());
            }
        }
        y += ((ups.size() + 2) / 3) * 12 + 4;
        // Recent activity.
        labels.add(new Object[]{rx, y, I18n.get("fieldframe.gui.trace_toggle"), Schematic.COPPER});
        y += 11;
        List<Object> lines = data.getList("trace");
        int room = Math.max(0, (y0 + h - 24 - y) / 9);
        for (int i = Math.max(0, lines.size() - room); i < lines.size(); i++) {
            labels.add(new Object[]{rx, y, font.plainSubstrByWidth(String.valueOf(lines.get(i)), rw), Schematic.INK_DIM});
            y += 9;
        }
        addRenderableWidget(Button.builder(Component.literal("‹ " + I18n.get("fieldframe.gui.back")), b -> {
            advanced = false;
            dropdown = null;
            rebuild();
        }).bounds(x0 + w - 74, y0 + h - 20, 68, 14).build());
    }

    /** "Takes from  [■ Orange storage — Raw Iron ×12]": the picker lists every storage with its contents. */
    private void bindingRow(FfData b, int rx, int y, int rw) {
        String key = b.getString("key", "");
        String kind = b.getString("kind", "ITEM_LANE");
        int idx = b.getInt("index", -1);
        boolean optional = b.getBool("optional", false);
        labels.add(new Object[]{rx, y + 2, font.plainSubstrByWidth(roleName(key, kind), 80), Schematic.INK_DIM});
        int px = rx + 82;
        int pw = rw - 82;
        List<Option> options = new ArrayList<>();
        if (optional || kind.startsWith("SIGNAL")) {
            options.add(new Option(targetText(kind, -1, key), Schematic.STEEL, -1));
        }
        int max = kind.endsWith("LANE") ? 2 : 7;
        for (int i = 0; i <= max; i++) {
            options.add(new Option(targetText(kind, i), targetColour(kind, i), i));
        }
        List<Component> tip = List.of(Component.literal(roleName(key, kind)), Component.literal(targetText(kind, idx, key)).withStyle(net.minecraft.ChatFormatting.GRAY),
                Component.translatable("fieldframe.gui.choose").withStyle(net.minecraft.ChatFormatting.DARK_AQUA));
        Dropdown dd = new Dropdown(px, y + 12, pw, options, v -> FfNetwork.Action.send(pos, revision(), "set_binding", face, key, v, null));
        hits.add(new Hit(px, y, pw, 12, () -> dropdown = dropdown != null && dropdown.y() == dd.y() && dropdown.x() == dd.x() ? null : dd, tip));
        pickers.add(new Object[]{px, y, pw, targetColour(kind, idx), targetText(kind, idx, key)});
    }

    private final List<Object[]> pickers = new ArrayList<>();

    /** "Comparator reads  [■ Green storage]": off, a storage, a tank or the RF buffer. */
    private void comparatorRow(int rx, int y, int rw) {
        String current = data.getString("comparator", dev.fieldframe.core.chassis.ChassisCore.DEFAULT_COMPARATOR);
        labels.add(new Object[]{rx, y + 2, font.plainSubstrByWidth(I18n.get("fieldframe.gui.comparator"), 80), Schematic.INK_DIM});
        int px = rx + 82;
        int pw = rw - 82;
        List<Option> options = new ArrayList<>();
        List<String> sources = dev.fieldframe.core.chassis.ChassisCore.COMPARATOR_SOURCES;
        for (int i = 0; i < sources.size(); i++) {
            options.add(new Option(comparatorText(sources.get(i)), comparatorColour(sources.get(i)), i));
        }
        List<Component> tip = List.of(Component.translatable("fieldframe.gui.comparator"),
                Component.translatable("fieldframe.gui.comparator.tip").withStyle(net.minecraft.ChatFormatting.GRAY),
                Component.translatable("fieldframe.gui.choose").withStyle(net.minecraft.ChatFormatting.DARK_AQUA));
        Dropdown dd = new Dropdown(px, y + 12, pw, options, v -> FfNetwork.Action.send(pos, revision(), "set_comparator", face, "", v, null));
        hits.add(new Hit(px, y, pw, 12, () -> dropdown = dropdown != null && dropdown.y() == dd.y() && dropdown.x() == dd.x() ? null : dd, tip));
        pickers.add(new Object[]{px, y, pw, comparatorColour(current), comparatorText(current)});
    }

    static String comparatorText(String source) {
        if (source.startsWith("storage_")) {
            return laneName(source.charAt(8) - '0');
        }
        if (source.startsWith("tank_")) {
            return tankName(source.charAt(5) - '0');
        }
        return I18n.get(source.equals("energy") ? "fieldframe.gui.comparator.energy" : "fieldframe.gui.comparator.off");
    }

    static int comparatorColour(String source) {
        if (source.startsWith("storage_")) {
            return LANE_COLOURS[source.charAt(8) - '0'];
        }
        if (source.startsWith("tank_")) {
            return LANE_COLOURS[source.charAt(5) - '0'];
        }
        return Schematic.STEEL;
    }

    private void configRow(FfData c, FfData config, FfData fd, int rx, int y, int rw) {
        String key = c.getString("key", "");
        String type = c.getString("type", "");
        String label = I18n.get("fieldframe.config." + key);
        labels.add(new Object[]{rx, y + 2, font.plainSubstrByWidth(label, 80), Schematic.INK_DIM});
        int cx = rx + 82;
        int cw = rw - 82;
        switch (type) {
            case "BOOL" -> {
                boolean v = c.getBool("value", false);
                addRenderableWidget(Button.builder(Component.literal(v ? "on" : "off"), b -> sendConfig(config.copy().putBool(key, !v))).bounds(cx, y, cw, 12).build());
            }
            case "ENUM" -> {
                List<String> opts = strings(c.getList("options"));
                String v = c.getString("value", opts.isEmpty() ? "" : opts.get(0));
                List<Option> options = new ArrayList<>();
                for (int i = 0; i < opts.size(); i++) {
                    options.add(new Option(I18n.get("fieldframe.option." + opts.get(i)), Schematic.STEEL, i));
                }
                Dropdown dd = new Dropdown(cx, y + 12, cw, options, i -> sendConfig(config.copy().putString(key, opts.get(i))));
                hits.add(new Hit(cx, y, cw, 12, () -> dropdown = dropdown != null && dropdown.y() == dd.y() ? null : dd,
                        List.of(Component.literal(label), Component.translatable("fieldframe.gui.choose").withStyle(net.minecraft.ChatFormatting.DARK_AQUA))));
                pickers.add(new Object[]{cx, y, cw, Schematic.STEEL, I18n.get("fieldframe.option." + v)});
            }
            case "INT" -> {
                long v = c.getLong("value", 0);
                long min = c.getLong("min", 0);
                long max = c.getLong("max", 0);
                addRenderableWidget(Button.builder(Component.literal("−"), b -> sendConfig(config.copy().putLong(key, Math.max(min, v - (Schematic.shift() ? 10 : 1)))))
                        .bounds(cx, y, 12, 12).tooltip(Tooltip.create(Component.literal("Shift: −10"))).build());
                addRenderableWidget(Button.builder(Component.literal("+"), b -> sendConfig(config.copy().putLong(key, Math.min(max, v + (Schematic.shift() ? 10 : 1)))))
                        .bounds(cx + cw - 12, y, 12, 12).tooltip(Tooltip.create(Component.literal("Shift: +10"))).build());
                labels.add(new Object[]{cx + cw / 2 - font.width(String.valueOf(v)) / 2, y + 2, String.valueOf(v), Schematic.INK});
            }
            case "ITEM_FILTER" -> {
                ItemFilter f = ItemFilter.fromData(c.getDataOrNull("value"));
                String desc = f.isAny() ? I18n.get("fieldframe.gui.filter_any") : f.entries().size() + " " + I18n.get("fieldframe.gui.filter_entries");
                addRenderableWidget(Button.builder(Component.literal(desc), b -> minecraft.gui.setScreen(new FilterScreen(this, key, c.getDataOrNull("value"))))
                        .bounds(cx, y, cw, 12).tooltip(Tooltip.create(Component.translatable("fieldframe.gui.filter_hint"))).build());
            }
            case "TEXT" -> {
                String value = c.getString("value", "");
                if ("pattern".equals(key) && dev.fieldframe.core.modules.BuiltinModules.RECIPE_ASSEMBLER.toString().equals(fd.getString("module", ""))) {
                    addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.edit_pattern"),
                            b -> minecraft.gui.setScreen(new PatternScreen(this, key, value))).bounds(cx, y, cw, 12).build());
                    return;
                }
                if (c.getLong("max", 24) > 128) {
                    int limit = (int) c.getLong("max", 4096);
                    addRenderableWidget(Button.builder(Component.translatable("fieldframe.gui.edit_program"),
                            b -> minecraft.gui.setScreen(new ProgramEditorScreen(this, key, value, limit))).bounds(cx, y, cw, 12).build());
                    return;
                }
                EditBox box = new EditBox(font, cx, y, cw, 12, Component.literal(label));
                box.setMaxLength((int) Math.max(1, c.getLong("max", 24)));
                box.setValue(value);
                addRenderableWidget(box);
                textBoxes.add(new Object[]{box, key});
            }
            default -> {
            }
        }
    }

    @Override
    protected void clearWidgets() {
        super.clearWidgets();
        pickers.clear();
    }

    private FfData currentConfig(FfData fd) {
        FfData out = new FfData();
        for (Object o : fd.getList("config")) {
            if (o instanceof FfData c && c.has("value")) {
                out.putAny(c.getString("key", "x"), c.getRaw("value"));
            }
        }
        return out;
    }

    /** Called by the filter editor. */
    void sendFilter(String key, FfData filter) {
        FfData fd = faceData();
        if (fd != null) {
            sendConfig(currentConfig(fd).put(key, filter));
        }
    }

    /** Called by the program editor and the pattern screen. */
    void sendProgram(String key, String text) {
        FfData fd = faceData();
        if (fd != null) {
            sendConfig(currentConfig(fd).putString(key, text));
        }
    }

    private void sendConfig(FfData cfg) {
        FfNetwork.Action.send(pos, revision(), "set_config", face, "", 0, cfg);
    }

    private void togglePause() {
        FfNetwork.Action.send(pos, revision(), "pause", face, "", data.getBool("paused", false) ? 0 : 1, null);
    }

    private void openHelp() {
        FfData fd = faceData();
        FfId entry = fd != null && FfId.tryParse(fd.getString("manual", "")) != null ? FfId.parse(fd.getString("manual", ""))
                : FfId.ff("basics/configurator");
        FfClient.openManual(entry);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (dropdown != null) {
            Dropdown dd = dropdown;
            dropdown = null;
            if (mx >= dd.x() && mx < dd.x() + dd.w() && my >= dd.y() && my < dd.y() + dd.height()) {
                int row = (int) ((my - dd.y() - 1) / Dropdown.ROW);
                if (row >= 0 && row < dd.options().size()) {
                    dd.pick().accept(dd.options().get(row).value());
                }
            }
            return true;
        }
        if (button == 0) {
            for (Hit hit : hits) {
                if (hit.click() != null && hit.over(mx, my)) {
                    hit.click().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        for (Object[] tb : textBoxes) {
            EditBox box = (EditBox) tb[0];
            if (box.isFocused()) {
                if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN) {
                    FfData fd = faceData();
                    if (fd != null) {
                        sendConfig(currentConfig(fd).putString((String) tb[1], box.getValue()));
                    }
                    return true;
                }
                return box.keyPressed(event) || super.keyPressed(event);
            }
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE && dropdown != null) {
            dropdown = null;
            return true;
        }
        if (key >= com.mojang.blaze3d.platform.InputConstants.KEY_1 && key <= com.mojang.blaze3d.platform.InputConstants.KEY_6) {
            select(SIDE_ORDER[key - com.mojang.blaze3d.platform.InputConstants.KEY_1].ordinal());
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_TAB) {
            select((face + 1) % 6);
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_A || key == com.mojang.blaze3d.platform.InputConstants.KEY_T) {
            advanced = !advanced;
            dropdown = null;
            rebuild();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_H) {
            openHelp();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_P) {
            togglePause();
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractBackground(g, mx, my, partial);
        Schematic.panel(g, x0, y0, w, h);
        renderHeader(g);
        g.fill(x0 + 4, y0 + 19, x0 + w - 4, y0 + 20, Schematic.STEEL);
        g.fill(x0 + 139, y0 + 22, x0 + 140, y0 + h - 4, Schematic.STEEL);
        renderSides(g, mx, my);
        renderStorage(g);
        if (advanced) {
            g.text(font, I18n.get("fieldframe.gui.advanced_title"), rightX(), y0 + 23, Schematic.COPPER, false);
        } else {
            renderModule(g);
        }
        for (Object[] p : pickers) {
            int px = (int) p[0];
            int py = (int) p[1];
            int pw = (int) p[2];
            boolean hover = mx >= px && mx < px + pw && my >= py && my < py + 12;
            g.fill(px, py, px + pw, py + 12, hover ? Schematic.STEEL : Schematic.SLOT);
            g.fill(px, py, px + 4, py + 12, (int) p[3]);
            g.text(font, font.plainSubstrByWidth((String) p[4], pw - 16), px + 7, py + 2, Schematic.INK, false);
            g.text(font, "▾", px + pw - 8, py + 2, Schematic.INK_DIM, false);
        }
    }

    private void renderHeader(GuiGraphicsExtractor g) {
        g.text(font, title, x0 + 7, y0 + 6, Schematic.INK, false);
        int tierLevel = data.getInt("tier_level", 0);
        if (tierLevel > 0) {
            FfId tid = FfId.tryParse(data.getString("tier", ""));
            String tier = tid == null ? "?" : I18n.get("tier." + tid.namespace() + "." + tid.path());
            g.text(font, I18n.get("fieldframe.gui.tier", tier), x0 + 12 + font.width(title), y0 + 6, AQUA, false);
        }
        int parts = data.getInt("bolt_parts", 1);
        if (parts > 1 || data.getBool("bolt_waiting", false)) {
            String bolt = data.getBool("bolt_waiting", false) ? I18n.get("fieldframe.gui.bolt_waiting") : I18n.get("fieldframe.gui.bolted", parts);
            g.text(font, bolt, x0 + 7, y0 + h - 12, data.getBool("bolt_waiting", false) ? 0xFFE0B040 : AQUA, false);
        }
        long e = data.getLong("energy", 0);
        long cap = Math.max(1, data.getLong("energyCap", 1));
        String rf = String.format("%,d / %,d RF", e, cap);
        int tx = x0 + w - 56 - font.width(rf);
        g.text(font, rf, tx, y0 + 6, Schematic.INK_DIM, false);
        Schematic.bar(g, tx - 54, y0 + 7, 50, 7, e / (double) cap, 0xFFE0524B);
        g.text(font, I18n.get("fieldframe.gui.power"), tx - 58 - font.width(I18n.get("fieldframe.gui.power")), y0 + 6, Schematic.INK_DIM, false);
    }

    private void renderSides(GuiGraphicsExtractor g, int mx, int my) {
        g.text(font, I18n.get("fieldframe.gui.sides"), x0 + 7, y0 + 23, Schematic.INK_DIM, false);
        for (int i = 0; i < SIDE_ORDER.length; i++) {
            LocalFace f = SIDE_ORDER[i];
            int tx = x0 + 7;
            int ty = y0 + 32 + i * 16;
            boolean sel = f.ordinal() == face;
            boolean hover = mx >= tx && mx < tx + 128 && my >= ty && my < ty + 15;
            if (sel || hover) {
                g.fill(tx, ty, tx + 128, ty + 15, sel ? 0xFF3A3024 : Schematic.PAPER_LIGHT);
            }
            if (sel) {
                g.fill(tx, ty, tx + 2, ty + 15, Schematic.COPPER);
            }
            FfData fd = faceData(f);
            g.text(font, sideName(f), tx + 5, ty + 4, sel ? Schematic.COPPER : Schematic.INK_DIM, false);
            if (fd == null) {
                g.text(font, "+ " + I18n.get("fieldframe.gui.empty"), tx + 44, ty + 4, Schematic.INK_DIM, false);
                continue;
            }
            FfId id = FfId.tryParse(fd.getString("module", ""));
            var item = id == null ? null : FfContent.moduleItem(id);
            g.item(item == null ? Schematic.safeStack(Items.BARRIER) : Schematic.safeStack(item), tx + 40, ty);
            g.text(font, font.plainSubstrByWidth(moduleName(fd), 66), tx + 58, ty + 4, Schematic.INK, false);
            int c = Schematic.severityColor(fd.getString("severity", "IDLE"));
            g.fill(tx + 124, ty + 5, tx + 127, ty + 8, c);
        }
    }

    private void renderStorage(GuiGraphicsExtractor g) {
        int x = x0 + 7;
        g.text(font, I18n.get("fieldframe.gui.storage"), x, y0 + 131, Schematic.INK_DIM, false);
        for (int l = 0; l < 3; l++) {
            int y = y0 + 141 + l * 18;
            g.fill(x, y, x + 3, y + 17, LANE_COLOURS[l]);
            g.text(font, I18n.get("fieldframe.colour." + l), x + 6, y + 5, LANE_COLOURS[l], false);
            Map<String, Long> c = laneContents(l);
            if (c.isEmpty()) {
                g.text(font, I18n.get("fieldframe.gui.empty"), x + 46, y + 5, Schematic.INK_DIM, false);
                continue;
            }
            int shown = 0;
            for (var e : c.entrySet()) {
                if (shown == 4) {
                    g.text(font, "+" + (c.size() - 4), x + 46 + 4 * 17, y + 5, Schematic.INK_DIM, false);
                    break;
                }
                ItemStack st = stackOf(e.getKey());
                int ix = x + 46 + shown * 17;
                g.item(st, ix, y + 1);
                g.itemDecorations(font, st.copyWithCount((int) Math.min(99, Math.max(1, e.getValue()))), ix, y + 1,
                        e.getValue() > 99 ? "99+" : null);
                shown++;
            }
        }
        g.text(font, I18n.get("fieldframe.gui.tanks"), x, y0 + 199, Schematic.INK_DIM, false);
        List<Object> tanks = data.getList("tanks");
        for (int t = 0; t < 3; t++) {
            int tx = x + t * 43;
            long amount = 0;
            long cap = 1;
            if (t < tanks.size() && tanks.get(t) instanceof FfData td) {
                amount = td.getLong("amount", 0);
                cap = Math.max(1, td.getLong("capacity", 1));
            }
            g.fill(tx, y0 + 209, tx + 3, y0 + 218, LANE_COLOURS[t]);
            Schematic.bar(g, tx + 5, y0 + 210, 34, 7, amount / (double) cap, 0xFF3F76E4);
        }
    }

    private void renderModule(GuiGraphicsExtractor g) {
        int x = rightX();
        int rw = rightW();
        LocalFace f = LocalFace.ALL[face];
        FfData fd = faceData();
        if (fd == null) {
            g.text(font, I18n.get("fieldframe.gui.side_empty_title", sideName(f).toLowerCase(Locale.ROOT)), x, y0 + 23, Schematic.COPPER, false);
            g.textWithWordWrap(font, Component.translatable("fieldframe.gui.side_empty_hint"), x, y0 + 36, rw, Schematic.INK_DIM);
            g.textWithWordWrap(font, Component.translatable("fieldframe.gui.storage_hint"), x, y0 + 80, rw, Schematic.INK_DIM);
            return;
        }
        if (fd.getBool("missing", false)) {
            g.textWithWordWrap(font, Component.translatable("fieldframe.gui.missing_hint", fd.getString("module", "?")), x, y0 + 23, rw, 0xFFE0524B);
            return;
        }
        String name = moduleName(fd);
        g.text(font, name, x, y0 + 23, Schematic.INK, false);
        g.text(font, I18n.get("fieldframe.gui.on_side", sideName(f).toLowerCase(Locale.ROOT)), x + font.width(name) + 5, y0 + 23, Schematic.INK_DIM, false);
        FfId id = FfId.tryParse(fd.getString("module", ""));
        if (id != null && net.minecraft.locale.Language.getInstance().has("module." + id.namespace() + "." + id.path() + ".desc")) {
            var lines = font.split(Component.translatable("module." + id.namespace() + "." + id.path() + ".desc"), rw);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                g.text(font, lines.get(i), x, y0 + 34 + i * 9, Schematic.INK_DIM, false);
            }
        }
        // Status, in words: what is happening, then what to do about it.
        String severity = fd.getString("severity", "IDLE");
        int sc = Schematic.severityColor(severity);
        String code = fd.getString("status", "IDLE").toLowerCase(Locale.ROOT);
        Object[] args = strings(fd.getList("args")).stream().map(ConfiguratorScreen::prettyArg).toArray();
        String key = "fieldframe.status." + code;
        String explain = net.minecraft.locale.Language.getInstance().has(key) ? I18n.get(key, args) : code;
        String fix = net.minecraft.locale.Language.getInstance().has(key + ".fix") ? I18n.get(key + ".fix", args) : "";
        int by = y0 + 55;
        g.fill(x, by, x + rw, by + 22, 0xFF151E24);
        g.fill(x, by, x + 2, by + 22, sc);
        Schematic.lamp(g, font, x + 5, by + 2, severity);
        g.text(font, font.plainSubstrByWidth(explain, rw - 20), x + 17, by + 3, sc, false);
        if (!fix.isEmpty()) {
            g.text(font, font.plainSubstrByWidth(fix, rw - 8), x + 5, by + 13, Schematic.INK_DIM, false);
        }
        double p = fd.getDouble("progress", -1);
        int py = y0 + 81;
        int tx = x;
        if (p >= 0) {
            Schematic.bar(g, x, py + 1, 60, 6, p, Schematic.COPPER);
            String pct = I18n.get("fieldframe.gui.progress", Math.round(p * 100));
            g.text(font, pct, x + 64, py, Schematic.INK_DIM, false);
            tx = x + 70 + font.width(pct);
        }
        long used = fd.getLong("rfUsed", 0);
        long made = fd.getLong("rfMade", 0);
        if (used > 0 || made > 0) {
            g.text(font, made > 0 ? I18n.get("fieldframe.gui.rf_make", made) : I18n.get("fieldframe.gui.rf_use", used), tx, py, Schematic.INK_DIM, false);
        }
        boolean any = false;
        for (Object o : fd.getList("bindings")) {
            if (o instanceof FfData b && !b.getString("kind", "").startsWith("SIGNAL")) {
                any = true;
                break;
            }
        }
        if (any || !fd.getList("config").isEmpty()) {
            g.text(font, I18n.get("fieldframe.gui.connections"), x, y0 + 91, Schematic.COPPER, false);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractRenderState(g, mx, my, partial);
        for (Object[] l : labels) {
            Object text = l[2];
            if (text instanceof net.minecraft.util.FormattedCharSequence seq) {
                g.text(font, seq, (int) l[0], (int) l[1], (int) l[3], false);
            } else {
                g.text(font, String.valueOf(text), (int) l[0], (int) l[1], (int) l[3], false);
            }
        }
        if (dropdown != null) {
            Dropdown dd = dropdown;
            g.nextStratum();
            int ddy = dd.y();
            if (ddy + dd.height() > y0 + h) {
                ddy = Math.max(y0 + 2, dd.y() - 12 - dd.height());
                dropdown = new Dropdown(dd.x(), ddy, dd.w(), dd.options(), dd.pick());
            }
            g.fill(dd.x() - 1, ddy - 1, dd.x() + dd.w() + 1, ddy + dd.height() + 1, Schematic.INK_DIM);
            g.fill(dd.x(), ddy, dd.x() + dd.w(), ddy + dd.height(), 0xFF101820);
            for (int i = 0; i < dd.options().size(); i++) {
                Option o = dd.options().get(i);
                int oy = ddy + 1 + i * Dropdown.ROW;
                boolean hover = mx >= dd.x() && mx < dd.x() + dd.w() && my >= oy && my < oy + Dropdown.ROW;
                if (hover) {
                    g.fill(dd.x(), oy, dd.x() + dd.w(), oy + Dropdown.ROW, Schematic.STEEL);
                }
                g.fill(dd.x() + 1, oy + 1, dd.x() + 4, oy + Dropdown.ROW - 1, o.colour());
                g.text(font, font.plainSubstrByWidth(o.text(), dd.w() - 10), dd.x() + 7, oy + 2, Schematic.INK, false);
            }
            return;
        }
        for (Hit hit : hits) {
            if (hit.tooltip() != null && hit.over(mx, my)) {
                g.setComponentTooltipForNextFrame(font, hit.tooltip(), mx, my);
                break;
            }
        }
    }

    /** Item and fluid ids in a status become their names ("minecraft:iron_ingot" -> "Iron Ingot"); words lose underscores. */
    public static String prettyArg(String arg) {
        StringBuilder out = new StringBuilder();
        for (String word : arg.split(" ")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            Identifier rl = word.indexOf(':') > 0 ? Identifier.tryParse(word) : null;
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                out.append(itemName(word));
            } else if (rl != null && BuiltInRegistries.FLUID.containsKey(rl)) {
                out.append(fluidName(word));
            } else {
                out.append(word.replace('_', ' '));
            }
        }
        return out.toString();
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    static List<String> strings(List<Object> l) {
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            out.add(String.valueOf(o));
        }
        return out;
    }
}
