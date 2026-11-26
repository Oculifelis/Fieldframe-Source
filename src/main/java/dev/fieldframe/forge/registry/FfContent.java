package dev.fieldframe.forge.registry;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.UpgradeDefinition;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.chassis.ChassisBlock;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import dev.fieldframe.forge.item.ConfiguratorItem;
import dev.fieldframe.forge.item.ManualItem;
import dev.fieldframe.forge.item.ModuleItem;
import dev.fieldframe.forge.item.UpgradeItem;
import dev.fieldframe.forge.menu.LaneAccessMenu;
import dev.fieldframe.forge.recipe.FfProcessRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** All Forge registrations. Module and upgrade items are created from the frozen Fieldframe registry. */
public final class FfContent {
    private FfContent() {
    }

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Fieldframe.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Fieldframe.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Fieldframe.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, Fieldframe.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Fieldframe.MOD_ID);
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES = DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, Fieldframe.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, Fieldframe.MOD_ID);
    public static final RegistryObject<RecipeSerializer<dev.fieldframe.forge.recipe.KeepingShapelessRecipe>> KEEPING_SHAPELESS =
            RECIPE_SERIALIZERS.register("keeping_shapeless", () -> dev.fieldframe.forge.recipe.KeepingShapelessRecipe.SERIALIZER);
    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Fieldframe.MOD_ID);
    private static final Map<String, RegistryObject<net.minecraft.sounds.SoundEvent>> SOUND_HOLDERS = new LinkedHashMap<>();

    static {
        for (String name : dev.fieldframe.core.chassis.MachineLook.SOUND_EVENTS) {
            SOUND_HOLDERS.put(name, SOUNDS.register(name, () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(new ResourceLocation(Fieldframe.MOD_ID, name))));
        }
    }

    /** A Fieldframe sound event by name (see MachineLook.SOUND_EVENTS and sounds.json). */
    public static net.minecraft.sounds.SoundEvent sound(String name) {
        return SOUND_HOLDERS.get(name).get();
    }

    public static final RegistryObject<ChassisBlock> CHASSIS = BLOCKS.register("chassis", () -> new ChassisBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final RegistryObject<BlockItem> CHASSIS_ITEM = ITEMS.register("chassis", () -> new BlockItem(CHASSIS.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<ChassisBlockEntity>> CHASSIS_BE = BLOCK_ENTITIES.register("chassis",
            () -> BlockEntityType.Builder.of(ChassisBlockEntity::new, CHASSIS.get()).build(null));
    public static final RegistryObject<dev.fieldframe.forge.item.FrameBoltItem> FRAME_BOLT = ITEMS.register("frame_bolt",
            () -> new dev.fieldframe.forge.item.FrameBoltItem(new Item.Properties().stacksTo(64)));
    public static final RegistryObject<ConfiguratorItem> CONFIGURATOR = ITEMS.register("configurator", () -> new ConfiguratorItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<ManualItem> MANUAL = ITEMS.register("field_manual", () -> new ManualItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<dev.fieldframe.forge.item.BlueprintItem> BLUEPRINT = ITEMS.register("blueprint_sheet",
            () -> new dev.fieldframe.forge.item.BlueprintItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> BLANK_MODULE = ITEMS.register("blank_module", () -> new Item(new Item.Properties()));
    public static final RegistryObject<dev.fieldframe.forge.item.ProgramCardItem> PROGRAM_CARD = ITEMS.register("program_card",
            () -> new dev.fieldframe.forge.item.ProgramCardItem(new Item.Properties().stacksTo(16)));
    public static final RegistryObject<dev.fieldframe.forge.item.FieldKitItem> FIELD_KIT = ITEMS.register("field_kit",
            () -> new dev.fieldframe.forge.item.FieldKitItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<MenuType<LaneAccessMenu>> LANE_MENU = MENUS.register("lane_access",
            () -> IForgeMenuType.create(LaneAccessMenu::fromNetwork));
    public static final RegistryObject<MenuType<dev.fieldframe.forge.menu.WorkshopMenu>> WORKSHOP_MENU = MENUS.register("workshop",
            () -> IForgeMenuType.create(dev.fieldframe.forge.menu.WorkshopMenu::fromNetwork));

    public static final Map<String, RegistryObject<RecipeType<FfProcessRecipe>>> PROCESS_TYPES = new LinkedHashMap<>();
    public static final Map<String, RegistryObject<RecipeSerializer<FfProcessRecipe>>> PROCESS_SERIALIZERS = new LinkedHashMap<>();

    static {
        for (String family : new String[]{"heating", "crushing", "pressing", "mixing", "washing", "process"}) {
            PROCESS_TYPES.put(family, RECIPE_TYPES.register(family, () -> RecipeType.simple(new ResourceLocation(Fieldframe.MOD_ID, family))));
            PROCESS_SERIALIZERS.put(family, RECIPE_SERIALIZERS.register(family, () -> new FfProcessRecipe.Serializer(family)));
        }
    }

    private static final Map<FfId, ModuleItem> MODULE_ITEMS = new LinkedHashMap<>();
    private static final Map<FfId, UpgradeItem> UPGRADE_ITEMS = new LinkedHashMap<>();
    private static final Map<FfId, dev.fieldframe.forge.item.TierKitItem> TIER_KITS = new LinkedHashMap<>();

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.fieldframe"))
            .icon(() -> new ItemStack(CHASSIS_ITEM.get()))
            .displayItems((params, out) -> {
                out.accept(CHASSIS_ITEM.get());
                out.accept(CONFIGURATOR.get());
                out.accept(MANUAL.get());
                out.accept(BLANK_MODULE.get());
                out.accept(BLUEPRINT.get());
                out.accept(FRAME_BOLT.get());
                out.accept(PROGRAM_CARD.get());
                out.accept(FIELD_KIT.get());
                MODULE_ITEMS.values().forEach(out::accept);
                UPGRADE_ITEMS.values().forEach(out::accept);
                TIER_KITS.values().forEach(out::accept);
                dev.fieldframe.forge.feature.FeatureContent.addToTab(out);
                dev.fieldframe.forge.feature.MaterialContent.addToTab(out);
            }).build());

    public static Map<FfId, ModuleItem> moduleItems() {
        return Collections.unmodifiableMap(MODULE_ITEMS);
    }

    public static Map<FfId, UpgradeItem> upgradeItems() {
        return Collections.unmodifiableMap(UPGRADE_ITEMS);
    }

    /** The kit item that raises a chassis to this tier, or null (the base tier has none). */
    public static dev.fieldframe.forge.item.TierKitItem tierKit(FfId tier) {
        return TIER_KITS.get(tier);
    }

    public static ModuleItem moduleItem(FfId id) {
        return MODULE_ITEMS.get(id);
    }

    public static UpgradeItem upgradeItem(FfId id) {
        return UPGRADE_ITEMS.get(id);
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        // Crushed ore became Metallurgy's dust; old worlds keep their stacks through these remaps.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(FfContent::onMissingMappings);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        TABS.register(bus);
        RECIPE_TYPES.register(bus);
        RECIPE_SERIALIZERS.register(bus);
        SOUNDS.register(bus);
        bus.addListener(FfContent::onRegister);
    }

    private static void onMissingMappings(net.minecraftforge.registries.MissingMappingsEvent event) {
        for (var m : event.getMappings(net.minecraftforge.registries.ForgeRegistries.Keys.ITEMS, Fieldframe.MOD_ID)) {
            String path = m.getKey().getPath();
            if (path.startsWith("crushed_")) {
                Item dust = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                        new net.minecraft.resources.ResourceLocation("metallurgy", path.substring("crushed_".length()) + "_dust"));
                if (dust != null && dust != net.minecraft.world.item.Items.AIR) {
                    m.remap(dust);
                }
            }
        }
    }

    /**
     * Registers one item per module/upgrade definition. Startup-phase extensions (Java addons in their
     * constructors, KubeJS startup scripts via the bridge) must have registered before this point; the
     * Fieldframe registry is frozen here.
     */
    private static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(ForgeRegistries.Keys.ITEMS)) {
            return;
        }
        Fieldframe.runStartupExtensions();
        FieldframeRegistry.freeze();
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            for (ModuleDefinition def : FieldframeRegistry.modules()) {
                ModuleItem item = new ModuleItem(def.id(), new Item.Properties());
                helper.register(new ResourceLocation(def.id().namespace(), def.id().path()), item);
                MODULE_ITEMS.put(def.id(), item);
            }
            for (UpgradeDefinition up : FieldframeRegistry.upgrades()) {
                UpgradeItem item = new UpgradeItem(up.id(), new Item.Properties().stacksTo(16));
                helper.register(new ResourceLocation(up.id().namespace(), up.id().path()), item);
                UPGRADE_ITEMS.put(up.id(), item);
            }
            for (dev.fieldframe.api.chassis.ChassisTier tier : FieldframeRegistry.tiers()) {
                if (tier.level() == 0) {
                    continue;
                }
                dev.fieldframe.forge.item.TierKitItem item = new dev.fieldframe.forge.item.TierKitItem(tier.id(), new Item.Properties().stacksTo(16));
                helper.register(new ResourceLocation(tier.kitId().namespace(), tier.kitId().path()), item);
                TIER_KITS.put(tier.id(), item);
            }
        });
    }
}
