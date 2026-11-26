package dev.fieldframe.forge.client.manual;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fieldframe.api.id.FfId;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Private, local-only manual state: bookmarks and short notes per entry, stored in
 * {@code config/fieldframe/manual_notebook.json} on this computer. Never uploaded or synchronized.
 * Bounded: 64 bookmarks, 64 notes of at most 512 characters.
 */
public final class ManualNotebook {
    public static final int MAX_BOOKMARKS = 64;
    public static final int MAX_NOTES = 64;
    public static final int MAX_NOTE = 512;

    private static final Set<FfId> BOOKMARKS = new LinkedHashSet<>();
    private static final Map<FfId, String> NOTES = new LinkedHashMap<>();
    private static boolean loaded;

    private ManualNotebook() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("fieldframe").resolve("manual_notebook.json");
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f) || Files.size(f) > 256 * 1024) {
                return;
            }
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.getAsJsonArray("bookmarks")) {
                FfId id = FfId.tryParse(e.getAsString());
                if (id != null && BOOKMARKS.size() < MAX_BOOKMARKS) {
                    BOOKMARKS.add(id);
                }
            }
            for (var e : o.getAsJsonObject("notes").entrySet()) {
                FfId id = FfId.tryParse(e.getKey());
                String text = e.getValue().getAsString();
                if (id != null && NOTES.size() < MAX_NOTES) {
                    NOTES.put(id, text.length() > MAX_NOTE ? text.substring(0, MAX_NOTE) : text);
                }
            }
        } catch (Exception ignored) {
            // A corrupt notebook is ignored, not fatal.
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            JsonArray b = new JsonArray();
            BOOKMARKS.forEach(id -> b.add(id.toString()));
            o.add("bookmarks", b);
            JsonObject n = new JsonObject();
            NOTES.forEach((k, v) -> n.addProperty(k.toString(), v));
            o.add("notes", n);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new Gson().toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Local convenience only.
        }
    }

    public static boolean isBookmarked(FfId id) {
        ensureLoaded();
        return BOOKMARKS.contains(id);
    }

    public static void toggleBookmark(FfId id) {
        ensureLoaded();
        if (!BOOKMARKS.remove(id) && BOOKMARKS.size() < MAX_BOOKMARKS) {
            BOOKMARKS.add(id);
        }
        save();
    }

    public static Set<FfId> bookmarks() {
        ensureLoaded();
        return Set.copyOf(BOOKMARKS);
    }

    public static String note(FfId id) {
        ensureLoaded();
        return NOTES.getOrDefault(id, "");
    }

    public static void setNote(FfId id, String text) {
        ensureLoaded();
        if (text == null || text.isBlank()) {
            NOTES.remove(id);
        } else if (NOTES.containsKey(id) || NOTES.size() < MAX_NOTES) {
            NOTES.put(id, text.length() > MAX_NOTE ? text.substring(0, MAX_NOTE) : text);
        }
        save();
    }
}
