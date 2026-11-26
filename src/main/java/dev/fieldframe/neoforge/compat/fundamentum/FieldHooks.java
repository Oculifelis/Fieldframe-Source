package dev.fieldframe.neoforge.compat.fundamentum;

import com.fundamentum.curios.Slots;
import com.fundamentum.hook.Hooks;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import dev.fieldframe.neoforge.item.ChargedGearItem;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Graviton Hook, Fieldframe's take on Terraria's Anti-Gravity Hook, on Fundamentum's grappling hooks (worn in the
 * hook slot; its range, speed and count are data in {@code data/fieldframe/fundamentum/hooks/graviton_hook.json}).
 * While one of its three hooks holds and it has charge, the wearer's gravity is cancelled (a transient modifier, so a
 * server never kicks them for floating) and {@link GravitonHookClient} lets them move freely in any direction within
 * {@link #TETHER} blocks of the hooks instead of being pulled to them. It spends {@code perUse} RF a second while it
 * holds the wearer up; without charge it is an ordinary fast three-hook grapple.
 */
public final class FieldHooks {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Fieldframe.MOD_ID);

    public static final DeferredItem<ChargedGearItem> GRAVITON_HOOK = ITEMS.registerItem("graviton_hook",
            p -> new ChargedGearItem(p, new ChargedGearItem.Charge(60_000, 1_500, 400, 0)), new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));

    /** How far from the middle of the latched hooks the wearer may float. */
    public static final double TETHER = 10.0;
    /** The gravity modifier while the field is up: -100% of gravity. */
    public static final ResourceLocation FIELD = ResourceLocation.fromNamespaceAndPath(Fieldframe.MOD_ID, "graviton_field");
    private static final AttributeModifier NO_GRAVITY = new AttributeModifier(FIELD, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(FieldHooks::capabilities);
        modBus.addListener(FieldHooks::creativeTab);
        NeoForge.EVENT_BUS.addListener(FieldHooks::playerTick);
    }

    /** True while the player floats in the Graviton Hook's field (both sides: the modifier syncs). */
    public static boolean floating(Player player) {
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        return gravity != null && gravity.hasModifier(FIELD);
    }

    /** The field is up while a Graviton Hook in the hook slot holds onto a block and has a second's RF left. */
    private static void playerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AttributeInstance gravity = player.getAttribute(Attributes.GRAVITY);
        if (gravity == null) {
            return;
        }
        ItemStack stack = Slots.equipped(player, Slots.HOOK);
        boolean up = false;
        if (stack.is(GRAVITON_HOOK.get()) && stack.getItem() instanceof ChargedGearItem hook && Hooks.latched(player) && !player.isPassenger()) {
            int cost = hook.charge().perUse();
            // Paid once a second while it holds the wearer up.
            up = player.tickCount % 20 == 0 ? hook.spend(stack, cost) : ItemEnergy.get(stack) >= cost;
        }
        if (up && !gravity.hasModifier(FIELD)) {
            gravity.addTransientModifier(NO_GRAVITY);
        } else if (!up && gravity.hasModifier(FIELD)) {
            gravity.removeModifier(FIELD);
        }
    }

    private static void capabilities(RegisterCapabilitiesEvent e) {
        ChargedGearItem.Charge c = GRAVITON_HOOK.get().charge();
        e.registerItem(Capabilities.EnergyStorage.ITEM, (stack, ctx) -> ItemEnergy.storage(stack, c.capacity(), c.receivePerTick()), GRAVITON_HOOK.get());
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey().equals(FfContent.TAB.getKey())) {
            e.accept(GRAVITON_HOOK.get());
            e.accept(GRAVITON_HOOK.get().charged());
        }
    }

    private FieldHooks() {}
}
