package dev.fieldframe.forge.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Data recipe for a Fieldframe process family ({@code fieldframe:crushing}, {@code pressing}, {@code mixing},
 * {@code washing}, {@code heating}). Schema (see docs/recipes.md):
 * <pre>
 * { "type": "fieldframe:crushing",
 *   "inputs":  [ { "ingredient": {...}, "count": 1, "catalyst": false } ],
 *   "fluid_input":  { "fluid": "minecraft:water" | "tag": "forge:water", "amount": 250 },
 *   "results": [ { "item": "minecraft:flint", "count": 1, "chance": 0.25 } ],
 *   "fluid_result": { "fluid": "minecraft:water", "amount": 100 },
 *   "duration": 100, "energy": 20 }
 * </pre>
 * Not usable in a vanilla crafting container; matching is done by the chassis processor via {@link RecipeBridge}.
 */
public final class FfProcessRecipe implements Recipe<Container> {
    public record Input(Ingredient ingredient, int count, boolean catalyst) {
    }

    public record FluidIn(ResourceLocation fluid, ResourceLocation tag, int amount) {
        public boolean matches(Fluid f) {
            if (fluid != null) {
                return fluid.equals(ForgeRegistries.FLUIDS.getKey(f));
            }
            return f.is(TagKey.create(net.minecraft.core.registries.Registries.FLUID, tag));
        }
    }

    public record Result(ItemStack stack, float chance) {
    }

    private final ResourceLocation id;
    private final String family;
    private final dev.fieldframe.api.id.FfId familyId;
    private final List<Input> inputs;
    private final FluidIn fluidInput;
    private final List<Result> results;
    private final FluidStack fluidResult;
    private final int duration;
    private final int energy;

    /**
     * @param family   serializer key ({@code crushing}, ... or {@code process} for script/addon families)
     * @param familyId the process family this recipe belongs to
     */
    public FfProcessRecipe(ResourceLocation id, String family, dev.fieldframe.api.id.FfId familyId, List<Input> inputs, FluidIn fluidInput,
                           List<Result> results, FluidStack fluidResult, int duration, int energy) {
        this.id = id;
        this.family = family;
        this.familyId = familyId;
        this.inputs = List.copyOf(inputs);
        this.fluidInput = fluidInput;
        this.results = List.copyOf(results);
        this.fluidResult = fluidResult;
        this.duration = duration;
        this.energy = energy;
    }

    public String family() {
        return family;
    }

    public dev.fieldframe.api.id.FfId familyId() {
        return familyId;
    }

    public List<Input> inputs() {
        return inputs;
    }

    public FluidIn fluidInput() {
        return fluidInput;
    }

    public List<Result> results() {
        return results;
    }

    public FluidStack fluidResult() {
        return fluidResult;
    }

    public int duration() {
        return duration;
    }

    public int energy() {
        return energy;
    }

    @Override
    public boolean matches(Container c, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(Container c, RegistryAccess access) {
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0).stack().copy();
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) {
        return false;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0).stack().copy();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> l = NonNullList.create();
        inputs.forEach(i -> l.add(i.ingredient()));
        return l;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return FfContent.PROCESS_SERIALIZERS.get(family).get();
    }

    @Override
    public RecipeType<?> getType() {
        return FfContent.PROCESS_TYPES.get(family).get();
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    public static final class Serializer implements RecipeSerializer<FfProcessRecipe> {
        private final String family;

        public Serializer(String family) {
            this.family = family;
        }

        @Override
        public FfProcessRecipe fromJson(ResourceLocation id, JsonObject json) {
            List<Input> inputs = new ArrayList<>();
            JsonArray ins = GsonHelper.getAsJsonArray(json, "inputs", new JsonArray());
            if (ins.size() > dev.fieldframe.api.process.ProcessRecipe.MAX_INPUTS) {
                throw new JsonParseException("At most " + dev.fieldframe.api.process.ProcessRecipe.MAX_INPUTS + " inputs");
            }
            for (JsonElement e : ins) {
                JsonObject o = e.getAsJsonObject();
                inputs.add(new Input(Ingredient.fromJson(o.get("ingredient")), clamp(GsonHelper.getAsInt(o, "count", 1), 1, 64, "count"),
                        GsonHelper.getAsBoolean(o, "catalyst", false)));
            }
            FluidIn fin = null;
            if (json.has("fluid_input")) {
                JsonObject f = GsonHelper.getAsJsonObject(json, "fluid_input");
                int amount = clamp(GsonHelper.getAsInt(f, "amount"), 1, 64_000, "amount");
                if (f.has("fluid")) {
                    ResourceLocation rl = new ResourceLocation(GsonHelper.getAsString(f, "fluid"));
                    if (!ForgeRegistries.FLUIDS.containsKey(rl)) {
                        throw new JsonParseException("Unknown fluid " + rl);
                    }
                    fin = new FluidIn(rl, null, amount);
                } else {
                    fin = new FluidIn(null, new ResourceLocation(GsonHelper.getAsString(f, "tag")), amount);
                }
            }
            List<Result> results = new ArrayList<>();
            for (JsonElement e : GsonHelper.getAsJsonArray(json, "results", new JsonArray())) {
                JsonObject o = e.getAsJsonObject();
                ItemStack st = CraftingHelper.getItemStack(o, true);
                float chance = GsonHelper.getAsFloat(o, "chance", 1f);
                if (!(chance > 0 && chance <= 1)) {
                    throw new JsonParseException("chance must be in (0, 1]");
                }
                results.add(new Result(st, chance));
            }
            if (results.size() > dev.fieldframe.api.process.ProcessRecipe.MAX_RESULTS) {
                throw new JsonParseException("Too many results");
            }
            FluidStack fres = FluidStack.EMPTY;
            if (json.has("fluid_result")) {
                JsonObject f = GsonHelper.getAsJsonObject(json, "fluid_result");
                Fluid fl = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(GsonHelper.getAsString(f, "fluid")));
                if (fl == null) {
                    throw new JsonParseException("Unknown fluid in fluid_result");
                }
                fres = new FluidStack(fl, clamp(GsonHelper.getAsInt(f, "amount"), 1, 64_000, "amount"));
            }
            if (inputs.isEmpty() && fin == null) {
                throw new JsonParseException("Recipe needs at least one input");
            }
            int duration = clamp(GsonHelper.getAsInt(json, "duration", 100), 1, 72_000, "duration");
            int energy = clamp(GsonHelper.getAsInt(json, "energy", 20), 0, 1_000_000, "energy");
            return new FfProcessRecipe(id, family, familyFrom(json), inputs, fin, results, fres, duration, energy);
        }

        private dev.fieldframe.api.id.FfId familyFrom(JsonObject json) {
            if (!family.equals("process")) {
                return dev.fieldframe.api.id.FfId.ff(family);
            }
            dev.fieldframe.api.id.FfId f = dev.fieldframe.api.id.FfId.tryParse(GsonHelper.getAsString(json, "family", ""));
            if (f == null) {
                throw new JsonParseException("fieldframe:process recipes need a valid \"family\" id");
            }
            return f;
        }

        private static int clamp(int v, int min, int max, String what) {
            if (v < min || v > max) {
                throw new JsonParseException(what + " must be between " + min + " and " + max + ", got " + v);
            }
            return v;
        }

        @Override
        public FfProcessRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            int n = Math.min(buf.readVarInt(), 4);
            List<Input> inputs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                inputs.add(new Input(Ingredient.fromNetwork(buf), buf.readVarInt(), buf.readBoolean()));
            }
            FluidIn fin = null;
            if (buf.readBoolean()) {
                boolean byId = buf.readBoolean();
                ResourceLocation rl = buf.readResourceLocation();
                fin = new FluidIn(byId ? rl : null, byId ? null : rl, buf.readVarInt());
            }
            int r = Math.min(buf.readVarInt(), 6);
            List<Result> results = new ArrayList<>();
            for (int i = 0; i < r; i++) {
                results.add(new Result(buf.readItem(), buf.readFloat()));
            }
            FluidStack fres = buf.readFluidStack();
            dev.fieldframe.api.id.FfId fam = dev.fieldframe.api.id.FfId.tryParse(buf.readUtf(256));
            return new FfProcessRecipe(id, family, fam == null ? dev.fieldframe.api.id.FfId.ff(family) : fam, inputs, fin, results, fres, buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, FfProcessRecipe r) {
            buf.writeVarInt(r.inputs.size());
            for (Input i : r.inputs) {
                i.ingredient().toNetwork(buf);
                buf.writeVarInt(i.count());
                buf.writeBoolean(i.catalyst());
            }
            buf.writeBoolean(r.fluidInput != null);
            if (r.fluidInput != null) {
                buf.writeBoolean(r.fluidInput.fluid() != null);
                buf.writeResourceLocation(r.fluidInput.fluid() != null ? r.fluidInput.fluid() : r.fluidInput.tag());
                buf.writeVarInt(r.fluidInput.amount());
            }
            buf.writeVarInt(r.results.size());
            for (Result res : r.results) {
                buf.writeItem(res.stack());
                buf.writeFloat(res.chance());
            }
            buf.writeFluidStack(r.fluidResult);
            buf.writeUtf(r.familyId.toString(), 256);
            buf.writeVarInt(r.duration);
            buf.writeVarInt(r.energy);
        }
    }
}
