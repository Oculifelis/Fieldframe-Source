package dev.fieldframe.forge.compat.fundamentum;

import com.fundamentum.curios.Slots;
import com.fundamentum.hook.Hooks;
import dev.fieldframe.forge.Fieldframe;
import dev.fieldframe.forge.feature.ItemEnergy;
import dev.fieldframe.forge.item.ChargedGearItem;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.UUID;

/**
 * The Graviton Hook, Fieldframe's take on Terraria's Anti-Gravity Hook, on Fundamentum's grappling hooks (worn in the
 * hook slot; its range, speed and count are data in {@code data/fieldframe/fundamentum/hooks/graviton_hook.json}).
 * While one of its three hooks holds and it has charge, the wearer's gravity is cancelled (a transient modifier, so a
 * server never kicks them for floating) and {@link GravitonHookClient} lets them move freely in any direction within
 * {@link #TETHER} blocks of the hooks instead of being pulled to them. It spends {@code perUse} RF a second while it
 * holds the wearer up; without charge it is an ordinary fast three-hook grapple.
 */
public final class FieldHooks {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Fieldframe.MOD_ID);

    public static final RegistryObject<ChargedGearItem> GRAVITON_HOOK = ITEMS.register("graviton_hook",
            () -> new ChargedGearItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON), new ChargedGearItem.Charge(60_000, 1_500, 400, 0)));

    /** How far from the middle of the latched hooks the wearer may float. */
    public static final double TETHER = 10.0;
    /** The gravity modifier while the field is up: -100% of gravity. */
    public static final UUID FIELD = UUID.fromString("5b0f7c1e-3a9d-4c62-9e1f-6d2a8b47c0f3");
    private static final AttributeModifier NO_GRAVITY = new AttributeModifier(FIELD, Fieldframe.MOD_ID + ":graviton_field", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL);

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(FieldHooks::creativeTab);
        MinecraftForge.EVENT_BUS.addListener(FieldHooks::playerTick);
    }

    /** True while the player floats in the Graviton Hook's field (both sides: the modifier syncs). */
    public static boolean floating(Player player) {
        AttributeInstance gravity = player.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
        return gravity != null && gravity.getModifier(FIELD) != null;
    }

    /** The field is up while a Graviton Hook in the hook slot holds onto a block and has a second's RF left. */
    private static void playerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer player)) {
            return;
        }
        AttributeInstance gravity = player.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
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
        if (up && gravity.getModifier(FIELD) == null) {
            gravity.addTransientModifier(NO_GRAVITY);
        } else if (!up && gravity.getModifier(FIELD) != null) {
            gravity.removeModifier(FIELD);
        }
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey().equals(FfContent.TAB.getKey())) {
            e.accept(GRAVITON_HOOK.get());
            e.accept(GRAVITON_HOOK.get().charged());
        }
    }

    private FieldHooks() {}
}
