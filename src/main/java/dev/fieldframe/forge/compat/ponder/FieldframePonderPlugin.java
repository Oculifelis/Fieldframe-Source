package dev.fieldframe.forge.compat.ponder;

import com.google.gson.JsonObject;
import dev.fieldframe.forge.Fieldframe;
import net.createmod.ponder.api.registration.MultiSceneBuilder;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/** Registers every indexed scene with Ponder; each one is played by {@link SceneInterpreter}. */
final class FieldframePonderPlugin implements PonderPlugin {
    /** Ponder has asked for scenes at least once (later resource reloads then re-register). */
    static volatile boolean registered;

    @Override
    public String getModId() {
        return Fieldframe.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        registered = true;
        int count = 0;
        for (Map.Entry<ResourceLocation, List<ResourceLocation>> e : PonderScenes.index().entrySet()) {
            if (e.getValue().isEmpty() || BuiltInRegistries.ITEM.getOptional(e.getKey()).isEmpty()) {
                continue;
            }
            MultiSceneBuilder b = helper.forComponents(e.getKey());
            for (ResourceLocation sceneId : e.getValue()) {
                JsonObject json = PonderScenes.scene(sceneId);
                ResourceLocation schematic = json == null ? null : ResourceLocation.tryParse(json.has("schematic") ? json.get("schematic").getAsString() : "");
                if (schematic == null) {
                    Fieldframe.LOG.warn("Fieldframe Ponder: scene {} (for {}) is missing or has no schematic", sceneId, e.getKey());
                    continue;
                }
                b.addStoryBoard(schematic, (scene, util) -> SceneInterpreter.play(sceneId, json, scene, util));
                count++;
            }
        }
        Fieldframe.LOG.info("Fieldframe Ponder: registered {} scene links", count);
    }
}
