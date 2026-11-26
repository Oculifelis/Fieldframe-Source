package dev.fieldframe.neoforge.client.manual;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualModel;
import dev.fieldframe.api.manual.ManualRegistry;
import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.ModList;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads manual content from client resources: {@code assets/<ns>/fieldframe_manual/categories/*.json},
 * {@code .../entries/**.json}, and localized overrides in {@code .../lang/<code>/entries/**.json}. Resource packs
 * can override any file. Afterwards the effective book is rebuilt (with Java and script contributors) and
 * validated; problems are logged, never fatal. Only inert data is read; nothing executes.
 */
public final class ManualLoader extends SimplePreparableReloadListener<ManualLoader.Raw> {
    record Raw(List<ManualModel.Category> categories, List<ManualModel.Entry> entries, List<ManualModel.Entry> localized, List<String> problems) {
    }

    private static final String ROOT = "fieldframe_manual";

    @Override
    protected Raw prepare(ResourceManager rm, ProfilerFiller profiler) {
        List<ManualModel.Category> cats = new ArrayList<>();
        List<ManualModel.Entry> entries = new ArrayList<>();
        List<ManualModel.Entry> localized = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        for (Map.Entry<Identifier, Resource> e : rm.listResources(ROOT, rl -> rl.getPath().endsWith(".json")).entrySet()) {
            Identifier rl = e.getKey();
            String path = rl.getPath().substring(ROOT.length() + 1, rl.getPath().length() - 5);
            try (BufferedReader r = e.getValue().openAsReader()) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                    if (sb.length() > FfJson.DEFAULT_MAX_CHARS) {
                        throw new IllegalArgumentException("file too large");
                    }
                }
                FfData d = FfJson.parse(sb.toString());
                if (path.startsWith("categories/")) {
                    cats.add(ManualModel.parseCategory(FfId.of(rl.getNamespace(), path.substring("categories/".length())), d));
                } else if (path.startsWith("entries/")) {
                    FfId id = FfId.of(rl.getNamespace(), path.substring("entries/".length()));
                    entries.add(ManualModel.parseEntry(id, d, rl.toString()));
                } else if (path.startsWith("lang/" + lang + "/entries/")) {
                    FfId id = FfId.of(rl.getNamespace(), path.substring(("lang/" + lang + "/entries/").length()));
                    localized.add(ManualModel.parseEntry(id, d, rl.toString()));
                }
            } catch (Exception ex) {
                problems.add(rl + ": " + ex.getMessage());
            }
        }
        return new Raw(cats, entries, localized, problems);
    }

    @Override
    protected void apply(Raw raw, ResourceManager rm, ProfilerFiller profiler) {
        ManualRegistry.Book book = ManualRegistry.rebuild(b -> {
            raw.categories().forEach(b::category);
            raw.entries().forEach(e -> b.entry(e, false));
            raw.localized().forEach(e -> b.entry(e, true));
            raw.problems().forEach(b::problem);
        }, id -> ModList.get().isLoaded(id));
        for (String p : book.problems()) {
            Fieldframe.LOG.warn("Fieldframe manual: {}", p);
        }
        Fieldframe.LOG.info("Fieldframe manual: {} categories, {} entries, {} problems", book.categories().size(), book.entries().size(), book.problems().size());
        ManualScreen.onBookReloaded();
    }
}
