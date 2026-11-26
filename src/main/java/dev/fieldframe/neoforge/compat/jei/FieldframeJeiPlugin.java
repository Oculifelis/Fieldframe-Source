package dev.fieldframe.neoforge.compat.jei;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.recipe.FfProcessRecipe;
import dev.fieldframe.neoforge.registry.FfContent;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JEI integration (loaded by JEI only): one category per process family with every Fieldframe recipe of that family
 * (including the Metallurgy mirror), an engine-fuels category from the synced fuels data, modules as crafting
 * stations (also for vanilla smelting, crafting, brewing and furnace fuel) and an information page per module.
 */
@JeiPlugin
public final class FieldframeJeiPlugin implements IModPlugin {
    /** The running JEI, for the automated client checks (null until JEI is ready). */
    public static volatile mezz.jei.api.runtime.IJeiRuntime runtime;

    static final Map<FfId, RecipeType<FfProcessRecipe>> TYPES = new LinkedHashMap<>();
    static final RecipeType<FuelView> FUELS = RecipeType.create(Fieldframe.MOD_ID, "engine_fuels", FuelView.class);
    static final RecipeType<AuraView> AURAS = RecipeType.create(Fieldframe.MOD_ID, "aura_chamber", AuraView.class);
    static final RecipeType<TableView> TREADMILL = RecipeType.create(Fieldframe.MOD_ID, "treadmill_animals", TableView.class);
    static final RecipeType<TableView> ELECTRODES = RecipeType.create(Fieldframe.MOD_ID, "galvanic_electrodes", TableView.class);
    static final RecipeType<TableView> TEMPERATURES = RecipeType.create(Fieldframe.MOD_ID, "temperatures", TableView.class);
    static final RecipeType<dev.fieldframe.neoforge.feature.flora.FluidArmorRows.Row> FLUID_ARMOR =
            RecipeType.create(Fieldframe.MOD_ID, "fluid_armor", dev.fieldframe.neoforge.feature.flora.FluidArmorRows.Row.class);

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Fieldframe.MOD_ID, "jei");
    }

    static RecipeType<FfProcessRecipe> type(FfId family) {
        return TYPES.computeIfAbsent(family, f -> RecipeType.create(Fieldframe.MOD_ID, f.namespace() + "_" + f.path(), FfProcessRecipe.class));
    }

    /** Process family -> the modules that run it (first one is the category icon). */
    static Map<FfId, List<FfId>> families() {
        Map<FfId, List<FfId>> out = new LinkedHashMap<>();
        for (ModuleDefinition d : FieldframeRegistry.modules()) {
            if (d.processFamily() != null && FfContent.moduleItem(d.id()) != null) {
                out.computeIfAbsent(d.processFamily(), k -> new ArrayList<>()).add(d.id());
            }
        }
        return out;
    }

    static Item item(FfId module) {
        return FfContent.moduleItem(module);
    }

    static net.minecraft.world.item.ItemStack stack(FfId module) {
        return new net.minecraft.world.item.ItemStack(item(module));
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration r) {
        var gui = r.getJeiHelpers().getGuiHelper();
        families().forEach((family, modules) -> r.addRecipeCategories(new ProcessCategory(type(family), family, item(modules.get(0)), gui)));
        r.addRecipeCategories(new FuelCategory(FUELS, item(BuiltinModules.FLUID_ENGINE), gui));
        r.addRecipeCategories(new AuraCategory(AURAS, iconFor(AURAS, BuiltinModules.AURA_CHAMBER), gui));
        r.addRecipeCategories(new TableCategory(TREADMILL, "fieldframe.jei.treadmill", iconFor(TREADMILL, dev.fieldframe.core.modules.power.Treadmill.ID), gui));
        r.addRecipeCategories(new TableCategory(ELECTRODES, "fieldframe.jei.electrodes",
                iconFor(ELECTRODES, dev.fieldframe.core.modules.power.GalvanicCell.ID), gui));
        r.addRecipeCategories(new TableCategory(TEMPERATURES, "fieldframe.jei.temperatures",
                iconFor(TEMPERATURES, dev.fieldframe.core.modules.power.HeatPower.HOT_JUNCTION), gui));
        r.addRecipeCategories(new FluidArmorCategory(FLUID_ARMOR, dev.fieldframe.neoforge.feature.FeatureContent.ARMOR.get("gold_fluid_chestplate").get(),
                item(BuiltinModules.ARMOR_INFUSER), gui));
    }

    /** Modules whose presentation hint {@code viewer_pages} names a page (a JEI category id). */
    static List<Item> modulesOnPage(ResourceLocation page) {
        List<Item> out = new ArrayList<>();
        for (ModuleDefinition d : FieldframeRegistry.modules()) {
            Item it = item(d.id());
            if (it != null && d.presentation().getList("viewer_pages").contains(page.toString())) {
                out.add(it);
            }
        }
        return out;
    }

    /** A page's icon: the first module listed on it (fallback while none is). */
    private static Item iconFor(RecipeType<?> type, FfId fallback) {
        List<Item> on = modulesOnPage(type.getUid());
        return on.isEmpty() ? item(fallback) : on.get(0);
    }

    @Override
    public void registerRecipes(IRecipeRegistration r) {
        Map<FfId, List<FfProcessRecipe>> byFamily = new LinkedHashMap<>();
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            for (var holder : FfContent.PROCESS_TYPES.values()) {
                for (var rh : level.getRecipeManager().getAllRecipesFor(holder.get())) {
                    FfProcessRecipe recipe = rh.value();
                    if (dev.fieldframe.core.material.RecipeFilters.removed(dev.fieldframe.api.id.FfId.of(rh.id().getNamespace(), rh.id().getPath()), recipe.familyId())) {
                        continue;
                    }
                    byFamily.computeIfAbsent(recipe.familyId(), k -> new ArrayList<>()).add(recipe);
                }
            }
            for (var rh : dev.fieldframe.neoforge.recipe.MetallurgyMirror.read(level.getRecipeManager(), level.registryAccess()).processes()) {
                byFamily.computeIfAbsent(rh.value().familyId(), k -> new ArrayList<>()).add(rh.value());
            }
        }
        for (FfId family : families().keySet()) {
            r.addRecipes(type(family), byFamily.getOrDefault(family, List.of()));
        }
        List<FuelView> fuels = FuelView.all();
        r.addRecipes(FUELS, fuels);
        r.addRecipes(AURAS, AuraView.all());
        // Data tables: like the fuels, read from the loaded server data (single player, or once synced).
        r.addRecipes(TREADMILL, TableView.treadmill());
        r.addRecipes(ELECTRODES, TableView.electrodes());
        r.addRecipes(TEMPERATURES, TableView.temperatures());
        r.addRecipes(FLUID_ARMOR, dev.fieldframe.neoforge.feature.flora.FluidArmorRows.all());
        Fieldframe.LOG.info("FIELDFRAME_JEI categories={} recipes={} fuels={}", families().size() + 2,
                byFamily.values().stream().mapToInt(List::size).sum(), fuels.size());
        for (ModuleDefinition d : FieldframeRegistry.modules()) {
            Item it = FfContent.moduleItem(d.id());
            if (it != null) {
                r.addIngredientInfo(new net.minecraft.world.item.ItemStack(it), mezz.jei.api.constants.VanillaTypes.ITEM_STACK, Component.translatable("module." + d.id().namespace() + "." + d.id().path() + ".desc"),
                        Component.translatable("fieldframe.jei.module_info"));
            }
        }
        bucketInfo(r, byFamily);
    }

    private static final FfId TRANSPOSING = FfId.ff("transposing");
    /** Fluids a machine gathers from the world rather than through a recipe. */
    private static final Map<String, FfId> WORLD_SOURCES = Map.of("steam", dev.fieldframe.core.modules.power.HeatPower.BOILER,
            "tree_sap", BuiltinModules.TREE_TAPPER, "latex", BuiltinModules.TREE_TAPPER);

    /** Fluid buckets have no crafting recipe: name the machines that make the fluid (RV2-01). Logs any bucket nothing makes. */
    private static void bucketInfo(IRecipeRegistration r, Map<FfId, List<FfProcessRecipe>> byFamily) {
        Map<Item, net.minecraft.world.level.material.Fluid> buckets = new LinkedHashMap<>();
        for (var s : dev.fieldframe.neoforge.feature.FeatureContent.FLUID_SETS.values()) {
            buckets.put(s.bucket().get(), s.source().get());
        }
        for (var s : dev.fieldframe.neoforge.feature.MaterialContent.FLUID_SETS.values()) {
            buckets.put(s.bucket().get(), s.source().get());
        }
        Map<FfId, List<FfId>> fam = families();
        List<String> orphans = new ArrayList<>();
        buckets.forEach((bucket, fluid) -> {
            java.util.Set<Item> makers = new java.util.LinkedHashSet<>();
            FfId extra = WORLD_SOURCES.get(net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid).getPath());
            if (extra != null && item(extra) != null) {
                makers.add(item(extra));
            }
            byFamily.forEach((family, recipes) -> {
                // The Transposer only empties buckets into fluid, so it is not a source.
                if (!family.equals(TRANSPOSING) && recipes.stream().anyMatch(rc -> !rc.fluidResult().isEmpty() && rc.fluidResult().getFluid().isSame(fluid))) {
                    for (FfId m : fam.getOrDefault(family, List.of())) {
                        makers.add(item(m));
                    }
                }
            });
            if (makers.isEmpty()) {
                orphans.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bucket)));
                return;
            }
            net.minecraft.network.chat.MutableComponent names = Component.empty();
            for (Item m : makers) {
                if (!names.getSiblings().isEmpty()) {
                    names.append(", ");
                }
                names.append(new net.minecraft.world.item.ItemStack(m).getHoverName());
            }
            Component msg = Component.translatable("fieldframe.jei.bucket_info", names);
            r.addIngredientInfo(new net.minecraft.world.item.ItemStack(bucket), mezz.jei.api.constants.VanillaTypes.ITEM_STACK, msg);
        });
        if (!orphans.isEmpty()) {
            Fieldframe.LOG.info("FIELDFRAME_JEI buckets nothing makes: {}", orphans);
        }
    }

    @Override
    public void registerGuiHandlers(mezz.jei.api.registration.IGuiHandlerRegistration r) {
        r.addGhostIngredientHandler(dev.fieldframe.neoforge.client.PatternScreen.class, new PatternGhostHandler());
        r.addGhostIngredientHandler(dev.fieldframe.neoforge.client.FilterScreen.class, new FilterGhostHandler());
    }

    @Override
    public void onRuntimeAvailable(mezz.jei.api.runtime.IJeiRuntime r) {
        runtime = r;
    }

    /**
     * Opens any JEI page by its id, e.g. an addon's (automated client check). Returns the number of entries on it, or -1
     * when JEI or the page is missing.
     */
    public static int showPage(ResourceLocation id) {
        var rt = runtime;
        if (rt == null) {
            return -1;
        }
        var type = rt.getJeiHelpers().getRecipeType(id).orElse(null);
        if (type == null) {
            return -1;
        }
        rt.getRecipesGui().showTypes(List.of(type));
        return (int) rt.getRecipeManager().createRecipeLookup(type).get().count();
    }

    /** Opens an item's information page (e.g. an addon's build guide); false without JEI or without such a page. */
    public static boolean showInfo(net.minecraft.world.item.Item item) {
        var rt = runtime;
        if (rt == null) {
            return false;
        }
        var stack = new net.minecraft.world.item.ItemStack(item);
        var factory = rt.getJeiHelpers().getFocusFactory();
        List<mezz.jei.api.recipe.IFocus<?>> focuses = List.of(
                factory.createFocus(mezz.jei.api.recipe.RecipeIngredientRole.INPUT, mezz.jei.api.constants.VanillaTypes.ITEM_STACK, stack),
                factory.createFocus(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT, mezz.jei.api.constants.VanillaTypes.ITEM_STACK, stack));
        var info = mezz.jei.api.constants.RecipeTypes.INFORMATION;
        var pages = rt.getRecipeManager().createRecipeLookup(info).limitFocus(focuses).get().toList();
        if (pages.isEmpty()) {
            return false;
        }
        rt.getRecipesGui().showRecipes(rt.getRecipeManager().getRecipeCategory(info), pages, focuses);
        return true;
    }

    /** Opens the Fluid Armor page (automated client check); the number of entries on it, or -1 without JEI. */
    public static int showFluidArmor() {
        return showPage(FLUID_ARMOR.getUid());
    }

    /** Opens the Fluid Armor page on the rows of these fluids (automated client check); false without JEI or rows. */
    public static boolean showFluidArmor(java.util.Set<String> fluids) {
        var rt = runtime;
        if (rt == null) {
            return false;
        }
        var rows = dev.fieldframe.neoforge.feature.flora.FluidArmorRows.all().stream().filter(r -> fluids.contains(r.id().toString())).toList();
        if (rows.isEmpty()) {
            return false;
        }
        rt.getRecipesGui().showRecipes(rt.getRecipeManager().getRecipeCategory(FLUID_ARMOR), rows, List.of());
        return true;
    }

    /** Opens the Aura Chamber page (automated client check). */
    public static boolean showAuras() {
        var rt = runtime;
        if (rt == null) {
            return false;
        }
        rt.getRecipesGui().showTypes(List.of(AURAS));
        return true;
    }

    /** Opens a data-table page: "treadmill", "electrodes" or "temperatures" (automated client check). */
    public static boolean showTable(String which) {
        var rt = runtime;
        if (rt == null) {
            return false;
        }
        RecipeType<TableView> t = switch (which) {
            case "treadmill" -> TREADMILL;
            case "electrodes" -> ELECTRODES;
            default -> TEMPERATURES;
        };
        rt.getRecipesGui().showTypes(List.of(t));
        return true;
    }

    /** True when JEI shows a page for this process family. */
    public static boolean hasFamily(FfId family) {
        return TYPES.containsKey(family) || dev.fieldframe.api.process.ProcessFamilies.HEATING.equals(family);
    }

    public static boolean showFamily(FfId family) {
        var rt = runtime;
        if (rt != null && dev.fieldframe.api.process.ProcessFamilies.HEATING.equals(family)) {
            // The Heater smelts with the furnace's own recipes.
            rt.getRecipesGui().showTypes(List.of(RecipeTypes.SMELTING));
            return true;
        }
        if (rt == null || !TYPES.containsKey(family)) {
            return false;
        }
        rt.getRecipesGui().showTypes(List.of(type(family)));
        return true;
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration r) {
        families().forEach((family, modules) -> modules.forEach(m -> r.addRecipeCatalyst(stack(m), type(family))));
        r.addRecipeCatalyst(stack(BuiltinModules.HEATER), RecipeTypes.SMELTING);
        r.addRecipeCatalyst(stack(BuiltinModules.RECIPE_ASSEMBLER), RecipeTypes.CRAFTING);
        r.addRecipeCatalyst(stack(BuiltinModules.BREWER), RecipeTypes.BREWING);
        r.addRecipeCatalyst(stack(BuiltinModules.FUEL_ENGINE), RecipeTypes.FUELING);
        r.addRecipeCatalyst(stack(BuiltinModules.ARMOR_INFUSER), FLUID_ARMOR);
        // Every module's presentation hint viewer_pages (built-in, addon or KubeJS): any JEI page, ours or another mod's.
        for (ModuleDefinition d : FieldframeRegistry.modules()) {
            Item it = item(d.id());
            if (it == null) {
                continue;
            }
            for (Object page : d.presentation().getList("viewer_pages")) {
                ResourceLocation id = ResourceLocation.tryParse(String.valueOf(page));
                if (id != null) {
                    r.getJeiHelpers().getRecipeType(id).ifPresent(t -> r.addRecipeCatalyst(new net.minecraft.world.item.ItemStack(it), t));
                }
            }
        }
        for (FfId engine : FuelView.engines()) {
            Item it = item(engine);
            if (it != null) {
                r.addRecipeCatalyst(new net.minecraft.world.item.ItemStack(it), FUELS);
            }
        }
    }
}
