package dev.fieldframe.neoforge.client.manual;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualModel;
import dev.fieldframe.api.manual.ManualPageTypes;
import dev.fieldframe.api.module.BindingSpec;
import dev.fieldframe.api.module.ConfigField;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.module.TunableSpec;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.api.client.ManualClientApi;
import dev.fieldframe.neoforge.api.client.ManualPageRenderer;
import dev.fieldframe.neoforge.client.Schematic;
import dev.fieldframe.neoforge.recipe.RecipeBridge;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

/** The shipped page types, registered through the public {@link ManualClientApi}. */
public final class BuiltinPages {
    private BuiltinPages() {
    }

    private static boolean done;

    public static synchronized void register() {
        if (done) {
            return;
        }
        done = true;
        ManualClientApi.registerRenderer(ManualPageTypes.TEXT, (ctx, p, x, y, w) -> ctx.text(localize(p.data().getString("text", "")), x, y, w, Schematic.INK));
        ManualClientApi.registerRenderer(ManualPageTypes.WARNING, (ctx, p, x, y, w) -> {
            int h = ctx.text(localize(p.data().getString("text", "")), x + 14, y + 3, w - 16, 0xFFF3D27A);
            ctx.graphics().fill(x, y, x + 3, y + h + 6, 0xFFE3B341);
            ctx.graphics().text(ctx.font(), "!", x + 6, y + 3, 0xFFE3B341, false);
            return h + 8;
        });
        ManualClientApi.registerRenderer(ManualPageTypes.MODULE_SPEC, BuiltinPages::moduleSpec);
        ManualClientApi.registerRenderer(ManualPageTypes.RECIPES, BuiltinPages::recipes);
        ManualClientApi.registerRenderer(ManualPageTypes.FACE_MAP, BuiltinPages::faceMap);
        ManualClientApi.registerRenderer(ManualPageTypes.TABLE, BuiltinPages::table);
        ManualClientApi.registerRenderer(ManualPageTypes.TUTORIAL, BuiltinPages::tutorial);
        ManualClientApi.registerRenderer(ManualPageTypes.STATUS_CODES, BuiltinPages::statusCodes);
        ManualClientApi.registerRenderer(ManualPageTypes.TEST_BENCH, new TestBenchPage());
        ManualClientApi.registerRenderer(ManualPageTypes.BLUEPRINT, new BlueprintPage());
    }

    public static String localize(String s) {
        if (s.startsWith("@")) {
            return I18n.get(s.substring(1));
        }
        return s;
    }

    static ItemStack icon(String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Identifier rl = Identifier.tryParse(id);
        var item = rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item == null ? ItemStack.EMPTY : dev.fieldframe.neoforge.client.Schematic.safeStack(item);
    }

    private static int moduleSpec(ManualPageRenderer.Context ctx, ManualModel.Page p, int x, int y, int w) {
        FfId id = FfId.tryParse(p.data().getString("module", ""));
        ModuleDefinition def = id == null ? null : FieldframeRegistry.module(id);
        if (def == null) {
            return ctx.text(I18n.get("fieldframe.manual.unknown_module", String.valueOf(id)), x, y, w, 0xFFE0524B);
        }
        int cy = y;
        var item = FfContent.moduleItem(def.id());
        if (item != null) {
            ctx.item(dev.fieldframe.neoforge.client.Schematic.safeStack(item), x, cy);
        }
        ctx.graphics().text(ctx.font(), I18n.get(def.translationKey()), x + 20, cy + 1, Schematic.COPPER, false);
        ctx.graphics().text(ctx.font(), I18n.get("fieldframe.category." + def.category().key()) + " · " + def.stage().name().toLowerCase(Locale.ROOT)
                + " · " + I18n.get("fieldframe.manual.phase", def.phase().name().toLowerCase(Locale.ROOT)), x + 20, cy + 10, Schematic.INK_DIM, false);
        cy += 22;
        if (FieldframeRegistry.isDisabled(def.id())) {
            cy += ctx.text(I18n.get("fieldframe.manual.disabled_by_pack"), x, cy, w, 0xFFE0524B) + 2;
        }
        cy += ctx.text(localize("@" + def.translationKey() + ".desc"), x, cy, w, Schematic.INK) + 4;
        StringBuilder faces = new StringBuilder();
        for (LocalFace f : def.allowedFaces()) {
            faces.append(f.letter);
        }
        cy += ctx.text("**" + I18n.get("fieldframe.manual.faces") + "** " + (def.allowedFaces().size() == 6 ? I18n.get("fieldframe.manual.any_face") : faces)
                + (def.exposures().isEmpty() ? "" : "   **" + I18n.get("fieldframe.manual.exposes") + "** " + def.exposures().toString().toLowerCase(Locale.ROOT)), x, cy, w, Schematic.INK) + 3;
        if (!def.bindings().isEmpty()) {
            ctx.graphics().text(ctx.font(), I18n.get("fieldframe.gui.bindings"), x, cy, Schematic.COPPER, false);
            cy += 10;
            for (BindingSpec b : def.bindings()) {
                String line = "• " + b.key() + " — " + I18n.get("fieldframe.binding_kind." + b.kind().name().toLowerCase(Locale.ROOT))
                        + ", default " + BindingSpec.label(b.kind(), b.defaultIndex()) + (b.optional() ? " (optional)" : "");
                ctx.graphics().text(ctx.font(), ctx.font().plainSubstrByWidth(line, w), x, cy, Schematic.INK, false);
                cy += 9;
            }
            cy += 3;
        }
        if (!def.config().isEmpty()) {
            ctx.graphics().text(ctx.font(), I18n.get("fieldframe.gui.settings"), x, cy, Schematic.COPPER, false);
            cy += 10;
            for (ConfigField f : def.config()) {
                String range = switch (f.type()) {
                    case INT -> f.min() + "–" + f.max() + ", default " + f.defaultNumber();
                    case ENUM -> String.join(" / ", f.options());
                    case BOOL -> "on/off";
                    default -> f.type().name().toLowerCase(Locale.ROOT);
                };
                ctx.graphics().text(ctx.font(), ctx.font().plainSubstrByWidth("• " + I18n.get("fieldframe.config." + f.key()) + ": " + range, w), x, cy, Schematic.INK, false);
                cy += 9;
            }
            cy += 3;
        }
        if (!def.tunables().isEmpty()) {
            ctx.graphics().text(ctx.font(), I18n.get("fieldframe.manual.tunables"), x, cy, Schematic.COPPER, false);
            cy += 10;
            for (TunableSpec t : def.tunables().values()) {
                double eff = FieldframeRegistry.tunable(def, t.key());
                ctx.graphics().text(ctx.font(), ctx.font().plainSubstrByWidth("• " + t.key() + " = " + fmt(eff) + " " + t.unit()
                        + (eff != t.defaultValue() ? " (pack: default " + fmt(t.defaultValue()) + ")" : ""), w), x, cy, Schematic.INK, false);
                cy += 9;
            }
        }
        return cy - y;
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    /** Live recipe panel: always reads the effective recipes of the connected server (after datapacks/scripts). */
    private static int recipes(ManualPageRenderer.Context ctx, ManualModel.Page p, int x, int y, int w) {
        Minecraft mc = Minecraft.getInstance();
        FfId family = FfId.tryParse(p.data().getString("family", ""));
        if (family == null) {
            FfId mod = FfId.tryParse(p.data().getString("module", ""));
            ModuleDefinition def = mod == null ? null : FieldframeRegistry.module(mod);
            family = def == null ? null : def.processFamily();
        }
        if (family == null) {
            return ctx.text(I18n.get("fieldframe.manual.no_family"), x, y, w, 0xFFE0524B);
        }
        if (mc.level == null) {
            return ctx.text(I18n.get("fieldframe.manual.recipes_need_world"), x, y, w, Schematic.INK_DIM);
        }
        List<ProcessRecipe> list = RecipeBridge.forLevel(mc.level).recipes(family);
        int cy = y;
        ctx.graphics().text(ctx.font(), I18n.get("fieldframe.manual.recipes_title", family.toString(), list.size()), x, cy, Schematic.COPPER, false);
        cy += 12;
        if (list.isEmpty()) {
            return cy - y + ctx.text(I18n.get("fieldframe.manual.no_recipes"), x, cy, w, Schematic.INK_DIM);
        }
        int limit = p.data().getIntClamped("limit", 8, 1, 40);
        int offset = (int) ((ctx.ticks() / 60) % Math.max(1, (list.size() + limit - 1) / limit)) * limit;
        if (p.data().getBool("static", false)) {
            offset = 0;
        }
        for (int i = offset; i < Math.min(list.size(), offset + limit); i++) {
            ProcessRecipe r = list.get(i);
            int rx = x;
            for (ProcessRecipe.ItemInput in : r.itemInputs()) {
                ItemStack shown = in.examples().isEmpty() ? ItemStack.EMPTY
                        : McItemVariant.toStack(in.examples().get((int) ((ctx.ticks() / 20) % in.examples().size())), in.count());
                ctx.graphics().text(ctx.font(), (in.consumed() ? "" : "⚙") + in.count() + "× " + ctx.font().plainSubstrByWidth(in.display(), 60), rx, cy + 4, in.consumed() ? Schematic.INK : 0xFF5AA7D6, false);
                rx += Math.min(76, ctx.font().width(in.count() + "× " + in.display()) + 6);
                if (!shown.isEmpty()) {
                    ctx.item(shown, rx, cy);
                }
            }
            if (r.fluidInput() != null) {
                ctx.graphics().text(ctx.font(), "+" + r.fluidInput().amount() + "mB " + r.fluidInput().display(), rx, cy + 4, 0xFF5AA7D6, false);
                rx += ctx.font().width("+" + r.fluidInput().amount() + "mB " + r.fluidInput().display()) + 4;
            }
            ctx.graphics().text(ctx.font(), "→", rx, cy + 4, Schematic.COPPER, false);
            rx += 10;
            for (ProcessRecipe.ItemResult res : r.itemResults()) {
                ItemStack s = McItemVariant.toStack(res.item(), res.count());
                ctx.item(s, rx, cy);
                ctx.graphics().itemDecorations(ctx.font(), s, rx, cy);
                if (res.chance() < 1) {
                    ctx.graphics().text(ctx.font(), Math.round(res.chance() * 100) + "%", rx + 1, cy + 17, Schematic.INK_DIM, false);
                }
                rx += 20;
            }
            if (r.fluidResult() != null) {
                ctx.graphics().text(ctx.font(), r.fluidResult().amount() + "mB " + r.fluidResult().fluid().id().path(), rx, cy + 4, 0xFF5AA7D6, false);
            }
            ctx.graphics().text(ctx.font(), r.durationTicks() + "t · " + r.totalEnergy() + " RF", x + w - 70, cy + 4, Schematic.INK_DIM, false);
            cy += 24;
        }
        if (list.size() > limit) {
            ctx.graphics().text(ctx.font(), I18n.get("fieldframe.manual.more_recipes", list.size() - limit), x, cy, Schematic.INK_DIM, false);
            cy += 10;
        }
        return cy - y;
    }

    private static int faceMap(ManualPageRenderer.Context ctx, ManualModel.Page p, int x, int y, int w) {
        FfData faces = p.data().getData("faces");
        LocalFace[] order = {LocalFace.TOP, LocalFace.LEFT, LocalFace.FRONT, LocalFace.RIGHT, LocalFace.BOTTOM, LocalFace.BACK};
        int[][] posn = {{1, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}, {1, 3}};
        for (int i = 0; i < 6; i++) {
            int bx = x + posn[i][0] * 26;
            int by = y + posn[i][1] * 26;
            ctx.graphics().fill(bx, by, bx + 24, by + 24, Schematic.STEEL);
            ctx.graphics().fill(bx + 1, by + 1, bx + 23, by + 23, Schematic.PAPER_LIGHT);
            String mod = faces.getString(order[i].key(), "");
            FfId id = FfId.tryParse(mod);
            if (id != null && FfContent.moduleItem(id) != null) {
                ctx.item(dev.fieldframe.neoforge.client.Schematic.safeStack(FfContent.moduleItem(id)), bx + 4, by + 4);
                if (ctx.mouseX() >= bx && ctx.mouseX() < bx + 24 && ctx.mouseY() >= by && ctx.mouseY() < by + 24) {
                    ModuleDefinition def = FieldframeRegistry.module(id);
                    ctx.tooltip(Component.literal(I18n.get("fieldframe.face." + order[i].key()) + ": " + (def == null ? mod : I18n.get(def.translationKey()))));
                }
                ModuleDefinition def = FieldframeRegistry.module(id);
                if (def != null) {
                    ctx.link(def.manualEntry(), bx, by, 24, 24);
                }
            }
            ctx.graphics().text(ctx.font(), String.valueOf(order[i].letter), bx + 2, by + 2, Schematic.INK_DIM, false);
        }
        int tx = x + 84;
        int th = ctx.text(localize(p.data().getString("text", "")), tx, y, w - 84, Schematic.INK);
        return Math.max(4 * 26 + 2, th);
    }

    private static int table(ManualPageRenderer.Context ctx, ManualModel.Page p, int x, int y, int w) {
        List<Object> headers = p.data().getList("headers");
        List<Object> rows = p.data().getList("rows");
        int cols = Math.max(1, headers.size());
        // Column widths follow content; the last column takes the rest and wraps.
        int[] cw = new int[cols];
        for (int c = 0; c < cols - 1; c++) {
            int m = ctx.font().width(localize(String.valueOf(headers.get(c))));
            for (Object r : rows) {
                if (r instanceof List<?> cells && c < cells.size()) {
                    m = Math.max(m, ctx.font().width(localize(String.valueOf(cells.get(c)))));
                }
            }
            cw[c] = Math.min(m + 8, w / cols + 30);
        }
        int used = 0;
        for (int c = 0; c < cols - 1; c++) {
            used += cw[c];
        }
        cw[cols - 1] = Math.max(40, w - used);
        int cy = y;
        int cx = x;
        for (int c = 0; c < headers.size(); c++) {
            ctx.graphics().text(ctx.font(), ctx.font().plainSubstrByWidth(localize(String.valueOf(headers.get(c))), cw[c] - 2), cx, cy, Schematic.COPPER, false);
            cx += cw[c];
        }
        cy += 11;
        for (Object r : rows) {
            if (r instanceof List<?> cells) {
                cx = x;
                int rowH = 10;
                for (int c = 0; c < Math.min(cols, cells.size()); c++) {
                    String t = localize(String.valueOf(cells.get(c)));
                    if (c == cols - 1) {
                        var lines = ctx.font().split(Component.literal(t), cw[c] - 2);
                        for (int i = 0; i < lines.size(); i++) {
                            ctx.graphics().text(ctx.font(), lines.get(i), cx, cy + i * 9, Schematic.INK, false);
                        }
                        rowH = Math.max(rowH, lines.size() * 9 + 1);
                    } else {
                        ctx.graphics().text(ctx.font(), ctx.font().plainSubstrByWidth(t, cw[c] - 2), cx, cy, Schematic.INK, false);
                    }
                    cx += cw[c];
                }
                cy += rowH;
            }
        }
        return cy - y;
    }

    private static int tutorial(ManualPageRenderer.Context ctx, ManualModel.Page p, int x, int y, int w) {
        int cy = y;
        int n = 1;
        for (Object o : p.data().getList("steps")) {
            String text = o instanceof FfData d ? d.getString("text", "") : String.valueOf(o);
            String adv = o instanceof FfData d ? d.getString("advancement", "") : "";
            boolean done = !adv.isEmpty() && advancementDone(adv);
            ctx.graphics().fill(x, cy, x + 11, cy + 11, done ? 0xFF5DBB63 : Schematic.COPPER);
            ctx.graphics().text(ctx.font(), done ? "\u2714" : String.valueOf(n), x + (done ? 2 : 3), cy + 2, 0xFF0B1014, false);
            n++;
            cy += Math.max(12, ctx.text(localize(text), x + 15, cy + 1, w - 15, Schematic.INK) + 4);
        }
        return cy - y;
    }

    private static java.lang.reflect.Field progressField;
    private static boolean progressFieldMissing;

    /**
     * Whether the reader has advancement {@code id}, from the client's copy of their progress (the map is private in
     * ClientAdvancements, so it is read reflectively; if that ever fails the page simply shows no ticks).
     */
    static boolean advancementDone(String id) {
        Minecraft mc = Minecraft.getInstance();
        Identifier rl = Identifier.tryParse(id);
        if (rl == null || mc.getConnection() == null || progressFieldMissing) {
            return false;
        }
        var adv = mc.getConnection().getAdvancements();
        var holder = adv.get(rl);
        if (holder == null) {
            return false;
        }
        try {
            if (progressField == null) {
                progressField = net.minecraft.client.multiplayer.ClientAdvancements.class.getDeclaredField("progress");
                progressField.setAccessible(true);
            }
            Object progress = ((java.util.Map<?, ?>) progressField.get(adv)).get(holder);
            return progress instanceof net.minecraft.advancements.AdvancementProgress ap && ap.isDone();
        } catch (ReflectiveOperationException | RuntimeException e) {
            progressFieldMissing = true;
            return false;
        }
    }

    private static final String[] STATUS_CODES = {"WORKING", "IDLE", "PASSIVE", "NO_INPUT", "OUTPUT_FULL", "INVALID_RECIPE", "WRONG_FLUID", "NO_FLUID",
            "NO_ENERGY", "SIGNAL_DISABLED", "NO_FUEL", "BUFFER_FULL", "NO_SKY", "NIGHT", "NEIGHBOR_UNLOADED", "NO_NEIGHBOR", "NEIGHBOR_FULL",
            "NEIGHBOR_EMPTY", "UNBOUND", "CANCELLED_BY_RULE", "MODULE_DISABLED", "MISSING_DEFINITION", "SCRIPT_ERROR", "FAULT"};

    private static int statusCodes(ManualPageRenderer.Context ctx, ManualModel.Page p, int x, int y, int w) {
        int cy = y;
        for (String code : STATUS_CODES) {
            String key = "fieldframe.status." + code.toLowerCase(Locale.ROOT);
            String fix = net.minecraft.locale.Language.getInstance().has(key + ".fix") ? " " + I18n.get(key + ".fix", "…", "…") : "";
            cy += ctx.text("**" + code + "** — " + I18n.get(key, "…", "…") + fix, x, cy, w, Schematic.INK) + 2;
        }
        return cy - y;
    }

    static ItemStack barrier() {
        return dev.fieldframe.neoforge.client.Schematic.safeStack(Items.BARRIER);
    }

    /** Blueprint page: the preview below, plus a line that copies the design onto the Blueprint Sheet in hand. */
    static final class BlueprintPage implements ManualPageRenderer {
        private final java.util.Map<ManualModel.Page, int[]> buttons = new java.util.WeakHashMap<>();

        @Override
        public int render(Context ctx, ManualModel.Page p, int x, int y, int w) {
            int h = blueprint(ctx, p, x, y, w);
            var player = Minecraft.getInstance().player;
            boolean holding = player != null && (player.getMainHandItem().getItem() instanceof dev.fieldframe.neoforge.item.BlueprintItem
                    || player.getOffhandItem().getItem() instanceof dev.fieldframe.neoforge.item.BlueprintItem);
            int by = y + h + 3;
            if (!holding) {
                buttons.remove(p);
                return h + 3 + ctx.text(I18n.get("fieldframe.manual.blueprint_hold"), x, by, w, Schematic.INK_DIM);
            }
            boolean hover = ctx.mouseX() >= x && ctx.mouseX() < x + w && ctx.mouseY() >= by && ctx.mouseY() < by + 11;
            int th = ctx.text("**[ " + I18n.get("fieldframe.manual.blueprint_copy") + " ]**", x, by, w, hover ? 0xFFFFD27A : Schematic.COPPER);
            buttons.put(p, new int[] {x, by, w, th});
            return h + 3 + th;
        }

        @Override
        public boolean mouseClicked(ManualModel.Page p, double mx, double my, int button) {
            int[] r = buttons.get(p);
            if (button != 0 || r == null || mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) {
                return false;
            }
            dev.fieldframe.neoforge.network.FfNetwork.ManualBlueprint.send(
                    dev.fieldframe.api.data.FfJson.toJsonString(p.data().getData("blueprint")));
            return true;
        }

        @Override
        public String textEquivalent(ManualModel.Page p) {
            return I18n.get("fieldframe.manual.blueprint_copy");
        }
    }

    /** Blueprint preview: one face net per chassis (offset shown), plus the bill of materials. */
    private static int blueprint(ManualPageRenderer.Context ctx, ManualModel.Page p, int x, int y, int w) {
        dev.fieldframe.api.blueprint.Blueprint bp;
        try {
            bp = dev.fieldframe.api.blueprint.Blueprint.fromData(p.data().getData("blueprint"));
        } catch (IllegalArgumentException e) {
            return ctx.text(I18n.get("fieldframe.manual.bad_blueprint", e.getMessage()), x, y, w, 0xFFE0524B);
        }
        int cy = y;
        cy += ctx.text("**" + BuiltinPages.localize(bp.name()) + "**  (" + bp.chassis().size() + " chassis)", x, cy, w, Schematic.COPPER) + 2;
        LocalFace[] order = {LocalFace.TOP, LocalFace.LEFT, LocalFace.FRONT, LocalFace.RIGHT, LocalFace.BOTTOM, LocalFace.BACK};
        int[][] posn = {{1, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}, {1, 3}};
        int cx = x;
        int rowH = 0;
        for (dev.fieldframe.api.blueprint.Blueprint.Chassis c : bp.chassis()) {
            if (cx + 60 > x + w) {
                cx = x;
                cy += rowH + 4;
                rowH = 0;
            }
            for (int i = 0; i < 6; i++) {
                int bx = cx + posn[i][0] * 18;
                int by = cy + 10 + posn[i][1] * 18;
                ctx.graphics().fill(bx, by, bx + 17, by + 17, Schematic.STEEL);
                ctx.graphics().fill(bx + 1, by + 1, bx + 16, by + 16, Schematic.PAPER_LIGHT);
                var face = c.faces().get(order[i]);
                if (face != null && FfContent.moduleItem(face.module()) != null) {
                    ctx.item(dev.fieldframe.neoforge.client.Schematic.safeStack(FfContent.moduleItem(face.module())), bx, by);
                }
            }
            ctx.graphics().text(ctx.font(), c.dx() + "," + c.dy() + "," + c.dz() + " " + c.front().name().substring(0, 1), cx, cy, Schematic.INK_DIM, false);
            rowH = Math.max(rowH, 10 + 4 * 18);
            cx += 62;
        }
        cy += rowH + 4;
        StringBuilder bom = new StringBuilder("**" + I18n.get("fieldframe.manual.bom") + "** ");
        bp.billOfMaterials(FfId.ff("chassis")).forEach((id, n) -> bom.append(n).append("x ").append(id.path()).append("  "));
        cy += ctx.text(bom.toString(), x, cy, w, Schematic.INK);
        for (String prob : bp.compatibilityProblems()) {
            cy += ctx.text("! " + prob, x, cy, w, 0xFFE0524B);
        }
        return cy - y;
    }
}
