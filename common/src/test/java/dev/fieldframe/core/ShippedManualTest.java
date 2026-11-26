package dev.fieldframe.core;

import dev.fieldframe.api.blueprint.Blueprint;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualModel;
import dev.fieldframe.api.manual.ManualPageTypes;
import dev.fieldframe.api.manual.ManualRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates the shipped manual content against the real built-in registry (links, modules, page data, blueprints). */
class ShippedManualTest {
    private static final Path ROOT = Path.of("..", "shared", "assets", "fieldframe", "fieldframe_manual");

    @Test
    void shippedManualHasNoProblems() throws IOException {
        TestSupport.freshRegistry();
        List<ManualModel.Category> cats = new ArrayList<>();
        List<ManualModel.Entry> entries = new ArrayList<>();
        List<String> blueprintProblems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String rel = ROOT.relativize(p).toString().replace('\\', '/');
                String id = rel.substring(rel.indexOf('/') + 1, rel.length() - 5);
                FfData d = FfJson.parse(Files.readString(p, StandardCharsets.UTF_8));
                if (rel.startsWith("categories/")) {
                    cats.add(ManualModel.parseCategory(FfId.ff(id), d));
                } else if (rel.startsWith("entries/")) {
                    ManualModel.Entry e = ManualModel.parseEntry(FfId.ff(id), d, rel);
                    entries.add(e);
                    for (ManualModel.Page page : e.pages()) {
                        if (page.type().equals(ManualPageTypes.BLUEPRINT)) {
                            try {
                                Blueprint bp = Blueprint.fromData(page.data().getData("blueprint"));
                                bp.compatibilityProblems().forEach(x -> blueprintProblems.add(rel + ": " + x));
                            } catch (IllegalArgumentException ex) {
                                blueprintProblems.add(rel + ": " + ex.getMessage());
                            }
                        }
                    }
                }
            }
        }
        assertTrue(entries.size() >= 15, "found " + entries.size() + " entries");
        ManualRegistry.Book book = ManualRegistry.rebuild(b -> {
            cats.forEach(b::category);
            entries.forEach(e -> b.entry(e, false));
        }, mod -> true);
        assertEquals(List.of(), book.problems());
        assertEquals(List.of(), blueprintProblems);
        // Every built-in module has a reference entry (created or authored).
        TestSupport.freshRegistry();
        dev.fieldframe.api.FieldframeRegistry.modules().forEach(m -> assertTrue(book.entries().containsKey(m.manualEntry()), "entry for " + m.id()));
        assertTrue(book.search("hysteresis").size() >= 1);
    }
}
