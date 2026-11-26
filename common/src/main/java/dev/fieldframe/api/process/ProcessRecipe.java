package dev.fieldframe.api.process;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidMatcher;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemMatcher;
import dev.fieldframe.api.resource.ItemVariant;

import java.util.List;

/**
 * A loader-independent processing recipe, produced by platform adapters from the effective server recipe
 * data (vanilla smelting, Fieldframe recipe types, datapack and KubeJS changes) or supplied by addons.
 */
public record ProcessRecipe(
        FfId id,
        FfId family,
        List<ItemInput> itemInputs,
        FluidInput fluidInput,
        List<ItemResult> itemResults,
        FluidResult fluidResult,
        int durationTicks,
        long energyPerTick) {

    public static final int MAX_INPUTS = 4;
    public static final int MAX_RESULTS = 6;

    /** {@code consumed=false} marks a catalyst: required present, never consumed. */
    /** {@code examples}: a few matching variants for display (recipe panels, test bench); never used for matching. */
    public record ItemInput(ItemMatcher matcher, int count, boolean consumed, String display, List<ItemVariant> examples) {
        public ItemInput {
            if (count < 1 || count > 64) {
                throw new IllegalArgumentException("input count");
            }
            display = display == null ? "" : display;
            examples = examples == null ? List.of() : List.copyOf(examples.subList(0, Math.min(8, examples.size())));
        }

        public ItemInput(ItemMatcher matcher, int count, boolean consumed, String display) {
            this(matcher, count, consumed, display, List.of());
        }
    }

    public record FluidInput(FluidMatcher matcher, long amount, String display) {
        public FluidInput {
            if (amount < 1 || amount > 64_000) {
                throw new IllegalArgumentException("fluid amount");
            }
            display = display == null ? "" : display;
        }
    }

    /** A product. {@code chance} in (0,1]; chance outputs are rolled once when the operation is reserved. */
    public record ItemResult(ItemVariant item, int count, double chance) {
        public ItemResult {
            if (count < 1 || count > 64 || !(chance > 0 && chance <= 1)) {
                throw new IllegalArgumentException("result");
            }
        }
    }

    public record FluidResult(FluidVariant fluid, long amount) {
        public FluidResult {
            if (amount < 1 || amount > 64_000) {
                throw new IllegalArgumentException("fluid result amount");
            }
        }
    }

    public ProcessRecipe {
        itemInputs = List.copyOf(itemInputs);
        itemResults = List.copyOf(itemResults);
        if (itemInputs.size() > MAX_INPUTS || itemResults.size() > MAX_RESULTS) {
            throw new IllegalArgumentException(id + ": too many inputs/results");
        }
        if (itemInputs.isEmpty() && fluidInput == null) {
            throw new IllegalArgumentException(id + ": recipe has no inputs");
        }
        if (durationTicks < 1 || durationTicks > 72_000) {
            throw new IllegalArgumentException(id + ": duration");
        }
        if (energyPerTick < 0 || energyPerTick > 1_000_000) {
            throw new IllegalArgumentException(id + ": energy");
        }
    }

    public long totalEnergy() {
        return energyPerTick * durationTicks;
    }
}
