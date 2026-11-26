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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
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
                Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(Input::ingredient),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(Input::count),
                Codec.BOOL.optionalFieldOf("catalyst", false).forGetter(Input::catalyst)).apply(i, Input::new));
        static final StreamCodec<RegistryFriendlyByteBuf, Input> STREAM = StreamCodec.composite(
                Ingredient.CONTENTS_STREAM_CODEC, Input::ingredient, ByteBufCodecs.VAR_INT, Input::count, ByteBufCodecs.BOOL, Input::catalyst, Input::new);
    }

    public record FluidIn(Optional<ResourceLocation> fluid, Optional<ResourceLocation> tag, int amount) {
        static final Codec<FluidIn> CODEC = RecordCodecBuilder.<FluidIn>create(i -> i.group(
                ResourceLocation.CODEC.optionalFieldOf("fluid").forGetter(FluidIn::fluid),
                ResourceLocation.CODEC.optionalFieldOf("tag").forGetter(FluidIn::tag),
                Codec.intRange(1, 64_000).fieldOf("amount").forGetter(FluidIn::amount)).apply(i, FluidIn::new))
                .validate(f -> f.fluid.isPresent() != f.tag.isPresent() ? DataResult.success(f)
                        : DataResult.error(() -> "fluid_input needs exactly one of 'fluid' or 'tag'"))
                .validate(f -> f.fluid.isEmpty() || BuiltInRegistries.FLUID.containsKey(f.fluid.get()) ? DataResult.success(f)
                        : DataResult.error(() -> "Unknown fluid " + f.fluid.get()));
        static final StreamCodec<RegistryFriendlyByteBuf, FluidIn> STREAM = StreamCodec.composite(
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), FluidIn::fluid,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), FluidIn::tag,
                ByteBufCodecs.VAR_INT, FluidIn::amount, FluidIn::new);

        public boolean matches(Fluid f) {
            if (fluid.isPresent()) {
                return fluid.get().equals(BuiltInRegistries.FLUID.getKey(f));
            }
            return f.is(TagKey.create(Registries.FLUID, tag.get()));
        }

        public String display() {
            return fluid.map(ResourceLocation::toString).orElseGet(() -> "#" + tag.get());
        }
    }

    public record Result(ItemStack stack, float chance) {
        private static final Codec<Item> ITEM = BuiltInRegistries.ITEM.byNameCodec();
        static final Codec<Result> CODEC = RecordCodecBuilder.<Result>create(i -> i.group(
                ITEM.fieldOf("id").forGetter(r -> r.stack.getItem()),
                Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(r -> r.stack.getCount()),
                Codec.floatRange(0.0001f, 1f).optionalFieldOf("chance", 1f).forGetter(Result::chance)
        ).apply(i, (item, count, chance) -> new Result(new ItemStack(item, count), chance)));
        static final StreamCodec<RegistryFriendlyByteBuf, Result> STREAM = StreamCodec.composite(
                ItemStack.STREAM_CODEC, Result::stack, ByteBufCodecs.FLOAT, Result::chance, Result::new);
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
    private final FluidStack fluidResult;
    private final int duration;
    private final int energy;

    public FfProcessRecipe(String family, FfId familyId, List<Input> inputs, Optional<FluidIn> fluidInput, List<Result> results,
                           FluidStack fluidResult, int duration, int energy) {
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
    public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0).stack().copy();
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) {
        return false;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return results.isEmpty() ? ItemStack.EMPTY : results.get(0).stack().copy();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> l = NonNullList.create();
        inputs.forEach(i -> l.add(i.ingredient()));
        return l;
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
        private final MapCodec<FfProcessRecipe> codec;
        private final StreamCodec<RegistryFriendlyByteBuf, FfProcessRecipe> stream;

        public Serializer(String family) {
            this.family = family;
            boolean generic = family.equals("process");
            this.codec = RecordCodecBuilder.<FfProcessRecipe>mapCodec(i -> i.group(
                    Codec.STRING.optionalFieldOf("family", "").forGetter(r -> generic ? r.familyId.toString() : ""),
                    Input.CODEC.sizeLimitedListOf(ProcessRecipe.MAX_INPUTS).optionalFieldOf("inputs", List.of()).forGetter(FfProcessRecipe::inputs),
                    FluidIn.CODEC.optionalFieldOf("fluid_input").forGetter(FfProcessRecipe::fluidInput),
                    Result.CODEC.sizeLimitedListOf(ProcessRecipe.MAX_RESULTS).optionalFieldOf("results", List.of()).forGetter(FfProcessRecipe::results),
                    FluidOut.CODEC.optionalFieldOf("fluid_result").forGetter(r -> r.fluidResult.isEmpty() ? Optional.empty()
                            : Optional.of(new FluidOut(r.fluidResult.getFluid(), r.fluidResult.getAmount()))),
                    Codec.intRange(1, 72_000).optionalFieldOf("duration", 100).forGetter(FfProcessRecipe::duration),
                    Codec.intRange(0, 1_000_000).optionalFieldOf("energy", 20).forGetter(FfProcessRecipe::energy)
            ).apply(i, (fam, ins, fin, res, fout, dur, en) -> new FfProcessRecipe(family, familyId(fam), ins, fin, res,
                    fout.map(f -> new FluidStack(f.fluid(), f.amount())).orElse(FluidStack.EMPTY), dur, en))).validate(r -> {
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
                FluidStack.OPTIONAL_STREAM_CODEC.encode(buf, r.fluidResult);
                buf.writeVarInt(r.duration);
                buf.writeVarInt(r.energy);
            }, buf -> {
                FfId fam = FfId.tryParse(buf.readUtf(256));
                List<Input> ins = Input.STREAM.apply(ByteBufCodecs.list(ProcessRecipe.MAX_INPUTS)).decode(buf);
                Optional<FluidIn> fin = ByteBufCodecs.optional(FluidIn.STREAM).decode(buf);
                List<Result> res = Result.STREAM.apply(ByteBufCodecs.list(ProcessRecipe.MAX_RESULTS)).decode(buf);
                FluidStack fout = FluidStack.OPTIONAL_STREAM_CODEC.decode(buf);
                return new FfProcessRecipe(family, fam == null ? FfId.ff(family) : fam, ins, fin, res, fout, buf.readVarInt(), buf.readVarInt());
            });
        }

        private FfId familyId(String fromJson) {
            if (!family.equals("process")) {
                return FfId.ff(family);
            }
            return FfId.tryParse(fromJson);
        }

        @Override
        public MapCodec<FfProcessRecipe> codec() {
            return codec;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, FfProcessRecipe> streamCodec() {
            return stream;
        }
    }
}
