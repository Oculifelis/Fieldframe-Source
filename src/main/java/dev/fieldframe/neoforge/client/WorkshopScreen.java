package dev.fieldframe.neoforge.client;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.gui.TabMotion;
import dev.fieldframe.core.gui.WorkshopArt;
import dev.fieldframe.core.gui.WorkshopLayout;
import dev.fieldframe.neoforge.menu.WorkshopMenu;
import dev.fieldframe.neoforge.network.FfNetwork;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The Workshop machine screen (direction A): one side's module at a time, with its lanes as real slots, the energy
 * gauge, progress arrow and tank; six face tabs on top pick the side. Side tabs slide open on the left (Info, Energy)
 * and right (Chassis, Redstone, Routing, Settings, Service bays); one open per side, remembered per module type.
 * Everything that changes the machine goes through the validated {@code FfNetwork.Action} path, as on the full
 * editor ({@link ConfiguratorScreen}), which the Info tab opens.
 */
public final class WorkshopScreen extends AbstractContainerScreen<WorkshopMenu> {
    private static final int W = WorkshopLayout.W, H = WorkshopLayout.H;

    enum Tab {
        INFO(false, WorkshopArt.TAB_INFO, 110, 144),
        ENERGY(false, WorkshopArt.TAB_ENERGY, 100, 104),
        CHASSIS(false, WorkshopArt.TAB_CHASSIS, 96, 84),
        REDSTONE(true, WorkshopArt.TAB_REDSTONE, 104, 0),
        ROUTING(true, WorkshopArt.TAB_ROUTING, 118, 0),
        SETTINGS(true, WorkshopArt.TAB_SETTINGS, 104, 0),
        BAYS(true, WorkshopArt.TAB_BAYS, 96, 58);

        final boolean right;
        final int colour, openW, openH;

        Tab(boolean right, int colour, int openW, int openH) {
            this.right = right;
            this.colour = colour;
            this.openW = openW;
            this.openH = openH;
        }

        String title() {
            return I18n.get("fieldframe.ws.tab." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** The open tab per side, remembered per module id across screens. */
    private static final Map<String, Tab> LEFT_OPEN = new HashMap<>(), RIGHT_OPEN = new HashMap<>();

    private static BlockPos pendingPos;
    private static int pendingFace;
    private static FfData pendingData;

    private final BlockPos pos;
    private int face;
    private FfData data = new FfData();
    private String shownModule;
    private final EnumMap<Tab, TabMotion> motion = new EnumMap<>(Tab.class);
    private final int[] tabX = new int[Tab.values().length], tabY = new int[Tab.values().length];
    private final int[] tabW = new int[Tab.values().length], tabH = new int[Tab.values().length];
    private final List<Hit> hits = new ArrayList<>();
    private Dropdown dropdown;
    private boolean handingOff;
    private long removeArmedAt = -1;
    /** Energy samples (wall-clock ms, RF) for the 30 s graph. */
    private final ArrayDeque<long[]> energyLog = new ArrayDeque<>();

    private record Hit(int x, int y, int w, int h, Runnable click, List<Component> tip) {
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

    public WorkshopScreen(WorkshopMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = W;
        this.imageHeight = H;
        this.pos = menu.pos();
        this.face = menu.face();
        this.inventoryLabelY = WorkshopLayout.INV_LABEL_Y;
        for (Tab t : Tab.values()) {
            motion.put(t, new TabMotion());
        }
        if (pendingData != null && pos.equals(pendingPos)) {
            update(pendingFace, pendingData);
        }
        pendingData = null;
        pendingPos = null;
    }

    /** A snapshot that arrived just before this screen opened. */
    public static void pending(BlockPos pos, int face, FfData data) {
        pendingPos = pos;
        pendingFace = face;
        pendingData = data;
    }

    public BlockPos pos() {
        return pos;
    }

    public void update(int face, FfData data) {
        this.face = face;
        this.data = data;
        long now = now();
        energyLog.addLast(new long[]{now, data.getLong("energy", 0)});
        while (energyLog.size() > 2 && now - energyLog.peekFirst()[0] > 32_000) {
            energyLog.removeFirst();
        }
        applyFace();
    }

    /** Lanes shown in each slot block, and the remembered tabs, follow the chosen side's module. */
    private void applyFace() {
        for (WorkshopLayout.Role r : WorkshopLayout.Role.values()) {
            menu.showLane(r, -1);
        }
        FfData fd = faceData();
        if (fd != null && !fd.getBool("missing", false)) {
            for (Object o : fd.getList("bindings")) {
                if (o instanceof FfData b && "ITEM_LANE".equals(b.getString("kind", ""))) {
                    WorkshopLayout.Role role = WorkshopLayout.role(b.getString("key", ""), b.getInt("default", 0));
                    int lane = b.getInt("index", -1);
                    if (lane >= 0 && menu.shownLane(role) < 0) {
                        menu.showLane(role, lane);
                    }
                }
            }
        }
        String module = fd == null ? "" : fd.getString("module", "");
        if (!module.equals(shownModule)) {
            shownModule = module;
            Tab l = LEFT_OPEN.get(module), r = RIGHT_OPEN.get(module);
            for (Tab t : Tab.values()) {
                motion.get(t).snap(t == l || t == r);
            }
            dropdown = null;
        }
    }

    /** Test hook: when set (>= 0), tab motion runs on this clock (ms) instead of the wall clock, for frame captures. */
    public static long testClock = -1;

    private static long now() {
        return testClock >= 0 ? testClock : System.nanoTime() / 1_000_000L;
    }

    private long revision() {
        return data.getLong("revision", 0);
    }

    private FfData faceData(int f) {
        return data.getData("faces").getDataOrNull(LocalFace.ALL[f].key());
    }

    private FfData faceData() {
        return faceData(face);
    }

    private void act(String action, String key, long value, FfData payload) {
        FfNetwork.Action.send(pos, revision(), action, face, key, (int) value, payload);
    }

    private void selectFace(int f) {
        if (f == face) {
            return;
        }
        FfNetwork.Action.send(pos, revision(), "select_face", f, "", 0, null);
        face = f;
        applyFace();
    }

    private void toggle(Tab t) {
        long now = now();
        boolean open = !motion.get(t).isOpen();
        for (Tab o : Tab.values()) {
            if (o.right == t.right && o != t) {
                motion.get(o).set(false, now);
            }
        }
        motion.get(t).set(open, now);
        (t.right ? RIGHT_OPEN : LEFT_OPEN).put(shownModule == null ? "" : shownModule, open ? t : null);
        dropdown = null;
    }

    @Override
    public void removed() {
        if (!handingOff && minecraft != null && minecraft.getConnection() != null) {
            FfNetwork.Close.send();
        }
        super.removed();
    }

    /** Closes the container and shows the full editor on the same side; the server keeps sending snapshots to it. */
    private void openFullEditor() {
        BlockPos p = pos;
        int f = face;
        FfData d = data;
        handingOff = true;
        minecraft.player.closeContainer();
        minecraft.setScreen(new ConfiguratorScreen(p, f, d));
        FfNetwork.Action.send(p, d.getLong("revision", 0), "select_face", f, "", 0, null);
    }

    private void openManual() {
        FfData fd = faceData();
        FfId entry = fd != null && FfId.tryParse(fd.getString("manual", "")) != null ? FfId.parse(fd.getString("manual", ""))
                : FfId.ff("basics/configurator");
        minecraft.player.closeContainer();
        FfClient.openManual(entry);
    }

    // ------------------------------------------------------------------ drawing

    private static int frameNow() {
        return (int) ((now() / 100) % WorkshopArt.FRAMES);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        hits.clear();
        int x = leftPos, y = topPos;
        // face tabs: the others behind the panel, the chosen one in front of it, joined to it
        for (int i = 0; i < 6; i++) {
            if (WorkshopLayout.FACE_ORDER[i] != face) {
                faceTab(g, i, false);
            }
        }
        GuiTex.blit(g, GuiTex.get("ws_panel", W, H, () -> WorkshopArt.panel(W, H, WorkshopLayout.INV_Y)), x, y, 0, 0, W, H, W, H);
        for (int i = 0; i < 6; i++) {
            if (WorkshopLayout.FACE_ORDER[i] == face) {
                faceTab(g, i, true);
            }
        }
        FfData fd = faceData();
        energyGauge(g, x + WorkshopLayout.ENERGY_X, y + WorkshopLayout.ENERGY_Y);
        boolean[] shown = new boolean[3];
        for (int i = 0; i < WorkshopMenu.LANE_SLOTS; i++) {
            Slot s = menu.slots.get(i);
            if (!s.isActive()) {
                continue;
            }
            int k = i % WorkshopMenu.PER_LANE;
            int block = k < 4 ? 0 : k < 8 ? 1 : 2;
            shown[block] = true;
            int c = block == 0 ? WorkshopArt.INPUT : block == 1 ? WorkshopArt.RESULT : WorkshopArt.SUPPLY;
            rim(g, x + s.x, y + s.y, 16, 16, c);
        }
        if (shown[0] || shown[1]) {
            double p = fd == null ? -1 : fd.getDouble("progress", -1);
            int level = p < 0 ? 0 : (int) Math.round(Math.min(1, p) * 24);
            ResourceLocation arrow = GuiTex.strip("ws_arrow", 24, 16, 25, f -> WorkshopArt.arrow(f / 24.0));
            GuiTex.blit(g, arrow, x + WorkshopLayout.ARROW_X, y + (shown[2] ? WorkshopLayout.ARROW_Y_FUEL : WorkshopLayout.ARROW_Y),
                    0, level * 16, 24, 16, 24, 16 * 25);
        }
        tank(g, fd, mx, my);
        status(g, fd);
        sideTabs(g, mx, my);
    }

    /** A slot (or any box) with its role colour as a rim around the recessed inset. */
    private static void rim(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, WorkshopArt.shade(c, 0.72));
        g.fill(x - 1, y + h + 1, x + w + 2, y + h + 2, WorkshopArt.shade(c, 1.25));
        g.fill(x + w + 1, y - 1, x + w + 2, y + h + 2, WorkshopArt.shade(c, 1.25));
        inset(g, x, y, w, h, WorkshopArt.SLOT_FILL);
    }

    /** Recessed box; (x, y, w, h) is the inner area, its border 1 px outside. */
    private static void inset(GuiGraphics g, int x, int y, int w, int h, int fill) {
        g.fill(x - 1, y - 1, x + w, y, WorkshopArt.SLOT_DARK);
        g.fill(x - 1, y - 1, x, y + h, WorkshopArt.SLOT_DARK);
        g.fill(x, y + h, x + w + 1, y + h + 1, WorkshopArt.SLOT_LIGHT);
        g.fill(x + w, y, x + w + 1, y + h + 1, WorkshopArt.SLOT_LIGHT);
        g.fill(x, y, x + w, y + h, fill);
    }

    private void energyGauge(GuiGraphics g, int x, int y) {
        int w = WorkshopLayout.ENERGY_W, h = WorkshopLayout.ENERGY_H;
        long cap = data.getLong("energyCap", 0);
        double frac = cap <= 0 ? 0 : Math.min(1, data.getLong("energy", 0) / (double) cap);
        int level = (int) Math.round(frac * h);
        inset(g, x, y, w, h, 0xFF160A0D);
        ResourceLocation tex = GuiTex.strip("ws_energy_" + level, w, h, WorkshopArt.FRAMES, f -> WorkshopArt.energyBar(w, h, level / (double) h, f));
        GuiTex.blit(g, tex, x, y, 0, frameNow() * h, w, h, w, h * WorkshopArt.FRAMES);
        hits.add(new Hit(x - 1, y - 1, w + 2, h + 2, null, List.of(Component.translatable("fieldframe.ws.energy"),
                Component.literal(data.getLong("energy", 0) + " / " + cap + " RF").withStyle(ChatFormatting.GRAY))));
    }

    private void tank(GuiGraphics g, FfData fd, int mx, int my) {
        if (fd == null) {
            return;
        }
        int lane = -1;
        WorkshopLayout.Role role = WorkshopLayout.Role.INPUT;
        for (Object o : fd.getList("bindings")) {
            if (o instanceof FfData b && "FLUID_LANE".equals(b.getString("kind", ""))) {
                lane = b.getInt("index", -1);
                role = WorkshopLayout.role(b.getString("key", ""), b.getInt("default", 0));
                break;
            }
        }
        if (lane < 0) {
            return;
        }
        int x = leftPos + WorkshopLayout.TANK_X, y = topPos + WorkshopLayout.TANK_Y, w = WorkshopLayout.TANK_W, h = WorkshopLayout.TANK_H;
        int c = role == WorkshopLayout.Role.RESULT ? WorkshopArt.TANK_RESULT : role == WorkshopLayout.Role.SUPPLY ? WorkshopArt.TANK_SUPPLY : WorkshopArt.TANK_INPUT;
        rim(g, x, y, w, h, c);
        List<Object> tanks = data.getList("tanks");
        long amount = 0, capacity = 0;
        String fluid = "";
        if (lane < tanks.size() && tanks.get(lane) instanceof FfData t) {
            amount = t.getLong("amount", 0);
            capacity = t.getLong("capacity", 0);
            fluid = t.getString("fluid", "");
        }
        int fill = capacity <= 0 ? 0 : (int) Math.round(h * Math.min(1, amount / (double) capacity));
        int col = fluid.contains("lava") ? 0xFFD8641E : fluid.contains("water") ? 0xFF3F6FE0 : 0xFF6FA0C8;
        if (fill > 0) {
            g.fill(x, y + h - fill, x + w, y + h, col);
            g.fill(x, y + h - fill, x + w, y + h - fill + 1, WorkshopArt.shade(col, 1.3));
        }
        for (int q = 1; q < 4; q++) {
            g.fill(x, y + h * q / 4, x + (q == 2 ? 6 : 4), y + h * q / 4 + 1, 0x80FFFFFF);
        }
        hits.add(new Hit(x - 1, y - 1, w + 2, h + 2, null, List.of(Component.literal(ConfiguratorScreen.tankName(lane)),
                Component.literal((fluid.isEmpty() ? I18n.get("fieldframe.ws.empty") : ConfiguratorScreen.fluidName(fluid)) + "  " + amount + " / " + capacity + " mB")
                        .withStyle(ChatFormatting.GRAY))));
    }

    private String statusText(FfData fd) {
        String code = fd.getString("status", "IDLE").toLowerCase(Locale.ROOT);
        Object[] args = ConfiguratorScreen.strings(fd.getList("args")).stream().map(ConfiguratorScreen::prettyArg).toArray();
        String key = "fieldframe.status." + code;
        return I18n.exists(key) ? I18n.get(key, args) : code;
    }

    private void status(GuiGraphics g, FfData fd) {
        int x = leftPos + WorkshopLayout.STATUS_X, y = topPos + WorkshopLayout.STATUS_Y;
        String text;
        int col;
        if (fd == null) {
            text = I18n.get("fieldframe.ws.no_module");
            col = WorkshopArt.SUB;
        } else if (fd.getBool("missing", false)) {
            text = I18n.get("fieldframe.ws.missing");
            col = WorkshopArt.BAD;
        } else {
            String sev = fd.getString("severity", "IDLE");
            col = sev.equals("ERROR") ? WorkshopArt.BAD : sev.equals("WARN") || sev.equals("WARNING") ? WorkshopArt.WARN
                    : sev.equals("OK") || sev.equals("WORKING") ? WorkshopArt.OK : WorkshopArt.SUB;
            text = statusText(fd);
            double p = fd.getDouble("progress", -1);
            if (p > 0 && p < 1) {
                text += " " + Math.round(p * 100) + "%";
            }
        }
        int room = WorkshopLayout.TANK_X - 6 - WorkshopLayout.STATUS_X;
        String cut = font.width(text) <= room ? text : font.plainSubstrByWidth(text, room - font.width("...")).stripTrailing() + "...";
        g.drawString(font, cut, x, y, col, false);
        if (!cut.equals(text)) {
            hits.add(new Hit(x, y - 1, room, 10, null, List.of(Component.literal(text))));
        }
    }

    // ------------------------------------------------------------------ face tabs

    private int faceTabX(int i) {
        return leftPos + 1 + i * WorkshopLayout.FACE_TAB_PITCH;
    }

    private void faceTab(GuiGraphics g, int i, boolean selected) {
        int f = WorkshopLayout.FACE_ORDER[i];
        int x = faceTabX(i), y = topPos - 28, w = WorkshopLayout.FACE_TAB_W;
        int h = selected ? WorkshopLayout.FACE_TAB_H_SEL : WorkshopLayout.FACE_TAB_H;
        int bg = selected ? WorkshopArt.PANEL_BG : WorkshopArt.TAB_OFF_BG;
        int hi = selected ? WorkshopArt.PANEL_HI : WorkshopArt.TAB_OFF_HI;
        int lo = selected ? WorkshopArt.PANEL_LO : WorkshopArt.TAB_OFF_LO;
        g.fill(x + 2, y, x + w - 2, y + 1, WorkshopArt.EDGE);
        g.fill(x, y + 2, x + 1, y + h, WorkshopArt.EDGE);
        g.fill(x + w - 1, y + 2, x + w, y + h, WorkshopArt.EDGE);
        g.fill(x + 1, y + 1, x + 2, y + 2, WorkshopArt.EDGE);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, WorkshopArt.EDGE);
        g.fill(x + 2, y + 1, x + w - 2, y + h, bg);
        g.fill(x + 1, y + 2, x + w - 1, y + h, bg);
        g.fill(x + 2, y + 1, x + w - 3, y + 3, hi);
        g.fill(x + 1, y + 2, x + 3, y + h, hi);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h, lo);
        if (!selected) {
            g.fill(x, y + h - 1, x + w, y + h, WorkshopArt.EDGE);
        }
        FfData fd = faceData(f);
        ItemStack icon = iconFor(fd);
        int iy = y + (selected ? 7 : 6);
        g.renderItem(icon, x + 6, iy);
        if (!selected || fd == null) {
            g.fill(x + 6, iy, x + 22, iy + 16, fd == null ? 0x90202224 : 0x60202224);
        }
        if (fd != null && !fd.getBool("missing", false)) {
            String sev = fd.getString("severity", "IDLE");
            if (sev.equals("ERROR") || sev.startsWith("WARN")) {
                g.fill(x + w - 7, y + 4, x + w - 4, y + 7, sev.equals("ERROR") ? WorkshopArt.BAD : WorkshopArt.WARN);
            }
        }
        LocalFace lf = LocalFace.ALL[f];
        hits.add(new Hit(x, y, w, h - (selected ? 3 : 0), () -> selectFace(f), List.of(Component.literal(ConfiguratorScreen.sideName(lf)),
                Component.literal(ConfiguratorScreen.moduleName(fd)).withStyle(ChatFormatting.GRAY))));
    }

    private static ItemStack iconFor(FfData fd) {
        if (fd != null) {
            FfId id = FfId.tryParse(fd.getString("module", ""));
            var item = id == null ? null : FfContent.moduleItem(id);
            if (item != null) {
                return new ItemStack(item);
            }
            if (fd.getBool("missing", false)) {
                return new ItemStack(Items.BARRIER);
            }
        }
        return new ItemStack(FfContent.CHASSIS_ITEM.get());
    }

    // ------------------------------------------------------------------ side tabs

    private int openHeight(Tab t) {
        FfData fd = faceData();
        return switch (t) {
            case REDSTONE -> 20 + Math.max(1, count(fd, "SIGNAL")) * 24 + 26;
            case ROUTING -> 20 + Math.max(1, count(fd, "LANE")) * 24 + 4;
            case SETTINGS -> 20 + simpleConfigs(fd).size() * 24 + (hasOtherConfig(fd) ? 10 : 0) + 3 * 17 + 4;
            default -> t.openH;
        };
    }

    private int openWidth(Tab t) {
        if (t == Tab.BAYS) {
            return Math.max(t.openW, 7 + data.getList("upgrades").size() * 20 + 7);
        }
        return t.openW;
    }

    private static int count(FfData fd, String kindPart) {
        int n = 0;
        if (fd != null) {
            for (Object o : fd.getList("bindings")) {
                if (o instanceof FfData b && b.getString("kind", "").contains(kindPart)) {
                    n++;
                }
            }
        }
        return n;
    }

    private void sideTabs(GuiGraphics g, int mx, int my) {
        long now = now();
        int ly = topPos + WorkshopLayout.SIDE_TAB_TOP, ry = ly;
        for (Tab t : Tab.values()) {
            TabMotion m = motion.get(t);
            double f = m.fraction(now);
            int w = TabMotion.lerp(WorkshopLayout.SIDE_TAB_W, openWidth(t), f);
            int h = TabMotion.lerp(WorkshopLayout.SIDE_TAB_H, openHeight(t), f);
            int x = t.right ? leftPos + W - 1 : leftPos - w + 1;
            int y = t.right ? ry : ly;
            if (t.right) {
                ry += h + WorkshopLayout.SIDE_TAB_GAP;
            } else {
                ly += h + WorkshopLayout.SIDE_TAB_GAP;
            }
            tabX[t.ordinal()] = x;
            tabY[t.ordinal()] = y;
            tabW[t.ordinal()] = w;
            tabH[t.ordinal()] = h;
            tabBody(g, x, y, w, h, t.colour, t.right);
            int ix = t.right ? x + 4 : x + w - 20;
            tabIcon(g, t, ix, y + 3);
            // the closed tab (or the header row when open) toggles it
            hits.add(new Hit(x, y, w, Math.min(h, 20), () -> toggle(t), m.isOpen() ? List.of() : List.of(Component.literal(t.title()))));
            double a = m.contentAlpha(now);
            if (a <= 0.02) {
                continue;
            }
            g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
            int alpha = (int) Math.round(a * 255);
            g.drawString(font, t.title(), x + (t.right ? 23 : 6), y + 7, WorkshopArt.withAlpha(WorkshopArt.HEAD, alpha), true);
            if (m.isOpen() && !m.moving(now)) {
                content(g, t, x, y, openWidth(t), openHeight(t), alpha, a > 0.6);
            } else {
                content(g, t, x, y, openWidth(t), openHeight(t), alpha, false);
                // content drawn while moving registers no clicks
                hits.removeIf(hit -> hit.click() != null && hit.x() >= x && hit.y() >= y + 20 && hit.x() < x + w && hit.y() < y + h);
            }
            g.disableScissor();
        }
    }

    private static void tabBody(GuiGraphics g, int x, int y, int w, int h, int col, boolean right) {
        int e = WorkshopArt.EDGE, hi = WorkshopArt.shade(col, 1.35), lo = WorkshopArt.shade(col, 0.6), seam = WorkshopArt.shade(col, 0.45);
        int ox = right ? x + w - 1 : x;            // outer edge column
        int ix = right ? x : x + w - 1;            // edge joined to the panel
        int inX0 = right ? x : x + 1, inX1 = right ? x + w - 1 : x + w;
        g.fill(inX0, y + 1, inX1, y + h - 1, col);
        g.fill(right ? x : x + 2, y, right ? x + w - 2 : x + w, y + 1, e);
        g.fill(right ? x : x + 2, y + h - 1, right ? x + w - 2 : x + w, y + h, e);
        g.fill(ox, y + 2, ox + 1, y + h - 2, e);
        int cx = right ? x + w - 2 : x + 1;
        g.fill(cx, y + 1, cx + 1, y + 2, e);
        g.fill(cx, y + h - 2, cx + 1, y + h - 1, e);
        g.fill(right ? x + 1 : x + 2, y + 1, right ? x + w - 2 : x + w - 1, y + 2, hi);
        g.fill(right ? x + 1 : x + 2, y + h - 2, right ? x + w - 2 : x + w - 1, y + h - 1, lo);
        if (right) {
            g.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, lo);
        } else {
            g.fill(x + 1, y + 2, x + 2, y + h - 2, hi);
        }
        g.fill(ix, y + 1, ix + 1, y + h - 1, seam);
    }

    private void tabIcon(GuiGraphics g, Tab t, int x, int y) {
        switch (t) {
            case INFO -> g.renderItem(new ItemStack(Items.BOOK), x, y);
            case ENERGY -> GuiTex.blit(g, GuiTex.get("ws_bolt", 16, 16, () -> WorkshopArt.BOLT), x, y, 0, 0, 16, 16, 16, 16);
            case CHASSIS -> g.renderItem(new ItemStack(FfContent.CHASSIS_ITEM.get()), x, y);
            case REDSTONE -> g.renderItem(new ItemStack(Items.REDSTONE), x, y);
            case ROUTING -> g.renderItem(new ItemStack(Items.HOPPER), x, y);
            case SETTINGS -> g.renderItem(new ItemStack(FfContent.CONFIGURATOR.get()), x, y);
            case BAYS -> {
                var up = FfContent.upgradeItem(FfId.ff("overclock_upgrade"));
                g.renderItem(up != null ? new ItemStack(up) : new ItemStack(Items.COMPARATOR), x, y);
            }
        }
    }

    private void content(GuiGraphics g, Tab t, int x, int y, int w, int h, int alpha, boolean items) {
        switch (t) {
            case INFO -> info(g, x, y, w, h, alpha);
            case ENERGY -> energy(g, x, y, w, alpha);
            case CHASSIS -> chassis(g, x, y, w, alpha, items);
            case REDSTONE -> redstone(g, x, y, w, alpha);
            case ROUTING -> routing(g, x, y, w, alpha);
            case SETTINGS -> settings(g, x, y, w, alpha);
            case BAYS -> bays(g, x, y, w, alpha, items);
        }
    }

    private int a(int col, int alpha) {
        return WorkshopArt.withAlpha(col, ((col >>> 24) * alpha) / 255);
    }

    private void info(GuiGraphics g, int x, int y, int w, int h, int al) {
        FfData fd = faceData();
        int tx = x + 6, tw = w - 12, ty = y + 22;
        if (fd == null) {
            g.drawWordWrap(font, Component.translatable("fieldframe.ws.no_module.hint"), tx, ty, tw, a(WorkshopArt.DIM, al));
            button(g, tx, y + h - 20, tw, I18n.get("fieldframe.ws.full_editor"), 0xFF2E5878, al, this::openFullEditor);
            return;
        }
        FfId id = FfId.tryParse(fd.getString("module", ""));
        if (id != null && I18n.exists("module." + id.namespace() + "." + id.path() + ".desc")) {
            var lines = font.split(Component.translatable("module." + id.namespace() + "." + id.path() + ".desc"), tw);
            for (int i = 0; i < Math.min(4, lines.size()); i++) {
                g.drawString(font, lines.get(i), tx, ty, a(WorkshopArt.DIM, al), false);
                ty += 9;
            }
            ty += 4;
        }
        g.drawString(font, font.plainSubstrByWidth(statusText(fd), tw), tx, ty, a(0xFFA8F0A0, al), false);
        ty += 10;
        long used = fd.getLong("rfUsed", 0), made = fd.getLong("rfMade", 0);
        if (used > 0 || made > 0) {
            g.drawString(font, made > 0 ? I18n.get("fieldframe.gui.rf_make", made) : I18n.get("fieldframe.gui.rf_use", used), tx, ty, a(WorkshopArt.DIM, al), false);
            ty += 10;
        }
        ty += 3;
        g.drawString(font, I18n.get("fieldframe.ws.recent"), tx, ty, a(WorkshopArt.HEAD, al), false);
        ty += 10;
        List<Object> trace = data.getList("trace");
        int room = Math.max(0, (y + h - 40 - ty) / 9);
        for (int i = Math.max(0, trace.size() - room); i < trace.size(); i++) {
            g.drawString(font, font.plainSubstrByWidth(String.valueOf(trace.get(i)), tw), tx, ty, a(0xFFB8B8B8, al), false);
            ty += 9;
        }
        button(g, tx, y + h - 36, tw, I18n.get("fieldframe.ws.how"), 0xFF2E5878, al, this::openManual);
        button(g, tx, y + h - 19, tw, I18n.get("fieldframe.ws.full_editor"), 0xFF2E5878, al, this::openFullEditor);
    }

    private void energy(GuiGraphics g, int x, int y, int w, int al) {
        long used = 0;
        for (int f = 0; f < 6; f++) {
            FfData fd = faceData(f);
            if (fd != null) {
                used += fd.getLong("rfUsed", 0);
            }
        }
        String[][] rows = {
                {I18n.get("fieldframe.ws.stored"), data.getLong("energy", 0) + " RF"},
                {I18n.get("fieldframe.ws.holds"), data.getLong("energyCap", 0) + " RF"},
                {I18n.get("fieldframe.ws.using"), used + " RF/t"},
                {I18n.get("fieldframe.ws.max_in"), data.getLong("energyIn", 0) + " RF/t"}};
        for (int i = 0; i < rows.length; i++) {
            int ry = y + 22 + i * 10;
            g.drawString(font, rows[i][0], x + 6, ry, a(WorkshopArt.DIM, al), false);
            g.drawString(font, rows[i][1], x + w - 6 - font.width(rows[i][1]), ry, a(WorkshopArt.WHITE, al), false);
        }
        g.drawString(font, I18n.get("fieldframe.ws.last30"), x + 6, y + 66, a(WorkshopArt.HEAD, al), false);
        int gx = x + 6, gy = y + 77, gw = w - 12, gh = 20;
        g.fill(gx - 1, gy - 1, gx + gw, gy, a(WorkshopArt.SLOT_DARK, al));
        g.fill(gx - 1, gy - 1, gx, gy + gh, a(WorkshopArt.SLOT_DARK, al));
        g.fill(gx, gy + gh, gx + gw + 1, gy + gh + 1, a(WorkshopArt.SLOT_LIGHT, al));
        g.fill(gx + gw, gy, gx + gw + 1, gy + gh + 1, a(WorkshopArt.SLOT_LIGHT, al));
        g.fill(gx, gy, gx + gw, gy + gh, a(0xFF1A0E0E, al));
        long cap = Math.max(1, data.getLong("energyCap", 0));
        long now = now();
        int bars = 20, bw = Math.max(1, (gw - 2) / bars);
        long last = energyLog.isEmpty() ? data.getLong("energy", 0) : energyLog.peekFirst()[1];
        var it = energyLog.iterator();
        long[] next = it.hasNext() ? it.next() : null;
        for (int b = 0; b < bars; b++) {
            long until = now - 30_000 + (b + 1) * 1_500L;
            while (next != null && next[0] <= until) {
                last = next[1];
                next = it.hasNext() ? it.next() : null;
            }
            int bh = (int) Math.round((gh - 2) * Math.min(1, last / (double) cap));
            int bx = gx + 1 + b * bw;
            if (bh > 0) {
                g.fill(bx, gy + gh - 1 - bh, bx + bw - 1, gy + gh - 1, a(0xFFC8281C, al));
                g.fill(bx, gy + gh - 1 - bh, bx + bw - 1, gy + gh - bh, a(0xFFFF8A60, al));
            }
        }
    }

    /** Chassis net: top above front; left, front, right, back across; bottom below. Click a side to show it. */
    private static final int[][] NET = {{1, 0}, {1, 2}, {1, 1}, {3, 1}, {0, 1}, {2, 1}};

    private void chassis(GuiGraphics g, int x, int y, int w, int al, boolean items) {
        int ox = x + (w - 72) / 2, oy = y + 22;
        for (int f = 0; f < 6; f++) {
            int lf = f;
            LocalFace face0 = LocalFace.ALL[f];
            int[] cell = NET[netIndex(face0)];
            int cx = ox + cell[0] * 18 + 1, cy = oy + cell[1] * 18 + 1;
            inset(g, cx, cy, 16, 16, a(WorkshopArt.SLOT_FILL, al));
            FfData fd = faceData(f);
            if (items) {
                g.renderItem(iconFor(fd), cx, cy);
                if (fd == null) {
                    g.fill(cx, cy, cx + 16, cy + 16, 0x90202224);
                }
            }
            if (f == face) {
                g.renderOutline(cx - 1, cy - 1, 18, 18, a(WorkshopArt.WHITE, al));
            }
            hits.add(new Hit(cx - 1, cy - 1, 18, 18, () -> selectFace(lf), List.of(Component.literal(ConfiguratorScreen.sideName(face0)),
                    Component.literal(ConfiguratorScreen.moduleName(fd)).withStyle(ChatFormatting.GRAY))));
        }
    }

    private static int netIndex(LocalFace f) {
        return switch (f) {
            case TOP -> 0;
            case BOTTOM -> 1;
            case FRONT -> 2;
            case BACK -> 3;
            case LEFT -> 4;
            case RIGHT -> 5;
        };
    }

    private void redstone(GuiGraphics g, int x, int y, int w, int al) {
        FfData fd = faceData();
        int ty = y + 20;
        boolean any = false;
        if (fd != null) {
            for (Object o : fd.getList("bindings")) {
                if (!(o instanceof FfData b) || !b.getString("kind", "").startsWith("SIGNAL")) {
                    continue;
                }
                any = true;
                String key = b.getString("key", "");
                int idx = b.getInt("index", -1);
                boolean optional = b.getBool("optional", false);
                g.drawString(font, font.plainSubstrByWidth(ConfiguratorScreen.roleName(key, b.getString("kind", "")), w - 12), x + 6, ty + 2, a(WorkshopArt.DIM, al), false);
                for (int c = 0; c < 8; c++) {
                    int bx = x + 8 + c * 11, by = ty + 12;
                    boolean sel = c == idx;
                    g.fill(bx, by, bx + 9, by + 10, a(WorkshopArt.EDGE, al));
                    g.fill(bx + 1, by + 1, bx + 8, by + 9, a(sel ? 0xFFC03028 : 0xFF2A1010, al));
                    if (sel) {
                        g.fill(bx + 1, by + 1, bx + 8, by + 2, a(0xFFFF7060, al));
                    }
                    int ch = c;
                    hits.add(new Hit(bx, by, 10, 10, () -> act("set_binding", key, sel && optional ? -1 : ch, null),
                            List.of(Component.translatable("fieldframe.gui.signal_n", c + 1),
                                    Component.translatable(sel ? "fieldframe.ws.click_clear" : "fieldframe.ws.click_pick").withStyle(ChatFormatting.DARK_AQUA))));
                }
                ty += 24;
            }
        }
        if (!any) {
            g.drawString(font, I18n.get("fieldframe.ws.no_signals"), x + 6, ty + 2, a(WorkshopArt.DIM, al), false);
            ty += 24;
        }
        String current = data.getString("comparator", dev.fieldframe.core.chassis.ChassisCore.DEFAULT_COMPARATOR);
        g.drawString(font, I18n.get("fieldframe.gui.comparator"), x + 6, ty + 2, a(WorkshopArt.DIM, al), false);
        List<Option> options = new ArrayList<>();
        List<String> sources = dev.fieldframe.core.chassis.ChassisCore.COMPARATOR_SOURCES;
        for (int i = 0; i < sources.size(); i++) {
            options.add(new Option(ConfiguratorScreen.comparatorText(sources.get(i)), ConfiguratorScreen.comparatorColour(sources.get(i)), i));
        }
        picker(g, x + 6, ty + 12, w - 12, ConfiguratorScreen.comparatorColour(current), ConfiguratorScreen.comparatorText(current), al,
                options, v -> act("set_comparator", "", v, null));
    }

    private void routing(GuiGraphics g, int x, int y, int w, int al) {
        FfData fd = faceData();
        int ty = y + 20;
        boolean any = false;
        if (fd != null) {
            for (Object o : fd.getList("bindings")) {
                if (!(o instanceof FfData b) || !b.getString("kind", "").endsWith("LANE")) {
                    continue;
                }
                any = true;
                String key = b.getString("key", "");
                String kind = b.getString("kind", "");
                int idx = b.getInt("index", -1);
                boolean fluid = kind.startsWith("FLUID");
                g.drawString(font, font.plainSubstrByWidth(ConfiguratorScreen.roleName(key, kind), w - 12), x + 6, ty, a(WorkshopArt.DIM, al), false);
                List<Option> options = new ArrayList<>();
                if (b.getBool("optional", false)) {
                    options.add(new Option(I18n.get("fieldframe.gui.not_connected"), Schematic.STEEL, -1));
                }
                for (int i = 0; i < 3; i++) {
                    options.add(new Option(fluid ? ConfiguratorScreen.tankName(i) : ConfiguratorScreen.laneName(i), ConfiguratorScreen.LANE_COLOURS[i], i));
                }
                String text = idx < 0 ? I18n.get("fieldframe.gui.not_connected") : fluid ? ConfiguratorScreen.tankName(idx) : ConfiguratorScreen.laneName(idx);
                int col = idx >= 0 && idx < 3 ? ConfiguratorScreen.LANE_COLOURS[idx] : Schematic.STEEL;
                picker(g, x + 6, ty + 10, w - 12, col, text, al, options, v -> act("set_binding", key, v, null));
                ty += 24;
            }
        }
        if (!any) {
            g.drawString(font, I18n.get("fieldframe.ws.no_routes"), x + 6, ty + 2, a(WorkshopArt.DIM, al), false);
        }
    }

    private static List<FfData> simpleConfigs(FfData fd) {
        List<FfData> out = new ArrayList<>();
        if (fd != null) {
            for (Object o : fd.getList("config")) {
                if (o instanceof FfData c && List.of("BOOL", "ENUM", "INT").contains(c.getString("type", ""))) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static boolean hasOtherConfig(FfData fd) {
        return fd != null && simpleConfigs(fd).size() < fd.getList("config").size();
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

    private void settings(GuiGraphics g, int x, int y, int w, int al) {
        FfData fd = faceData();
        int ty = y + 20, cw = w - 12, cx = x + 6;
        for (FfData c : simpleConfigs(fd)) {
            String key = c.getString("key", "");
            g.drawString(font, font.plainSubstrByWidth(I18n.get("fieldframe.config." + key), cw), cx, ty, a(WorkshopArt.DIM, al), false);
            FfData config = currentConfig(fd);
            switch (c.getString("type", "")) {
                case "BOOL" -> {
                    boolean v = c.getBool("value", false);
                    button(g, cx, ty + 10, cw, I18n.get(v ? "fieldframe.ws.on" : "fieldframe.ws.off"), v ? 0xFF3E6A3E : 0xFF4A4A4A, al,
                            () -> act("set_config", "", 0, config.copy().putBool(key, !v)));
                }
                case "ENUM" -> {
                    List<String> opts = ConfiguratorScreen.strings(c.getList("options"));
                    String v = c.getString("value", opts.isEmpty() ? "" : opts.get(0));
                    List<Option> options = new ArrayList<>();
                    for (int i = 0; i < opts.size(); i++) {
                        options.add(new Option(I18n.get("fieldframe.option." + opts.get(i)), Schematic.STEEL, i));
                    }
                    picker(g, cx, ty + 10, cw, Schematic.STEEL, I18n.get("fieldframe.option." + v), al, options,
                            i -> act("set_config", "", 0, config.copy().putString(key, opts.get(i))));
                }
                default -> {
                    long v = c.getLong("value", 0), min = c.getLong("min", 0), max = c.getLong("max", 0);
                    button(g, cx, ty + 10, 12, "-", 0xFF4E4466, al, () -> act("set_config", "", 0, config.copy().putLong(key, Math.max(min, v - (Screen.hasShiftDown() ? 10 : 1)))));
                    button(g, cx + cw - 12, ty + 10, 12, "+", 0xFF4E4466, al, () -> act("set_config", "", 0, config.copy().putLong(key, Math.min(max, v + (Screen.hasShiftDown() ? 10 : 1)))));
                    String s = String.valueOf(v);
                    g.drawString(font, s, cx + cw / 2 - font.width(s) / 2, ty + 13, a(WorkshopArt.WHITE, al), false);
                }
            }
            ty += 24;
        }
        if (hasOtherConfig(fd)) {
            g.drawString(font, font.plainSubstrByWidth(I18n.get("fieldframe.ws.more_in_editor"), cw), cx, ty, a(WorkshopArt.SUB, al), false);
            ty += 10;
        }
        boolean quiet = data.getBool("quiet", false);
        button(g, cx, ty, cw, I18n.get(quiet ? "fieldframe.ws.sounds_off" : "fieldframe.ws.sounds_on"), 0xFF4E4466, al,
                () -> act("set_quiet", "", quiet ? 0 : 1, null));
        boolean paused = data.getBool("paused", false);
        button(g, cx, ty + 17, cw, I18n.get(paused ? "fieldframe.ws.resume" : "fieldframe.ws.pause"), 0xFF4E4466, al,
                () -> act("pause", "", paused ? 0 : 1, null));
        if (fd != null) {
            boolean armed = removeArmedAt >= 0 && now() - removeArmedAt < 3000;
            button(g, cx, ty + 34, cw, I18n.get(armed ? "fieldframe.ws.remove_sure" : "fieldframe.ws.remove"), 0xFF8A3A30, al, () -> {
                if (removeArmedAt >= 0 && now() - removeArmedAt < 3000) {
                    removeArmedAt = -1;
                    act("remove_module", "", 0, null);
                } else {
                    removeArmedAt = now();
                }
            });
        }
    }

    private void bays(GuiGraphics g, int x, int y, int w, int al, boolean items) {
        List<Object> ups = data.getList("upgrades");
        for (int i = 0; i < ups.size(); i++) {
            int bx = x + 7 + i * 20, by = y + 23;
            inset(g, bx, by, 16, 16, a(WorkshopArt.SLOT_FILL, al));
            String id = String.valueOf(ups.get(i));
            FfId fid = id.isEmpty() ? null : FfId.tryParse(id);
            if (fid == null) {
                hits.add(new Hit(bx - 1, by - 1, 18, 18, null, List.of(Component.translatable("fieldframe.ws.bay_empty"))));
                continue;
            }
            var item = FfContent.upgradeItem(fid);
            if (items && item != null) {
                g.renderItem(new ItemStack(item), bx, by);
            }
            int bay = i;
            String name = I18n.get("upgrade." + fid.namespace() + "." + fid.path());
            hits.add(new Hit(bx - 1, by - 1, 18, 18, () -> act("remove_upgrade", "", bay, null), List.of(Component.literal(name),
                    Component.translatable("fieldframe.ws.click_remove").withStyle(ChatFormatting.DARK_AQUA))));
        }
        double oc = data.getDouble("overclock", 0);
        String effect = ups.isEmpty() ? I18n.get("fieldframe.ws.no_bays")
                : oc > 0 ? I18n.get("fieldframe.ws.overclock", String.format(Locale.ROOT, "%.1f", 1 + oc), Math.round(data.getDouble("heat", 0)))
                : I18n.get("fieldframe.ws.bays_hint");
        g.drawString(font, font.plainSubstrByWidth(effect, w - 12), x + 6, y + 44, a(data.getBool("overheated", false) ? WorkshopArt.BAD : WorkshopArt.DIM, al), false);
    }

    // ------------------------------------------------------------------ widgets (drawn, not Minecraft widgets)

    private void button(GuiGraphics g, int x, int y, int w, String text, int col, int al, Runnable click) {
        int h = 14;
        g.fill(x, y, x + w, y + h, a(WorkshopArt.EDGE, al));
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, a(col, al));
        g.fill(x + 1, y + 1, x + w - 1, y + 2, a(WorkshopArt.shade(col, 1.45), al));
        g.fill(x + 1, y + 1, x + 2, y + h - 1, a(WorkshopArt.shade(col, 1.45), al));
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, a(WorkshopArt.shade(col, 0.6), al));
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, a(WorkshopArt.shade(col, 0.6), al));
        String s = font.plainSubstrByWidth(text, w - 4);
        g.drawString(font, s, x + (w - font.width(s)) / 2, y + 3, a(WorkshopArt.WHITE, al), true);
        hits.add(new Hit(x, y, w, h, click, List.of()));
    }

    private void picker(GuiGraphics g, int x, int y, int w, int chip, String text, int al, List<Option> options, IntConsumer pick) {
        int h = 11;
        g.fill(x, y, x + w, y + h, a(0xFF1C1C1C, al));
        g.fill(x, y, x + w, y + 1, a(0xFF0A0A0A, al));
        g.fill(x, y, x + 1, y + h, a(0xFF0A0A0A, al));
        g.fill(x, y + h - 1, x + w, y + h, a(0xFF8B8B8B, al));
        g.fill(x + w - 1, y, x + w, y + h, a(0xFF8B8B8B, al));
        g.fill(x + 1, y + 1, x + 4, y + 10, a(chip, al));
        g.drawString(font, font.plainSubstrByWidth(text, w - 18), x + 7, y + 2, a(0xFFF0F0F0, al), false);
        for (int i = 0; i < 3; i++) {
            g.fill(x + w - 8 + i, y + 4 + i, x + w - 3 - i, y + 5 + i, a(0xFFD0D0D0, al));
        }
        Dropdown dd = new Dropdown(x, y + h, w, options, pick);
        hits.add(new Hit(x, y, w, h, () -> dropdown = dropdown != null && dropdown.x() == x && dropdown.y() == dd.y() ? null : dd,
                List.of(Component.translatable("fieldframe.gui.choose").withStyle(ChatFormatting.DARK_AQUA))));
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        FfData fd = faceData();
        String side = I18n.get("fieldframe.ws.side", ConfiguratorScreen.sideName(LocalFace.ALL[face]));
        int sideW = font.width(side);
        g.drawString(font, font.plainSubstrByWidth(ConfiguratorScreen.moduleName(fd), W - 22 - sideW), 8, 6, WorkshopArt.TITLE, false);
        g.drawString(font, side, W - 8 - sideW, 6, WorkshopArt.SUB, false);
        g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, WorkshopArt.INV_LABEL, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        super.render(g, mx, my, partial);
        renderTooltip(g, mx, my);
        if (dropdown != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            Dropdown dd = dropdown;
            int h = dd.height();
            g.fill(dd.x(), dd.y(), dd.x() + dd.w(), dd.y() + h, 0xFF000000);
            g.fill(dd.x() + 1, dd.y() + 1, dd.x() + dd.w() - 1, dd.y() + h - 1, 0xFF202326);
            for (int i = 0; i < dd.options().size(); i++) {
                Option o = dd.options().get(i);
                int oy = dd.y() + 1 + i * Dropdown.ROW;
                if (mx >= dd.x() && mx < dd.x() + dd.w() && my >= oy && my < oy + Dropdown.ROW) {
                    g.fill(dd.x() + 1, oy, dd.x() + dd.w() - 1, oy + Dropdown.ROW, 0xFF3A4046);
                }
                g.fill(dd.x() + 2, oy + 1, dd.x() + 5, oy + 10, o.colour());
                g.drawString(font, font.plainSubstrByWidth(o.text(), dd.w() - 10), dd.x() + 8, oy + 2, 0xFFF0F0F0, false);
            }
            g.pose().popPose();
            return;
        }
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.over(mx, my)) {
                    if (!h.tip().isEmpty()) {
                        g.renderComponentTooltip(font, h.tip(), mx, my);
                    }
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
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
        if (button == 0 && menu.getCarried().isEmpty()) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.over(mx, my) && h.click() != null) {
                    h.click().run();
                    return true;
                }
            }
        }
        if (overTab(mx, my)) {
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean overTab(double mx, double my) {
        for (int i = 0; i < tabX.length; i++) {
            if (mx >= tabX[i] && mx < tabX[i] + tabW[i] && my >= tabY[i] && my < tabY[i] + tabH[i]) {
                return true;
            }
        }
        return my >= topPos - 28 && my < topPos && mx >= leftPos && mx < leftPos + 6 * WorkshopLayout.FACE_TAB_PITCH;
    }

    @Override
    protected boolean hasClickedOutside(double mx, double my, int xo, int yo, int button) {
        return super.hasClickedOutside(mx, my, xo, yo, button) && !overTab(mx, my);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_TAB) {
            int i = 0;
            for (int k = 0; k < 6; k++) {
                if (WorkshopLayout.FACE_ORDER[k] == face) {
                    i = k;
                }
            }
            int step = Screen.hasShiftDown() ? 5 : 1;
            selectFace(WorkshopLayout.FACE_ORDER[(i + step) % 6]);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && dropdown != null) {
            dropdown = null;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    /** Test hook: open or close a side tab by name. */
    public void testToggle(String tab) {
        toggle(Tab.valueOf(tab.toUpperCase(Locale.ROOT)));
    }

    /** Test hook: whether a side tab is open. */
    public boolean testIsOpen(String tab) {
        return motion.get(Tab.valueOf(tab.toUpperCase(Locale.ROOT))).isOpen();
    }

    /** Test hook: pick a side as the face tabs do. */
    public void testSelectFace(int f) {
        selectFace(f);
    }

    /** Test hook: the Info tab's Full editor button. */
    public void testFullEditor() {
        openFullEditor();
    }

    /** Test hook: the side shown. */
    public int shownFace() {
        return face;
    }
}
