package dev.fieldframe.neoforge.client.manual;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.ChassisSpec;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualModel;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.resource.ItemFilter;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.VariantCodec;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.neoforge.api.client.ManualPageRenderer;
import dev.fieldframe.neoforge.client.Schematic;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Schematic test bench: runs a scenario on the pure domain simulator ({@link SimulatedWorld}) with synthetic
 * items, recipes and fuels. It never touches the live world, never posts events to listeners
 * ({@link ChassisCore#setSimulation}), and cannot simulate addon behaviours that need a real world.
 * Controls: play/pause, step, reset. Shows lanes, RF, module statuses and an RF graph.
 */
final class TestBenchPage implements ManualPageRenderer {
    private static final class Bench {
        ChassisCore core;
        SimulatedWorld world;
        boolean playing;
        long lastTick = -1;
        String error;
        final Deque<Long> energy = new ArrayDeque<>();
        int[] buttons = new int[4];
    }

    private final Map<ManualModel.Page, Bench> benches = new WeakHashMap<>();

    static Bench build(FfData sc) {
        Bench b = new Bench();
        try {
            SimulatedWorld w = new SimulatedWorld();
            for (Object o : sc.getList("recipes")) {
                if (o instanceof FfData r) {
                    FfId fam = FfId.parse(r.getString("family", "fieldframe:heating"));
                    w.addRecipe(new ProcessRecipe(FfId.ff("bench/" + w.recipes.size()), fam,
                            List.of(new ProcessRecipe.ItemInput(ItemFilter.allowIds(r.getString("input", "minecraft:stone")), r.getIntClamped("count", 1, 1, 64), true,
                                    r.getString("input", ""), List.of(ItemVariant.Simple.of(r.getString("input", "minecraft:stone"))))),
                            null, List.of(new ProcessRecipe.ItemResult(ItemVariant.Simple.of(r.getString("output", "minecraft:stone")), r.getIntClamped("out_count", 1, 1, 64), 1.0)),
                            null, r.getIntClamped("duration", 40, 1, 72000), r.getIntClamped("energy", 10, 0, 100000)));
                }
            }
            FfData fuels = sc.getData("fuels");
            for (String k : fuels.keys()) {
                w.fuels.put(FfId.parse(k), fuels.getIntClamped(k, 0, 0, 100000));
            }
            if (sc.getBool("sky", true)) {
                w.sky.add(LocalFace.TOP);
            }
            ChassisCore c = new ChassisCore(ChassisSpec.STANDARD, VariantCodec.SIMPLE, () -> w.time);
            c.setSimulation(true);
            c.setAddress("manual-bench");
            FfData mods = sc.getData("modules");
            for (LocalFace f : LocalFace.ALL) {
                FfData md = mods.getDataOrNull(f.key());
                if (md == null) {
                    continue;
                }
                ModuleDefinition def = FieldframeRegistry.module(FfId.parse(md.getString("module", "")));
                if (def == null) {
                    b.error = I18n.get("fieldframe.manual.bench_unknown", md.getString("module", ""));
                    continue;
                }
                c.install(f, def, md.getDataOrNull("config"));
                FfData binds = md.getData("bindings");
                for (String k : binds.keys()) {
                    c.setBinding(f, k, binds.getInt(k, 0));
                }
            }
            FfData lanes = sc.getData("lanes");
            for (int l = 0; l < 3; l++) {
                for (Object o : lanes.getList(String.valueOf((char) ('A' + l)))) {
                    if (o instanceof List<?> pair && pair.size() == 2) {
                        c.lane(l).insert(ItemVariant.Simple.of(String.valueOf(pair.get(0))), (int) Math.min(64 * 27, ((Number) pair.get(1)).longValue()), false);
                    }
                }
            }
            c.energy().receive(sc.getLong("energy", 0), false);
            b.core = c;
            b.world = w;
        } catch (RuntimeException e) {
            b.error = "Scenario error: " + e.getMessage();
        }
        return b;
    }

    private void step(Bench b, int ticks) {
        for (int i = 0; i < ticks && b.core != null; i++) {
            b.world.time++;
            b.core.tick(b.world);
            if (b.world.time % 5 == 0) {
                b.energy.addLast(b.core.energy().stored());
                while (b.energy.size() > 60) {
                    b.energy.removeFirst();
                }
            }
        }
    }

    @Override
    public int render(Context ctx, ManualModel.Page page, int x, int y, int w) {
        Bench b = benches.computeIfAbsent(page, p -> build(p.data().getData("scenario")));
        var g = ctx.graphics();
        var font = ctx.font();
        int cy = y;
        String caption = BuiltinPages.localize(page.data().getString("text", ""));
        if (!caption.isEmpty()) {
            cy += ctx.text(caption, x, cy, w, Schematic.INK) + 4;
        }
        if (b.error != null) {
            cy += ctx.text(b.error, x, cy, w, 0xFFE0524B) + 2;
        }
        if (b.core == null) {
            return cy - y;
        }
        if (b.playing && ctx.ticks() != b.lastTick) {
            step(b, page.data().getIntClamped("speed", 4, 1, 40));
        }
        b.lastTick = ctx.ticks();
        // Buttons.
        String[] labels = {b.playing ? "❚❚" : "▶", "+1", "+20", "↺"};
        for (int i = 0; i < 4; i++) {
            int bx = x + i * 26;
            g.fill(bx, cy, bx + 24, cy + 12, Schematic.STEEL);
            g.drawString(font, labels[i], bx + 12 - font.width(labels[i]) / 2, cy + 2, Schematic.INK, false);
            b.buttons[i] = bx;
        }
        buttonsY.put(page, cy);
        g.drawString(font, "t=" + b.world.time, x + 110, cy + 2, Schematic.INK_DIM, false);
        cy += 16;
        // Modules.
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = b.core.module(f);
            if (m == null) {
                continue;
            }
            Schematic.lamp(g, font, x, cy, m.status().severity().name());
            String name = m.isMissing() ? m.moduleId().toString() : I18n.get(m.definition().translationKey());
            g.drawString(font, font.plainSubstrByWidth(f.letter + " " + name + " — " + m.status().compact(), w - 12), x + 12, cy + 1, Schematic.INK, false);
            cy += 11;
        }
        cy += 2;
        // Lanes.
        for (int l = 0; l < 3; l++) {
            var lane = b.core.lane(l);
            g.drawString(font, net.minecraft.client.resources.language.I18n.get("fieldframe.colour." + l), x, cy + 4, dev.fieldframe.neoforge.client.ConfiguratorScreen.LANE_COLOURS[l], false);
            int ix = x + 40;
            for (int s = 0; s < lane.slots(); s++) {
                g.fill(ix + s * 18 - 1, cy - 1, ix + s * 18 + 17, cy + 17, Schematic.SLOT);
                ItemVariant v = lane.variant(s);
                if (v != null) {
                    ItemStack st = BuiltinPages.icon(v.id().toString());
                    if (!st.isEmpty()) {
                        st.setCount(lane.count(s));
                        ctx.item(st, ix + s * 18, cy);
                        g.renderItemDecorations(font, st, ix + s * 18, cy);
                    }
                }
            }
            cy += 19;
        }
        // RF + graph.
        long e = b.core.energy().stored();
        Schematic.bar(g, x, cy, 80, 6, e / (double) Math.max(1, b.core.energy().capacity()), 0xFFE3B341);
        g.drawString(font, dev.fieldframe.api.energy.Energy.format(e), x + 84, cy - 1, Schematic.INK_DIM, false);
        cy += 9;
        int gx = x;
        int gh = 24;
        g.fill(gx, cy, gx + 120, cy + gh, Schematic.SLOT);
        int i = 0;
        long cap = Math.max(1, b.core.energy().capacity());
        for (long v : b.energy) {
            int hgt = (int) (v * (gh - 2) / cap);
            g.fill(gx + 1 + i * 2, cy + gh - 1 - hgt, gx + 2 + i * 2, cy + gh - 1, 0xFFE3B341);
            i++;
        }
        cy += gh + 2;
        g.drawString(font, I18n.get("fieldframe.manual.bench_note"), x, cy, Schematic.INK_DIM, false);
        cy += 10;
        return cy - y;
    }

    private final Map<ManualModel.Page, Integer> buttonsY = new WeakHashMap<>();

    @Override
    public boolean mouseClicked(ManualModel.Page page, double mx, double my, int button) {
        Bench b = benches.get(page);
        Integer by = buttonsY.get(page);
        if (b == null || by == null || my < by || my > by + 12) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (mx >= b.buttons[i] && mx < b.buttons[i] + 24) {
                switch (i) {
                    case 0 -> b.playing = !b.playing;
                    case 1 -> step(b, 1);
                    case 2 -> step(b, 20);
                    default -> benches.put(page, build(page.data().getData("scenario")));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public String textEquivalent(ManualModel.Page page) {
        return page.data().getString("text", "") + " (interactive simulation)";
    }
}
