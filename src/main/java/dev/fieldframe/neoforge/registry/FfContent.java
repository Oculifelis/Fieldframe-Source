package dev.fieldframe.neoforge.registry;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.UpgradeDefinition;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.item.ConfiguratorItem;
import dev.fieldframe.neoforge.item.ManualItem;
import dev.fieldframe.neoforge.item.ModuleItem;
import dev.fieldframe.neoforge.item.UpgradeItem;
import dev.fieldframe.neoforge.menu.LaneAccessMenu;
import dev.fieldframe.neoforge.recipe.FfProcessRecipe;
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
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** All NeoForge registrations. Module and upgrade items are created from the frozen Fieldframe registry. */
public final class FfContent {
    private FfContent() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Fieldframe.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Fieldframe.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Fieldframe.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Fieldframe.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Fieldframe.MOD_ID);
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, Fieldframe.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, Fieldframe.MOD_ID);
    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, Fieldframe.MOD_ID);
    private static final Map<String, DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent>> SOUND_HOLDERS = new LinkedHashMap<>();

    static {
        for (String name : dev.fieldframe.core.chassis.MachineLook.SOUND_EVENTS) {
            SOUND_HOLDERS.put(name, SOUNDS.register(name, () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(Fieldframe.MOD_ID, name))));
        }
    }

    /** A Fieldframe sound event by name (see MachineLook.SOUND_EVENTS and sounds.json). */
    public static net.minecraft.sounds.SoundEvent sound(String name) {
        return SOUND_HOLDERS.get(name).get();
    }

    public static final DeferredBlock<ChassisBlock> CHASSIS = BLOCKS.register("chassis", () -> new ChassisBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> CHASSIS_ITEM = ITEMS.registerSimpleBlockItem("chassis", CHASSIS);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChassisBlockEntity>> CHASSIS_BE = BLOCK_ENTITIES.register("chassis",
            () -> BlockEntityType.Builder.of(ChassisBlockEntity::new, CHASSIS.get()).build(null));
    public static final DeferredItem<ConfiguratorItem> CONFIGURATOR = ITEMS.register("configurator", () -> new ConfiguratorItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<ManualItem> MANUAL = ITEMS.register("field_manual", () -> new ManualItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<dev.fieldframe.neoforge.item.BlueprintItem> BLUEPRINT = ITEMS.register("blueprint_sheet",
            () -> new dev.fieldframe.neoforge.item.BlueprintItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> BLANK_MODULE = ITEMS.registerSimpleItem("blank_module");
    public static final DeferredItem<dev.fieldframe.neoforge.item.FrameBoltItem> FRAME_BOLT = ITEMS.register("frame_bolt",
            () -> new dev.fieldframe.neoforge.item.FrameBoltItem(new Item.Properties().stacksTo(64)));
    public static final DeferredItem<dev.fieldframe.neoforge.item.ProgramCardItem> PROGRAM_CARD = ITEMS.register("program_card",
            () -> new dev.fieldframe.neoforge.item.ProgramCardItem(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<dev.fieldframe.neoforge.item.FieldKitItem> FIELD_KIT = ITEMS.register("field_kit",
            () -> new dev.fieldframe.neoforge.item.FieldKitItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<MenuType<?>, MenuType<LaneAccessMenu>> LANE_MENU = MENUS.register("lane_access",
            () -> IMenuTypeExtension.create(LaneAccessMenu::fromNetwork));
    public static final DeferredHolder<MenuType<?>, MenuType<dev.fieldframe.neoforge.menu.WorkshopMenu>> WORKSHOP_MENU = MENUS.register("workshop",
            () -> IMenuTypeExtension.create(dev.fieldframe.neoforge.menu.WorkshopMenu::fromNetwork));

    public static final Map<String, DeferredHolder<RecipeType<?>, RecipeType<FfProcessRecipe>>> PROCESS_TYPES = new LinkedHashMap<>();
    public static final Map<String, DeferredHolder<RecipeSerializer<?>, RecipeSerializer<FfProcessRecipe>>> PROCESS_SERIALIZERS = new LinkedHashMap<>();

    static {
        for (String family : new String[]{"heating", "crushing", "pressing", "mixing", "washing", "process"}) {
            PROCESS_TYPES.put(family, RECIPE_TYPES.register(family, () -> RecipeType.simple(ResourceLocation.fromNamespaceAndPath(Fieldframe.MOD_ID, family))));
            PROCESS_SERIALIZERS.put(family, RECIPE_SERIALIZERS.register(family, () -> new FfProcessRecipe.Serializer(family)));
        }
    }

    private static final Map<FfId, ModuleItem> MODULE_ITEMS = new LinkedHashMap<>();
    private static final Map<FfId, UpgradeItem> UPGRADE_ITEMS = new LinkedHashMap<>();
    private static final Map<FfId, dev.fieldframe.neoforge.item.TierKitItem> TIER_KITS = new LinkedHashMap<>();

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
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
                dev.fieldframe.neoforge.feature.FeatureContent.addToTab(out);
                dev.fieldframe.neoforge.feature.MaterialContent.addToTab(out);
            }).build());

    public static Map<FfId, ModuleItem> moduleItems() {
        return Collections.unmodifiableMap(MODULE_ITEMS);
    }

    public static ModuleItem moduleItem(FfId id) {
        return MODULE_ITEMS.get(id);
    }

    public static UpgradeItem upgradeItem(FfId id) {
        return UPGRADE_ITEMS.get(id);
    }

    /** The kit item that raises a chassis to this tier, or null (the base tier has none). */
    public static dev.fieldframe.neoforge.item.TierKitItem tierKit(FfId tier) {
        return TIER_KITS.get(tier);
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        RECIPE_SERIALIZERS.register("keeping_shapeless", () -> dev.fieldframe.neoforge.recipe.KeepingShapelessRecipe.SERIALIZER);
        // Crushed ore became Metallurgy's dust; old worlds keep their stacks through these aliases.
        for (String metal : new String[] {"iron", "copper", "gold"}) {
            ITEMS.addAlias(ResourceLocation.fromNamespaceAndPath(Fieldframe.MOD_ID, "crushed_" + metal),
                    ResourceLocation.fromNamespaceAndPath("metallurgy", metal + "_dust"));
        }
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        TABS.register(bus);
        RECIPE_TYPES.register(bus);
        RECIPE_SERIALIZERS.register(bus);
        SOUNDS.register(bus);
        bus.addListener(FfContent::onRegister);
    }

    /** Registers one item per module/upgrade definition; the Fieldframe registry freezes here. */
    private static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.ITEM)) {
            return;
        }
        Fieldframe.runStartupExtensions();
        FieldframeRegistry.freeze();
        event.register(Registries.ITEM, helper -> {
            for (ModuleDefinition def : FieldframeRegistry.modules()) {
                ModuleItem item = new ModuleItem(def.id(), new Item.Properties());
                helper.register(ResourceLocation.fromNamespaceAndPath(def.id().namespace(), def.id().path()), item);
                MODULE_ITEMS.put(def.id(), item);
            }
            for (UpgradeDefinition up : FieldframeRegistry.upgrades()) {
                UpgradeItem item = new UpgradeItem(up.id(), new Item.Properties().stacksTo(16));
                helper.register(ResourceLocation.fromNamespaceAndPath(up.id().namespace(), up.id().path()), item);
                UPGRADE_ITEMS.put(up.id(), item);
            }
            for (dev.fieldframe.api.chassis.ChassisTier tier : FieldframeRegistry.tiers()) {
                if (tier.level() == 0) {
                    continue;
                }
                dev.fieldframe.neoforge.item.TierKitItem item = new dev.fieldframe.neoforge.item.TierKitItem(tier.id(), new Item.Properties().stacksTo(16));
                helper.register(ResourceLocation.fromNamespaceAndPath(tier.kitId().namespace(), tier.kitId().path()), item);
                TIER_KITS.put(tier.id(), item);
            }
        });
    }
}
