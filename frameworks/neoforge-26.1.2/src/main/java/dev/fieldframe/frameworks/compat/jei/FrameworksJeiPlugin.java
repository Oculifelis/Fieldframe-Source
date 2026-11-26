package dev.fieldframe.frameworks.compat.jei;

import dev.fieldframe.api.frame.FrameComponents;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.frameworks.ArcAlloys;
import dev.fieldframe.frameworks.FoundryAlloys;
import dev.fieldframe.frameworks.Frameworks;
import dev.fieldframe.frameworks.FwContent;
import dev.fieldframe.frameworks.FwFluids;
import dev.fieldframe.frameworks.FwTuning;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JEI integration (loaded by JEI only): the Foundry's and Arc Furnace's alloys and "how to build" pages. Everything shown comes from data:
 * alloys and tuning synced from the server, frame components (any mod's, after pack changes) with what each adds and its
 * role. The Starcore modules get Fieldframe's own module pages, like any addon module.
 */
@JeiPlugin
public final class FrameworksJeiPlugin implements IModPlugin {
    static final IRecipeType<FoundryAlloys.Alloy> ALLOYS = IRecipeType.create(Frameworks.MOD_ID, "foundry_alloys", FoundryAlloys.Alloy.class);
    static final IRecipeType<ArcAlloys.Alloy> ARC_ALLOYS = IRecipeType.create(Frameworks.MOD_ID, "arc_alloys", ArcAlloys.Alloy.class);

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(Frameworks.MOD_ID, "jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration r) {
        r.addRecipeCategories(new AlloyCategory(ALLOYS, FwContent.CRUCIBLE_ITEM.get(), r.getJeiHelpers().getGuiHelper()));
        r.addRecipeCategories(new ArcAlloyCategory(ARC_ALLOYS, FwContent.ELECTRODE_ITEM.get(), r.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration r) {
        r.addRecipes(ALLOYS, FoundryAlloys.all());
        r.addRecipes(ARC_ALLOYS, ArcAlloys.all());
        String minSize = n(FwTuning.getInt("grand_frame.min_size"));
        String maxSize = n(FwTuning.getInt("grand_frame.max_size"));
        r.addIngredientInfo(FwContent.CASING_ITEM.get(), Component.translatable("fieldframe_frameworks.jei.info.frame_casing", minSize, maxSize));
        r.addIngredientInfo(FwContent.CORE_ITEM.get(), Component.translatable("fieldframe_frameworks.jei.info.frame_core", minSize, maxSize,
                n(FwTuning.getInt("grand_frame.base_slots")), n(FwTuning.getLong("grand_frame.base_mb")), n(FwTuning.getLong("grand_frame.base_rf"))));
        String minSide = n(FwTuning.getInt("gantry.min_side"));
        String maxSide = n(FwTuning.getInt("gantry.max_side"));
        r.addIngredientInfo(FwContent.GANTRY_CONTROLLER_ITEM.get(), Component.translatable("fieldframe_frameworks.jei.info.gantry_controller",
                minSide, maxSide, n(FwTuning.getInt("gantry.quarry_depth")), n(FwTuning.getInt("gantry.farm_depth"))));
        r.addIngredientInfo(FwContent.GANTRY_RAIL_ITEM.get(), Component.translatable("fieldframe_frameworks.jei.info.gantry_rail", minSide, maxSide));
        r.addIngredientInfo(FwFluids.DEUTERIUM_BUCKET.get(), Component.translatable("fieldframe_frameworks.jei.info.deuterium_bucket"));
        // Every frame component, from any mod and after pack changes: what it adds, plus its role's own page.
        for (Map.Entry<FfId, FrameComponents.Component> e : FrameComponents.effective().entrySet()) {
            Item item = item(e.getKey());
            if (item != Items.AIR) {
                r.addIngredientInfo(item, componentInfo(e.getValue()).toArray(new Component[0]));
            }
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration r) {
        r.addCraftingStation(ALLOYS, FwContent.CORE_ITEM.get());
        r.addCraftingStation(ARC_ALLOYS, FwContent.CORE_ITEM.get());
        for (Map.Entry<FfId, FrameComponents.Component> e : FrameComponents.effective().entrySet()) {
            Item item = item(e.getKey());
            if (e.getValue().role().equals("crucible") && item != Items.AIR) {
                r.addCraftingStation(ALLOYS, item);
            }
            if (e.getValue().role().equals("arc_electrode") && item != Items.AIR) {
                r.addCraftingStation(ARC_ALLOYS, item);
            }
        }
    }

    private static Item item(FfId block) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath(block.namespace(), block.path())).orElse(Items.AIR);
    }

    private static List<Component> componentInfo(FrameComponents.Component c) {
        List<String> adds = new ArrayList<>();
        if (c.slotsPerLane() > 0) {
            adds.add(Component.translatable("fieldframe_frameworks.jei.adds.slots", n(c.slotsPerLane())).getString());
        }
        if (c.mbPerTank() > 0) {
            adds.add(Component.translatable("fieldframe_frameworks.jei.adds.mb", n(c.mbPerTank())).getString());
        }
        if (c.rfCapacity() > 0) {
            adds.add(Component.translatable("fieldframe_frameworks.jei.adds.rf", n(c.rfCapacity())).getString());
        }
        if (c.speed() > 0) {
            adds.add(Component.translatable("fieldframe_frameworks.jei.adds.speed", String.format(Locale.ROOT, "%.0f", c.speed() * 100)).getString());
        }
        List<Component> out = new ArrayList<>();
        if (!adds.isEmpty()) {
            out.add(Component.translatable("fieldframe_frameworks.jei.info.component", String.join(", ", adds)));
        }
        switch (c.role()) {
            case "crucible" -> out.add(Component.translatable("fieldframe_frameworks.jei.info.role.crucible",
                    n(FwTuning.getLong("foundry.mb_per_crucible")), n(FwTuning.getLong("foundry.heat_rf")), n(FwTuning.getLong("foundry.ore_bonus_mb"))));
            case "coil" -> out.add(Component.translatable("fieldframe_frameworks.jei.info.role.coil",
                    n(Math.round(FwTuning.get("starcore.field_per_coil"))), n(FwTuning.getLong("starcore.ignition_rf")),
                    n(Math.round(FwTuning.get("starcore.ignition_fuel"))), n(FwTuning.getInt("starcore.escapes_per_coil"))));
            case "cooling_fin" -> out.add(Component.translatable("fieldframe_frameworks.jei.info.role.cooling_fin",
                    n(FwTuning.getLong("cooling_tower.mb_per_fin"))));
            case "arc_electrode" -> out.add(Component.translatable("fieldframe_frameworks.jei.info.role.arc_electrode",
                    n(FwTuning.getLong("arc_furnace.arc_rf")), n(FwTuning.getInt("arc_furnace.items_per_electrode")),
                    String.format(Locale.ROOT, "%.1f", FwTuning.get("arc_furnace.interval") / 20.0), n(ArcAlloys.all().size())));
            default -> {
            }
        }
        if (out.isEmpty()) {
            out.add(Component.translatable("fieldframe_frameworks.jei.info.component_role", c.role()));
        }
        return out;
    }

    private static String n(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }
}
