package dev.fieldframe.neoforge.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.Optional;

/**
 * Data recipe for a Fieldframe process family. JSON schema (identical across editions except the result id key,
 * see docs/recipes.md):
 * <pre>
 * { "type": "fieldframe:crushing" | "fieldframe:process" (+ "family": "ns:id"),
 *   "inputs":  [ { "ingredient": {...}, "count": 1, "catalyst": false } ],
 *   "fluid_input":  { "fluid": "minecraft:water" | "tag": "c:water", "amount": 250 },
 *   "results": [ { "id": "minecraft:flint", "count": 1, "chance": 0.25 } ],
 *   "fluid_result": { "fluid": "minecraft:water", "amount": 100 },
 *   "duration": 100, "energy": 20 }
 * </pre>
 */
public final class FfProcessRecipe implements Recipe<RecipeInput> {
    public record Input(Ingredient ingredient, int count, boolean catalyst) {
        static final Codec<Input> CODEC = RecordCodecBuilder.create(i -> i.group(
                Ingredient.CODEC.fieldOf("ingredient").forGetter(Input::ingredient),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(Input::count),
                Codec.BOOL.optionalFieldOf("catalyst", false).forGetter(Input::catalyst)).apply(i, Input::new));
        static final StreamCodec<RegistryFriendlyByteBuf, Input> STREAM = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, Input::ingredient, ByteBufCodecs.VAR_INT, Input::count, ByteBufCodecs.BOOL, Input::catalyst, Input::new);
    }

    public record FluidIn(Optional<Identifier> fluid, Optional<Identifier> tag, int amount) {
        static final Codec<FluidIn> CODEC = RecordCodecBuilder.<FluidIn>create(i -> i.group(
                Identifier.CODEC.optionalFieldOf("fluid").forGetter(FluidIn::fluid),
                Identifier.CODEC.optionalFieldOf("tag").forGetter(FluidIn::tag),
                Codec.intRange(1, 64_000).fieldOf("amount").forGetter(FluidIn::amount)).apply(i, FluidIn::new))
                .validate(f -> f.fluid.isPresent() != f.tag.isPresent() ? DataResult.success(f)
                        : DataResult.error(() -> "fluid_input needs exactly one of 'fluid' or 'tag'"))
                .validate(f -> f.fluid.isEmpty() || BuiltInRegistries.FLUID.containsKey(f.fluid.get()) ? DataResult.success(f)
                        : DataResult.error(() -> "Unknown fluid " + f.fluid.get()));
        static final StreamCodec<RegistryFriendlyByteBuf, FluidIn> STREAM = StreamCodec.composite(
                ByteBufCodecs.optional(Identifier.STREAM_CODEC), FluidIn::fluid,
                ByteBufCodecs.optional(Identifier.STREAM_CODEC), FluidIn::tag,
                ByteBufCodecs.VAR_INT, FluidIn::amount, FluidIn::new);

        public boolean matches(Fluid f) {
            if (fluid.isPresent()) {
                return fluid.get().equals(BuiltInRegistries.FLUID.getKey(f));
            }
            return f.is(TagKey.create(Registries.FLUID, tag.get()));
        }

        public String display() {
            return fluid.map(Identifier::toString).orElseGet(() -> "#" + tag.get());
        }
    }

    public record Result(ItemStackTemplate template, float chance) {
        static final Codec<Result> CODEC = RecordCodecBuilder.<Result>create(i -> i.group(
                ItemStackTemplate.MAP_CODEC.forGetter(Result::template),
                Codec.floatRange(0.0001f, 1f).optionalFieldOf("chance", 1f).forGetter(Result::chance)
        ).apply(i, Result::new)).validate(r -> r.template.count() >= 1 && r.template.count() <= 64 ? DataResult.success(r)
                : DataResult.error(() -> "result count must be 1..64"));
        static final StreamCodec<RegistryFriendlyByteBuf, Result> STREAM = StreamCodec.composite(
                ItemStackTemplate.STREAM_CODEC, Result::template, ByteBufCodecs.FLOAT, Result::chance, Result::new);

        public ItemStack stack() {
            return template.create();
        }
    }

    record FluidOut(Fluid fluid, int amount) {
        static final Codec<FluidOut> CODEC = RecordCodecBuilder.create(i -> i.group(
                BuiltInRegistries.FLUID.byNameCodec().fieldOf("fluid").forGetter(FluidOut::fluid),
                Codec.intRange(1, 64_000).fieldOf("amount").forGetter(FluidOut::amount)).apply(i, FluidOut::new));
    }

    private final String family;
    private final FfId familyId;
    private final List<Input> inputs;
    private final Optional<FluidIn> fluidInput;
    private final List<Result> results;
    /** Fluid and amount only: a FluidStack cannot be built while recipes load (components are not bound yet on 26.1). */
    private final Optional<FluidOut> fluidResult;
    private FluidStack fluidResultStack;
    private final int duration;
    private final int energy;

    public FfProcessRecipe(String family, FfId familyId, List<Input> inputs, Optional<FluidIn> fluidInput, List<Result> results,
                           Optional<FluidOut> fluidResult, int duration, int energy) {
        this.family = family;
        this.familyId = familyId;
        this.inputs = List.copyOf(inputs);
        this.fluidInput = fluidInput;
        this.results = List.copyOf(results);
        this.fluidResult = fluidResult;
        this.duration = duration;
        this.energy = energy;
    }

    public FfId familyId() {
        return familyId;
    }

    public List<Input> inputs() {
        return inputs;
    }

    public Optional<FluidIn> fluidInput() {
        return fluidInput;
    }

    public List<Result> results() {
        return results;
    }

    public FluidStack fluidResult() {
        if (fluidResultStack == null) {
            fluidResultStack = fluidResult.map(f -> new FluidStack(f.fluid(), f.amount())).orElse(FluidStack.EMPTY);
        }
        return fluidResultStack;
    }

    Optional<FluidOut> fluidResultSpec() {
        return fluidResult;
    }

    public int duration() {
        return duration;
    }

    public int energy() {
        return energy;
    }

    @Override
    public boolean matches(RecipeInput input, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(RecipeInput input) {
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0).stack();
    }

    @Override
    public boolean showNotification() {
        return false;
    }

    @Override
    public String group() {
        return "";
    }

    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return RecipeBookCategories.CRAFTING_MISC;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecipeSerializer<? extends Recipe<RecipeInput>> getSerializer() {
        return (RecipeSerializer<? extends Recipe<RecipeInput>>) (Object) FfContent.PROCESS_SERIALIZERS.get(family).get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecipeType<? extends Recipe<RecipeInput>> getType() {
        return (RecipeType<? extends Recipe<RecipeInput>>) (Object) FfContent.PROCESS_TYPES.get(family).get();
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    /** Builds the serializer for one family (26.1 serializers are records of codec + stream codec). */
    public static RecipeSerializer<FfProcessRecipe> serializer(String family) {
        Serializer s = new Serializer(family);
        return new RecipeSerializer<>(s.codec, s.stream);
    }

    static final class Serializer {
        private final String family;
        private final MapCodec<FfProcessRecipe> codec;
        private final StreamCodec<RegistryFriendlyByteBuf, FfProcessRecipe> stream;

        Serializer(String family) {
            this.family = family;
            boolean generic = family.equals("process");
            this.codec = RecordCodecBuilder.<FfProcessRecipe>mapCodec(i -> i.group(
                    Codec.STRING.optionalFieldOf("family", "").forGetter(r -> generic ? r.familyId.toString() : ""),
                    Input.CODEC.sizeLimitedListOf(ProcessRecipe.MAX_INPUTS).optionalFieldOf("inputs", List.of()).forGetter(FfProcessRecipe::inputs),
                    FluidIn.CODEC.optionalFieldOf("fluid_input").forGetter(FfProcessRecipe::fluidInput),
                    Result.CODEC.sizeLimitedListOf(ProcessRecipe.MAX_RESULTS).optionalFieldOf("results", List.of()).forGetter(FfProcessRecipe::results),
                    FluidOut.CODEC.optionalFieldOf("fluid_result").forGetter(FfProcessRecipe::fluidResultSpec),
                    Codec.intRange(1, 72_000).optionalFieldOf("duration", 100).forGetter(FfProcessRecipe::duration),
                    Codec.intRange(0, 1_000_000).optionalFieldOf("energy", 20).forGetter(FfProcessRecipe::energy)
            ).apply(i, (fam, ins, fin, res, fout, dur, en) -> new FfProcessRecipe(family, familyId(fam), ins, fin, res,
                    fout, dur, en))).validate(r -> {
                if (r.familyId == null) {
                    return DataResult.error(() -> "fieldframe:process recipes need a valid \"family\" id");
                }
                if (r.inputs.isEmpty() && r.fluidInput.isEmpty()) {
                    return DataResult.error(() -> "Recipe needs at least one input");
                }
                return DataResult.success(r);
            });
            this.stream = StreamCodec.of((buf, r) -> {
                buf.writeUtf(r.familyId.toString(), 256);
                Input.STREAM.apply(ByteBufCodecs.list(ProcessRecipe.MAX_INPUTS)).encode(buf, r.inputs);
                ByteBufCodecs.optional(FluidIn.STREAM).encode(buf, r.fluidInput);
                Result.STREAM.apply(ByteBufCodecs.list(ProcessRecipe.MAX_RESULTS)).encode(buf, r.results);
                buf.writeBoolean(r.fluidResult.isPresent());
                r.fluidResult.ifPresent(f -> {
                    buf.writeUtf(BuiltInRegistries.FLUID.getKey(f.fluid()).toString(), 256);
                    buf.writeVarInt(f.amount());
                });
                buf.writeVarInt(r.duration);
                buf.writeVarInt(r.energy);
            }, buf -> {
                FfId fam = FfId.tryParse(buf.readUtf(256));
                List<Input> ins = Input.STREAM.apply(ByteBufCodecs.list(ProcessRecipe.MAX_INPUTS)).decode(buf);
                Optional<FluidIn> fin = ByteBufCodecs.optional(FluidIn.STREAM).decode(buf);
                List<Result> res = Result.STREAM.apply(ByteBufCodecs.list(ProcessRecipe.MAX_RESULTS)).decode(buf);
                Optional<FluidOut> fout = buf.readBoolean()
                        ? Optional.of(new FluidOut(BuiltInRegistries.FLUID.getValue(net.minecraft.resources.Identifier.parse(buf.readUtf(256))), buf.readVarInt()))
                        : Optional.empty();
                return new FfProcessRecipe(family, fam == null ? FfId.ff(family) : fam, ins, fin, res, fout, buf.readVarInt(), buf.readVarInt());
            });
        }

        private FfId familyId(String fromJson) {
            if (!family.equals("process")) {
                return FfId.ff(family);
            }
            return FfId.tryParse(fromJson);
        }

    }
}
