package dev.fieldframe.neoforge.compat.ponder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import net.createmod.ponder.api.element.TextElementBuilder;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Plays one scene file. Steps run in order; any step may add {@code "wait": ticks} (an idle afterwards). Text comes
 * from the language file ({@code <ns>.ponder.<scene>.text_<n>}, written by tools/gen_lang.py from the scene's English);
 * the English in the file is the fallback. An unknown step stops the scene with a log line naming file and step.
 *
 * <p>Steps: {@code show} ("base", or a section {@code from}/{@code to} with {@code dir}), {@code hide},
 * {@code text} (+ {@code at}, {@code face}, {@code for}, {@code outline}), {@code install} / {@code remove} (a module
 * on a chassis face: {@code at}, {@code face}), {@code working} (lit faceplate), {@code block} (set a block),
 * {@code idle}, {@code rotate} (camera, degrees).
 */
final class SceneInterpreter {
    private SceneInterpreter() {
    }

    static void play(ResourceLocation id, JsonObject json, SceneBuilder scene, SceneBuildingUtil util) {
        String key = sceneKey(id);
        scene.title(key, json.has("title") ? json.get("title").getAsString() : key);
        if (json.has("base_plate")) {
            JsonArray b = json.getAsJsonArray("base_plate");
            scene.configureBasePlate(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt());
        }
        if (json.has("scale")) {
            scene.scaleSceneView(json.get("scale").getAsFloat());
        }
        int textNo = 0;
        JsonArray steps = json.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            JsonObject s = steps.get(i).getAsJsonObject();
            try {
                if (s.has("text")) {
                    textNo++;
                }
                step(id, key, textNo, s, scene, util);
                if (s.has("wait")) {
                    scene.idle(s.get("wait").getAsInt());
                }
            } catch (RuntimeException ex) {
                Fieldframe.LOG.error("Fieldframe Ponder: scene {} step {} ({}): {}", id, i + 1, s, ex.getMessage());
                scene.markAsFinished();
                return;
            }
        }
        scene.markAsFinished();
    }

    /** The scene's key in Ponder (and in its language keys): the path for Fieldframe's own, else namespace.path. */
    static String sceneKey(ResourceLocation id) {
        return id.getNamespace().equals(Fieldframe.MOD_ID) ? id.getPath() : id.getNamespace() + "." + id.getPath();
    }

    private static void step(ResourceLocation id, String key, int textNo, JsonObject s, SceneBuilder scene, SceneBuildingUtil util) {
        if (s.has("show")) {
            if ("base".equals(s.get("show").getAsString())) {
                scene.showBasePlate();
            } else {
                scene.world().showSection(selection(s, util), dir(s, "dir", Direction.DOWN));
            }
        } else if (s.has("hide")) {
            scene.world().hideSection(selection(s, util), dir(s, "dir", Direction.UP));
        } else if (s.has("text")) {
            int ticks = s.has("for") ? s.get("for").getAsInt() : 60;
            String english = s.get("text").getAsString();
            String lang = Fieldframe.MOD_ID + ".ponder." + key + ".text_" + textNo;
            String shown = Language.getInstance().getOrDefault(lang, english);
            TextElementBuilder t;
            if (s.has("at")) {
                BlockPos at = pos(s.getAsJsonArray("at"));
                t = s.has("outline") && s.get("outline").getAsBoolean()
                        ? scene.overlay().showOutlineWithText(util.select().position(at), ticks)
                        : scene.overlay().showText(ticks);
                t.pointAt(s.has("face") ? util.vector().blockSurface(at, dir(s, "face", Direction.UP)) : util.vector().centerOf(at))
                        .placeNearTarget();
            } else {
                t = scene.overlay().showText(ticks).independent();
            }
            t.text(shown).attachKeyFrame();
            scene.idle(s.has("hold") ? s.get("hold").getAsInt() : ticks + 10);
        } else if (s.has("install") || s.has("remove")) {
            boolean install = s.has("install");
            LocalFace face = LocalFace.valueOf(s.get("face").getAsString().toUpperCase(Locale.ROOT));
            String module = install ? ResourceLocation.parse(s.get("install").getAsString()).toString() : null;
            chassis(s, util, scene, c -> {
                if (install) {
                    c.putString(face.key(), module);
                } else {
                    c.remove(face.key());
                    c.putInt("work", c.getInt("work") & ~(1 << face.ordinal()));
                }
            });
        } else if (s.has("working")) {
            LocalFace face = LocalFace.valueOf(s.get("working").getAsString().toUpperCase(Locale.ROOT));
            boolean on = !s.has("on") || s.get("on").getAsBoolean();
            chassis(s, util, scene, c -> {
                int w = c.getInt("work");
                c.putInt("work", on ? w | 1 << face.ordinal() : w & ~(1 << face.ordinal()));
            });
        } else if (s.has("block")) {
            var block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(s.get("block").getAsString()))
                    .orElseThrow(() -> new IllegalArgumentException("unknown block " + s.get("block")));
            scene.world().setBlock(pos(s.getAsJsonArray("at")), block.defaultBlockState(), true);
        } else if (s.has("idle")) {
            scene.idle(s.get("idle").getAsInt());
        } else if (s.has("rotate")) {
            scene.rotateCameraY(s.get("rotate").getAsFloat());
        } else {
            throw new IllegalArgumentException("unknown step in " + id);
        }
    }

    /** Edits the chassis's client data (faceplates), which the virtual level's chassis reads like a server update. */
    private static void chassis(JsonObject s, SceneBuildingUtil util, SceneBuilder scene, java.util.function.Consumer<CompoundTag> edit) {
        Selection at = util.select().position(pos(s.getAsJsonArray("at")));
        scene.world().modifyBlockEntityNBT(at, ChassisBlockEntity.class, tag -> {
            CompoundTag c = tag.getCompound("client");
            edit.accept(c);
            tag.put("client", c);
        }, true);
    }

    private static Selection selection(JsonObject s, SceneBuildingUtil util) {
        BlockPos from = pos(s.getAsJsonArray("from"));
        BlockPos to = s.has("to") ? pos(s.getAsJsonArray("to")) : from;
        return util.select().fromTo(from, to);
    }

    private static BlockPos pos(JsonArray a) {
        if (a == null || a.size() != 3) {
            throw new IllegalArgumentException("position must be [x, y, z]");
        }
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static Direction dir(JsonObject s, String field, Direction fallback) {
        JsonElement e = s.get(field);
        if (e == null) {
            return fallback;
        }
        Direction d = Direction.byName(e.getAsString());
        if (d == null) {
            throw new IllegalArgumentException("bad direction " + e);
        }
        return d;
    }
}
